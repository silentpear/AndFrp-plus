package io.github.silentpear.andfrpplus.protocol

import android.content.Context
import io.github.silentpear.andfrpplus.data.AuthType
import io.github.silentpear.andfrpplus.data.Connection
import io.github.silentpear.andfrpplus.data.CredentialStore
import io.github.silentpear.andfrpplus.data.Protocol
import java.io.File

object RemoteFactory {

    fun create(
        context: Context,
        connection: Connection,
        credentials: CredentialStore,
        onHostKey: (String) -> Unit = {}
    ): RemoteFileSystem {
        val password = credentials.getPassword(connection.id)
        val passphrase = credentials.getPassphrase(connection.id)
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

    fun testConnection(
        context: Context,
        connection: Connection,
        credentials: CredentialStore
    ): Result<String> {
        val fs = create(context, connection, credentials)
        return runCatching {
            fs.connect()
            val info = fs.serverInfo()
            fs.close()
            info
        }.onFailure { runCatching { fs.close() } }
    }
}
