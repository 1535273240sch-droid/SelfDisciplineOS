package os.aether.discipline.system

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the emergency window visible as an ongoing notification and clears it when the window expires.
 * The actual re-lock is still driven by AlarmScheduler.relockAt(), so the service is not on the critical path.
 */
class EmergencyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val until = intent?.getLongExtra("until", 0L)?.takeIf { it > 0L } ?: LockRuntime.emergencyUntil
        val manager = getSystemService(NotificationManager::class.java)
        fun build(text: String): Notification = NotificationCompat.Builder(this, "aether.emergency")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("紧急通行")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        val first = build("剩余 ${format(until - System.currentTimeMillis())}")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, first)
        }
        scope.launch {
            while (true) {
                val remain = until - System.currentTimeMillis()
                if (remain <= 0L || LockRuntime.mode != LockRuntime.EMERGENCY) break
                manager.notify(ID, build("剩余 ${format(remain)}"))
                delay(30_000L)
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun format(ms: Long): String {
        val total = (ms.coerceAtLeast(0L) / 1000L).toInt()
        return "%d:%02d".format(total / 60, total % 60)
    }

    companion object {
        private const val ID = 23
    }
}
