package os.aether.discipline.system

import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.topjohnwu.superuser.Shell
import java.io.File

object Root {
    fun install() {
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(12),
        )
    }

    fun available(): Boolean = try {
        Shell.getShell().isRoot
    } catch (_: Throwable) {
        false
    }

    fun exec(command: String): Pair<Boolean, String> = try {
        val result = Shell.cmd(command).exec()
        val text = (result.out + result.err).joinToString("\n").trim()
        result.isSuccess to text.ifBlank { "exit ${result.code}" }
    } catch (t: Throwable) {
        false to (t.message ?: "su 失败")
    }
}

object LockRuntime {
    const val OFF = "OFF"
    const val LOCKED = "LOCKED"
    const val EMERGENCY = "EMERGENCY"
    const val GIVEN_UP = "GIVEN_UP"

    @Volatile var mode: String = OFF
    @Volatile var emergencyUntil: Long = 0L
    @Volatile var screenOff: Boolean = false
    @Volatile var suppressStop: Boolean = false
    @Volatile var whitelist: Set<String> = emptySet()

    fun inEmergency(): Boolean = mode == EMERGENCY && System.currentTimeMillis() < emergencyUntil

    fun persist(context: Context) {
        File(context.filesDir, "lock.session").writeText("$mode,$emergencyUntil")
    }

    fun restore(context: Context) {
        val file = File(context.filesDir, "lock.session")
        if (!file.exists()) return
        val parts = file.readText().split(",")
        mode = parts.getOrNull(0) ?: OFF
        emergencyUntil = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        if (mode == EMERGENCY && System.currentTimeMillis() >= emergencyUntil) mode = LOCKED
    }
}

object DeviceOwnerCtl {
    fun component(context: Context) = ComponentName(context, AetherAdminReceiver::class.java)

    fun dpm(context: Context) = context.getSystemService(DevicePolicyManager::class.java)

    fun isOwner(context: Context) = runCatching { dpm(context).isDeviceOwnerApp(context.packageName) }.getOrDefault(false)

    fun apply(context: Context, emergencyPackages: Array<String>) {
        if (!isOwner(context)) return
        val admin = component(context)
        val dpm = dpm(context)
        runCatching { dpm.setStatusBarDisabled(admin, !LockRuntime.inEmergency()) }
        runCatching { dpm.setKeyguardDisabled(admin, true) }
        runCatching { dpm.setCameraDisabled(admin, !LockRuntime.inEmergency()) }
        runCatching {
            dpm.setLockTaskPackages(admin, emergencyPackages)
            dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
        }
    }

    fun release(context: Context) {
        if (!isOwner(context)) return
        val admin = component(context)
        val dpm = dpm(context)
        runCatching { dpm.setStatusBarDisabled(admin, false) }
        runCatching { dpm.setKeyguardDisabled(admin, false) }
        runCatching { dpm.setCameraDisabled(admin, false) }
        runCatching { dpm.setLockTaskPackages(admin, arrayOf(context.packageName)) }
    }

    fun clear(context: Context): String {
        val dpm = dpm(context)
        val admin = component(context)
        val logs = mutableListOf<String>()
        if (dpm.isDeviceOwnerApp(context.packageName)) {
            runCatching { dpm.clearDeviceOwnerApp(context.packageName); logs += "已解除 Device Owner" }
                .onFailure { logs += it.message.orEmpty() }
        }
        if (dpm.isAdminActive(admin)) {
            runCatching { dpm.removeActiveAdmin(admin); logs += "已移除设备管理员" }
                .onFailure { logs += it.message.orEmpty() }
        }
        LockRuntime.mode = LockRuntime.OFF
        LockRuntime.persist(context)
        return logs.joinToString("；").ifBlank { "当前不是设备管理员" }
    }

    fun tryPath1(context: Context): String {
        val cmp = "${context.packageName}/${AetherAdminReceiver::class.java.name}"
        val first = Root.exec("dpm set-device-owner --user 0 $cmp")
        if (isOwner(context) || first.first) return "路径 1 已执行：${first.second}"
        val second = Root.exec("dpm set-device-owner $cmp")
        return "路径 1：${first.second.ifBlank { second.second }}".take(500)
    }

    fun tryPath2(context: Context): String {
        val backup = Root.exec(
            "mkdir -p /sdcard/Download/DSH-Go/backup && " +
                "(cp /data/system/device_owner_2.xml /sdcard/Download/DSH-Go/backup/device_owner_2.xml.bak || true) && " +
                "(cp /data/system/device_policies.xml /sdcard/Download/DSH-Go/backup/device_policies.xml.bak || true) && echo BACKED_UP",
        )
        if (!backup.second.contains("BACKED_UP")) return "备份失败，已停止：${backup.second}"
        val cmp = "${context.packageName}/${AetherAdminReceiver::class.java.name}"
        val xml = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?><device-owner package="${context.packageName}" name="Aether" component="$cmp" userId="0" />"""
        val file = File(context.filesDir, "device_owner_2.xml")
        file.writeText(xml)
        val copied = Root.exec("cp ${file.absolutePath} /data/system/device_owner_2.xml && chmod 600 /data/system/device_owner_2.xml && chown system:system /data/system/device_owner_2.xml && echo WROTE")
        return if (copied.second.contains("WROTE")) {
            "已备份并写入 device_owner_2.xml。需要重启后才生效。备份在 Download/DSH-Go/backup。"
        } else {
            "写入失败：${copied.second}"
        }
    }
}

fun exactAlarmAllowed(context: Context): Boolean {
    val am = context.getSystemService(AlarmManager::class.java)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.canScheduleExactAlarms() else true
}

fun usageAllowed(context: Context): Boolean {
    val ops = context.getSystemService(AppOpsManager::class.java)
    val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
    return mode == AppOpsManager.MODE_ALLOWED
}

fun accessibilityOn(context: Context): Boolean {
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
    return enabled.contains(GuardService::class.java.name)
}

fun openExactAlarm(context: Context) {
    context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun openUsage(context: Context) {
    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun openOverlay(context: Context) {
    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun openAllFiles(context: Context) {
    context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun openBattery(context: Context) {
    val pm = context.getSystemService(PowerManager::class.java)
    if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

class AetherAdminReceiver : android.app.admin.DeviceAdminReceiver()
