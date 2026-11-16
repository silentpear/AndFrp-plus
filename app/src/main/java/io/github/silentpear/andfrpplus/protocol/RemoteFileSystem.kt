package io.github.silentpear.andfrpplus.protocol

import io.github.silentpear.andfrpplus.data.RemoteEntry
import java.io.Closeable
import java.io.File

class RemoteException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface RemoteFileSystem : Closeable {

    suspend fun connect()

    suspend fun list(path: String): List<RemoteEntry>

    suspend fun stat(path: String): RemoteEntry?

    suspend fun mkdir(path: String)

    suspend fun delete(path: String, recursive: Boolean)

    suspend fun rename(from: String, to: String)

    suspend fun chmod(path: String, mode: Int)

    /** 打开远端文件的读取流，供在线预览/播放使用（调用方负责关闭）。 */
    suspend fun openRead(path: String): java.io.InputStream

    suspend fun download(remote: String, local: File, onProgress: (Long) -> Unit)

    suspend fun upload(local: File, remote: String, onProgress: (Long) -> Unit)

    suspend fun size(path: String): Long

    suspend fun exists(path: String): Boolean

    suspend fun quota(): Pair<Long, Long>?

    suspend fun readText(path: String, maxBytes: Int = 2_000_000): String

    suspend fun writeText(path: String, content: String)

    suspend fun serverInfo(): String

    suspend fun supportsPermissions(): Boolean = false
}

object RemotePaths {
    fun join(base: String, name: String): String {
        val b = base.trimEnd('/')
        return if (b.isEmpty()) "/$name" else "$b/$name"
    }

    fun parent(path: String): String {
        val p = path.trimEnd('/')
        if (p.isEmpty() || p == "/") return "/"
        val idx = p.lastIndexOf('/')
        return if (idx <= 0) "/" else p.substring(0, idx)
    }

    fun name(path: String): String = path.trimEnd('/').substringAfterLast('/')

    fun normalize(path: String): String {
        if (path.isBlank()) return "/"
        val parts = ArrayDeque<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(segment)
            }
        }
        return "/" + parts.joinToString("/")
    }
}

fun permissionString(mode: Int?): String {
    if (mode == null) return ""
    val sb = StringBuilder()
    sb.append(if (mode and 0x4000 != 0) 'd' else '-')
    val bits = listOf(0x100, 0x80, 0x40, 0x20, 0x10, 0x08, 0x04, 0x02, 0x01)
    val chars = "rwxrwxrwx"
    bits.forEachIndexed { i, bit ->
        sb.append(if (mode and bit != 0) chars[i] else '-')
    }
    return sb.toString()
}

/** 把 sshj 的定位读取包装成 InputStream，供预览/播放流式读取。 */
class PositionalInputStream(
    private val readAt: (Long, ByteArray, Int, Int) -> Int,
    private val lengthProvider: () -> Long,
    private val closer: () -> Unit
) : java.io.InputStream() {
    private var position = 0L
    private var single: ByteArray? = null
    private var total = -1L

    override fun read(): Int {
        val buffer = single ?: ByteArray(1).also { single = it }
        val n = read(buffer, 0, 1)
        return if (n <= 0) -1 else buffer[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (total > 0 && position >= total) return -1
        val n = readAt(position, b, off, len)
        if (n <= 0) return -1
        position += n
        return n
    }

    override fun available(): Int {
        if (total < 0) total = runCatching { lengthProvider() }.getOrDefault(0L)
        val left = (total - position).coerceAtLeast(0L)
        return left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    override fun skip(n: Long): Long {
        val step = n.coerceAtLeast(0L)
        position += step
        return step
    }

    override fun close() {
        runCatching { closer() }
    }
}
