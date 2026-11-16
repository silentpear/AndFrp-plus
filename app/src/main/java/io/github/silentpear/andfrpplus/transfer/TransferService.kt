package io.github.silentpear.andfrpplus.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.silentpear.andfrpplus.data.TransferStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 传输期间的前台服务：让进程不被系统回收、网络连接不被挂起。
 * 队列清空后自动停止，并在整个过程中持有一个 CPU 唤醒锁。
 */
class TransferService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        acquireWakeLock()
        running.set(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(this, null))
        scope.launch {
            while (true) {
                val queue = TransferBridge.manager?.queue?.value.orEmpty()
                val active = queue.filter {
                    it.status == TransferStatus.RUNNING || it.status == TransferStatus.QUEUED
                }
                if (active.isEmpty()) break
                notifyProgress(this@TransferService, active.size, active.sumOf { it.transferredBytes }, active.sumOf { it.totalBytes })
                delay(1500)
            }
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndFrpPlus:transfer").apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    companion object {
        private const val CHANNEL_ID = "andfrp_transfer_service"
        private const val NOTIFICATION_ID = 1001
        private val running = AtomicBoolean(false)

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(NotificationManager::class.java) ?: return
                if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                    manager.createNotificationChannel(
                        NotificationChannel(
                            CHANNEL_ID,
                            "后台传输",
                            NotificationManager.IMPORTANCE_LOW
                        ).apply {
                            description = "文件传输进行中时保持运行"
                            setShowBadge(false)
                        }
                    )
                }
            }
        }

        private fun buildNotification(context: Context, text: CharSequence?): Notification {
            ensureChannel(context)
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("AndFrp+ 正在传输")
                .setContentText(text ?: "准备中…")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        }

        private fun notifyProgress(context: Context, count: Int, done: Long, total: Long) {
            val text = buildString {
                append("$count 个任务")
                if (total > 0) {
                    append("  ")
                    append((done * 100 / total).coerceIn(0, 100))
                    append("%")
                }
            }
            runCatching {
                val manager = context.getSystemService(NotificationManager::class.java)
                manager?.notify(NOTIFICATION_ID, buildNotification(context, text))
            }
        }

        fun start(context: Context) {
            if (!running.compareAndSet(false, true)) return
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, TransferService::class.java)
                )
            }.onFailure { running.set(false) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TransferService::class.java)) }
            running.set(false)
        }
    }
}

/** 让前台服务能读到队列状态，避免每次都要重新装配依赖。 */
object TransferBridge {
    @Volatile
    var manager: TransferManager? = null
}
