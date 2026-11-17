package os.aether.discipline.system

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.launch
import os.aether.discipline.AetherApp
import os.aether.discipline.domain.inLockWindow
import os.aether.discipline.domain.isWeekend
import os.aether.discipline.domain.lockTime
import os.aether.discipline.domain.nextLock
import os.aether.discipline.domain.prepTime
import os.aether.discipline.domain.releaseTime
import os.aether.discipline.domain.wakeTime
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

object AlarmScheduler {
    const val LOCK = "os.aether.discipline.LOCK"
    const val WAKE = "os.aether.discipline.WAKE"
    const val PREP = "os.aether.discipline.PREP"
    const val RELEASE = "os.aether.discipline.RELEASE"
    const val REVIEW = "os.aether.discipline.REVIEW"
    const val BACKUP = "os.aether.discipline.BACKUP"
    const val CHIME = "os.aether.discipline.CHIME"
    const val WATCHDOG = "os.aether.discipline.WATCHDOG"
    const val RELOCK = "os.aether.discipline.RELOCK"

    fun schedule(app: AetherApp) {
        app.scope.launch {
            val now = LocalDateTime.now()
            val t = app.repo.loadThresholds()
            val zone = ZoneId.systemDefault()
            fun at(time: LocalDateTime) = time.atZone(zone).toInstant().toEpochMilli()
            fun next(timeOf: (LocalDate) -> LocalTime): LocalDateTime {
                val todayAt = LocalDateTime.of(now.toLocalDate(), timeOf(now.toLocalDate()))
                return if (now.isBefore(todayAt)) todayAt else {
                    val tomorrow = now.toLocalDate().plusDays(1)
                    LocalDateTime.of(tomorrow, timeOf(tomorrow))
                }
            }
            setActivity(app, LockActivity::class.java, at(nextLock(now, t)), 10)
            setActivity(app, BriefingActivity::class.java, at(next { wakeTime(it, t) }), 11)
            set(app, PREP, at(next { prepTime(it, t) }), 12)
            set(app, RELEASE, at(next { releaseTime(it, t) }), 13)
            val reviewBase = nextLock(now, t).minusMinutes(20)
            val review = if (reviewBase.isAfter(now)) reviewBase else nextLock(nextLock(now, t).plusMinutes(1), t).minusMinutes(20)
            set(app, REVIEW, at(review), 14)
            set(app, BACKUP, at(next { LocalTime.of(3, 30) }), 15)
            nextChime(now, t.weekendSilentUntil)?.let { set(app, CHIME, at(it), 16) }
            if (inLockWindow(now, t) && LockRuntime.mode == LockRuntime.LOCKED) {
                setActivity(app, LockActivity::class.java, System.currentTimeMillis() + 5 * 60_000L, 17)
            }
        }
    }

    fun set(context: Context, action: String, trigger: Long, request: Int) {
        if (trigger <= System.currentTimeMillis()) return
        val am = context.getSystemService(AlarmManager::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val op = PendingIntent.getBroadcast(context, request, Intent(context, AlarmReceiver::class.java).setAction(action), flags)
        val show = PendingIntent.getActivity(context, request + 100, Intent(context, LockActivity::class.java), flags)
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(trigger, show), op)
        } catch (_: SecurityException) {
            openExactAlarm(context)
        }
    }

    fun setActivity(context: Context, clazz: Class<*>, trigger: Long, request: Int) {
        if (trigger <= System.currentTimeMillis()) return
        val am = context.getSystemService(AlarmManager::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val op = PendingIntent.getActivity(context, request, Intent(context, clazz).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(trigger, op), op)
        } catch (_: SecurityException) {
            openExactAlarm(context)
        }
    }

    fun relockAt(context: Context, trigger: Long) {
        setActivity(context, LockActivity::class.java, trigger, 18)
    }

    private fun nextChime(now: LocalDateTime, silent: LocalTime): LocalDateTime? {
        var day = now.toLocalDate()
        repeat(8) {
            if (isWeekend(day)) {
                val at = LocalDateTime.of(day, silent)
                if (at.isAfter(now)) return at
            }
            day = day.plusDays(1)
        }
        return null
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as AetherApp
        app.scope.launch {
            try {
                when (intent.action) {
                    AlarmScheduler.PREP -> app.repo.refreshBriefing()
                    AlarmScheduler.LOCK -> openLock(context, false)
                    AlarmScheduler.WAKE -> openBriefing(context)
                    AlarmScheduler.RELEASE -> release(context)
                    AlarmScheduler.CHIME -> chimeIfIdle(context)
                    AlarmScheduler.REVIEW -> review(app)
                    AlarmScheduler.BACKUP -> app.repo.backupNow()
                    AlarmScheduler.WATCHDOG -> if (LockRuntime.mode == LockRuntime.LOCKED) openLock(context, false)
                    AlarmScheduler.RELOCK -> {
                        LockRuntime.mode = LockRuntime.LOCKED
                        LockRuntime.persist(context)
                        app.repo.revokeEmergency()
                        openLock(context, false)
                    }
                }
            } finally {
                AlarmScheduler.schedule(app)
                pending.finish()
            }
        }
    }

    private fun openLock(context: Context, release: Boolean) {
        if (!release) {
            LockRuntime.mode = LockRuntime.LOCKED
            LockRuntime.persist(context)
        }
        context.startActivity(Intent(context, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("release", release))
    }

    private fun openBriefing(context: Context) {
        context.startActivity(Intent(context, BriefingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
    }

    private fun release(context: Context) {
        LockRuntime.mode = LockRuntime.OFF
        LockRuntime.persist(context)
        DeviceOwnerCtl.release(context)
        context.startActivity(Intent(context, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("release", true))
    }

    private fun chimeIfIdle(context: Context) {
        val today = java.time.LocalDate.now().toString()
        val touched = context.getSharedPreferences("aether", Context.MODE_PRIVATE).getString("briefing_touch", "")
        if (touched == today) return
        ToneGenerator(AudioManager.STREAM_ALARM, 70).apply {
            startTone(ToneGenerator.TONE_PROP_BEEP, 180)
        }
    }

    private suspend fun review(app: AetherApp) {
        val ui = app.repo.today()
        val text = "今晚复盘：驾考 ${ui.drivingDone}/${ui.drivingTarget}，热量 ${ui.calories}，${ui.sleepText}。两个硬指标只看驾考和不断链。"
        app.repo.saveMessage("ASSISTANT", text)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as AetherApp
        app.scope.launch {
            try {
                LockRuntime.restore(context)
                val boot = java.time.Instant.ofEpochMilli(os.aether.discipline.data.AetherRepository.bootInstant())
                val whenBoot = java.time.LocalDateTime.ofInstant(boot, java.time.ZoneId.systemDefault())
                val marker = File(context.filesDir, "boot.mark")
                val stamp = boot.toEpochMilli().toString()
                if (marker.takeIf { it.exists() }?.readText() != stamp) {
                    marker.writeText(stamp)
                    val t = app.repo.loadThresholds()
                    if (inLockWindow(whenBoot, t)) {
                        app.repo.rebootDuringLock(whenBoot.toLocalDate().toString())
                        LockRuntime.mode = LockRuntime.LOCKED
                        LockRuntime.persist(context)
                        context.startActivity(Intent(context, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } else if (DeviceOwnerCtl.isOwner(context)) {
                        DeviceOwnerCtl.release(context)
                    }
                }
                if (Root.available()) {
                    Root.exec("dumpsys deviceidle whitelist +${context.packageName}")
                }
            } finally {
                AlarmScheduler.schedule(app)
                pending.finish()
            }
        }
    }
}
