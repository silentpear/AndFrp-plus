package io.github.ferryapp.ferry.protocol

import io.github.ferryapp.ferry.data.RemoteEntry
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
