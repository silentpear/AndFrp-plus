package io.github.ferryapp.ferry.documents

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import io.github.ferryapp.ferry.FerryApp
import io.github.ferryapp.ferry.data.Connection
import io.github.ferryapp.ferry.protocol.RemoteFactory
import io.github.ferryapp.ferry.protocol.RemoteFileSystem
import io.github.ferryapp.ferry.protocol.RemotePaths
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

class FerryDocumentsProvider : DocumentsProvider() {

    private lateinit var app: FerryApp
    private val openFiles = ConcurrentHashMap<String, RemoteFileSystem>()
    private val recentRootId = "ferry_recent"
    private val rootPrefix = "conn:"

    override fun onCreate(): Boolean {
        app = context?.applicationContext as? FerryApp ?: return false
        return true
    }

    private fun connections(): List<Connection> = app.connectionStore.connections.value

    private fun connectionFor(rootId: String): Connection? {
        if (!rootId.startsWith(rootPrefix)) return null
        val id = rootId.removePrefix(rootPrefix)
        return app.connectionStore.byId(id)
    }

    private fun fsFor(connection: Connection): RemoteFileSystem {
        return openFiles.getOrPut(connection.id) {
            RemoteFactory.create(app, connection, app.credentials)
        }.also { fs ->
            runBlocking { runCatching { fs.connect() } }
        }
    }

    private fun docId(connectionId: String, path: String): String = "$connectionId|$path"

