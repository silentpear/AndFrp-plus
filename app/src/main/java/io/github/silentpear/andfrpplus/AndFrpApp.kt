package io.github.silentpear.andfrpplus

import android.app.Application
import io.github.silentpear.andfrpplus.data.ConnectionStore
import io.github.silentpear.andfrpplus.data.CredentialStore
import io.github.silentpear.andfrpplus.data.HistoryStore
import io.github.silentpear.andfrpplus.data.SettingsStore
import io.github.silentpear.andfrpplus.data.SyncStore
import io.github.silentpear.andfrpplus.transfer.TransferManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class AndFrpApp : Application() {

    lateinit var connectionStore: ConnectionStore
        private set
    lateinit var credentials: CredentialStore
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var history: HistoryStore
        private set
    lateinit var syncStore: SyncStore
        private set
    lateinit var transfers: TransferManager
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initialized = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        if (!initialized.compareAndSet(false, true)) return
        installBouncyCastle()
        connectionStore = ConnectionStore(this)
        credentials = CredentialStore(this)
        settings = SettingsStore(this)
        history = HistoryStore(this)
        syncStore = SyncStore(this)
        transfers = TransferManager(this, credentials, settings, history).apply {
            connectionProvider = { id -> connectionStore.byId(id) }
            conflictPolicy = settings.conflictPolicy
        }
        scope.launch {
            connectionStore.load()
            history.load()
            syncStore.load()
        }
    }

    /**
     * sshj 通过名字 "BC" 查找 JCE provider 完成 X25519/Ed25519 协商。
     * Android 系统自带一个同名的裁剪版 provider，缺少 X25519，且名字被它占住，
     * 直接 addProvider 完整版不会生效。必须先移除，再插到列表最前面。
     *
     * 已在真实服务器验证：不替换时报 "no such algorithm: X25519 for provider BC"，
     * 替换后 KEX / 认证 / SFTP 全部正常。
     */
    private fun installBouncyCastle() {
        runCatching {
            java.security.Security.removeProvider("BC")
            java.security.Security.insertProviderAt(
                org.bouncycastle.jce.provider.BouncyCastleProvider(),
                1
            )
            net.schmizz.sshj.common.SecurityUtils.setSecurityProvider("BC")
        }
    }
}
