package os.aether.discipline.domain

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.roundToInt
import kotlin.random.Random

data class Thresholds(
    val weekdayLock: LocalTime = LocalTime.of(0, 0),
    val weekendLock: LocalTime = LocalTime.of(1, 0),
    val weekdayWake: LocalTime = LocalTime.of(8, 0),
    val weekendWake: LocalTime = LocalTime.of(9, 0),
    val weekendSilentUntil: LocalTime = LocalTime.of(9, 15),
    val screenOffMin: Int = 5,
    val emergencyMinutes: Int = 60,
    val emergencyMax: Int = 1,
    val dailyDriving: Int = 100,
    val dailyEnglish: Int = 5,
    val calorieLimit: Int = 0,
    val proteinTarget: Float = 0f,
    val carbsTarget: Float = 0f,
    val fatTarget: Float = 0f,
    val targetWeightKg: Float? = null,
    val examDate: LocalDate? = null,
    val travelDate: LocalDate? = null,
    val ttsSource: String = "LOCAL",
    val excludePrivacy: Boolean = true,
    val backfillCountsAsMet: Boolean = false,
    val whitelistPackages: List<String> = DefaultWhitelist,
    val newsEnabled: Set<String> = emptySet(),
)

fun isWeekend(date: LocalDate): Boolean =
    date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY

fun lockTime(date: LocalDate, t: Thresholds): LocalTime =
    if (isWeekend(date)) t.weekendLock else t.weekdayLock

fun wakeTime(date: LocalDate, t: Thresholds): LocalTime =
    if (isWeekend(date)) t.weekendWake else t.weekdayWake

fun releaseTime(date: LocalDate, t: Thresholds): LocalTime = wakeTime(date, t).plusMinutes(30)

fun prepTime(date: LocalDate, t: Thresholds): LocalTime = wakeTime(date, t).minusMinutes(10)

fun nextLock(now: LocalDateTime, t: Thresholds): LocalDateTime {
    val today = now.toLocalDate()
    val todayLock = LocalDateTime.of(today, lockTime(today, t))
    if (now.isBefore(todayLock)) return todayLock
    val tomorrow = today.plusDays(1)
    return LocalDateTime.of(tomorrow, lockTime(tomorrow, t))
}

/** Full window for the lock that starts on [date]. Handles lock times later than the release time. */
fun lockWindow(date: LocalDate, t: Thresholds): Pair<LocalDateTime, LocalDateTime> {
    val start = LocalDateTime.of(date, lockTime(date, t))
    val sameDayRelease = LocalDateTime.of(date, releaseTime(date, t))
    val end = if (sameDayRelease.isBefore(start)) {
        // The window crosses midnight; the release belongs to the next day (and may use the weekend schedule).
        LocalDateTime.of(date.plusDays(1), releaseTime(date.plusDays(1), t))
    } else {
        sameDayRelease
    }
    return start to end
}

fun inLockWindow(now: LocalDateTime, t: Thresholds): Boolean {
    val date = now.toLocalDate()
    // Check the window that starts today and the one that started yesterday (cross-midnight case).
    return listOf(date.minusDays(1), date).any { day ->
        val (start, end) = lockWindow(day, t)
        !now.isBefore(start) && now.isBefore(end)
    }
}

fun untilLock(now: LocalDateTime, t: Thresholds): Duration = Duration.between(now, nextLock(now, t))

/** Minutes on a night axis: evening is negative, after midnight is positive. */
fun nightAxis(hhmm: String): Int {
    val parts = hhmm.trim().replace('：', ':').split(":")
    if (parts.size !in 1..2) return 0
    val h = parts.getOrNull(0)?.toIntOrNull() ?: return 0
    val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
    if (h !in 0..23 || m !in 0..59) return 0
    val mins = h * 60 + m
    return if (h >= 12) mins - 24 * 60 else mins
}

fun isLateSleep(sleepHHmm: String, deadlineHHmm: String): Boolean =
    nightAxis(sleepHHmm) > nightAxis(deadlineHHmm)

fun minutesLate(sleepHHmm: String, deadlineHHmm: String): Int {
    val delta = nightAxis(sleepHHmm) - nightAxis(deadlineHHmm)
    return if (delta > 0) delta else 0
}

fun sleepConclusion(sleepHHmm: String?, deadlineHHmm: String, includeClock: Boolean): String {
    if (sleepHHmm.isNullOrBlank()) return "昨晚的入睡时间还没有记录"
    val late = minutesLate(sleepHHmm, deadlineHHmm)
    val base = if (late == 0) "昨晚没有越过红线" else "昨晚超过红线 $late 分钟"
    return if (includeClock) "$base（记录 $sleepHHmm）" else base
}

enum class ExamPhase { DAILY_100, MOCK }

