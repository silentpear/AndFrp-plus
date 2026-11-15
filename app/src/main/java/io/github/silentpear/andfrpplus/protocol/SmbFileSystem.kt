package io.github.silentpear.andfrpplus.protocol

import io.github.silentpear.andfrpplus.data.Connection
import io.github.silentpear.andfrpplus.data.RemoteEntry
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties

class SmbFileSystem(
    private val connection: Connection,
    private val password: String?
) : RemoteFileSystem {

    private var context: CIFSContext? = null
    private var shareRoot: String = ""

    private fun ctx(): CIFSContext = context ?: throw RemoteException("未连接")

    override suspend fun connect() = withContext(Dispatchers.IO) {
        if (context != null) return@withContext
        val props = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.responseTimeout", "${connection.timeoutSeconds * 1000}")
            setProperty("jcifs.smb.client.connTimeout", "${connection.timeoutSeconds * 1000}")
            setProperty("jcifs.resolveOrder", "DNS")
        }
        try {
            val base = BaseContext(PropertyConfiguration(props))
            val auth = NtlmPasswordAuthenticator(
                connection.smbDomain.ifBlank { null },
                connection.username,
                password.orEmpty()
            )
            val authed = base.withCredentials(auth)
            val rawPath = connection.initialPath.ifBlank { "/" }
            val normalized = if (rawPath.startsWith("/")) rawPath else "/$rawPath"
            shareRoot = "smb://${connection.host}:${connection.port}$normalized"
            val rootUrl = shareRoot.trimEnd('/') + "/"
            val probe = SmbFile(rootUrl, authed)
            if (!probe.exists()) throw RemoteException("无法访问共享路径：$rootUrl")
            context = authed
        } catch (e: RemoteException) {
            throw e
        } catch (e: Exception) {
            throw RemoteException("SMB 连接失败：${e.message ?: e::class.simpleName}", e)
        }
    }

    private fun smb(path: String): SmbFile {
        val p = path.trimStart('/')
        val root = shareRoot.trimEnd('/')
        val url = if (p.isEmpty()) "$root/" else "$root/$p"
        return SmbFile(url, ctx())
    }

    override suspend fun list(path: String): List<RemoteEntry> = withContext(Dispatchers.IO) {
        val dir = smb(path)
        (dir.list() ?: emptyArray()).mapNotNull { f ->
            val rawName = f.path.trimEnd('/').substringAfterLast('/')
            if (rawName.isBlank()) return@mapNotNull null
            runCatching {
                RemoteEntry(
                    name = rawName,
                    path = RemotePaths.join(path, rawName),
                    isDirectory = f.isDirectory,
                    size = if (f.isFile) f.length() else 0L,
                    modifiedAt = f.lastModified()
                )
            }.getOrNull()
        }
    }

    override suspend fun stat(path: String): RemoteEntry? = withContext(Dispatchers.IO) {
        runCatching {
            val f = smb(path)
            if (!f.exists()) return@runCatching null
            RemoteEntry(
                name = RemotePaths.name(path),
                path = path,
                isDirectory = f.isDirectory,
                size = if (f.isFile) f.length() else 0L,
                modifiedAt = f.lastModified()
            )
        }.getOrNull()
    }

    override suspend fun mkdir(path: String) = withContext(Dispatchers.IO) {
        smb(path).mkdirs()
    }

    override suspend fun delete(path: String, recursive: Boolean) = withContext(Dispatchers.IO) {
        val f = smb(path)
        if (recursive && f.isDirectory) f.delete() else f.delete()
    }

    override suspend fun rename(from: String, to: String) = withContext(Dispatchers.IO) {
        smb(from).renameTo(smb(to))
    }

    override suspend fun chmod(path: String, mode: Int) {
        throw RemoteException("SMB 不支持修改权限")
    }

    override suspend fun download(remote: String, local: File, onProgress: (Long) -> Unit) =
        withContext(Dispatchers.IO) {
            val f = smb(remote)
            val total = f.length()
            local.parentFile?.mkdirs()
            var written = 0L
            f.inputStream.use { input ->
                java.io.FileOutputStream(local).use { out ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var lastTick = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        if (written - lastTick > 256 * 1024) {
                            onProgress(written)
                            lastTick = written
                        }
                    }
                }
            }
            onProgress(total)
        }

    override suspend fun upload(local: File, remote: String, onProgress: (Long) -> Unit) =
        withContext(Dispatchers.IO) {
            val f = smb(remote)
            val total = local.length()
            f.outputStream.use { out ->
                java.io.FileInputStream(local).use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var sent = 0L
                    var lastTick = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        sent += read
                        if (sent - lastTick > 256 * 1024) {
                            onProgress(sent)
                            lastTick = sent
                        }
                    }
                }
            }
            onProgress(total)
        }

    override suspend fun size(path: String): Long = stat(path)?.size ?: 0L

    override suspend fun exists(path: String): Boolean = stat(path) != null

    override suspend fun quota(): Pair<Long, Long>? = null

    override suspend fun readText(path: String, maxBytes: Int): String = withContext(Dispatchers.IO) {
        val bytes = smb(path).inputStream.use { it.readBytes() }
        String(bytes, 0, minOf(bytes.size, maxBytes), Charsets.UTF_8)
    }

    override suspend fun writeText(path: String, content: String) = withContext(Dispatchers.IO) {
        smb(path).outputStream.use { it.write(content.toByteArray(Charsets.UTF_8)) }
    }

    override suspend fun serverInfo(): String = withContext(Dispatchers.IO) {
        "SMB ${connection.host}  ·  ${connection.initialPath}"
    }

    override fun close() {
        runCatching { context?.close() }
        context = null
    }
}
