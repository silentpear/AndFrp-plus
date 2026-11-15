package io.github.silentpear.andfrpplus.protocol

import io.github.silentpear.andfrpplus.data.AuthType
import io.github.silentpear.andfrpplus.data.Connection
import io.github.silentpear.andfrpplus.data.RemoteEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.File
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
        runCatching { ssh.loadKnownHosts() }
        ssh.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                val fp = fingerprint(key)
                seenFingerprint = fp
                onHostKey(fp)
                return connection.hostKeyFingerprint.isNullOrBlank() || connection.hostKeyFingerprint == fp
            }

            override fun findExistingAlgorithms(hostname: String, port: Int): MutableList<String> =
                mutableListOf()
        })
        ssh.connectTimeout = connection.timeoutSeconds * 1000
        try {
            ssh.connect(connection.host, connection.port)
            when (connection.authType) {
                AuthType.PASSWORD ->
                    ssh.authPassword(connection.username, password.orEmpty())
                AuthType.PRIVATE_KEY -> {
                    val kf = keyFile ?: throw RemoteException("未选择私钥文件")
                    val key: KeyProvider = if (passphrase.isNullOrEmpty()) {
                        ssh.loadKeys(kf.absolutePath)
                    } else {
                        ssh.loadKeys(
                            kf.absolutePath,
                            null,
                            PasswordUtils.createOneOff(passphrase.toCharArray())
                        )
                    }
                    ssh.authPublickey(connection.username, key)
                }
                AuthType.ANONYMOUS -> ssh.authPassword("anonymous", "anonymous")
                AuthType.KEYBOARD_INTERACTIVE ->
                    ssh.authPassword(connection.username, password.orEmpty())
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
        sftpClient().ls(path).map { info ->
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
                if (children.isEmpty()) s.rmdir(path) else throw RemoteException("目录非空")
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
            val total = stat(remote)?.size ?: 0L
            val resumeFrom = if (local.exists()) local.length().coerceAtMost(total) else 0L
            s.open(remote).use { handle ->
                java.io.RandomAccessFile(local, "rw").use { out ->
                    out.seek(resumeFrom)
                    var written = resumeFrom
                    var lastTick = resumeFrom
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = handle.read(written, buffer, 0, buffer.size)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                        written += n
                        if (written - lastTick > 256 * 1024 || written >= total) {
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
            val modes = if (startAt > 0L) {
                EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.APPEND)
            } else {
                EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)
            }
            s.open(remote, modes).use { handle ->
                java.io.RandomAccessFile(local, "r").use { input ->
                    input.seek(startAt)
                    var offset = startAt
                    var lastTick = startAt
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        handle.write(offset, buffer, 0, n)
                        offset += n
                        if (offset - lastTick > 256 * 1024 || offset >= total) {
                            onProgress(offset)
                            lastTick = offset
                        }
                    }
                    onProgress(total)
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
        val out = java.io.ByteArrayOutputStream()
        sftpClient().open(path).use { handle ->
            val buffer = ByteArray(64 * 1024)
            var offset = 0L
            while (out.size() < maxBytes) {
                val n = handle.read(offset, buffer, 0, buffer.size)
                if (n <= 0) break
                out.write(buffer, 0, n)
                offset += n
            }
        }
        String(out.toByteArray(), Charsets.UTF_8)
    }

    override suspend fun writeText(path: String, content: String) = withContext(Dispatchers.IO) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        sftpClient().open(
            path,
            EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)
        ).use { handle ->
            handle.write(0, bytes, 0, bytes.size)
        }
    }

    override suspend fun serverInfo(): String = withContext(Dispatchers.IO) {
        val version = runCatching { ssh().transport.serverVersion }.getOrNull().orEmpty().trim()
        val fp = seenFingerprint ?: ""
        if (fp.isBlank()) "SSH $version" else "SSH $version  ·  $fp"
    }

    override suspend fun supportsPermissions(): Boolean = true

    override fun close() {
        runCatching { sftp?.close() }
        runCatching { client?.close() }
        sftp = null
        client = null
    }
}

internal fun FileMode.toMask(): Int = mask