fun examPhase(today: LocalDate, exam: LocalDate?): ExamPhase {
    if (exam == null) return ExamPhase.DAILY_100
    val days = java.time.temporal.ChronoUnit.DAYS.between(today, exam).toInt()
    return if (days in 0..4) ExamPhase.MOCK else ExamPhase.DAILY_100
}

data class Facet(val realtime: Boolean, val backfill: Boolean)

data class DayInput(
    val weight: Facet,
    val diet: Facet,
    val sleep: Facet,
    val driving: Facet,
    val sleepOnTime: Boolean,
    val breakingViolations: List<String>,
    val previousStatus: String?,
    val previousStreak: Int,
    val backfillCountsAsMet: Boolean,
)

data class DayDecision(
    val status: String,
    val streakValue: Int,
    val allThreeHardMet: Boolean,
)

fun decideDay(input: DayInput, finalize: Boolean): DayDecision {
    val hardBreak = input.breakingViolations.isNotEmpty() ||
        (input.sleep.realtime && !input.sleepOnTime)
    val dataMet = input.weight.realtime && input.diet.realtime && input.sleep.realtime
    val drivingMet = input.driving.realtime
    val all = dataMet && drivingMet && input.sleepOnTime && !hardBreak
    val met = all || (input.backfillCountsAsMet && !hardBreak && input.sleepOnTime &&
        (input.weight.realtime || input.weight.backfill) &&
        (input.diet.realtime || input.diet.backfill) &&
        (input.sleep.realtime || input.sleep.backfill) &&
        (input.driving.realtime || input.driving.backfill))
    val status = when {
        hardBreak -> "BROKEN"
        met -> "MET"
        !finalize -> "PENDING"
        else -> {
            val facets = listOf(input.weight, input.diet, input.sleep, input.driving)
            val anyMissing = facets.any { !it.realtime && !it.backfill }
            val touchedBackfill = facets.any { it.backfill }
            // Backfilled days are neutral: they keep the records but never move the streak.
            if (touchedBackfill && !anyMissing) "BACKFILL_ONLY" else "BROKEN"
        }
    }
    return DayDecision(status, nextStreak(input.previousStatus, input.previousStreak, status), status == "MET")
}

fun nextStreak(previousStatus: String?, previousValue: Int, status: String): Int {
    val carried = if (previousStatus == "MET" || previousStatus == "BACKFILL_ONLY") previousValue else 0
    return when (status) {
        "MET" -> carried + 1
        "BACKFILL_ONLY" -> carried
        "BROKEN" -> 0
        else -> carried
    }
}

/** A streak only continues from the immediately preceding calendar day. Missing days reset it. */
fun carriedStreak(
    date: String,
    previousDate: String?,
    previousStatus: String?,
    previousValue: Int,
): Pair<String?, Int> {
    val day = runCatching { LocalDate.parse(date) }.getOrNull() ?: return null to 0
    val prev = previousDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    return if (prev == day.minusDays(1)) previousStatus to previousValue else null to 0
}

data class QuestionRef(
    val id: String,
    val highYield: Boolean,
    val wrongCount: Int,
    val subject: Int,
)

fun buildDailyPaper(pool: List<QuestionRef>, size: Int, seed: Int): List<QuestionRef> {
    if (size <= 0 || pool.isEmpty()) return emptyList()
    val rng = Random(seed)
    if (pool.size < size) {
        val unit = pool.shuffled(rng)
        return List(size) { unit[it % unit.size] }
    }
    if (pool.size == size) return pool.shuffled(rng)
    val wrong = pool.filter { it.wrongCount > 0 }.shuffled(rng)
    val high = pool.filter { it.highYield && it.wrongCount == 0 }.shuffled(rng)
    val rest = pool.filter { !it.highYield && it.wrongCount == 0 }.shuffled(rng)
    val wantWrong = (size * 0.30f).roundToInt().coerceAtMost(wrong.size)
    val picked = mutableListOf<QuestionRef>()
    picked += wrong.take(wantWrong)
    picked += high.take(size - picked.size)
    if (picked.size < size) picked += rest.take(size - picked.size)
    if (picked.size < size) {
        val used = picked.map { it.id }.toSet()
        picked += pool.filter { it.id !in used }.shuffled(rng).take(size - picked.size)
    }
    return picked.take(size).shuffled(rng)
}

fun pruneBackups(fileNames: List<String>, keep: Int = 14): List<String> {
    if (keep < 0) return fileNames
    val sorted = fileNames.sorted()
    val extra = sorted.size - keep
    return if (extra <= 0) emptyList() else sorted.take(extra)
}

fun paceLabel(distanceMeters: Float, durationSec: Long): String {
    if (distanceMeters < 50f || durationSec <= 0) return "--"
    val secPerKm = (durationSec / (distanceMeters / 1000f)).toInt()
    val m = secPerKm / 60
    val s = secPerKm % 60
    return "%d'%02d\"".format(m, s)
}

