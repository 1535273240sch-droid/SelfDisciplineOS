package os.aether.discipline.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import os.aether.discipline.data.AetherRepository
import os.aether.discipline.data.TodayUi
import os.aether.discipline.domain.mealTypeFor
import java.time.LocalDate
import java.time.LocalTime

@Composable
fun RecordScreen(repo: AetherRepository, initialSection: String = "all", past: Boolean = false) {
    val scope = rememberCoroutineScope()
    val today by remember(repo) { repo.observeToday() }.collectAsStateWithLifecycle(TodayUi())
    var date by rememberSaveable { mutableStateOf(LocalDate.now().minusDays(1).toString()) }
    var weight by rememberSaveable { mutableStateOf("") }
    var fasting by rememberSaveable { mutableStateOf(true) }
    var sleep by rememberSaveable { mutableStateOf("") }
    var wake by rememberSaveable { mutableStateOf("") }
    var food by rememberSaveable { mutableStateOf("") }
    var calories by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var weightNote by rememberSaveable { mutableStateOf("") }
    var sleepNote by rememberSaveable { mutableStateOf("") }
    var dietNote by rememberSaveable { mutableStateOf("") }
    val clock = remember { Regex("^([01]?\\d|2[0-3]):[0-5]\\d$") }

    fun save(showResult: (String) -> Unit, operation: suspend (String) -> String) {
        if (saving) return
        val day = if (past) date.trim() else LocalDate.now().toString()
        if (past && !runCatching { LocalDate.parse(day).isBefore(LocalDate.now()) }.getOrDefault(false)) {
            showResult("日期要早于今天，格式为 YYYY-MM-DD")
            return
        }
        saving = true
        scope.launch {
            try {
                showResult(operation(day))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showResult("保存失败，请重试。")
            } finally {
                saving = false
            }
        }
    }

    ScreenColumn {
        PageTitle(
            if (past) "补录历史" else "今日记录",
            if (past) "补录后可在趋势里查看，已结算的连续天数保持原结果。"
            else "保存后可在今日页查看硬指标进度。",
        )
        if (past) {
            GlassCard {
                RecordField(date, { date = it }, "日期 YYYY-MM-DD")
            }
        }
        if (initialSection != "sleep") {
            if (initialSection == "diet") SectionTitle("饮食与体重")
            GlassCard {
                SectionTitle("体重")
                if (!past && today.weightLogged) Quiet("最新记录 · ${today.weightText}")
                RecordField(weight, { weight = it }, "体重 kg", KeyboardType.Decimal)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(fasting, { fasting = it })
                    Body("空腹")
                }
                PrimaryButton("保存体重", enabled = !saving) {
                    save({ weightNote = it }) { day ->
                        val kg = weight.trim().replace(',', '.').toFloatOrNull()
                        if (kg == null || kg !in 30f..250f) "体重须在 30–250 kg 之间"
                        else {
                            repo.recordWeight(kg, fasting, if (past) "BACKFILL" else "MANUAL", backfill = past, date = day)
                            weight = ""
                            if (past) "已补录体重" else "已保存体重"
                        }
                    }
                }
                if (weightNote.isNotBlank()) Quiet(weightNote)
            }
            GlassCard {
                SectionTitle("饮食")
                if (!past && today.dietLogged) Quiet("今日已记录 ${today.calories} kcal")
                RecordField(food, { food = it }, "吃了什么")
                RecordField(calories, { calories = it.filter { char -> char in '0'..'9' } }, "热量 kcal（可留空）", KeyboardType.Number)
                PrimaryButton("保存饮食", enabled = !saving) {
                    save({ dietNote = it }) { day ->
                        val kcal = calories.toIntOrNull()
                        when {
                            food.isBlank() -> "请先填写吃了什么"
                            calories.isNotBlank() && kcal == null -> "请输入有效的热量"
                            else -> {
                                repo.recordDiet(
                                    mealTypeFor(LocalTime.now().hour), food, kcal ?: 0, 0f, 0f, 0f,
                                    needsBackfill = kcal == null, backfill = past, date = day,
                                )
                                food = ""
                                calories = ""
                                if (past) "已补录饮食" else "已保存饮食"
                            }
                        }
                    }
                }
                if (dietNote.isNotBlank()) Quiet(dietNote)
            }
        }
        if (initialSection != "diet") {
            SleepCard(past, today, sleep, wake, { sleep = it }, { wake = it }, sleepNote, saving) {
                save({ sleepNote = it }) { day ->
                    val bed = sleep.trim().replace('：', ':')
                    val morning = wake.trim().replace('：', ':')
                    when {
                        !clock.matches(bed) -> "入睡时间要写成 HH:mm"
                        morning.isNotEmpty() && !clock.matches(morning) -> "起床时间要写成 HH:mm"
                        else -> {
                            repo.recordSleep(bed, morning.ifBlank { null }, sourceBackfill = past, date = day)
                            sleep = ""
                            wake = ""
                            if (past) "已补录作息" else "已保存作息"
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SleepCard(
    past: Boolean, today: TodayUi, sleep: String, wake: String,
    onSleep: (String) -> Unit, onWake: (String) -> Unit, note: String, saving: Boolean, onSave: () -> Unit,
) {
    GlassCard {
        SectionTitle("昨晚作息")
        if (!past && today.sleepLogged) Quiet(today.sleepText)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RecordField(sleep, onSleep, "入睡 HH:mm", modifier = Modifier.weight(1f))
            RecordField(wake, onWake, "起床（选填）", modifier = Modifier.weight(1f))
        }
        PrimaryButton("保存作息", enabled = !saving, onClick = onSave)
        if (note.isNotBlank()) Quiet(note)
    }
}

@Composable
private fun RecordField(
    value: String, onValue: (String) -> Unit, label: String,
    keyboard: KeyboardType = KeyboardType.Text, modifier: Modifier = Modifier.fillMaxWidth(),
) {
    OutlinedTextField(
        value, onValue, label = { Text(label) }, modifier = modifier, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
    )
}
