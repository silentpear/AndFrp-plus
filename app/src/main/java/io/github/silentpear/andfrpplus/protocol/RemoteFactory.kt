package io.github.silentpear.andfrpplus.protocol

import android.content.Context
import io.github.silentpear.andfrpplus.data.AuthType
import io.github.silentpear.andfrpplus.data.Connection
import io.github.silentpear.andfrpplus.data.CredentialStore
import io.github.silentpear.andfrpplus.data.Protocol
import java.io.File

object RemoteFactory {

    /**
     * @param passwordOverride 表单里刚输入、尚未保存的密码；为空时才回退到已保存的凭据。
     * @param passphraseOverride 私钥口令，同上。
     */
    fun create(
        context: Context,
        connection: Connection,
        credentials: CredentialStore,
        onHostKey: (String) -> Unit = {},
        passwordOverride: String? = null,
        passphraseOverride: String? = null
    ): RemoteFileSystem {
        val stored = credentials.getPassword(connection.id)
        val password = when {
            !passwordOverride.isNullOrEmpty() -> passwordOverride
            !stored.isNullOrEmpty() -> stored
            else -> null
        }
        val passphrase = when {
            !passphraseOverride.isNullOrEmpty() -> passphraseOverride
            else -> credentials.getPassphrase(connection.id)
        }
        val keyFile = connection.privateKeyPath?.let { File(it) }
        return when (connection.protocol) {
            Protocol.SFTP, Protocol.SCP -> SftpFileSystem(
                connection = connection,
                password = if (connection.authType == AuthType.PASSWORD) password else null,
                keyFile = if (connection.authType == AuthType.PRIVATE_KEY) keyFile else null,
                passphrase = passphrase,
                onHostKey = onHostKey
            )
            Protocol.FTP, Protocol.FTPS, Protocol.FTPS_IMPLICIT -> FtpFileSystem(
                connection = connection,
                password = password
            )
            Protocol.WEBDAV -> WebDavFileSystem(
                connection = connection,
                password = password
            )
            Protocol.SMB -> SmbFileSystem(
                connection = connection,
                password = password
            )
        }
    }

    suspend fun testConnection(
        context: Context,
        connection: Connection,
        credentials: CredentialStore,
        password: String? = null,
        passphrase: String? = null
    ): Result<String> {
        val fs = create(
            context = context,
            connection = connection,
            credentials = credentials,
            passwordOverride = password,
            passphraseOverride = passphrase
        )
        return try {
            fs.connect()
            val info = fs.serverInfo()
            fs.close()
            Result.success(info)
        } catch (e: Exception) {
            runCatching { fs.close() }
            Result.failure(e)
        }
    }
}
