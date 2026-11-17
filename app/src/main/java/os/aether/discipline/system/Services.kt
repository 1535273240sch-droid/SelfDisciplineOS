package os.aether.discipline.system

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import os.aether.discipline.AetherApp
import os.aether.discipline.data.db.GpsWorkout
import os.aether.discipline.domain.AlwaysLockPackages
import os.aether.discipline.domain.paceLabel
import os.aether.discipline.domain.workoutKcal
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class WorkoutLive(
    val active: Boolean = false,
    val id: Long = 0,
    val type: String = "RUN",
    val meters: Float = 0f,
    val seconds: Long = 0,
    val points: List<Pair<Double, Double>> = emptyList(),
    val cadence: Int = 0,
    val message: String = "",
)

object WorkoutBus {
    val state = MutableStateFlow(WorkoutLive())
}

class LocationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var stepsStart = -1
    private var lastSteps = 0

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground("正在记录轨迹")
        if (intent?.getBooleanExtra("stop", false) == true) {
            scope.launch {
                finish()
                stopSelf()
            }
            return START_NOT_STICKY
        }
        val type = intent?.getStringExtra("type") ?: "RUN"
        val resumed = intent == null
        job?.cancel()
        job = scope.launch { track(type, resumed) }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private suspend fun track(type: String, resumed: Boolean) {
        val app = application as AetherApp
        val date = os.aether.discipline.data.AetherRepository.todayString()
        // A START_STICKY restart must attach to the unfinished row instead of creating a duplicate.
        val existing = if (resumed) app.repo.db.gps().latest()?.takeIf { !it.finished } else null
        val id = existing?.id ?: app.repo.db.gps().insert(
            GpsWorkout(
                date = date,
                startTime = System.currentTimeMillis(),
                durationSec = 0,
                workoutType = type,
                distanceMeters = 0f,
                avgPaceSecPerKm = 0,
                caloriesBurned = 0,
                routeGeoJson = """{"type":"LineString","coordinates":[]}""",
            ),
        )
        WorkoutBus.state.value = WorkoutLive(active = true, id = id, type = type)
        val started = existing?.startTime ?: System.currentTimeMillis()
        val points = mutableListOf<Pair<Double, Double>>()
        var meters = existing?.distanceMeters ?: 0f
        listenSteps()
        val client = runCatching { LocationServices.getFusedLocationProviderClient(this) }.getOrElse {
            WorkoutBus.state.value = WorkoutLive(message = "这台机器没有可用的定位服务")
            stopSelf()
            return
        }
        val request = com.google.android.gms.location.LocationRequest.Builder(2000)
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(1000)
            .build()
        val callback = object : com.google.android.gms.location.LocationCallback() {
            override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                val loc = result.lastLocation ?: return
                if (loc.accuracy > 40f) return
                val here = loc.latitude to loc.longitude
                val prev = points.lastOrNull()
                if (prev != null) {
                    val delta = haversine(prev.first, prev.second, here.first, here.second)
                    if (delta < 4f) return
                    meters += delta
                }
                points += here
                val seconds = (System.currentTimeMillis() - started) / 1000
                val minutes = (seconds / 60f).coerceAtLeast(1f / 60f)
                val cadence = if (lastSteps > stepsStart && stepsStart >= 0) ((lastSteps - stepsStart) / minutes).toInt() else 0
                WorkoutBus.state.value = WorkoutLive(true, id, type, meters, seconds, points.toList(), cadence)
                if (points.size % 5 == 0) {
                    val snapshot = points.toList()
                    scope.launch(Dispatchers.IO) { persist(app, id, type, meters, seconds, snapshot, cadence, false) }
                }
            }
        }
        val startedUpdates = runCatching {
            client.requestLocationUpdates(request, callback, mainLooper)
        }
        if (startedUpdates.isFailure) {
            WorkoutBus.state.value = WorkoutLive(message = "定位权限或定位服务不可用")
            stopSelf()
            return
        }
        // location updates stay registered until the service stops
        kotlinx.coroutines.awaitCancellation()
    }

    private suspend fun finish() {
        val live = WorkoutBus.state.value
        val app = application as AetherApp
        val id = live.id.takeIf { it != 0L }
            ?: app.repo.db.gps().latest()?.takeIf { !it.finished }?.id
            ?: return
        persist(app, id, live.type, live.meters, live.seconds, live.points, live.cadence, true)
        val pace = paceLabel(live.meters, live.seconds)
        val kind = if (live.type == "RUN") "跑了" else "走了"
        app.repo.saveMessage("ASSISTANT", "今天$kind ${"%.2f".format(live.meters / 1000f)} 公里，配速 $pace。")
        WorkoutBus.state.value = WorkoutLive()
    }

    private suspend fun persist(
        app: AetherApp,
        id: Long,
        type: String,
        meters: Float,
        seconds: Long,
        points: List<Pair<Double, Double>>,
        cadence: Int,
        finished: Boolean,
    ) {
        val row = app.repo.db.gps().get(id) ?: return
        val kg = app.repo.db.weight().latest()?.weightKg
            ?: app.repo.loadThresholds().targetWeightKg
            ?: 70f
        val coords = points.joinToString(",") { "[${it.second},${it.first}]" }
        app.repo.db.gps().update(
            row.copy(
                durationSec = seconds,
                distanceMeters = meters,
                avgPaceSecPerKm = if (meters < 50f) 0 else (seconds / (meters / 1000f)).toInt(),
                caloriesBurned = workoutKcal(type, kg, meters),
                avgCadenceSpm = cadence,
                finished = finished,
                routeGeoJson = if (points.isEmpty()) row.routeGeoJson else """{"type":"LineString","coordinates":[$coords]}""",
            ),
        )
    }

    private fun listenSteps() {
        val manager = getSystemService(SensorManager::class.java)
        val sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
        manager.registerListener(object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val value = event.values.firstOrNull()?.toInt() ?: return
                if (stepsStart < 0) stepsStart = value
                lastSteps = value
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun startAsForeground(text: String) {
        val notification = NotificationCompat.Builder(this, "aether.workout")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Aether")
            .setContentText(text)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(21, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(21, notification)
        }
    }
}

private fun haversine(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Float {
    val radius = 6371000.0
    val dLat = Math.toRadians(bLat - aLat)
    val dLng = Math.toRadians(bLng - aLng)
    val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLng / 2).pow(2)
    return (2 * radius * asin(sqrt(h))).toFloat()
}

class LockWatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val open = PendingIntent.getActivity(this, 3, Intent(this, LockActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = NotificationCompat.Builder(this, "aether.lock")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("锁屏看守")
            .setContentText("到点前保持遮罩")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(22, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(22, notification)
        }
        scope.launch {
            while (LockRuntime.mode == LockRuntime.LOCKED) {
                kotlinx.coroutines.delay(5000)
                if (LockRuntime.mode != LockRuntime.LOCKED) break
                val front = foregroundPackage()
                if (front != null && front != packageName && front !in AlwaysLockPackages && front != "com.android.systemui") {
                    (application as AetherApp).repo.escape(detail = front)
                    startActivity(Intent(this@LockWatchService, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun foregroundPackage(): String? {
        if (!usageAllowed(this)) return null
        val usm = getSystemService(android.app.usage.UsageStatsManager::class.java)
        val end = System.currentTimeMillis()
        val events = usm.queryEvents(end - 8000, end)
        val event = android.app.usage.UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) last = event.packageName
        }
        return last
    }
}

class GuardService : android.accessibilityservice.AccessibilityService() {
    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (LockRuntime.mode != LockRuntime.LOCKED && !LockRuntime.inEmergency()) return
        if (pkg == packageName || pkg in AlwaysLockPackages) return
        if (LockRuntime.inEmergency() && pkg in LockRuntime.whitelist) return
        if (LockRuntime.mode != LockRuntime.LOCKED) return
        (application as AetherApp).scope.launch {
            (application as AetherApp).repo.escape(detail = pkg)
            startActivity(Intent(this@GuardService, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun onInterrupt() = Unit
}