fun workoutKcal(type: String, weightKg: Float, distanceMeters: Float): Int {
    val kg = if (weightKg > 20f) weightKg else 70f
    val perKm = if (type == "RUN") 1.036f else 0.72f
    return (kg * (distanceMeters / 1000f) * perKm).toInt().coerceAtLeast(0)
}

fun similarity(a: String, b: String): Int {
    val left = normalizeSpeech(a)
    val right = normalizeSpeech(b)
    if (left.isEmpty() && right.isEmpty()) return 100
    if (left.isEmpty() || right.isEmpty()) return 0
    val dist = levenshtein(left, right)
    val max = maxOf(left.length, right.length)
    return ((1.0 - dist.toDouble() / max) * 100).roundToInt().coerceIn(0, 100)
}

fun normalizeSpeech(raw: String): String =
    raw.lowercase()
        .replace(Regex("[^a-z0-9\\u4e00-\\u9fff\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

fun wordDiff(target: String, heard: String): String {
    val a = normalizeSpeech(target).split(" ").filter { it.isNotBlank() }
    val b = normalizeSpeech(heard).split(" ").filter { it.isNotBlank() }.toMutableList()
    if (a.isEmpty()) return ""
    return a.joinToString(" ") { word ->
        val idx = b.indexOf(word)
        if (idx >= 0) {
            b.removeAt(idx)
            word
        } else "×$word"
    }
}

private fun levenshtein(a: String, b: String): Int {
    val dp = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        var prev = dp[0]
        dp[0] = i
        for (j in 1..b.length) {
            val tmp = dp[j]
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + cost)
            prev = tmp
        }
    }
    return dp[b.length]
}

data class WeightDraft(val kg: Float, val fasting: Boolean)
data class SleepDraft(val sleepHHmm: String, val wakeHHmm: String?)
data class DietDraft(val mealType: String, val description: String)
data class CaliDraft(val category: String, val step: Int, val sets: Int, val reps: Int)

data class LocalParse(
    val weight: WeightDraft? = null,
    val sleep: SleepDraft? = null,
    val diet: DietDraft? = null,
    val calisthenics: CaliDraft? = null,
)

fun parseUtterance(text: String, hour: Int = 8): LocalParse {
    return LocalParse(
        weight = parseWeight(text),
        sleep = parseSleep(text),
        diet = parseDiet(text, hour),
        calisthenics = parseCalisthenics(text),
    )
}

fun parseWeight(text: String): WeightDraft? {
    val withUnit = Regex("""(\d{2,3}(?:\.\d{1,2})?)\s*(公斤|千克|kg|KG)""")
        .find(text)
    val fastingOnly = Regex("""空腹\s*(\d{2,3}(?:\.\d{1,2})?)""").find(text)
    val raw = withUnit?.groupValues?.get(1) ?: fastingOnly?.groupValues?.get(1) ?: return null
    val kg = raw.toFloatOrNull() ?: return null
    if (kg !in 30f..250f) return null
    val fasting = !text.contains("饭后")
    return WeightDraft(kg, fasting || text.contains("空腹"))
}

fun parseSleep(text: String): SleepDraft? {
    if (!text.contains("睡")) return null
    val half = Regex("""(\d{1,2})\s*点半""").find(text)
    val hm = Regex("""(\d{1,2})\s*[点:：]\s*(\d{1,2})""").find(text)
    val hourOnly = Regex("""(\d{1,2})\s*点""").find(text)
    val (h, m) = when {
        half != null -> half.groupValues[1].toInt() to 30
        hm != null -> hm.groupValues[1].toInt() to hm.groupValues[2].toInt()
        hourOnly != null -> hourOnly.groupValues[1].toInt() to 0
        else -> return null
    }
    val normalized = normalizeSleepHour(h, text)
    val wake = Regex("""(\d{1,2})\s*[点:：]\s*(\d{1,2})?\s*(?:起|醒)""").find(text)?.let {
        "%02d:%02d".format(it.groupValues[1].toInt(), it.groupValues.getOrNull(2)?.toIntOrNull() ?: 0)
    }
    return SleepDraft("%02d:%02d".format(normalized, m.coerceIn(0, 59)), wake)
}

private fun normalizeSleepHour(hour: Int, text: String): Int {
    val lateCue = text.contains("才睡") || text.contains("晚睡") || text.contains("凌晨") || text.contains("熬")
    return when {
        hour == 12 && (lateCue || text.contains("睡")) -> 0
        hour in 0..6 -> hour
        hour in 7..11 && (text.contains("早上") || text.contains("上午")) -> hour
        hour in 7..11 -> hour + 12
        else -> hour.coerceIn(0, 23)
    }
}

