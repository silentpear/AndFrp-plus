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
}
