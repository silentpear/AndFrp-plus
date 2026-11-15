package io.github.silentpear.andfrpplus.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class Protocol(val label: String, val defaultPort: Int) {
    FTP("FTP", 21),
    FTPS("FTPS (显式 TLS)", 21),
    FTPS_IMPLICIT("FTPS (隐式 TLS)", 990),
    SFTP("SFTP (SSH)", 22),
    SCP("SCP", 22),
    WEBDAV("WebDAV", 443),
    SMB("SMB / CIFS", 445)
}

@Serializable
enum class AuthType { PASSWORD, PRIVATE_KEY, ANONYMOUS, KEYBOARD_INTERACTIVE }

@Serializable
enum class ProxyType { NONE, HTTP, SOCKS5 }

@Serializable
data class Connection(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val protocol: Protocol = Protocol.SFTP,
    val host: String = "",
    val port: Int = Protocol.SFTP.defaultPort,
    val username: String = "",
    val authType: AuthType = AuthType.PASSWORD,
    val initialPath: String = "/",
    val group: String = "默认",
    val passiveMode: Boolean = true,
    val timeoutSeconds: Int = 30,
    val encoding: String = "UTF-8",
    val proxyType: ProxyType = ProxyType.NONE,
    val proxyHost: String = "",
    val proxyPort: Int = 0,
    val hostKeyFingerprint: String? = null,
    val privateKeyPath: String? = null,
    val lastConnectedAt: Long = 0L,
    val sortOrder: Int = 0,
    val serverVersion: String? = null,
    val smbDomain: String = "",
    val insecureTls: Boolean = false
) {
    val displayAddress: String get() = "$host:$port"
    val schemes: List<String>
        get() = when (protocol) {
            Protocol.SFTP, Protocol.SCP -> listOf("sftp")
            Protocol.FTPS, Protocol.FTPS_IMPLICIT -> listOf("ftps")
            Protocol.WEBDAV -> listOf("webdav", "https")
            Protocol.SMB -> listOf("smb")
            else -> listOf("ftp")
        }
}

@Serializable
data class RemoteEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean = false,
    val size: Long = 0L,
    val modifiedAt: Long = 0L,
    val permissions: Int? = null,
    val owner: String? = null,
    val group: String? = null
) {
    val displaySize: String
        get() = if (isDirectory) "" else formatBytes(size)

    companion object {
        fun formatBytes(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val units = arrayOf("KB", "MB", "GB", "TB")
            var value = bytes.toDouble() / 1024
            var idx = 0
            while (value >= 1024 && idx < units.lastIndex) {
                value /= 1024
                idx++
            }
            return if (value >= 100) String.format("%.0f %s", value, units[idx])
            else String.format("%.1f %s", value, units[idx])
        }
    }
}

@Serializable
enum class TransferDirection { UPLOAD, DOWNLOAD }

@Serializable
enum class TransferStatus { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }

@Serializable
data class TransferItem(
    val id: String = UUID.randomUUID().toString(),
    val connectionId: String,
    val connectionName: String,
    val direction: TransferDirection,
    val localPath: String,
    val remotePath: String,
    val totalBytes: Long = 0L,
    val transferredBytes: Long = 0L,
    val status: TransferStatus = TransferStatus.QUEUED,
    val error: String? = null,
    val speedBps: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long = 0L,
    val finishedAt: Long = 0L
) {
    val fileName: String get() = remotePath.substringAfterLast('/').ifBlank { remotePath }
    val progress: Float
        get() = if (totalBytes <= 0L) 0f
        else (transferredBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
    val remainingBytes: Long get() = (totalBytes - transferredBytes).coerceAtLeast(0L)
}

@Serializable
data class HistoryRecord(
    val id: String = UUID.randomUUID().toString(),
    val connectionName: String,
    val direction: TransferDirection,
    val fileName: String,
    val remotePath: String,
    val localPath: String,
    val bytes: Long,
    val success: Boolean,
    val message: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
enum class ConflictPolicy { OVERWRITE, SKIP, RENAME, ASK }

@Serializable
enum class SyncMode { MIRROR_LOCAL_TO_REMOTE, MIRROR_REMOTE_TO_LOCAL, TWO_WAY }

@Serializable
data class SyncTask(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val connectionId: String,
    val localDir: String,
    val remoteDir: String,
    val mode: SyncMode = SyncMode.MIRROR_LOCAL_TO_REMOTE,
    val deleteExtra: Boolean = false,
    val wifiOnly: Boolean = false,
    val enabled: Boolean = true,
    val scheduleMinutes: Int = 0,
    val lastRunAt: Long = 0L,
    val lastResult: String? = null
)

@Serializable
data class SyncPlanEntry(
    val relativePath: String,
    val action: SyncAction,
    val reason: String,
    val sizeBytes: Long = 0
)

@Serializable
enum class SyncAction { UPLOAD, DOWNLOAD, DELETE_REMOTE, DELETE_LOCAL, UNCHANGED }