    private fun parseDocId(id: String): Pair<String, String> {
        val idx = id.indexOf('|')
        if (idx < 0) throw FileNotFoundException("无效的文档 ID：$id")
        return id.substring(0, idx) to RemotePaths.normalize(id.substring(idx + 1))
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cols = projection ?: arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY, Root.COLUMN_FLAGS, Root.COLUMN_ICON,
            Root.COLUMN_AVAILABLE_BYTES
        )
        val cursor = MatrixCursor(cols)
        for (conn in connections()) {
            cursor.newRow().apply {
                add(Root.COLUMN_ROOT_ID, rootPrefix + conn.id)
                add(Root.COLUMN_DOCUMENT_ID, docId(conn.id, conn.initialPath.ifBlank { "/" }))
                add(Root.COLUMN_TITLE, conn.name)
                add(Root.COLUMN_SUMMARY, "${conn.protocol.label} · ${conn.host}")
                add(
                    Root.COLUMN_FLAGS,
                    Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD or
                        Root.FLAG_SUPPORTS_RECENTS or Root.FLAG_SUPPORTS_SEARCH
                )
                add(Root.COLUMN_ICON, android.R.drawable.ic_menu_upload)
            }
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: defaultDocProjection())
        val (connId, path) = parseDocId(documentId)
        val conn = connections().firstOrNull { it.id == connId }
            ?: throw FileNotFoundException("连接不存在")
        if (path == "/") {
            cursor.newRow().apply {
                add(Document.COLUMN_DOCUMENT_ID, documentId)
                add(Document.COLUMN_DISPLAY_NAME, conn.name)
                add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                add(Document.COLUMN_FLAGS, Document.FLAG_DIR_SUPPORTS_CREATE)
                add(Document.COLUMN_SIZE, 0L)
                add(Document.COLUMN_LAST_MODIFIED, 0L)
            }
            return cursor
        }
        val fs = fsFor(conn)
        val entry = runBlocking { fs.stat(path) } ?: throw FileNotFoundException("文件不存在：$path")
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentId)
            add(Document.COLUMN_DISPLAY_NAME, entry.name)
            add(Document.COLUMN_MIME_TYPE, if (entry.isDirectory) Document.MIME_TYPE_DIR else guessMime(entry.name))
            add(Document.COLUMN_FLAGS, docFlags(entry.isDirectory))
            add(Document.COLUMN_SIZE, entry.size)
            add(Document.COLUMN_LAST_MODIFIED, entry.modifiedAt)
        }
        return cursor
    }

    private fun docFlags(isDir: Boolean): Int = if (isDir) {
        Document.FLAG_DIR_SUPPORTS_CREATE or Document.FLAG_SUPPORTS_DELETE or
            Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_REMOVE
    } else {
        Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or
            Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_THUMBNAIL
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(projection ?: defaultDocProjection())
        val (connId, path) = parseDocId(parentDocumentId)
        val conn = connections().firstOrNull { it.id == connId } ?: return cursor
        val fs = fsFor(conn)
        val entries = runBlocking { runCatching { fs.list(path) }.getOrDefault(emptyList()) }
        for (e in entries) {
            cursor.newRow().apply {
                add(Document.COLUMN_DOCUMENT_ID, docId(connId, e.path))
                add(Document.COLUMN_DISPLAY_NAME, e.name)
                add(Document.COLUMN_MIME_TYPE, if (e.isDirectory) Document.MIME_TYPE_DIR else guessMime(e.name))
                add(Document.COLUMN_FLAGS, docFlags(e.isDirectory))
                add(Document.COLUMN_SIZE, e.size)
                add(Document.COLUMN_LAST_MODIFIED, e.modifiedAt)
            }
        }
        return cursor
    }

    override fun queryRecentDocuments(rootId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: defaultDocProjection())
        val conn = connectionFor(rootId) ?: return cursor
        val fs = fsFor(conn)
        val entries = runBlocking { runCatching { fs.list(conn.initialPath.ifBlank { "/" }) }.getOrDefault(emptyList()) }
        entries.filter { !it.isDirectory }.sortedByDescending { it.modifiedAt }.take(20).forEach { e ->
            cursor.newRow().apply {
                add(Document.COLUMN_DOCUMENT_ID, docId(conn.id, e.path))
                add(Document.COLUMN_DISPLAY_NAME, e.name)
                add(Document.COLUMN_MIME_TYPE, guessMime(e.name))
                add(Document.COLUMN_SIZE, e.size)
                add(Document.COLUMN_LAST_MODIFIED, e.modifiedAt)
            }
        }
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val (pConn, pPath) = runCatching { parseDocId(parentDocumentId) }.getOrNull() ?: return false
        val (cConn, cPath) = runCatching { parseDocId(documentId) }.getOrNull() ?: return false
        if (pConn != cConn) return false
        return cPath.startsWith(pPath.trimEnd('/') + "/") || cPath == pPath
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String {
        val (connId, parentPath) = parseDocId(parentDocumentId)
        val conn = connections().firstOrNull { it.id == connId } ?: throw FileNotFoundException()
        val fs = fsFor(conn)
        val target = RemotePaths.join(parentPath, displayName)
        runBlocking {
            if (mimeType == Document.MIME_TYPE_DIR) fs.mkdir(target) else fs.writeText(target, "")
        }
        return docId(connId, target)
    }

    override fun deleteDocument(documentId: String) {
        val (connId, path) = parseDocId(documentId)
        val conn = connections().firstOrNull { it.id == connId } ?: throw FileNotFoundException()
        val fs = fsFor(conn)
        runBlocking { fs.delete(path, recursive = true) }
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val (connId, path) = parseDocId(documentId)
        val conn = connections().firstOrNull { it.id == connId } ?: throw FileNotFoundException()
        val fs = fsFor(conn)
        val target = RemotePaths.join(RemotePaths.parent(path), displayName)
        runBlocking { fs.rename(path, target) }
        return docId(connId, target)
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val (connId, path) = parseDocId(documentId)
        val conn = connections().firstOrNull { it.id == connId } ?: throw FileNotFoundException()
        val fs = fsFor(conn)
        val cacheDir = File(app.cacheDir, "docs/${connId.hashCode()}").apply { mkdirs() }
        val local = File(cacheDir, path.hashCode().toUInt().toString() + "_" + RemotePaths.name(path))

        val wantsWrite = mode.contains('w') || mode.contains('a')
        val truncate = mode.contains('w')

        if (truncate) {
            local.delete()
        } else if (!local.exists()) {
            runBlocking { fs.download(path, local) {} }
        }

        return ParcelFileDescriptor.open(
            local,
            ParcelFileDescriptor.parseMode(mode),
            android.os.Handler(android.os.Looper.getMainLooper())
        ) { _ ->
            if (wantsWrite && local.exists()) {
                runBlocking {
                    runCatching { fs.upload(local, path) {} }
                }
            }
        }
    }

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: android.graphics.Point?,
        signal: CancellationSignal?
    ): android.content.res.AssetFileDescriptor {
        val (connId, path) = parseDocId(documentId)
        val conn = connections().firstOrNull { it.id == connId } ?: throw FileNotFoundException()
        val fs = fsFor(conn)
        val cacheDir = File(app.cacheDir, "thumbs").apply { mkdirs() }
        val local = File(cacheDir, path.hashCode().toUInt().toString() + ".bin")
        if (!local.exists()) runBlocking { fs.download(path, local) {} }
        return android.content.res.AssetFileDescriptor.open(
            local,
            android.content.res.AssetFileDescriptor.ACCESS_OPEN
        )
    }

    private fun defaultDocProjection(): Array<String> = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_FLAGS,
        Document.COLUMN_SIZE,
        Document.COLUMN_LAST_MODIFIED
    )

    private fun guessMime(name: String): String {
        return android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
    }
}
