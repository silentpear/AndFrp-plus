package io.github.silentpear.andfrpplus.transfer

import android.content.Context
import io.github.silentpear.andfrpplus.data.ConflictPolicy
import io.github.silentpear.andfrpplus.data.Connection
import io.github.silentpear.andfrpplus.data.CredentialStore
import io.github.silentpear.andfrpplus.data.HistoryRecord
import io.github.silentpear.andfrpplus.data.HistoryStore
import io.github.silentpear.andfrpplus.data.SettingsStore
import io.github.silentpear.andfrpplus.data.TransferDirection
import io.github.silentpear.andfrpplus.data.TransferItem
import io.github.silentpear.andfrpplus.data.TransferStatus
import io.github.silentpear.andfrpplus.protocol.RemoteException
import io.github.silentpear.andfrpplus.protocol.RemoteFactory
import io.github.silentpear.andfrpplus.protocol.RemoteFileSystem
import io.github.silentpear.andfrpplus.protocol.RemotePaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class TransferManager(
    private val context: Context,
    private val credentials: CredentialStore,
    private val settings: SettingsStore,
    private val history: HistoryStore
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _queue = MutableStateFlow<List<TransferItem>>(emptyList())
    val queue: StateFlow<List<TransferItem>> = _queue.asStateFlow()
    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    private val connections = ConcurrentHashMap<String, RemoteFileSystem>()
    private val cancelled = ConcurrentHashMap<String, AtomicBoolean>()
    private val paused = ConcurrentHashMap<String, AtomicBoolean>()
    private val retries = ConcurrentHashMap<String, Int>()
    // 因网络中断被挂起的任务，回到前台时自动续跑
    private val interrupted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val rateTrackers = ConcurrentHashMap<String, RateTracker>()

    var conflictPolicy: ConflictPolicy = ConflictPolicy.ASK
    var conflictResolver: (suspend (String) -> ConflictPolicy)? = null
    var onFinished: ((TransferItem) -> Unit)? = null

    fun enqueue(item: TransferItem) {
        _queue.value = _queue.value + item
        scope.launch { processQueue() }
    }

    fun enqueueAll(items: List<TransferItem>) {
        _queue.value = _queue.value + items
        scope.launch { processQueue() }
    }

    fun pause(id: String) {
        paused[id] = AtomicBoolean(true)
        update(id) { it.copy(status = TransferStatus.PAUSED) }
    }

    fun resume(id: String) {
        paused[id]?.set(false)
        update(id) { it.copy(status = TransferStatus.QUEUED) }
        scope.launch { processQueue() }
    }

    fun cancel(id: String) {
        cancelled[id] = AtomicBoolean(true)
        paused[id]?.set(false)
        update(id) { it.copy(status = TransferStatus.CANCELLED) }
    }

    fun retry(id: String) {
        cancelled.remove(id)
        paused.remove(id)
        retries.remove(id)
        update(id) { it.copy(status = TransferStatus.QUEUED, error = null, transferredBytes = 0) }
        scope.launch { processQueue() }
    }

    /** 回到前台时调用：把因网络中断挂起的任务重新排队。 */
    fun resumeInterrupted() {
        if (interrupted.isEmpty()) return
        val ids = interrupted.toSet()
        interrupted.clear()
        ids.forEach { id ->
            retries.remove(id)
            cancelled.remove(id)
            paused[id]?.set(false)
            update(id) { it.copy(status = TransferStatus.QUEUED, error = null) }
        }
        processQueue()
    }

    fun clearFinished() {
        _queue.value = _queue.value.filter {
            it.status == TransferStatus.QUEUED || it.status == TransferStatus.RUNNING
        }
    }

    fun reorder(from: Int, to: Int) {
        val list = _queue.value.toMutableList()
        if (from !in list.indices || to !in list.indices) return
        val item = list.removeAt(from)
        list.add(to, item)
        _queue.value = list
    }

    private fun update(id: String, transform: (TransferItem) -> TransferItem) {
        _queue.value = _queue.value.map { if (it.id == id) transform(it) else it }
    }

    private fun reportProgress(id: String, bytes: Long) {
        val tracker = rateTrackers.getOrPut(id) { RateTracker() }
        val now = System.currentTimeMillis()
        val speed = tracker.sample(bytes, now)
        _queue.value = _queue.value.map {
            if (it.id == id) it.copy(transferredBytes = bytes, speedBps = speed) else it
        }
    }

    private fun processQueue() {
        val capacity = settings.concurrency
        val runningNow = _queue.value.count { it.status == TransferStatus.RUNNING }
        val pending = _queue.value.filter { it.status == TransferStatus.QUEUED }.take((capacity - runningNow).coerceAtLeast(0))
        for (item in pending) {
            update(item.id) { it.copy(status = TransferStatus.RUNNING) }
            scope.launch { runItem(item) }
        }
    }

    private suspend fun runItem(item: TransferItem) {
        if (paused[item.id]?.get() == true) return
        update(item.id) { it.copy(status = TransferStatus.RUNNING, startedAt = System.currentTimeMillis(), error = null) }
        _activeCount.value = _queue.value.count { it.status == TransferStatus.RUNNING }

        try {
            val connection = findConnection(item.connectionId)
                ?: throw RemoteException("连接不存在或已删除")
            val fs = connect(connection)

            if (settings.wifiOnly && !isOnWifi()) {
                throw RemoteException("已开启「仅 Wi-Fi」，当前不在 Wi-Fi 网络")
            }

            val decision = resolveConflict(item, fs)
            if (decision == ConflictPolicy.SKIP) {
                update(item.id) { it.copy(status = TransferStatus.COMPLETED, finishedAt = System.currentTimeMillis()) }
                onFinished?.invoke(item.copy(status = TransferStatus.COMPLETED))
                return
            }

            val remoteTarget = if (decision == ConflictPolicy.RENAME) nextAvailableName(fs, item.remotePath) else item.remotePath
            val localTarget = if (decision == ConflictPolicy.RENAME) nextAvailableLocal(File(item.localPath)) else File(item.localPath)

            when (item.direction) {
                TransferDirection.DOWNLOAD -> {
                    val progress: (Long) -> Unit = { bytes ->
                        if (cancelled[item.id]?.get() == true) throw RemoteException("已取消")
                        reportProgress(item.id, bytes)
                    }
                    fs.download(remoteTarget, localTarget, progress)
                    finishOk(item, localTarget, remoteTarget)
                }
                TransferDirection.UPLOAD -> {
                    val progress: (Long) -> Unit = { bytes ->
                        if (cancelled[item.id]?.get() == true) throw RemoteException("已取消")
                        reportProgress(item.id, bytes)
                    }
                    fs.upload(localTarget, remoteTarget, progress)
                    finishOk(item, localTarget, remoteTarget)
                }
            }
        } catch (e: Exception) {
            val wasCancelled = cancelled[item.id]?.get() == true
            // 连接可能已经失效（后台被挂起、服务端超时），丢弃缓存客户端以便重试时重建
            dropConnection(item.connectionId)
            val currentRetry = retries.getOrDefault(item.id, 0)
            val socketLike = listOf("abort", "Socket", "closed", "reset", "timed out", "Broken pipe", "EOF")
                .any { (e.message ?: "").contains(it, true) }
            if (!wasCancelled && currentRetry < MAX_RETRIES) {
                retries[item.id] = currentRetry + 1
                val backoff = (2000L * (1 shl currentRetry)).coerceAtMost(30_000L)
                update(item.id) {
                    it.copy(
                        status = TransferStatus.QUEUED,
                        error = "连接中断，${backoff / 1000} 秒后重连（${currentRetry + 1}/$MAX_RETRIES）"
                    )
                }
                delay(backoff)
                runItem(item)
                return
            }
            // 多次重试仍失败：不要直接判死，挂起成「可继续」状态，回到前台时自动接着跑
            if (!wasCancelled && socketLike) {
                interrupted.add(item.id)
                update(item.id) {
                    it.copy(
                        status = TransferStatus.PAUSED,
                        error = "网络不稳定，已暂停 · 回到应用会自动重试"
                    )
                }
                return
            }
            val message = e.message ?: e::class.simpleName ?: "未知错误"
            update(item.id) {
                it.copy(
                    status = if (wasCancelled) TransferStatus.CANCELLED else TransferStatus.FAILED,
                    error = if (wasCancelled) null else message,
                    finishedAt = System.currentTimeMillis()
                )
            }
            history.add(
                HistoryRecord(
                    connectionName = item.connectionName,
                    direction = item.direction,
                    fileName = item.fileName,
                    remotePath = item.remotePath,
                    localPath = item.localPath,
                    bytes = item.transferredBytes,
                    success = false,
                    message = message
                )
            )
            onFinished?.invoke(item.copy(status = if (wasCancelled) TransferStatus.CANCELLED else TransferStatus.FAILED, error = message))
        } finally {
            _activeCount.value = _queue.value.count { it.status == TransferStatus.RUNNING }
        }
    }

    private suspend fun finishOk(item: TransferItem, local: File, remote: String) {
        val finalSize = if (item.direction == TransferDirection.DOWNLOAD) local.length() else local.length()
        update(item.id) {
            it.copy(
                status = TransferStatus.COMPLETED,
                transferredBytes = finalSize,
                totalBytes = if (it.totalBytes > 0) it.totalBytes else finalSize,
                finishedAt = System.currentTimeMillis()
            )
        }
        history.add(
            HistoryRecord(
                connectionName = item.connectionName,
                direction = item.direction,
                fileName = item.fileName,
                remotePath = remote,
                localPath = local.absolutePath,
                bytes = finalSize,
                success = true
            )
        )
        cancelled.remove(item.id)
        paused.remove(item.id)
        retries.remove(item.id)
        onFinished?.invoke(item.copy(status = TransferStatus.COMPLETED))
    }

    private suspend fun resolveConflict(item: TransferItem, fs: RemoteFileSystem): ConflictPolicy {
        val remoteExists = runCatching { fs.exists(item.remotePath) }.getOrDefault(false)
        val localExists = File(item.localPath).exists()
        val conflicts = if (item.direction == TransferDirection.DOWNLOAD) localExists else remoteExists
        if (!conflicts) return ConflictPolicy.OVERWRITE
        return when (conflictPolicy) {
            ConflictPolicy.ASK -> conflictResolver?.invoke(item.fileName) ?: ConflictPolicy.OVERWRITE
            else -> conflictPolicy
        }
    }

    private suspend fun nextAvailableName(fs: RemoteFileSystem, remote: String): String {
        val parent = RemotePaths.parent(remote)
        val name = RemotePaths.name(remote)
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var idx = 2
        while (true) {
            val candidate = RemotePaths.join(parent, "$base ($idx)$ext")
            if (!runCatching { fs.exists(candidate) }.getOrDefault(false)) return candidate
            idx++
            if (idx > 999) return remote
        }
    }

    private fun nextAvailableLocal(file: File): File {
        if (!file.exists()) return file
        val name = file.nameWithoutExtension
        val ext = file.extension
        var idx = 2
        while (true) {
            val candidate = File(file.parentFile, if (ext.isBlank()) "$name ($idx)" else "$name ($idx).$ext")
            if (!candidate.exists()) return candidate
            idx++
        }
    }

    private fun findConnection(id: String): Connection? =
        connectionProvider?.invoke(id)

    var connectionProvider: ((String) -> Connection?)? = null

    private suspend fun connect(connection: Connection): RemoteFileSystem {
        connections[connection.id]?.let { return it }
        val fs = RemoteFactory.create(context, connection, credentials)
        // 必须真正建立连接，否则各操作会抛「未连接」
        fs.connect()
        connections[connection.id] = fs
        return fs
    }

    /** 丢弃某个连接的缓存客户端，下次使用时重新建立连接。 */
    fun dropConnection(id: String) {
        connections.remove(id)?.let { runCatching { it.close() } }
    }

    fun closeConnection(id: String) {
        connections.remove(id)?.let { runCatching { it.close() } }
    }

    private fun isOnWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun shutdown() {
        connections.values.forEach { runCatching { it.close() } }
        connections.clear()
    }

    private companion object {
        const val MAX_RETRIES = 5
    }
}

internal class RateTracker {
    private var lastBytes = 0L
    private var lastTime = 0L
    private var smoothed = 0L

    fun sample(bytes: Long, now: Long): Long {
        if (lastTime == 0L) {
            lastTime = now
            lastBytes = bytes
            return 0L
        }
        val elapsed = now - lastTime
        if (elapsed < 300) return smoothed
        val delta = (bytes - lastBytes).coerceAtLeast(0)
        val instant = delta * 1000 / elapsed
        smoothed = if (smoothed == 0L) instant else (smoothed * 3 + instant) / 4
        lastBytes = bytes
        lastTime = now
        return smoothed
    }
}
