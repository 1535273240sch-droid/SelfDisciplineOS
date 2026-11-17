package os.aether.discipline

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import os.aether.discipline.data.AetherRepository
import os.aether.discipline.domain.inLockWindow
import os.aether.discipline.system.AlarmScheduler
import os.aether.discipline.system.DeviceOwnerCtl
import os.aether.discipline.system.LockRuntime
import os.aether.discipline.system.Root
import java.time.LocalDateTime

class AetherApp : Application() {
    lateinit var repo: AetherRepository
        private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        repo = AetherRepository(this)
        LockRuntime.restore(this)
        runCatching { Root.install() }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("aether.lock", "锁屏", NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(NotificationChannel("aether.workout", "运动", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel("aether.emergency", "紧急通行", NotificationManager.IMPORTANCE_LOW))
        scope.launch(Dispatchers.IO) {
            repo.ensureSeed()
            runCatching { repo.settleMissedDays() }
            primeRoot()
            runCatching { reconcileLockState() }
            AlarmScheduler.schedule(this@AetherApp)
        }
    }

    /** Do not let an old LOCKED/EMERGENCY session leak into daytime after a crash or missed release alarm. */
    private suspend fun reconcileLockState() {
        val t = repo.loadThresholds()
        val now = LocalDateTime.now()
        val inWindow = inLockWindow(now, t)
        when {
            LockRuntime.mode == LockRuntime.EMERGENCY && !LockRuntime.inEmergency() -> {
                LockRuntime.mode = if (inWindow) LockRuntime.LOCKED else LockRuntime.OFF
                LockRuntime.persist(this)
                if (!inWindow) DeviceOwnerCtl.release(this)
            }
            LockRuntime.mode == LockRuntime.LOCKED && !inWindow -> {
                LockRuntime.mode = LockRuntime.OFF
                LockRuntime.emergencyUntil = 0L
                LockRuntime.persist(this)
                DeviceOwnerCtl.release(this)
            }
        }
    }

    private fun primeRoot() {
        val prefs = getSharedPreferences("aether", MODE_PRIVATE)
        if (prefs.getBoolean("root_primed", false) || !Root.available()) return
        prefs.edit().putBoolean("root_primed", true).apply()
        Root.exec("dumpsys deviceidle whitelist +$packageName")
        Root.enableAccessibility(this)
        Root.exec("appops set $packageName SYSTEM_ALERT_WINDOW allow")
        // Device Owner is deliberately NOT set here: it is irreversible without a factory reset.
        // The user must trigger it explicitly from the Root settings screen.
    }
}
