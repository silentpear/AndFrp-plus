package io.github.ferryapp.ferry.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.ferryapp.ferry.FerryApp
import io.github.ferryapp.ferry.data.ConflictPolicy
import io.github.ferryapp.ferry.data.Connection
import io.github.ferryapp.ferry.data.CredentialStore
import io.github.ferryapp.ferry.data.HistoryRecord
import io.github.ferryapp.ferry.data.Protocol
import io.github.ferryapp.ferry.data.RemoteEntry
import io.github.ferryapp.ferry.data.SettingsStore
import io.github.ferryapp.ferry.data.SyncTask
import io.github.ferryapp.ferry.data.TransferDirection
import io.github.ferryapp.ferry.data.TransferItem
import io.github.ferryapp.ferry.protocol.RemoteFactory
import io.github.ferryapp.ferry.protocol.RemoteFileSystem
import io.github.ferryapp.ferry.protocol.RemotePaths
import io.github.ferryapp.ferry.transfer.SyncPlanner
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

class MainViewModel(private val app: FerryApp) : ViewModel() {

    val connectionStore = app.connectionStore
    val credentials: CredentialStore = app.credentials
    val settings: SettingsStore = app.settings
    val historyStore = app.history
    val syncStore = app.syncStore
    val transfers = app.transfers

    private val _availableFs = mutableMapOf<String, RemoteFileSystem>()
    private val _pendingUri = MutableStateFlow<String?>(null)
    val pendingUri: StateFlow<String?> = _pendingUri.asStateFlow()

    private val _pendingFiles = MutableStateFlow<List<String>>(emptyList())
    val pendingFiles: StateFlow<List<String>> = _pendingFiles.asStateFlow()

    private val _locked = MutableStateFlow(app.settings.appLock)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private val _clipboardImport = MutableStateFlow<ClipboardImport?>(null)
    val clipboardImport: StateFlow<ClipboardImport?> = _clipboardImport.asStateFlow()

    init {
        transfers.onFinished = { item ->
            if (item.status == io.github.ferryapp.ferry.data.TransferStatus.COMPLETED) {
                NotificationHelper.showCompleted(app, item.fileName)
            }
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
        val fs = RemoteFactory.create(app, connection, credentials)
        fs.connect()
        _availableFs[connection.id] = fs
        connectionStore.touch(connection.id)
        fs
    }

    fun disconnect(id: String) {
        _availableFs.remove(id)?.let { runCatching { it.close() } }
    }

    suspend fun testConnection(connection: Connection): Result<String> = withContext(Dispatchers.IO) {
        RemoteFactory.testConnection(app, connection, credentials)
    }

    suspend fun saveConnection(connection: Connection, password: String?, passphrase: String?) {
        if (!password.isNullOrEmpty()) credentials.putPassword(connection.id, password)
        if (!passphrase.isNullOrEmpty()) credentials.putPassphrase(connection.id, passphrase)
        connectionStore.upsert(connection)
    }

    suspend fun deleteConnection(id: String) {
        disconnect(id)
        credentials.clear(id)
        connectionStore.delete(id)
    }

    fun queueDownload(connection: Connection, entry: RemoteEntry, localDir: File) {
        val target = File(localDir, entry.name)
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
        val items = entries.map { entry ->
            TransferItem(
                connectionId = connection.id,
                connectionName = connection.name,
                direction = TransferDirection.DOWNLOAD,
                localPath = File(localDir, entry.name).absolutePath,
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

    suspend fun planSync(task: SyncTask): List<io.github.ferryapp.ferry.data.SyncPlanEntry> =
        withContext(Dispatchers.IO) {
            val connection = connectionStore.byId(task.connectionId)
                ?: return@withContext emptyList()
            val fs = connect(connection)
            SyncPlanner.plan(task, fs)
        }

    suspend fun executeSync(task: SyncTask, plan: List<io.github.ferryapp.ferry.data.SyncPlanEntry>) {
        val connection = connectionStore.byId(task.connectionId) ?: return
        val uploads = plan.filter { it.action == io.github.ferryapp.ferry.data.SyncAction.UPLOAD }
        val downloads = plan.filter { it.action == io.github.ferryapp.ferry.data.SyncAction.DOWNLOAD }
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
        fun factory(app: FerryApp) = object : ViewModelProvider.Factory {
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
