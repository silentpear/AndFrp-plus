package io.github.silentpear.andfrpplus.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.silentpear.andfrpplus.AndFrpApp
import io.github.silentpear.andfrpplus.data.ConflictPolicy
import io.github.silentpear.andfrpplus.data.Connection
import io.github.silentpear.andfrpplus.data.CredentialStore
import io.github.silentpear.andfrpplus.data.HistoryRecord
import io.github.silentpear.andfrpplus.data.Protocol
import io.github.silentpear.andfrpplus.data.RemoteEntry
import io.github.silentpear.andfrpplus.data.SettingsStore
import io.github.silentpear.andfrpplus.data.SyncTask
import io.github.silentpear.andfrpplus.data.TransferDirection
import io.github.silentpear.andfrpplus.data.TransferItem
import io.github.silentpear.andfrpplus.protocol.RemoteFactory
import io.github.silentpear.andfrpplus.protocol.RemoteFileSystem
import io.github.silentpear.andfrpplus.protocol.RemotePaths
import io.github.silentpear.andfrpplus.transfer.SyncPlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ClipboardImport(
    val protocol: Protocol,
    val username: String,
    val host: String,
    val port: Int,
    val path: String
)

class MainViewModel(private val app: AndFrpApp) : ViewModel() {

    private val _bgMode = MutableStateFlow(app.settings.bgMode)
    val bgMode: StateFlow<Int> = _bgMode.asStateFlow()
    private val _bgColor = MutableStateFlow(app.settings.bgColor)
    val bgColor: StateFlow<Int> = _bgColor.asStateFlow()
    private val _bgImage = MutableStateFlow(app.settings.bgImageUri)
    val bgImage: StateFlow<String?> = _bgImage.asStateFlow()

    fun setBackgroundMode(mode: Int) {
        settings.bgMode = mode
        _bgMode.value = mode
    }

    fun setBackgroundColor(color: Int) {
        settings.bgColor = color
        _bgColor.value = color
        setBackgroundMode(1)
    }

    fun setBackgroundImage(uri: String?) {
        settings.bgImageUri = uri
        _bgImage.value = uri
        if (uri != null) setBackgroundMode(2) else setBackgroundMode(0)
    }

    val connectionStore = app.connectionStore
    val credentials: CredentialStore = app.credentials
    val settings: SettingsStore = app.settings
    val historyStore = app.history
    val syncStore = app.syncStore
    val transfers = app.transfers

    private val _availableFs = mutableMapOf<String, RemoteFileSystem>()
    // 用户在「需要密码」对话框里输入的密码，优先于存储，避免存储异常导致反复失败
    private val memoryPasswords = mutableMapOf<String, String>()
    private val _pendingUri = MutableStateFlow<String?>(null)
    val pendingUri: StateFlow<String?> = _pendingUri.asStateFlow()

    private val _pendingFiles = MutableStateFlow<List<String>>(emptyList())
    val pendingFiles: StateFlow<List<String>> = _pendingFiles.asStateFlow()

    private val _locked = MutableStateFlow(app.settings.appLock)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private val _clipboardImport = MutableStateFlow<ClipboardImport?>(null)
    val clipboardImport: StateFlow<ClipboardImport?> = _clipboardImport.asStateFlow()

    init {
        io.github.silentpear.andfrpplus.transfer.TransferBridge.manager = transfers
        viewModelScope.launch {
            transfers.queue.collect { queue ->
                val active = queue.any {
                    it.status == io.github.silentpear.andfrpplus.data.TransferStatus.RUNNING ||
                        it.status == io.github.silentpear.andfrpplus.data.TransferStatus.QUEUED
                }
                if (active) {
                    io.github.silentpear.andfrpplus.transfer.TransferService.start(app)
                } else {
                    io.github.silentpear.andfrpplus.transfer.TransferService.stop(app)
                }
            }
        }
        transfers.onFinished = { item ->
            if (item.status == io.github.silentpear.andfrpplus.data.TransferStatus.COMPLETED) {
                if (item.direction == TransferDirection.DOWNLOAD) {
                    viewModelScope.launch { exportDownloadToTree(item) }
                }
                NotificationHelper.showCompleted(app, item.fileName)
            }
        }
    }

