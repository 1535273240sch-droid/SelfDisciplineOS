package os.aether.discipline

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import os.aether.discipline.data.AetherRepository
import os.aether.discipline.system.AlarmScheduler
import os.aether.discipline.system.GuardService
import os.aether.discipline.system.LockRuntime
import os.aether.discipline.system.Root

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
        scope.launch(Dispatchers.IO) {
            repo.ensureSeed()
            primeRoot()
            AlarmScheduler.schedule(this@AetherApp)
        }
    }

    private fun primeRoot() {
        val prefs = getSharedPreferences("aether", MODE_PRIVATE)
        if (prefs.getBoolean("root_primed", false) || !Root.available()) return
        prefs.edit().putBoolean("root_primed", true).apply()
        Root.exec("dumpsys deviceidle whitelist +$packageName")
        val component = "$packageName/${GuardService::class.java.name}"
        val current = Root.exec("settings get secure enabled_accessibility_services").second
        val merged = buildList {
            current.split(":").map { it.trim() }.filter { it.isNotBlank() && it != "null" }.forEach { add(it) }
            if (component !in this) add(component)
        }.joinToString(":")
        Root.exec("settings put secure enabled_accessibility_services $merged")
        Root.exec("settings put secure accessibility_enabled 1")
        Root.exec("appops set $packageName SYSTEM_ALERT_WINDOW allow")
        os.aether.discipline.system.DeviceOwnerCtl.tryPath1(this)
    }
}
