package io.github.ferryapp.ferry.protocol

import io.github.ferryapp.ferry.data.Connection
import io.github.ferryapp.ferry.data.Protocol
import io.github.ferryapp.ferry.data.RemoteEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Calendar

class FtpFileSystem(
    private val connection: Connection,
    private val password: String?
) : RemoteFileSystem {

    private var client: FTPClient? = null
    private var basePath: String = "/"

    private fun ftp(): FTPClient = client ?: throw RemoteException("未连接")

    override suspend fun connect() = withContext(Dispatchers.IO) {
        if (client != null) return@withContext
        val ftpClient: FTPClient = when (connection.protocol) {
            Protocol.FTPS -> FTPSClient(false)
            Protocol.FTPS_IMPLICIT -> FTPSClient(true)
            else -> FTPClient()
        }
        if (ftpClient is FTPSClient && connection.insecureTls) {
            ftpClient.setEndpointCheckingEnabled(false)
            ftpClient.setTrustManager(TrustAllManager())
        }
        ftpClient.controlEncoding = connection.encoding
        ftpClient.connectTimeout = connection.timeoutSeconds * 1000
        ftpClient.defaultTimeout = connection.timeoutSeconds * 1000
        ftpClient.dataTimeout = connection.timeoutSeconds * 1000

        try {
            val proxy = when (connection.proxyType) {
                io.github.ferryapp.ferry.data.ProxyType.SOCKS5 ->
                    Proxy(Proxy.Type.SOCKS, InetSocketAddress(connection.proxyHost, connection.proxyPort))
                io.github.ferryapp.ferry.data.ProxyType.HTTP ->
                    Proxy(Proxy.Type.HTTP, InetSocketAddress(connection.proxyHost, connection.proxyPort))
                else -> Proxy.NO_PROXY
            }
            ftpClient.proxy = proxy
            ftpClient.connect(connection.host, connection.port)
            if (!FTPReply.isPositiveCompletion(ftpClient.replyCode)) {
                throw RemoteException("服务器拒绝连接：${ftpClient.replyString}")
            }
            if (!ftpClient.login(connection.username.ifBlank { "anonymous" }, password.orEmpty())) {
                throw RemoteException("登录失败：${ftpClient.replyString}")
            }
            if (ftpClient is FTPSClient) {
                ftpClient.execPBSZ(0)
                ftpClient.execPROT("P")
            }
            if (connection.passiveMode) {
                ftpClient.enterLocalPassiveMode()
            } else {
                ftpClient.enterLocalActiveMode()
            }
            ftpClient.setFileType(FTP.BINARY_FILE_TYPE)
            ftpClient.setAutodetectUTF8(connection.encoding.equals("UTF-8", true))
            val initial = connection.initialPath.ifBlank { "/" }
            if (initial != "/") ftpClient.changeWorkingDirectory(initial)
            basePath = ftpClient.printWorkingDirectory() ?: "/"
            client = ftpClient
        } catch (e: Exception) {
            runCatching { ftpClient.disconnect() }
            throw RemoteException("FTP 连接失败：${e.message ?: e::class.simpleName}", e)
        }
    }

    private fun absolute(path: String): String {
        if (path.startsWith("/")) return path
        return RemotePaths.join(basePath, path)
    }

    override suspend fun list(path: String): List<RemoteEntry> = withContext(Dispatchers.IO) {
        val target = absolute(path)
        val files = ftp().listFiles(target)
            ?: throw RemoteException("无法列出目录：${ftp().replyString}")
        files.filter { it.name != "." && it.name != ".." }.map { it.toEntry(target) }
    }

    private fun FTPFile.toEntry(parent: String): RemoteEntry = RemoteEntry(
        name = name,
        path = RemotePaths.join(parent, name),
        isDirectory = isDirectory,
        isSymlink = isSymbolicLink,
        size = size,
        modifiedAt = timestamp?.timeInMillis ?: 0L,
        permissions = if (isDirectory) 0x4000 or (rawListing?.let { 0 } ?: 0) else 0x8000,
        owner = user,
        group = group
    )

    override suspend fun stat(path: String): RemoteEntry? = withContext(Dispatchers.IO) {
        val target = absolute(path)
        val name = RemotePaths.name(target)
        runCatching {
            val parent = RemotePaths.parent(target)
            ftp().listFiles(parent)?.firstOrNull { it.name == name }?.toEntry(parent)
        }.getOrNull()
    }

    override suspend fun mkdir(path: String) = withContext(Dispatchers.IO) {
        if (!ftp().makeDirectory(absolute(path))) {
            throw RemoteException("创建目录失败：${ftp().replyString}")
        }
    }

    override suspend fun delete(path: String, recursive: Boolean) = withContext(Dispatchers.IO) {
        val target = absolute(path)
        val stat = stat(target)
        if (stat?.isDirectory == true) {
            if (recursive) {
                deleteRecursive(target)
            } else if (!ftp().removeDirectory(target)) {
                throw RemoteException("删除目录失败：${ftp().replyString}")
            }
        } else if (!ftp().deleteFile(target)) {
            throw RemoteException("删除文件失败：${ftp().replyString}")
        }
    }

    private suspend fun deleteRecursive(path: String) {
        val children = list(path)
        for (child in children) {
            if (child.isDirectory) deleteRecursive(child.path) else ftp().deleteFile(child.path)
        }
        ftp().removeDirectory(path)
    }

    override suspend fun rename(from: String, to: String) = withContext(Dispatchers.IO) {
        if (!ftp().rename(absolute(from), absolute(to))) {
            throw RemoteException("重命名失败：${ftp().replyString}")
        }
    }

    override suspend fun chmod(path: String, mode: Int) {
        throw RemoteException("FTP 协议不支持修改权限")
    }

    override suspend fun download(remote: String, local: File, onProgress: (Long) -> Unit) =
        withContext(Dispatchers.IO) {
            val target = absolute(remote)
            local.parentFile?.mkdirs()
            val existing = if (local.exists()) local.length() else 0L
            val total = stat(target)?.size ?: 0L
            ftp().setRestartOffset(existing)
            val input = ftp().retrieveFileStream(target)
                ?: throw RemoteException("下载失败：${ftp().replyString}")
            var written = existing
            java.io.RandomAccessFile(local, "rw").use { out ->
                out.seek(existing)
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
            input.close()
            ftp().completePendingCommand()
            ftp().setRestartOffset(0)
            onProgress(if (total > 0) total else written)
        }

    override suspend fun upload(local: File, remote: String, onProgress: (Long) -> Unit) =
        withContext(Dispatchers.IO) {
            val target = absolute(remote)
            ftp().makeDirectory(RemotePaths.parent(target))
            val total = local.length()
            val existing = stat(target)?.size ?: 0L
            if (existing == total && total > 0) {
                onProgress(total)
                return@withContext
            }
            val startAt = if (existing in 1 until total) existing else 0L
            ftp().setRestartOffset(startAt)
            val output = ftp().storeFileStream(target)
                ?: throw RemoteException("上传失败：${ftp().replyString}")
            var sent = startAt
            RandomAccessFile(local, "r").use { input ->
                input.seek(startAt)
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var lastTick = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    sent += read
                    if (sent - lastTick > 256 * 1024) {
                        onProgress(sent)
                        lastTick = sent
                    }
                }
            }
            output.close()
            ftp().completePendingCommand()
            ftp().setRestartOffset(0)
            onProgress(total)
        }

    override suspend fun size(path: String): Long = stat(path)?.size ?: 0L

    override suspend fun exists(path: String): Boolean = stat(path) != null

    override suspend fun quota(): Pair<Long, Long>? = null

    override suspend fun readText(path: String, maxBytes: Int): String = withContext(Dispatchers.IO) {
        val target = absolute(path)
        val output = java.io.ByteArrayOutputStream()
        val ok = ftp().retrieveFile(target, output)
        if (!ok) throw RemoteException("读取失败：${ftp().replyString}")
        val bytes = output.toByteArray()
        String(bytes, 0, minOf(bytes.size, maxBytes), Charsets.UTF_8)
    }

    override suspend fun writeText(path: String, content: String) = withContext(Dispatchers.IO) {
        val target = absolute(path)
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (!ftp().storeFile(target, java.io.ByteArrayInputStream(bytes))) {
            throw RemoteException("写入失败：${ftp().replyString}")
        }
    }

    override suspend fun serverInfo(): String = withContext(Dispatchers.IO) {
        val c = ftp()
        (c.replyString ?: "FTP").trim()
    }

    override fun close() {
        runCatching { ftp().logout() }
        runCatching { ftp().disconnect() }
        client = null
    }
}

private class TrustAllManager : javax.net.ssl.X509TrustManager {
    override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) = Unit
    override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) = Unit
    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
}
