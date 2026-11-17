package os.aether.discipline.system

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import os.aether.discipline.AetherApp
import os.aether.discipline.domain.AlwaysLockPackages
import os.aether.discipline.domain.isWeekend
import os.aether.discipline.ui.AccentButton
import os.aether.discipline.ui.AetherTheme
import os.aether.discipline.ui.Body
import os.aether.discipline.ui.GlassCard
import os.aether.discipline.ui.Quiet
import java.time.LocalDate
import java.time.LocalTime

class LockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (intent.getBooleanExtra("release", false)) {
            releaseAndFinish()
            return
        }
        LockRuntime.mode = if (LockRuntime.inEmergency()) LockRuntime.EMERGENCY else LockRuntime.LOCKED
        LockRuntime.persist(this)
        runCatching { ContextCompat.startForegroundService(this, android.content.Intent(this, LockWatchService::class.java)) }
        setContent {
            val app = application as AetherApp
            val scope = rememberCoroutineScope()
            var clock by remember { mutableStateOf(LocalTime.now().toString().take(5)) }
            var note by remember { mutableStateOf("今晚的复盘还在路上。") }
            var pin by remember { mutableStateOf("") }
            var message by remember { mutableStateOf("") }
            var asking by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                val ui = app.repo.today()
                note = app.repo.morningLines().ifBlank { "连续 ${ui.streak} 天。${ui.brokenReason}" }
                val minutes = app.repo.loadThresholds().screenOffMin
                delay(minutes * 60_000L)
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val attrs = window.attributes
                attrs.screenBrightness = 0.01f
                window.attributes = attrs
                Root.exec("input keyevent 26")
            }
            LaunchedEffect(Unit) {
                while (true) {
                    clock = LocalTime.now().toString().take(5)
                    delay(1000)
                }
            }
            AetherTheme("midnight") {
                Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Quiet(if (LockRuntime.inEmergency()) "紧急通行中" else "该睡了")
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize().weight(1f, false)) {
                        Text(clock, color = androidx.compose.ui.graphics.Color.White, fontSize = 72.sp, fontWeight = FontWeight.Light, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(18.dp))
                        GlassCard { Body(note) }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (asking) {
                            OutlinedTextField(pin, { if (it.length <= 6) pin = it.filter(Char::isDigit) }, label = { Text("6 位紧急密码") })
                            AccentButton("确认解锁", {
                                scope.launch {
                                    if (!app.repo.checkPin(pin)) {
                                        message = "密码不对，或者还没设置"
                                        return@launch
                                    }
                                    val result = app.repo.grantEmergency()
                                    message = result.message
                                    if (!result.ok) return@launch
                                    LockRuntime.mode = LockRuntime.EMERGENCY
                                    LockRuntime.emergencyUntil = result.until
                                    LockRuntime.suppressStop = true
                                    LockRuntime.persist(this@LockActivity)
                                    AlarmScheduler.relockAt(this@LockActivity, result.until)
                                    applyTask(app)
                                    finish()
                                }
                            })
                        } else {
                            AccentButton("紧急工作解锁") { asking = true }
                        }
                        AccentButton("今晚放弃", {
                            scope.launch {
                                app.repo.giveUp()
                                LockRuntime.mode = LockRuntime.GIVEN_UP
                                LockRuntime.suppressStop = true
                                LockRuntime.persist(this@LockActivity)
                                releaseAndFinish()
                            }
                        })
                        if (message.isNotBlank()) Quiet(message)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        LockRuntime.screenOff = false
        val attrs = window.attributes
        attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = attrs
        val app = application as AetherApp
        app.scope.launch { applyTask(app) }
    }

    override fun onStop() {
        val off = !window.decorView.isShown || !getSystemService(android.os.PowerManager::class.java).isInteractive
        if (LockRuntime.suppressStop || LockRuntime.screenOff || off || LockRuntime.mode != LockRuntime.LOCKED) {
            LockRuntime.suppressStop = false
        } else {
            (application as AetherApp).scope.launch {
                (application as AetherApp).repo.escape(detail = "离开遮罩")
            }
        }
        super.onStop()
    }

    private suspend fun applyTask(app: AetherApp) {
        val t = app.repo.loadThresholds()
        val packages = if (LockRuntime.inEmergency()) {
            (t.whitelistPackages + packageName).distinct()
        } else {
            (AlwaysLockPackages + packageName).distinct()
        }
        LockRuntime.whitelist = packages.toSet()
        DeviceOwnerCtl.apply(this, packages.toTypedArray())
        runCatching { startLockTask() }
    }

    private fun releaseAndFinish() {
        LockRuntime.mode = LockRuntime.OFF
        LockRuntime.persist(this)
        DeviceOwnerCtl.release(this)
        runCatching { stopLockTask() }
        finish()
    }
}

class BriefingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContent {
            val app = application as AetherApp
            val scope = rememberCoroutineScope()
            var lines by remember { mutableStateOf("正在整理今早的简报。") }
            var news by remember { mutableStateOf("") }
            LaunchedEffect(Unit) {
                val t = app.repo.loadThresholds()
                val weekend = isWeekend(LocalDate.now())
                if (!weekend || LocalTime.now().isAfter(t.weekendSilentUntil)) {
                    ToneGenerator(AudioManager.STREAM_ALARM, 60).startTone(ToneGenerator.TONE_PROP_BEEP, 160)
                }
                lines = app.repo.morningLines()
                news = app.repo.today().briefing
            }
            AetherTheme("frost") {
                Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Quiet("早晨")
                    GlassCard { Body(lines) }
                    GlassCard { Quiet(news.take(700)) }
                    AccentButton("开始今天", {
                        scope.launch {
                            getSharedPreferences("aether", MODE_PRIVATE).edit().putString("briefing_touch", LocalDate.now().toString()).apply()
                            LockRuntime.mode = LockRuntime.OFF
                            LockRuntime.suppressStop = true
                            LockRuntime.persist(this@BriefingActivity)
                            DeviceOwnerCtl.release(this@BriefingActivity)
                            runCatching { stopLockTask() }
                            finish()
                        }
                    })
                }
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        getSharedPreferences("aether", MODE_PRIVATE).edit().putString("briefing_touch", LocalDate.now().toString()).apply()
        return super.dispatchTouchEvent(ev)
    }
}