    /**
     * 下载完成后再把文件写进用户选的文件夹。
     * 走 SAF 而不是直接写路径，所以即使目录是 /Download 也不会遇到 EACCES。
     */
    private suspend fun exportDownloadToTree(item: TransferItem) = withContext(Dispatchers.IO) {
        val tree = settings.downloadTreeUri ?: return@withContext
        val source = File(item.localPath)
        if (!source.exists()) return@withContext
        runCatching {
            val root = androidx.documentfile.provider.DocumentFile
                .fromTreeUri(app, android.net.Uri.parse(tree)) ?: return@runCatching
            val name = source.name
            val target = root.findFile(name)
                ?: root.createFile("application/octet-stream", name)
                ?: return@runCatching
            app.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            }
        }.onSuccess {
            source.delete()
        }
    }

    fun refreshLockState() {
        _locked.value = settings.appLock
    }

    fun unlock() {
        _locked.value = false
    }

    fun onUriReceived(uri: String) {
        _pendingUri.value = uri
        parseConnectionUri(uri)?.let { _clipboardImport.value = it }
    }

    fun onFilesShared(files: List<String>) {
        _pendingFiles.value = files
    }

    fun consumeUri() {
        _pendingUri.value = null
    }

    fun consumeFiles() {
        _pendingFiles.value = emptyList()
    }

    fun consumeClipboardImport() {
        _clipboardImport.value = null
    }

    fun parseConnectionUri(raw: String): ClipboardImport? {
        val trimmed = raw.trim()
        val protocol = when {
            trimmed.startsWith("sftp://") -> Protocol.SFTP
            trimmed.startsWith("ftps://") -> Protocol.FTPS
            trimmed.startsWith("ftp://") -> Protocol.FTP
            trimmed.startsWith("webdav://") || trimmed.startsWith("dav://") -> Protocol.WEBDAV
            trimmed.startsWith("smb://") -> Protocol.SMB
            else -> return null
        }
        val withoutScheme = trimmed.substringAfter("://")
        val authority = withoutScheme.substringBefore('/')
        val path = "/" + withoutScheme.substringAfter('/', "")
        val userPart = authority.substringBefore('@', "")
        val hostPart = if (userPart.isEmpty()) authority else authority.substringAfter('@')
        val username = userPart.substringBefore(':')
        val host = hostPart.substringBefore(':')
        val port = hostPart.substringAfter(':', "").toIntOrNull() ?: protocol.defaultPort
        if (host.isBlank()) return null
        return ClipboardImport(protocol, username, host, port, path)
    }

    suspend fun connect(connection: Connection): RemoteFileSystem = withContext(Dispatchers.IO) {
        _availableFs[connection.id]?.let { return@withContext it }
        val fs = RemoteFactory.create(
            app,
            connection,
            credentials,
            passwordOverride = memoryPasswords[connection.id]
        )
        fs.connect()
        _availableFs[connection.id] = fs
        connectionStore.touch(connection.id)
        fs
    }

    fun disconnect(id: String) {
        _availableFs.remove(id)?.let { runCatching { it.close() } }
    }

    suspend fun testConnection(
        connection: Connection,
        password: String?,
        passphrase: String?
    ): Result<String> = withContext(Dispatchers.IO) {
        RemoteFactory.testConnection(app, connection, credentials, password, passphrase)
    }

    suspend fun saveConnection(connection: Connection, password: String?, passphrase: String?) {
        if (!password.isNullOrEmpty()) credentials.putPassword(connection.id, password)
        if (!passphrase.isNullOrEmpty()) credentials.putPassphrase(connection.id, passphrase)
        connectionStore.upsert(connection)
    }

    /** 单独更新密码（浏览页认证失败后补填用），并丢弃可能已缓存的连接。 */
    fun updatePassword(connectionId: String, password: String) {
        memoryPasswords[connectionId] = password
        credentials.putPassword(connectionId, password)
        disconnect(connectionId)
    }

    suspend fun deleteConnection(id: String) {
        disconnect(id)
        credentials.clear(id)
        connectionStore.delete(id)
    }

    /** 返回一个确实可写的下载目录；首选用户设置，不可写时退回应用专属目录。 */
    /** 设置/清除用户选择的下载目录，并持久化读写权限。 */
    fun setDownloadTree(uri: android.net.Uri?) {
        if (uri == null) {
            settings.downloadTreeUri = null
            return
        }
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        settings.downloadTreeUri = uri.toString()
    }

    /** 当前下载目录的可读名称，用于设置页显示。 */
    fun downloadTargetLabel(): String {
        val tree = settings.downloadTreeUri ?: return "应用专属目录：" + settings.defaultDownloadDir
        val name = runCatching {
            androidx.documentfile.provider.DocumentFile.fromTreeUri(app, android.net.Uri.parse(tree))?.name
        }.getOrNull()
        return "已选文件夹：" + (name ?: tree.substringAfterLast("%3A").substringAfterLast(":"))
    }

    /** SAF 导出用的暂存目录。 */
    fun stagingDir(): File =
        File(app.getExternalFilesDir(null) ?: app.filesDir, "staging").apply { mkdirs() }

    fun safeDownloadDir(preferred: File?): File {
        if (!settings.downloadTreeUri.isNullOrEmpty()) return stagingDir()
        val candidates = listOfNotNull(
            preferred,
            File(settings.lastLocalDir),
            File(settings.defaultDownloadDir)
        )
        for (dir in candidates) {
            if (runCatching {
                    dir.mkdirs()
                    val probe = File(dir, ".ferry_write_test")
                    probe.writeText("ok")
                    probe.delete()
                }.isSuccess
            ) return dir
        }
        return File(settings.defaultDownloadDir).apply { mkdirs() }
    }

    fun queueDownload(connection: Connection, entry: RemoteEntry, localDir: File) {
        val dir = safeDownloadDir(localDir)
        val target = File(dir, entry.name)
        transfers.enqueue(
            TransferItem(
                connectionId = connection.id,
                connectionName = connection.name,
                direction = TransferDirection.DOWNLOAD,
                localPath = target.absolutePath,
                remotePath = entry.path,
                totalBytes = entry.size
            )
        )
    }

    fun queueDownloads(connection: Connection, entries: List<RemoteEntry>, localDir: File) {
        val dir = safeDownloadDir(localDir)
        val items = entries.map { entry ->
            TransferItem(
                connectionId = connection.id,
                connectionName = connection.name,
                direction = TransferDirection.DOWNLOAD,
                localPath = File(dir, entry.name).absolutePath,
                remotePath = entry.path,
                totalBytes = entry.size
            )
        }
        transfers.enqueueAll(items)
    }

    fun queueUpload(connection: Connection, localFile: File, remoteDir: String) {
        transfers.enqueue(
            TransferItem(
                connectionId = connection.id,
                connectionName = connection.name,
                direction = TransferDirection.UPLOAD,
                localPath = localFile.absolutePath,
                remotePath = RemotePaths.join(remoteDir, localFile.name),
                totalBytes = localFile.length()
            )
        )
    }

    fun queueUploads(connection: Connection, localFiles: List<File>, remoteDir: String) {
        val items = localFiles.map { file ->
            TransferItem(
                connectionId = connection.id,
                connectionName = connection.name,
                direction = TransferDirection.UPLOAD,
                localPath = file.absolutePath,
                remotePath = RemotePaths.join(remoteDir, file.name),
                totalBytes = file.length()
            )
        }
        transfers.enqueueAll(items)
    }

    suspend fun planSync(task: SyncTask): List<io.github.silentpear.andfrpplus.data.SyncPlanEntry> =
        withContext(Dispatchers.IO) {
            val connection = connectionStore.byId(task.connectionId)
                ?: return@withContext emptyList()
            val fs = connect(connection)
            SyncPlanner.plan(task, fs)
        }

    suspend fun executeSync(task: SyncTask, plan: List<io.github.silentpear.andfrpplus.data.SyncPlanEntry>) {
        val connection = connectionStore.byId(task.connectionId) ?: return
        val uploads = plan.filter { it.action == io.github.silentpear.andfrpplus.data.SyncAction.UPLOAD }
        val downloads = plan.filter { it.action == io.github.silentpear.andfrpplus.data.SyncAction.DOWNLOAD }
        uploads.forEach { entry ->
            val local = File(task.localDir, entry.relativePath)
            if (local.exists()) {
                transfers.enqueue(
                    TransferItem(
                        connectionId = connection.id,
                        connectionName = connection.name,
                        direction = TransferDirection.UPLOAD,
                        localPath = local.absolutePath,
                        remotePath = RemotePaths.join(task.remoteDir, entry.relativePath),
                        totalBytes = local.length()
                    )
                )
            }
        }
        downloads.forEach { entry ->
            transfers.enqueue(
                TransferItem(
                    connectionId = connection.id,
                    connectionName = connection.name,
                    direction = TransferDirection.DOWNLOAD,
                    localPath = File(task.localDir, entry.relativePath).absolutePath,
                    remotePath = RemotePaths.join(task.remoteDir, entry.relativePath),
                    totalBytes = entry.sizeBytes
                )
            )
        }
        syncStore.upsert(task.copy(lastRunAt = System.currentTimeMillis(), lastResult = "已排队 ${uploads.size + downloads.size} 项"))
    }

    fun setConflictPolicy(policy: ConflictPolicy) {
        settings.conflictPolicy = policy
        transfers.conflictPolicy = policy
    }

    fun recordHistory(record: HistoryRecord) {
        viewModelScope.launch { historyStore.add(record) }
    }

    suspend fun deleteRemote(fs: RemoteFileSystem, path: String) = withContext(Dispatchers.IO) {
        fs.delete(path, recursive = true)
    }

    suspend fun createFolder(fs: RemoteFileSystem, path: String) = withContext(Dispatchers.IO) {
        fs.mkdir(path)
    }

    suspend fun rename(fs: RemoteFileSystem, from: String, to: String) = withContext(Dispatchers.IO) {
        fs.rename(from, to)
    }

    suspend fun chmod(fs: RemoteFileSystem, path: String, mode: Int) = withContext(Dispatchers.IO) {
        fs.chmod(path, mode)
    }

    suspend fun readText(fs: RemoteFileSystem, path: String): String = withContext(Dispatchers.IO) {
        fs.readText(path)
    }

    suspend fun writeText(fs: RemoteFileSystem, path: String, content: String) = withContext(Dispatchers.IO) {
        fs.writeText(path, content)
    }

    suspend fun search(fs: RemoteFileSystem, root: String, query: String): List<RemoteEntry> =
        withContext(Dispatchers.IO) {
            val result = mutableListOf<RemoteEntry>()
            val lowered = query.lowercase()
            suspend fun recurse(path: String, depth: Int) {
                if (depth > 6 || result.size >= 200) return
                val entries = runCatching { fs.list(path) }.getOrDefault(emptyList())
                for (e in entries) {
                    if (e.name.lowercase().contains(lowered)) result += e
                    if (e.isDirectory) recurse(e.path, depth + 1)
                }
            }
            recurse(root, 0)
            result
        }

    companion object {
        fun factory(app: AndFrpApp) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MainViewModel(app) as T
        }
    }
}

object NotificationHelper {
    private const val CHANNEL_ID = "ferry_transfers"

    fun ensureChannel(context: android.content.Context) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
            val channel = android.app.NotificationChannel(
                CHANNEL_ID,
                "传输完成",
                android.app.NotificationManager.IMPORTANCE_DEFAULT
            )
            manager?.createNotificationChannel(channel)
        }
    }

    fun showCompleted(context: android.content.Context, fileName: String) {
        ensureChannel(context)
        val notification = androidx.core.app.NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("传输完成")
            .setContentText(fileName)
            .setAutoCancel(true)
            .build()
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(context).notify(
                System.currentTimeMillis().toInt(),
                notification
            )
        }
    }
}
