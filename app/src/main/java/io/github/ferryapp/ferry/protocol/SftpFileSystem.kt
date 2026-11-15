package io.github.ferryapp.ferry.protocol

import io.github.ferryapp.ferry.data.AuthType
import io.github.ferryapp.ferry.data.Connection
import io.github.ferryapp.ferry.data.RemoteEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.ServerKeyVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import java.io.File
import java.net.SocketAddress
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.EnumSet

class SftpFileSystem(
    private val connection: Connection,
    private val password: String?,
    private val keyFile: File?,
    private val passphrase: String?,
    private val onHostKey: (String) -> Unit
) : RemoteFileSystem {

    private var client: SSHClient? = null
    private var sftp: SFTPClient? = null
    private var seenFingerprint: String? = null

    private fun ssh(): SSHClient = client ?: throw RemoteException("未连接")

    private fun sftpClient(): SFTPClient = sftp ?: throw RemoteException("未连接")

    override suspend fun connect() = withContext(Dispatchers.IO) {
        if (client != null) return@withContext
        val ssh = SSHClient()
        ssh.loadKnownHosts()
        ssh.addHostKeyVerifier(object : ServerKeyVerifier {
            override fun verifyServerKey(
                sshClient: SSHClient,
                remoteAddress: SocketAddress,
                serverKey: PublicKey
            ): Boolean {
                val fp = fingerprint(serverKey)
                seenFingerprint = fp
                onHostKey(fp)
                return connection.hostKeyFingerprint.isNullOrBlank() || connection.hostKeyFingerprint == fp
            }
        })
        ssh.connectTimeout = connection.timeoutSeconds * 1000
        ssh.timeout = connection.timeoutSeconds * 1000
        try {
            ssh.connect(connection.host, connection.port)
            when (connection.authType) {
                AuthType.PASSWORD -> ssh.authPassword(connection.username, password.orEmpty())
                AuthType.PRIVATE_KEY -> {
                    val key: KeyProvider = if (passphrase.isNullOrEmpty()) {
                        ssh.loadKeys(keyFile!!.absolutePath)
                    } else {
                        ssh.loadKeys(keyFile!!.absolutePath, null, passphrase)
                    }
                    ssh.authPublickey(connection.username, key)
                }
                AuthType.ANONYMOUS -> ssh.authPassword("anonymous", "anonymous")
                AuthType.KEYBOARD_INTERACTIVE -> ssh.authPassword(connection.username, password.orEmpty())
            }
            if (!ssh.isAuthenticated) {
                throw RemoteException("认证失败：请检查用户名/密码或私钥")
            }
            sftp = ssh.newSFTPClient()
            client = ssh
        } catch (e: Exception) {
            runCatching { ssh.close() }
            throw RemoteException("SSH 连接失败：${e.message ?: e::class.simpleName}", e)
        }
    }

    private fun fingerprint(key: PublicKey): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.encoded)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    override suspend fun list(path: String): List<RemoteEntry> = withContext(Dispatchers.IO) {
        val entries = sftpClient().ls(path)
        entries.map { info ->
            val attrs = info.attributes
            RemoteEntry(
                name = info.name,
                path = info.path,
                isDirectory = attrs.type == FileMode.Type.DIRECTORY,
                isSymlink = attrs.type == FileMode.Type.SYMLINK,
                size = attrs.size,
                modifiedAt = attrs.mtime * 1000,
                permissions = attrs.mode.toMask(),
                owner = null,
                group = null
            )
        }.filter { it.name != "." && it.name != ".." }
    }

    override suspend fun stat(path: String): RemoteEntry? = withContext(Dispatchers.IO) {
        runCatching {
            val attrs = sftpClient().statExistence(path) ?: return@runCatching null
            RemoteEntry(
                name = RemotePaths.name(path),
                path = path,
                isDirectory = attrs.type == FileMode.Type.DIRECTORY,
                isSymlink = attrs.type == FileMode.Type.SYMLINK,
                size = attrs.size,
                modifiedAt = attrs.mtime * 1000,
                permissions = attrs.mode.toMask(),
                owner = null,
                group = null
            )
        }.getOrNull()
    }

    override suspend fun mkdir(path: String) = withContext(Dispatchers.IO) {
        sftpClient().mkdirs(path)
    }

    override suspend fun delete(path: String, recursive: Boolean) = withContext(Dispatchers.IO) {
        val s = sftpClient()
        val attrs = s.statExistence(path) ?: return@withContext
        if (attrs.type == FileMode.Type.DIRECTORY) {
            if (recursive) {
                s.rmdir(path)
            } else {
                val children = s.ls(path).filter { it.name != "." && it.name != ".." }
                if (children.isEmpty()) s.rmdir(path)
                else throw RemoteException("目录非空")
            }
        } else {
            s.rm(path)
        }
    }

    override suspend fun rename(from: String, to: String) = withContext(Dispatchers.IO) {
        sftpClient().rename(from, to)
    }

    override suspend fun chmod(path: String, mode: Int) = withContext(Dispatchers.IO) {
        sftpClient().chmod(path, mode)
    }

    override suspend fun download(remote: String, local: File, onProgress: (Long) -> Unit) =
        withContext(Dispatchers.IO) {
            local.parentFile?.mkdirs()
            val s = sftpClient()
            val resumeFrom = if (local.exists()) local.length() else 0L
            val total = stat(remote)?.size ?: 0L
            s.open(remote).use { handle ->
                var toSkip = resumeFrom
                val skipBuffer = ByteArray(64 * 1024)
                while (toSkip > 0) {
                    val chunk = minOf(toSkip, skipBuffer.size.toLong()).toInt()
                    val read = handle.read(skipBuffer, 0, chunk)
                    if (read <= 0) break
                    toSkip -= read
                }
                java.io.RandomAccessFile(local, "rw").use { out ->
                    out.seek(resumeFrom)
                    var written = resumeFrom
                    var lastTick = resumeFrom
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = handle.read(buffer, 0, buffer.size)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        if (written - lastTick > 256 * 1024 || written == total) {
                            onProgress(written)
                            lastTick = written
                        }
                    }
                    onProgress(written)
                }
            }
        }

    override suspend fun upload(local: File, remote: String, onProgress: (Long) -> Unit) =
        withContext(Dispatchers.IO) {
            val s = sftpClient()
            val total = local.length()
            val existing = stat(remote)?.size ?: 0L
            if (existing == total && total > 0) {
                onProgress(total)
                return@withContext
            }
            val startAt = if (existing in 1 until total) existing else 0L
            java.io.RandomAccessFile(local, "r").use { input ->
                input.seek(startAt)
                val modes = if (startAt > 0L) {
                    EnumSet.of(OpenMode.WRITE, OpenMode.CREATE, OpenMode.APPEND)
                } else {
                    EnumSet.of(OpenMode.WRITE, OpenMode.CREATE, OpenMode.TRUNC)
                }
                s.open(remote, modes).use { handle ->
                    var sent = startAt
                    var lastTick = startAt
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        handle.write(buffer, 0, read)
                        sent += read
                        if (sent - lastTick > 256 * 1024 || sent == total) {
                            onProgress(sent)
                            lastTick = sent
                        }
                    }
                    onProgress(sent)
                }
            }
        }

    override suspend fun size(path: String): Long = withContext(Dispatchers.IO) {
        sftpClient().statExistence(path)?.size ?: 0L
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { sftpClient().statExistence(path) != null }.getOrDefault(false)
    }

    override suspend fun quota(): Pair<Long, Long>? = null

    override suspend fun readText(path: String, maxBytes: Int): String = withContext(Dispatchers.IO) {
        sftpClient().open(path).use { handle ->
            val size = minOf(handle.length(), maxBytes.toLong()).toInt()
            val buffer = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val read = handle.read(buffer, offset, size - offset)
                if (read < 0) break
                offset += read
            }
            String(buffer, 0, offset, Charsets.UTF_8)
        }
    }

    override suspend fun writeText(path: String, content: String) = withContext(Dispatchers.IO) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        sftpClient().open(path, EnumSet.of(OpenMode.WRITE, OpenMode.CREATE, OpenMode.TRUNC)).use { handle ->
            handle.write(bytes, 0, bytes.size)
        }
    }

    override suspend fun serverInfo(): String = withContext(Dispatchers.IO) {
        val remoteVersion = runCatching { ssh().remoteVersion }.getOrDefault("")
        val fp = seenFingerprint ?: ""
        "SSH ${remoteVersion.trim()}  ·  $fp"
    }

    override suspend fun supportsPermissions(): Boolean = true

    override fun close() {
        runCatching { sftp?.close() }
        runCatching { client?.close() }
        sftp = null
        client = null
    }
}

internal fun FileMode.toMask(): Int {
    var mask = when (type) {
        FileMode.Type.DIRECTORY -> 0x4000
        FileMode.Type.REGULAR -> 0x8000
        FileMode.Type.SYMLINK -> 0xA000
        else -> 0
    }
    val perms = permissions ?: return mask
    val mapping = listOf(
        FileMode.Permission.USR_R to 0x100,
        FileMode.Permission.USR_W to 0x80,
        FileMode.Permission.USR_X to 0x40,
        FileMode.Permission.GRP_R to 0x20,
        FileMode.Permission.GRP_W to 0x10,
        FileMode.Permission.GRP_X to 0x08,
        FileMode.Permission.OTH_R to 0x04,
        FileMode.Permission.OTH_W to 0x02,
        FileMode.Permission.OTH_X to 0x01
    )
    for ((permission, bit) in mapping) {
        if (perms.contains(permission)) mask = mask or bit
    }
    return mask
}