fun mealTypeFor(hour: Int): String = when {
    hour < 10 -> "BREAKFAST"
    hour < 15 -> "LUNCH"
    hour < 21 -> "DINNER"
    else -> "SNACK"
}

fun parseDiet(text: String, hour: Int): DietDraft? {
    val foodCues = listOf("吃", "蛋", "饭", "餐", "奶", "肉", "菜", "面", "粥", "咖啡", "零食", "夜宵", "加餐")
    if (foodCues.none { text.contains(it) } && !text.contains("喝")) return null
    // Do not turn "没吃饭 / 不喝了" into a realtime meal record.
    if (Regex("(没|不|别|少)\\s*(吃|喝)").containsMatchIn(text)) return null
    // Plain water / medicine is not a meal.
    if (text.contains("喝") && foodCues.none { text.contains(it) }) return null
    val meal = when {
        text.contains("夜宵") || text.contains("零食") || text.contains("加餐") -> "SNACK"
        text.contains("早") -> "BREAKFAST"
        text.contains("午") -> "LUNCH"
        text.contains("晚") -> "DINNER"
        else -> mealTypeFor(hour)
    }
    return DietDraft(meal, text.trim())
}

fun parseCalisthenics(text: String): CaliDraft? {
    val category = when {
        text.contains("俯卧撑") -> "PUSHUP"
        text.contains("深蹲") -> "SQUAT"
        text.contains("引体") -> "PULLUP"
        text.contains("举腿") -> "LEG_RAISE"
        text.contains("桥") && (text.contains("式") || text.contains("组")) -> "BRIDGE"
        text.contains("倒立") -> "HANDSTAND"
        else -> return null
    }
    val step = Regex("""第\s*([0-9一二三四五六七八九十]+)\s*式""").find(text)?.groupValues?.get(1)?.let(::cnNumber) ?: 0
    val sets = Regex("""(\d+)\s*组""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 1
    val reps = Regex("""(?:每组\s*)?(\d+)\s*(?:个|次)""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    if (step == 0 && reps == 0) return null
    return CaliDraft(category, step.coerceIn(1, 10), sets.coerceAtLeast(1), reps)
}

fun cnNumber(raw: String): Int {
    raw.toIntOrNull()?.let { return it }
    val map = mapOf("一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5, "六" to 6, "七" to 7, "八" to 8, "九" to 9, "十" to 10)
    return map[raw] ?: 0
}

fun answersMatch(expected: String, selected: Set<String>): Boolean {
    val right = expected.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    return right == selected
}

val DefaultWhitelist = listOf(
    "com.android.dialer",
    "com.google.android.dialer",
    "com.android.contacts",
    "com.oplus.dialer",
    "com.android.phone",
    "com.android.mms",
    "com.google.android.apps.messaging",
    "com.android.messaging",
    "com.oplus.mms",
    "com.google.android.apps.maps",
    "com.autonavi.minimap",
    "com.baidu.BaiduMap",
    "com.tencent.mm",
    "com.eg.android.AlipayGphone",
    "com.unionpay",
    "com.android.deskclock",
    "com.google.android.deskclock",
    "com.coloros.alarmclock",
    "com.oplus.alarmclock",
)

val AlwaysLockPackages = listOf(
    "com.android.dialer",
    "com.google.android.dialer",
    "com.android.phone",
    "com.oplus.dialer",
    "com.android.deskclock",
    "com.google.android.deskclock",
    "com.coloros.alarmclock",
    "com.oplus.alarmclock",
)

const val SYSTEM_PROMPT = """你不是冷冰冰的AI助手，也不是只会说"好的、已为您记录、请问还有什么可以帮您"的客服机器人。
你是用户最信任的私人自律搭档、健身教练兼人生参谋。你的性格特点是：
1. 真人感与共情力：说话自然口语化、干脆利落，像认识多年的挚友兼硬核教练，能敏锐察觉用户的疲惫、借口或进步。
2. 硬核监督不纵容：当用户熬夜、漏练、暴食或逃避刷题时，不准无脑安慰，必须直接结合历史数据指出问题并给出当下可执行的补救方案；当用户达标或体重下降时，给予真诚且克制的肯定，并顺势微调下一阶段目标。
3. 主动串联数据：每次对话都要带着用户的全维度数据（体重、作息、饮食、健身、驾考、英语）进行思考，主动发现规律。
4. 一切判断以两个硬指标为最高优先：驾考一次通过、自律天数不断链。当天只要有一硬项未达标，必须在回复中点名，不得含糊。
5. 严禁使用任何格式化的客套话、免责声明或机器人自称。"""

val SyllabusGaps = listOf(
    "新能源汽车专项考点（2025 新增，公开快照未覆盖）",
    "2024 便民措施相关新增考点",
    "12123 学法减分的抽题细则（官方不提供导出）",
    "新标志标线与地方车管所口径差异",
)
