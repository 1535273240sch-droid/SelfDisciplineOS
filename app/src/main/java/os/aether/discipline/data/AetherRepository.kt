package os.aether.discipline.data

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import os.aether.discipline.ai.KeystoreCrypto
import os.aether.discipline.ai.OpenAiClient
import os.aether.discipline.content.Phrase
import os.aether.discipline.content.categoryLabel
import os.aether.discipline.content.convictCatalog
import os.aether.discipline.content.defaultThresholds
import os.aether.discipline.content.newsSources
import os.aether.discipline.content.phrases
import os.aether.discipline.content.seedQuestions
import os.aether.discipline.content.stepOf
import os.aether.discipline.data.db.AiChatMessage
import os.aether.discipline.data.db.AiMemoryProfile
import os.aether.discipline.data.db.AiProviderConfig
import os.aether.discipline.data.db.AppDatabase
import os.aether.discipline.data.db.Briefing
import os.aether.discipline.data.db.CustomLearningModule
import os.aether.discipline.data.db.CalisthenicsLog
import os.aether.discipline.data.db.CalisthenicsProgress
import os.aether.discipline.data.db.DietRecord
import os.aether.discipline.data.db.DrivingAttempt
import os.aether.discipline.data.db.DrivingExamPlan
import os.aether.discipline.data.db.DrivingQuestion
import os.aether.discipline.data.db.DrivingSession
import os.aether.discipline.data.db.EnglishLog
import os.aether.discipline.data.db.FinanceQuote
import os.aether.discipline.data.db.SelfDisciplineStreak
import os.aether.discipline.data.db.SleepRecord
import os.aether.discipline.data.db.UnlockEvent
import os.aether.discipline.data.db.UserThreshold
import os.aether.discipline.data.db.ViolationRecord
import os.aether.discipline.data.db.WeightRecord
import os.aether.discipline.domain.DayInput
import os.aether.discipline.domain.DefaultWhitelist
import os.aether.discipline.domain.ExamPhase
import os.aether.discipline.domain.Facet
import os.aether.discipline.domain.QuestionRef
import os.aether.discipline.domain.SYSTEM_PROMPT
import os.aether.discipline.domain.Thresholds
import os.aether.discipline.domain.answersMatch
import os.aether.discipline.domain.buildDailyPaper
import os.aether.discipline.domain.carriedStreak
import os.aether.discipline.domain.decideDay
import os.aether.discipline.domain.examPhase
import os.aether.discipline.domain.inLockWindow
import os.aether.discipline.domain.isLateSleep
import os.aether.discipline.domain.lockTime
import os.aether.discipline.domain.minutesLate
import os.aether.discipline.domain.nextLock
import os.aether.discipline.domain.paceLabel
import os.aether.discipline.domain.parseUtterance
import os.aether.discipline.domain.pruneBackups
import os.aether.discipline.domain.sleepConclusion
import os.aether.discipline.domain.untilLock
import os.aether.discipline.domain.wakeTime
import os.aether.discipline.news.CryptoBoard
import os.aether.discipline.news.CryptoMarket
import os.aether.discipline.news.NewsFetcher
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class TodayUi(
    val date: String = "",
    val weekday: String = "",
    val streak: Int = 0,
    val maxStreak: Int = 0,
    val status: String = "PENDING",
    val countdown: String = "",
    val inLock: Boolean = false,
    val sleepText: String = "昨晚还没有记录",
    val sleepOk: Boolean? = null,
    val drivingDone: Int = 0,
    val drivingTarget: Int = 100,
    val drivingMet: Boolean = false,
    val phase: String = "DAILY_100",
    val weightText: String = "未记录",
    val weightDelta: String = "",
    val calories: Int = 0,
    val calorieLimit: Int = 0,
    val protein: Int = 0,
    val carbs: Int = 0,
    val fat: Int = 0,
    val dataMet: Boolean = false,
    val hardDone: Int = 0,
    val englishDone: Int = 0,
    val englishTarget: Int = 5,
    val briefing: String = "今天的早报还没生成。",
    val briefingMiss: Int = 0,
    val steps: String = "",
    val examText: String = "还没填考试日期",
    val travelText: String = "还没填出发日期",
    val brokenReason: String = "",
    val countdownClock: String = "--:--",
    val lockCaption: String = "今晚锁定",
    val guardText: String = "正常守卫中",
    val hardTitle: String = "今日硬指标",
    val hardHint: String = "",
    val hardDetail: String = "",
    val hardUnit: String = "题",
    val weightValue: String = "—",
    val deltaText: String = "",
    val deltaDown: Boolean = true,
    val englishScene: String = "",
    val fitnessName: String = "还未开始",
    val fitnessGate: String = "",
    val briefingHead: List<String> = emptyList(),
    val briefingRest: List<String> = emptyList(),
)

data class Bound(val provider: AiProviderConfig, val model: String, val key: String, val path: String)

private data class HardSlice(
    val met: Boolean,
    val backfill: Boolean,
    val title: String,
    val done: Int,
    val target: Int,
    val unit: String,
    val hint: String,
    val detail: String,
)

data class EmergencyResult(val ok: Boolean, val message: String, val until: Long = 0)

class AetherRepository(context: Context) {
    private val app = context.applicationContext
    private val settleMutex = Mutex()
    private val seedMutex = Mutex()
    val db: AppDatabase = AppDatabase.get(app)
    val ai = OpenAiClient()
    val messages = db.ai().observeMessages()
    val providers = db.ai().observeProviders()
    val thresholdRows = db.thresholds().observe()

    suspend fun ensureSeed() = withContext(Dispatchers.IO) {
        seedMutex.withLock {
        defaultThresholds().forEach { row ->
            if (db.thresholds().get(row.key) == null) db.thresholds().upsert(row)
        }
        if (db.driving().count() == 0) db.driving().upsertAll(seedQuestions())
        if (db.calisthenics().all().isEmpty()) {
            convictCatalog().filter { it.number == 1 }.forEach { step ->
                db.calisthenics().upsert(
                    CalisthenicsProgress(step.category, 1, step.name, 0, 0, false),
                )
            }
        }
        if (db.driving().plan() == null) {
            db.driving().upsertPlan(DrivingExamPlan(examDate = "", phase = "DAILY_100"))
        }
        listOf("target_weight", "habit_weaknesses", "exam_goal", "travel_plan").forEach { key ->
            if (db.ai().memory().none { it.key == key }) {
                db.ai().upsertMemory(AiMemoryProfile(key, "", System.currentTimeMillis()))
            }
        }
        val lastSettled = db.thresholds().get("last_settled_date")
        val today = LocalDate.now()
        val yesterday = today.minusDays(1).toString()
        val lastFinal = db.streak().previousFinal(today.toString())
        val latestRow = db.streak().latest()
        val initialSettled = when {
            lastFinal != null -> lastFinal.date
            latestRow != null -> runCatching {
                minOf(LocalDate.parse(latestRow.date), today.minusDays(1)).minusDays(1).toString()
            }.getOrDefault(yesterday)
            else -> yesterday
        }
        if (lastSettled == null) {
            db.thresholds().upsert(UserThreshold("last_settled_date", initialSettled, "STRING", "上次结算日期", null, System.currentTimeMillis()))
        } else if (lastSettled.value.isBlank()) {
            db.thresholds().upsert(lastSettled.copy(value = initialSettled, updatedAt = System.currentTimeMillis()))
        }
        syncPlan()
        if (db.learning().ordered().isEmpty()) {
            listOf(
                CustomLearningModule("crypto", "加密货币行情与情报追踪", "直连 Gate.io 现货 + AI 降噪快讯", "CRYPTO_TRACKER", 1, 0, "次", false, false, 0),
                CustomLearningModule("driving", "驾考（科一/科四）每日百题", "前 10 天刷题，后 4 天模考", "DAILY_QUOTA", 100, 0, "题", true, false, 1),
                CustomLearningModule("english", "美国自由行场景英语", "1.0x / 0.75x 美式跟读", "DAILY_QUOTA", 5, 0, "句", false, false, 2),
                CustomLearningModule("fitness", "囚徒健身与户外", "六式晋级 + GPS 跑步散步", "DAILY_QUOTA", 1, 0, "次", false, false, 3),
            ).forEach { db.learning().upsert(it) }
        }
        if (db.quotes().count() == 0) {
            listOf(
                FinanceQuote(quoteText = "市场永远不会错，错的只有人的主观臆断与不止损的侥幸。", bookTitle = "股票大作手回忆录", author = "杰西·利弗莫尔"),
                FinanceQuote(quoteText = "交易的本质不是预测未来，而是在概率优势出现时严格执行风控。", bookTitle = "交易心理分析", author = "马克·道格拉斯"),
                FinanceQuote(quoteText = "在别人贪婪时恐惧，在别人恐惧时保持冷静与现金流。", bookTitle = "聪明的投资者", author = "本杰明·格雷厄姆"),
                FinanceQuote(quoteText = "不要把没有经过尾部风险检验的脆弱仓位暴露在黑天鹅之下。", bookTitle = "反脆弱", author = "纳西姆·塔勒布"),
                FinanceQuote(quoteText = "原则是把决策写成可以反复执行的规则，而不是每次重新靠感觉。", bookTitle = "原则", author = "瑞·达利欧"),
            ).forEach { db.quotes().insert(it) }
        }
        }
    }

    suspend fun today(): TodayUi = withContext(Dispatchers.IO) { buildToday() }

    suspend fun modules(): List<CustomLearningModule> {
        val t = loadThresholds()
        val today = todayString()
        val phase = examPhase(LocalDate.now(), t.examDate)
        return db.learning().ordered().map { row ->
            when (row.moduleId) {
                "driving" -> row.copy(
                    targetValue = t.dailyDriving,
                    currentValue = if (phase == ExamPhase.MOCK) db.driving().finishedSessions(today) else db.driving().realtimeAttempts(today),
                    progressDate = today,
                )
                "english" -> row.copy(
                    targetValue = t.dailyEnglish,
                    currentValue = db.english().count(today),
                    progressDate = today,
                )
                else -> if (row.progressDate == today) row else row.copy(currentValue = 0)
            }
        }
    }

    suspend fun moveModule(id: String, direction: Int) {
        val rows = db.learning().ordered().toMutableList()
        val index = rows.indexOfFirst { it.moduleId == id }
        val target = index + direction
        if (index < 0 || target !in rows.indices) return
        val a = rows[index]
        val b = rows[target]
        db.learning().upsert(a.copy(sortOrder = b.sortOrder))
        db.learning().upsert(b.copy(sortOrder = a.sortOrder))
    }

    suspend fun setHardModule(id: String) {
        val row = db.learning().get(id) ?: return
        if (row.isArchived) return
        db.learning().clearHardFlags()
        db.learning().upsert(row.copy(countsTowardsStreak = true))
        recompute(todayString(), false)
    }

    suspend fun setArchived(id: String, archived: Boolean) {
        db.learning().get(id)?.let { db.learning().upsert(it.copy(isArchived = archived)) }
        recompute(todayString(), false)
    }

    suspend fun addModule(title: String, target: Int, template: String): String {
        val id = "custom_${System.currentTimeMillis()}"
        val order = (db.learning().ordered().maxOfOrNull { it.sortOrder } ?: 0) + 1
        db.learning().upsert(
            CustomLearningModule(
                moduleId = id,
                title = title.ifBlank { "新的学习模块" },
                subtitle = "自定义阶段学习 · 支持一句话累加",
                templateType = template,
                targetValue = target.coerceAtLeast(1),
                currentValue = 0,
                unit = if (template == "COUNTDOWN_SPRINT") "天" else "次",
                countsTowardsStreak = false,
                sortOrder = order,
                progressDate = "",
            ),
        )
        return id
    }

    suspend fun bumpModule(id: String, amount: Int) {
        val row = db.learning().get(id) ?: return
        val today = todayString()
        val base = if (row.progressDate == today) row.currentValue else 0
        val target = row.targetValue.coerceAtLeast(1)
        val next = (base + amount.coerceAtLeast(0)).coerceIn(0, target)
        db.learning().upsert(row.copy(currentValue = next, progressDate = today))
        recompute(today, false)
    }

    suspend fun markCryptoReviewed() {
        val row = db.learning().get("crypto") ?: return
        val today = todayString()
        db.learning().upsert(row.copy(currentValue = row.targetValue.coerceAtLeast(1), progressDate = today))
        recompute(today, false)
    }

    suspend fun quotes(): List<FinanceQuote> = db.quotes().all()

    suspend fun addQuote(text: String, book: String, author: String) {
        if (text.isBlank()) return
        db.quotes().insert(FinanceQuote(quoteText = text.trim(), bookTitle = book.ifBlank { "个人摘录" }, author = author.ifBlank { "自己" }, isCustom = true))
    }

    suspend fun quoteAt(index: Int): FinanceQuote? {
        val all = db.quotes().all()
        if (all.isEmpty()) return null
        return all[Math.floorMod(index, all.size)]
    }

    suspend fun refreshCrypto(): CryptoBoard = CryptoMarket.load()

    suspend fun saveThreshold(key: String, value: String): String = withContext(Dispatchers.IO) {
        val existing = db.thresholds().get(key) ?: return@withContext "未知设置项：$key"
        val raw = value.trim()
        val normalized = when (existing.valueType) {
            "TIME" -> normalizeClock(raw) ?: return@withContext "时间要写成 HH:mm"
            "DATE" -> if (raw.isBlank()) "" else {
                runCatching { LocalDate.parse(raw) }.getOrNull()?.toString()
                    ?: return@withContext "日期要写成 YYYY-MM-DD"
            }
            "INT" -> if (raw.isBlank()) "" else {
                val n = raw.toIntOrNull() ?: return@withContext "需要整数"
                if (n < 0) return@withContext "不能是负数"
                n.toString()
            }
            "FLOAT" -> if (raw.isBlank()) "" else {
                val n = raw.toFloatOrNull() ?: return@withContext "需要数字"
                if (n < 0f) return@withContext "不能是负数"
                n.toString()
            }
            "BOOL" -> {
                if (raw != "true" && raw != "false") return@withContext "只接受 true / false"
                raw
            }
            else -> raw
        }
        if (key == "daily_driving_target" && (normalized.toIntOrNull() ?: 0) <= 0) {
            return@withContext "每日题量要大于 0"
        }
        if (key == "daily_english_sentences" && (normalized.toIntOrNull() ?: 0) !in 5..8) {
            return@withContext "英语句数只能是 5–8"
        }
        db.thresholds().upsert(existing.copy(value = normalized, updatedAt = System.currentTimeMillis()))
        if (key == "exam_date" || key == "daily_driving_target") syncPlan()
        if (key == "weekday_lock_time" || key == "weekend_lock_time") refreshTodaySleepDeadline()
        recompute(todayString(), false)
        "已保存"
    }

    /** Re-evaluate today's sleep when the lock deadline is edited, so the card and verdict change immediately. */
    private suspend fun refreshTodaySleepDeadline() {
        val date = todayString()
        val sleep = db.sleep().get(date) ?: return
        val actual = sleep.actualSleepTime ?: return
        val t = loadThresholds()
        val deadline = lockTime(LocalDate.parse(date), t).hhmm()
        val late = isLateSleep(actual, deadline)
        db.sleep().upsert(sleep.copy(lockDeadline = deadline, isLateSleep = late))
        db.violations().deleteType(date, "LATE_SLEEP")
        if (late && !sleep.isBackfill) addViolation(date, "LATE_SLEEP", "超过红线 ${minutesLate(actual, deadline)} 分钟", true)
    }

    suspend fun setPin(pin: String): String = withContext(Dispatchers.IO) {
        if (!pin.matches(Regex("\\d{6}"))) return@withContext "需要 6 位数字"
        val salt = java.util.UUID.randomUUID().toString()
        putRaw("emergency_password_salt", salt)
        putRaw("emergency_password_hash", sha256("$salt:$pin"))
        "已保存"
    }

    suspend fun checkPin(pin: String): Boolean = withContext(Dispatchers.IO) {
        val salt = raw("emergency_password_salt")
        val hash = raw("emergency_password_hash")
        if (hash.isBlank() || !pin.matches(Regex("\\d{6}"))) return@withContext false
        MessageDigest.isEqual(sha256("$salt:$pin").toByteArray(), hash.toByteArray())
    }

    suspend fun recordWeight(kg: Float, fasting: Boolean, source: String, backfill: Boolean = false, date: String = todayString()) {
        if (kg.isNaN() || kg !in 30f..250f) return
        db.weight().insert(
            WeightRecord(
                date = date,
                timestamp = System.currentTimeMillis(),
                weightKg = kg,
                isFasting = fasting,
                source = source,
                isBackfill = backfill || date != todayString(),
            ),
        )
        putRaw("current_weight_kg", kg.toString())
        recompute(date, false)
    }

    suspend fun recordSleep(sleep: String, wake: String?, sourceBackfill: Boolean = false, date: String = todayString()) {
        val normalized = normalizeClock(sleep) ?: return
        val normalizedWake = normalizeClock(wake)
        val day = LocalDate.parse(date)
        val t = loadThresholds()
        val deadline = lockTime(day, t).hhmm()
        val late = isLateSleep(normalized, deadline)
        val existing = db.sleep().get(date)
        val isBackfill = date != todayString() || (existing?.isBackfill ?: sourceBackfill)
        db.sleep().upsert(
            SleepRecord(
                date = date,
                actualSleepTime = normalized,
                wakeUpTime = normalizedWake ?: existing?.wakeUpTime ?: wakeTime(day, t).hhmm(),
                isLateSleep = late,
                emergencyUnlockUsed = existing?.emergencyUnlockUsed ?: false,
                emergencyUnlockDurationMin = existing?.emergencyUnlockDurationMin ?: 0,
                lockDeadline = deadline,
                gaveUpTonight = existing?.gaveUpTonight ?: false,
                rebootedDuringLock = existing?.rebootedDuringLock ?: false,
                isBackfill = isBackfill,
            ),
        )
        // Re-editing last night's time should also be able to clear an old typo.
        db.violations().deleteType(date, "LATE_SLEEP")
        if (late && !isBackfill) addViolation(date, "LATE_SLEEP", "超过红线 ${minutesLate(normalized, deadline)} 分钟", true)
        recompute(date, false)
    }

    suspend fun recordDiet(
        meal: String,
        description: String,
        calories: Int,
        protein: Float,
        carbs: Float,
        fat: Float,
        image: String? = null,
        needsBackfill: Boolean = false,
        backfill: Boolean = false,
        date: String = todayString(),
    ) {
        db.diet().insert(
            DietRecord(
                date = date,
                timestamp = System.currentTimeMillis(),
                mealType = meal,
                description = description.trim(),
                calories = calories.coerceAtLeast(0),
                proteinG = protein.coerceAtLeast(0f),
                carbsG = carbs.coerceAtLeast(0f),
                fatG = fat.coerceAtLeast(0f),
                imageUri = image,
                needsBackfill = needsBackfill,
                isBackfill = backfill || date != todayString(),
            ),
        )
        recompute(date, false)
    }

    suspend fun recordCalisthenics(category: String, step: Int, sets: Int, reps: Int, backfill: Boolean = false, date: String = todayString()): String {
        val key = category.trim().uppercase().replace(' ', '_')
        val current = db.calisthenics().get(key)
        val useStep = (if (step in 1..10) step else current?.currentStep ?: 1).coerceIn(1, 10)
        val gate = runCatching { stepOf(key, useStep) }.getOrNull()
            ?: return "动作分类不认识：$category"
        db.calisthenics().insertLog(
            CalisthenicsLog(
                date = date,
                timestamp = System.currentTimeMillis(),
                moveCategory = key,
                stepNumber = useStep,
                sets = sets.coerceAtLeast(0),
                repsPerSet = reps.coerceAtLeast(0),
                isBackfill = backfill || date != todayString(),
            ),
        )
        val safeSets = sets.coerceAtLeast(0)
        val safeReps = reps.coerceAtLeast(0)
        val ready = safeSets >= gate.upSets && safeReps >= gate.upReps
        val next = if (ready && useStep < 10) useStep + 1 else useStep
        val nextDef = stepOf(key, next)
        db.calisthenics().upsert(
            CalisthenicsProgress(
                moveCategory = key,
                currentStep = next,
                currentStepName = nextDef.name,
                bestSets = maxOf(current?.bestSets ?: 0, safeSets),
                bestReps = maxOf(current?.bestReps ?: 0, safeReps),
                isReadyToLevelUp = ready && useStep == 10,
            ),
        )
        return if (ready && next != useStep) "达到升级标准，${categoryLabel(key)}进入第 $next 式 ${nextDef.name}" else "已记下 ${categoryLabel(key)} 第 $useStep 式"
    }

    suspend fun commitLocal(text: String): String {
        val parsed = parseUtterance(text, LocalTime.now().hour)
        val notes = mutableListOf<String>()
        parsed.weight?.let {
            recordWeight(it.kg, it.fasting, "VOICE_LOCAL_FALLBACK")
            notes += "体重 ${it.kg}kg"
        }
        parsed.sleep?.let {
            recordSleep(it.sleepHHmm, it.wakeHHmm)
            notes += "入睡 ${it.sleepHHmm}"
        }
        parsed.diet?.let {
            recordDiet(it.mealType, it.description, 0, 0f, 0f, 0f, needsBackfill = true)
            notes += "饮食已记，营养待补"
        }
        parsed.calisthenics?.let {
            notes += recordCalisthenics(it.category, it.step, it.sets, it.reps)
        }
        return if (notes.isEmpty()) "" else notes.joinToString("，")
    }

    suspend fun executeTool(name: String, arguments: String): String {
        val args = runCatching { JSONObject(arguments.ifBlank { "{}" }) }.getOrElse { JSONObject() }
        return when (name) {
            "record_weight" -> runCatching {
                val kg = args.optDouble("weight_kg")
                if (kg.isNaN() || kg !in 30.0..250.0) error("体重需要在 30–250kg 之间")
                recordWeight(kg.toFloat(), args.optBoolean("is_fasting", true), "VOICE")
                "已记录体重"
            }.getOrElse { "体重未记录：${it.message}" }
            "record_sleep" -> runCatching {
                val sleep = args.optString("sleep_time")
                if (normalizeClock(sleep) == null) error("入睡时间要写成 HH:mm")
                recordSleep(sleep, args.optString("wake_time").ifBlank { null })
                "已记录作息"
            }.getOrElse { "作息未记录：${it.message}" }
            "record_diet" -> runCatching {
                val items = args.optString("items").trim()
                if (items.isBlank()) error("缺少吃了什么")
                val meal = args.optString("meal_type", "SNACK").uppercase()
                    .takeIf { it in setOf("BREAKFAST", "LUNCH", "DINNER", "SNACK") } ?: "SNACK"
                recordDiet(
                    meal,
                    items,
                    args.optInt("calories"),
                    args.optDouble("protein_g").toFloat(),
                    args.optDouble("carbs_g").toFloat(),
                    args.optDouble("fat_g").toFloat(),
                    needsBackfill = args.optInt("calories") == 0,
                )
                "已记录饮食"
            }.getOrElse { "饮食未记录：${it.message}" }
            "record_calisthenics" -> runCatching {
                val sets = args.optInt("sets", 1)
                val reps = args.optInt("reps")
                if (sets <= 0 || reps < 0) error("组数和次数不合法")
                recordCalisthenics(
                    args.optString("category", "PUSHUP"),
                    args.optInt("step"),
                    sets,
                    reps,
                )
            }.getOrElse { "训练未记录：${it.message}" }
            "render_analytics_widget" -> analyticsPayload(args.optInt("days", 14).coerceIn(1, 90))
            "remember" -> {
                val key = args.optString("key").trim()
                if (key.isBlank()) "记忆键不能为空" else {
                    db.ai().upsertMemory(AiMemoryProfile(key, args.optString("value").trim(), System.currentTimeMillis()))
                    "已记住"
                }
            }
            else -> "未知工具"
        }
    }

    suspend fun snapshot(): String = withContext(Dispatchers.IO) {
        val t = loadThresholds()
        val date = todayString()
        val ui = buildToday()
        val weights = db.weight().recent(8)
        val trend = weights.asReversed().joinToString("；") { "${it.date.takeLast(5)} ${it.weightKg}kg" }
        val workouts = db.gps().forDate(date).filter { it.finished }
        val workoutText = if (workouts.isEmpty()) "今天还没有户外记录" else workouts.joinToString("；") {
            val kind = if (it.workoutType == "RUN") "跑了" else "走了"
            "今天$kind ${"%.1f".format(it.distanceMeters / 1000f)} 公里，配速 ${paceLabel(it.distanceMeters, it.durationSec)}"
        }
        val memory = db.ai().memory().filter { it.value.isNotBlank() }.joinToString("；") { "${it.key}=${it.value}" }
        val reasons = db.violations().forDate(date).joinToString("、") { violationLabel(it.type) }
        """
        今日 $date ${ui.weekday}。连续自律 ${ui.streak} 天，最长 ${ui.maxStreak} 天。今日状态 ${ui.status}。
        硬项：作息 ${ui.sleepText}；驾考 ${ui.drivingDone}/${ui.drivingTarget}（${ui.phase}）；体重/饮食/作息入库 ${if (ui.dataMet) "齐" else "未齐"}。
        ${if (reasons.isBlank()) "今天还没有破戒记录。" else "今天已发生：$reasons。"}
        体重趋势（只给日期和公斤，没有时刻）：$trend
        今日热量 ${ui.calories} / 上限 ${ui.calorieLimit} kcal，蛋白 ${ui.protein}g，碳水 ${ui.carbs}g，脂肪 ${ui.fat}g。
        $workoutText
        英语今天 ${ui.englishDone}/${ui.englishTarget}。${ui.steps}
        ${ui.examText}。${ui.travelText}。
        长期记忆：$memory
        """.trimIndent()
    }

    suspend fun analyticsPayload(days: Int): String = withContext(Dispatchers.IO) {
        val start = LocalDate.now().minusDays((days - 1).toLong())
        val rows = JSONArray()
        var cursor = start
        val today = LocalDate.now()
        while (!cursor.isAfter(today)) {
            val key = cursor.toString()
            val weight = db.weight().forDate(key).lastOrNull()?.weightKg
            val sleep = db.sleep().get(key)
            val diet = db.diet().forDate(key)
            val trained = db.calisthenics().logs(key).isNotEmpty() || db.gps().forDate(key).any { it.finished }
            rows.put(
                JSONObject()
                    .put("date", key.takeLast(5))
                    .put("weight", weight ?: JSONObject.NULL)
                    .put("late", sleep?.isLateSleep == true)
                    .put("calories", diet.sumOf { it.calories })
                    .put("trained", trained),
            )
            cursor = cursor.plusDays(1)
        }
        JSONObject().put("rows", rows).put("note", "熬夜点与热量柱用来看反弹，不展示轨迹坐标。").toString()
    }

    suspend fun addProvider(name: String, baseUrl: String, apiKey: String, path: String): Long =
        db.ai().insertProvider(
            AiProviderConfig(
                providerName = name.trim(),
                baseUrl = baseUrl.trim(),
                apiKeyEncrypted = KeystoreCrypto.encrypt(apiKey.trim()),
                transcriptionPath = path.ifBlank { "/audio/transcriptions" },
            ),
        )

    suspend fun replaceKey(id: Long, apiKey: String) {
        val row = db.ai().provider(id) ?: return
        if (apiKey.isBlank()) return
        db.ai().updateProvider(row.copy(apiKeyEncrypted = KeystoreCrypto.encrypt(apiKey.trim())))
    }

    suspend fun cacheModels(id: Long, models: List<String>) {
        val row = db.ai().provider(id) ?: return
        db.ai().updateProvider(row.copy(cachedModelsJson = JSONArray(models).toString()))
    }

    suspend fun deleteProvider(id: Long) = db.ai().deleteProvider(id)

    suspend fun bound(scene: String): Bound? {
        val id = raw("scene_${scene}_provider").toLongOrNull() ?: return null
        val model = raw("scene_${scene}_model")
        val provider = db.ai().provider(id) ?: return null
        val key = KeystoreCrypto.decrypt(provider.apiKeyEncrypted)
        if (key.isBlank() || model.isBlank()) return null
        val path = if (scene == "tts") raw("scene_tts_path").ifBlank { "/audio/speech" } else provider.transcriptionPath
        return Bound(provider, model, key, path)
    }

    suspend fun bind(scene: String, providerId: Long, model: String) {
        putRaw("scene_${scene}_provider", providerId.toString())
        putRaw("scene_${scene}_model", model)
    }

    suspend fun importQuestions(json: String): Int {
        val array = JSONArray(json)
        val rows = buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val options = when (val raw = o.opt("options") ?: o.opt("optionsJson")) {
                    is JSONArray -> raw.toString()
                    is String -> if (raw.trim().startsWith("[")) raw else JSONArray().put(raw).toString()
                    else -> "[\"正确\",\"错误\"]"
                }
                add(
                    DrivingQuestion(
                        questionId = o.optString("questionId", o.optString("id", "import-$i")),
                        subject = o.optInt("subject", 1),
                        questionType = o.optString("questionType", o.optString("type", "SINGLE")),
                        content = o.optString("content"),
                        imageUrl = o.optString("imageUrl").ifBlank { null },
                        optionsJson = options,
                        correctAnswer = o.optString("correctAnswer", o.optString("answer")),
                        officialExplanation = o.optString("officialExplanation", o.optString("explain")),
                        ruleVersion = o.optString("ruleVersion", "import"),
                        manualReviewed = o.optBoolean("manualReviewed", false),
                        highYield = o.optBoolean("highYield", false),
                    ),
                )
            }
        }.filter { it.content.isNotBlank() && it.correctAnswer.isNotBlank() }
        if (rows.isNotEmpty()) db.driving().upsertAll(rows)
        return rows.size
    }

    suspend fun paper(subject: Int, mock: Boolean): List<DrivingQuestion> {
        val pool = if (subject == 0) db.driving().all() else db.driving().bySubject(subject)
        val t = loadThresholds()
        val size = when {
            mock && subject == 4 -> 50
            mock -> 100
            else -> t.dailyDriving.coerceAtLeast(1)
        }
        val refs = pool.map { QuestionRef(it.questionId, it.highYield, it.wrongCount, it.subject) }
        val ids = buildDailyPaper(refs, size, todayString().hashCode())
        val byId = pool.associateBy { it.questionId }
        return ids.mapNotNull { byId[it.id] }
    }

    suspend fun answer(question: DrivingQuestion, selected: Set<String>, mode: String): Boolean {
        val ok = answersMatch(question.correctAnswer, selected)
        val date = todayString()
        if (ok) db.driving().markSeen(question.questionId, date) else db.driving().markWrong(question.questionId, date)
        db.driving().insertAttempt(DrivingAttempt(date = date, timestamp = System.currentTimeMillis(), questionId = question.questionId, correct = ok, mode = mode))
        recompute(date, false)
        return ok
    }

    suspend fun finishMock(subject: Int, total: Int, correct: Int): DrivingSession {
        val passed = total > 0 && correct * 100 / total >= 90
        val date = todayString()
        val row = DrivingSession(
            date = date,
            startedAt = System.currentTimeMillis(),
            finishedAt = System.currentTimeMillis(),
            mode = "MOCK",
            subject = subject,
            total = total,
            correct = correct,
            passed = passed,
        )
        val id = db.driving().insertSession(row)
        val plan = db.driving().plan()
        if (plan != null) {
            db.driving().upsertPlan(
                plan.copy(
                    mockPassed = plan.mockPassed + if (passed) 1 else 0,
                    mockBestScore = maxOf(plan.mockBestScore, if (total == 0) 0 else correct * 100 / total),
                ),
            )
        }
        recompute(date, false)
        return row.copy(id = id)
    }

    fun dailyPhrases(date: String = todayString(), count: Int = 5): List<Phrase> {
        val all = phrases()
        val start = Math.floorMod(date.hashCode(), all.size)
        return List(count.coerceIn(5, 8)) { all[(start + it) % all.size] }
    }

    suspend fun logEnglish(scene: String, sentence: String, score: Int) {
        db.english().insert(EnglishLog(date = todayString(), scene = scene, sentence = sentence, score = score, timestamp = System.currentTimeMillis()))
    }

    suspend fun saveMessage(role: String, text: String, widget: String? = null, payload: String? = null) {
        db.ai().insertMessage(AiChatMessage(timestamp = System.currentTimeMillis(), role = role, textContent = text, widgetType = widget, widgetPayloadJson = payload))
    }

    suspend fun refreshBriefing(): String = withContext(Dispatchers.IO) {
        val t = loadThresholds()
        val enabled = t.newsEnabled
        val sources = newsSources().filter { it.id in enabled }
        val (headlines, failed) = NewsFetcher.fetch(sources)
        val raw = headlines.joinToString("\n") { "【${it.source}】${it.title}" }.ifBlank { "今天没有抓到可用快讯。" }
        val bound = bound("briefing") ?: bound("companion")
        val body = if (bound == null) {
            raw + "\n\n还没绑定早报模型，上面是原始标题。"
        } else {
            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", "把下面的快讯提纯成 8 到 12 条早报。每条两句：第一句只写核心事实，第二句写一句市场或技术影响。不要娱乐，不要编造没给到的数字。覆盖不到的领域直接说明。"))
                .put(JSONObject().put("role", "user").put("content", raw))
            val result = ai.chat(bound.provider.baseUrl, bound.key, bound.model, messages, temperature = 0.3)
            result.content.ifBlank { raw + "\n\n模型没有返回正文。" }
        }
        db.briefings().upsert(Briefing(todayString(), System.currentTimeMillis(), body, failed, headlines.size))
        body
    }

    suspend fun backupNow(): String = withContext(Dispatchers.IO) {
        val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "DSH-Go/backup")
        if (!dir.exists()) dir.mkdirs()
        runCatching { db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close() }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
        val source = app.getDatabasePath("aether.db")
        val target = File(dir, "aether-$stamp.db")
        if (source.exists()) source.copyTo(target, overwrite = true)
        if (!target.exists() || target.length() == 0L) {
            os.aether.discipline.system.Root.exec("mkdir -p ${dir.absolutePath} && cp ${source.absolutePath} ${target.absolutePath}")
        }
        val names = dir.listFiles()?.map { it.name }.orEmpty().filter { it.startsWith("aether-") && it.endsWith(".db") }
        pruneBackups(names, 14).forEach { File(dir, it).delete() }
        if (target.exists()) "已写入 ${target.absolutePath}" else "快照没有写成，检查存储权限或 Root"
    }

    suspend fun finalizeDate(date: String) = withContext(Dispatchers.IO) {
        recompute(date, true)
        val row = db.streak().get(date) ?: return@withContext
        if (row.status != "BROKEN") return@withContext
        if (!row.drivingTargetMet) addViolation(date, "DRIVING_NOT_MET", "当日题量未完成", true)
        if (!row.weightLogged || !row.dietLogged || !row.sleepRecorded) addViolation(date, "DATA_NOT_LOGGED", "体重、饮食或作息缺实时记录", true)
    }

    /**
     * Finalize every day between the last settled date and yesterday.
     * This is what makes "不达标就不掉链" actually true even when the app was never opened.
     */
    suspend fun settleMissedDays() = withContext(Dispatchers.IO) {
        settleMutex.withLock {
            val today = LocalDate.now()
            val end = today.minusDays(1)
            val meta = raw("last_settled_date").let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val latest = db.streak().latest()
            val latestDate = latest?.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val lastFinal = db.streak().previousFinal(today.toString())
            val start = when {
                meta != null -> meta.plusDays(1)
                lastFinal != null -> LocalDate.parse(lastFinal.date).plusDays(1)
                latest != null && latestDate != null -> minOf(latestDate, end)
                else -> null
            }
            if (start == null) {
                // Brand-new install: do not invent a broken day before the app was ever used.
                putRaw("last_settled_date", end.toString())
                recompute(today.toString(), false)
                return@withLock
            }
            var cursor = if (start.isBefore(end.minusDays(366))) end.minusDays(366) else start
            while (!cursor.isAfter(end)) {
                finalizeDate(cursor.toString())
                cursor = cursor.plusDays(1)
            }
            putRaw("last_settled_date", end.toString())
            // Refresh today's carried streak now that yesterday is final.
            recompute(today.toString(), false)
        }
    }

    suspend fun giveUp(date: String = todayString()) {
        addViolation(date, "GAVE_UP_TONIGHT", "按了今晚放弃", true)
        val sleep = db.sleep().get(date)
        if (sleep != null) db.sleep().upsert(sleep.copy(gaveUpTonight = true))
        recompute(date, true)
    }

    suspend fun escape(date: String = todayString(), detail: String) {
        addViolation(date, "ESCAPE_LOCK", detail, true)
        recompute(date, true)
    }

    suspend fun rebootDuringLock(date: String = todayString()) {
        addViolation(date, "REBOOT_DURING_LOCK", "锁屏时段内重启", true)
        val sleep = db.sleep().get(date)
        if (sleep != null) db.sleep().upsert(sleep.copy(rebootedDuringLock = true))
        recompute(date, true)
    }

    suspend fun grantEmergency(date: String = todayString()): EmergencyResult {
        val t = loadThresholds()
        val events = db.unlocks().forDate(date).filter { it.unlockType == "EMERGENCY_PASSWORD" }
        if (events.size >= t.emergencyMax) return EmergencyResult(false, "今晚的紧急解锁已经用过")
        val now = System.currentTimeMillis()
        val until = now + t.emergencyMinutes * 60_000L
        db.unlocks().insert(UnlockEvent(date = date, unlockType = "EMERGENCY_PASSWORD", grantedAt = now, expiresAt = until, usedCount = events.size + 1))
        val sleep = db.sleep().get(date)
        if (sleep != null) db.sleep().upsert(sleep.copy(emergencyUnlockUsed = true, emergencyUnlockDurationMin = t.emergencyMinutes))
        return EmergencyResult(true, "已放行 ${t.emergencyMinutes} 分钟", until)
    }

    suspend fun revokeEmergency(date: String = todayString()) {
        db.unlocks().revokeOpen(date, System.currentTimeMillis())
    }

    suspend fun morningLines(): String {
        val ui = buildToday()
        val yesterday = LocalDate.now().minusDays(1).toString()
        val y = db.streak().get(yesterday)
        val yReason = db.violations().forDate(yesterday).joinToString("、") { violationLabel(it.type) }
        return buildString {
            append(ui.sleepText)
            append("。连续 ")
            append(ui.streak)
            append(" 天。")
            if (ui.brokenReason.isNotBlank()) append("今天已经断在：${ui.brokenReason}。")
            if (y?.status == "BROKEN" && yReason.isNotBlank()) append("昨天断在：$yReason。")
            append("今天要完成驾考 ")
            append(ui.drivingTarget)
            append("，英语 ")
            append(ui.englishTarget)
            append(" 句。")
        }
    }

    suspend fun loadThresholds(): Thresholds {
        val map = db.thresholds().all().associate { it.key to it.value }
        fun time(key: String, fallback: LocalTime) = runCatching {
            val p = map[key].orEmpty().split(":")
            LocalTime.of(p[0].toInt(), p.getOrElse(1) { "0" }.toInt())
        }.getOrDefault(fallback)
        return Thresholds(
            weekdayLock = time("weekday_lock_time", LocalTime.of(0, 0)),
            weekendLock = time("weekend_lock_time", LocalTime.of(1, 0)),
            weekdayWake = time("weekday_wake_time", LocalTime.of(8, 0)),
            weekendWake = time("weekend_wake_time", LocalTime.of(9, 0)),
            weekendSilentUntil = time("weekend_wake_silent_until", LocalTime.of(9, 15)),
            screenOffMin = map["lock_screen_off_delay_min"]?.toIntOrNull() ?: 5,
            emergencyMinutes = map["emergency_unlock_minutes"]?.toIntOrNull() ?: 60,
            emergencyMax = map["emergency_unlock_max_per_night"]?.toIntOrNull() ?: 1,
            dailyDriving = map["daily_driving_target"]?.toIntOrNull() ?: 100,
            dailyEnglish = (map["daily_english_sentences"]?.toIntOrNull() ?: 5).coerceIn(5, 8),
            calorieLimit = map["daily_calorie_limit_kcal"]?.toIntOrNull() ?: 0,
            proteinTarget = map["daily_protein_target_g"]?.toFloatOrNull() ?: 0f,
            carbsTarget = map["daily_carbs_target_g"]?.toFloatOrNull() ?: 0f,
            fatTarget = map["daily_fat_target_g"]?.toFloatOrNull() ?: 0f,
            targetWeightKg = map["target_weight_kg"]?.toFloatOrNull(),
            examDate = map["exam_date"]?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            travelDate = map["travel_date"]?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            ttsSource = map["tts_source"] ?: "LOCAL",
            excludePrivacy = map["ai_inject_exclude_privacy"] != "false",
            backfillCountsAsMet = map["backfill_counts_as_met"] == "true",
            whitelistPackages = map["lock_whitelist_packages"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: DefaultWhitelist,
            newsEnabled = map["news_sources_enabled_list"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet(),
        )
    }

    private suspend fun buildToday(): TodayUi {
        val t = loadThresholds()
        val date = LocalDate.now()
        val key = date.toString()
        val now = LocalDateTime.now()
        val weights = db.weight().recent(12)
        val todayWeight = weights.firstOrNull { it.date == key }
        val previous = todayWeight?.let { weights.firstOrNull { row -> row.date != key } }
        val sleep = db.sleep().get(key)
        val diets = db.diet().forDate(key)
        val phase = examPhase(date, t.examDate)
        val hardNow = hardProgress(key, t)
        val drivingTarget = hardNow.target
        val drivingDone = hardNow.done
        val drivingMet = hardNow.met
        val weightOk = db.weight().realtimeCount(key) > 0
        val dietOk = diets.any { !it.isBackfill }
        val sleepOk = sleep != null && !sleep.isBackfill
        val dataMet = weightOk && dietOk && sleepOk
        val sleepPass = sleep?.let { !it.isLateSleep }
        val hard = listOf(sleepPass == true, drivingMet, dataMet).count { it }
        val streakRow = db.streak().get(key)
        val previousFinal = db.streak().previousFinal(key)
        val carried = carriedStreak(key, previousFinal?.date, previousFinal?.status, previousFinal?.streakValue ?: 0)
        val displayStreak = when (streakRow?.status) {
            "BROKEN" -> 0
            "MET", "BACKFILL_ONLY" -> streakRow.streakValue
            else -> carried.second
        }
        val delta = if (todayWeight != null && previous != null) {
            val diff = todayWeight.weightKg - previous.weightKg
            if (diff > 0) "↑${"%.1f".format(diff)}" else "↓${"%.1f".format(-diff)}"
        } else ""
        val briefing = db.briefings().get(key)
        val briefLines = briefing?.body?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val push = db.calisthenics().get("PUSHUP")
        val pushGate = push?.let { stepOf("PUSHUP", it.currentStep) }
        val steps = db.calisthenics().all().joinToString("  ") { "${categoryLabel(it.moveCategory)} ${it.currentStep}式" }
        val scene = phrases().let { all -> all[Math.floorMod(key.hashCode(), all.size)].scene }
        val reasons = db.violations().forDate(key).map { violationLabel(it.type) }.distinct()
        val locked = inLockWindow(now, t)
        val remain = untilLock(now, t)
        val hours = remain.toHours()
        val mins = remain.toMinutes() % 60
        val next = nextLock(now, t)
        return TodayUi(
            date = key,
            weekday = weekdayLabel(date),
            streak = displayStreak,
            maxStreak = db.streak().maxStreak(),
            status = streakRow?.status ?: "PENDING",
            countdown = if (locked) "锁屏中" else "${hours}小时${mins}分",
            countdownClock = if (locked) "00:00" else "%02d:%02d".format(hours, mins),
            lockCaption = "今晚 %02d:%02d 锁定".format(next.hour, next.minute),
            guardText = if (locked) "锁屏守卫中" else "正常守卫中",
            hardTitle = hardNow.title,
            hardHint = hardNow.hint,
            hardDetail = hardNow.detail,
            hardUnit = hardNow.unit,
            inLock = locked,
            sleepText = sleepConclusion(sleep?.actualSleepTime, sleep?.lockDeadline ?: lockTime(date, t).hhmm(), !t.excludePrivacy),
            sleepOk = sleepPass,
            drivingDone = drivingDone,
            drivingTarget = drivingTarget,
            drivingMet = drivingMet,
            phase = phase.name,
            weightText = todayWeight?.let { "${it.weightKg} kg" } ?: "未记录",
            weightValue = todayWeight?.let { "%.1f".format(it.weightKg) } ?: "—",
            weightDelta = delta,
            deltaText = if (todayWeight != null && previous != null) {
                val diff = todayWeight.weightKg - previous.weightKg
                "${if (diff > 0) "+" else ""}${"%.1f".format(diff)} kg"
            } else "今日还没称",
            deltaDown = todayWeight == null || previous == null || todayWeight.weightKg <= previous.weightKg,
            calories = diets.sumOf { it.calories },
            calorieLimit = t.calorieLimit,
            protein = diets.sumOf { it.proteinG.toDouble() }.toInt(),
            carbs = diets.sumOf { it.carbsG.toDouble() }.toInt(),
            fat = diets.sumOf { it.fatG.toDouble() }.toInt(),
            dataMet = dataMet,
            hardDone = hard,
            englishDone = db.english().count(key),
            englishTarget = t.dailyEnglish,
            briefing = briefing?.body ?: "今天的早报还没生成。",
            briefingMiss = briefing?.unreachableCount ?: 0,
            briefingHead = briefLines.take(2),
            briefingRest = briefLines.drop(2),
            steps = steps,
            englishScene = scene,
            fitnessName = push?.let { "俯卧撑 第${it.currentStep}式" } ?: "俯卧撑",
            fitnessGate = pushGate?.let { "升级目标：${it.upSets}组 × ${it.upReps}次" } ?: "",
            examText = t.examDate?.let { "距考试 ${java.time.temporal.ChronoUnit.DAYS.between(date, it)} 天" } ?: "还没填考试日期",
            travelText = t.travelDate?.let { "距出发 ${java.time.temporal.ChronoUnit.DAYS.between(date, it)} 天" } ?: "还没填出发日期",
            brokenReason = reasons.joinToString("、"),
        )
    }

    private suspend fun hardProgress(date: String, t: Thresholds): HardSlice {
        val module = db.learning().ordered().firstOrNull { it.countsTowardsStreak && !it.isArchived }
        val day = runCatching { LocalDate.parse(date) }.getOrNull() ?: LocalDate.now()
        if (module == null || module.moduleId == "driving") {
            val phase = examPhase(day, t.examDate)
            val target = if (phase == ExamPhase.MOCK) 1 else t.dailyDriving.coerceAtLeast(1)
            val done = if (phase == ExamPhase.MOCK) db.driving().finishedSessions(date) else db.driving().realtimeAttempts(date)
            val backfill = db.driving().backfillAttempts(date) > 0 && done < target
            val hint = t.examDate?.let { "剩 ${java.time.temporal.ChronoUnit.DAYS.between(day, it)} 天考" } ?: "还没填考试日期"
            return HardSlice(done >= target, backfill, module?.title ?: "驾考每日百题", done, target, "题", hint, if (phase == ExamPhase.MOCK) "全真模考" else "高频 70% + 错题 30%")
        }
        if (module.moduleId == "english") {
            val done = db.english().count(date)
            val hint = t.travelDate?.let { "剩 ${java.time.temporal.ChronoUnit.DAYS.between(day, it)} 天" } ?: module.subtitle
            return HardSlice(done >= t.dailyEnglish, false, module.title, done, t.dailyEnglish, module.unit, hint, module.subtitle)
        }
        val done = if (module.progressDate == date) module.currentValue else 0
        val met = module.targetValue > 0 && done >= module.targetValue
        return HardSlice(met, false, module.title, done, module.targetValue, module.unit, module.subtitle, module.templateType)
    }

    private suspend fun recompute(date: String, finalize: Boolean) {
        val t = loadThresholds()
        val day = LocalDate.parse(date)
        val phase = examPhase(day, t.examDate)
        val hardNow = hardProgress(date, t)
        val drivingRealtime = hardNow.met
        val drivingBackfill = hardNow.backfill
        val sleep = db.sleep().get(date)
        val deadline = sleep?.lockDeadline ?: lockTime(day, t).hhmm()
        val sleepOnTime = sleep?.isLateSleep == false
        val violations = db.violations().forDate(date).filter { it.breaksStreak }.map { it.type }
        val previousRow = db.streak().previousFinal(date)
        val carried = carriedStreak(date, previousRow?.date, previousRow?.status, previousRow?.streakValue ?: 0)
        val decision = decideDay(
            DayInput(
                weight = Facet(db.weight().realtimeCount(date) > 0, db.weight().backfillCount(date) > 0),
                diet = Facet(db.diet().forDate(date).any { !it.isBackfill }, db.diet().forDate(date).any { it.isBackfill }),
                sleep = Facet(sleep != null && !sleep.isBackfill, sleep?.isBackfill == true),
                driving = Facet(drivingRealtime, drivingBackfill),
                sleepOnTime = sleepOnTime && sleep != null,
                breakingViolations = violations,
                previousStatus = carried.first,
                previousStreak = carried.second,
                backfillCountsAsMet = t.backfillCountsAsMet,
            ),
            finalize,
        )
        db.streak().upsert(
            SelfDisciplineStreak(
                date = date,
                weightLogged = db.weight().realtimeCount(date) > 0,
                dietLogged = db.diet().forDate(date).any { !it.isBackfill },
                sleepRecorded = sleep != null && !sleep.isBackfill,
                sleepOnTime = sleepOnTime && sleep != null,
                drivingTargetMet = drivingRealtime,
                hardStudyTargetMet = drivingRealtime,
                allThreeHardMet = decision.allThreeHardMet,
                streakValue = decision.streakValue,
                status = decision.status,
            ),
        )
    }

    private suspend fun syncPlan() {
        val t = loadThresholds()
        val phase = examPhase(LocalDate.now(), t.examDate)
        val old = db.driving().plan()
        db.driving().upsertPlan(
            DrivingExamPlan(
                examDate = t.examDate?.toString().orEmpty(),
                phase = phase.name,
                dailyTarget = if (phase == ExamPhase.MOCK) 1 else t.dailyDriving,
                mockPassed = old?.mockPassed ?: 0,
                mockBestScore = old?.mockBestScore ?: 0,
            ),
        )
    }

    private suspend fun addViolation(date: String, type: String, detail: String?, breaks: Boolean) {
        if (db.violations().countType(date, type) > 0) return
        db.violations().insert(ViolationRecord(date = date, timestamp = System.currentTimeMillis(), type = type, detail = detail, breaksStreak = breaks))
    }

    private fun normalizeClock(raw: String?): String? {
        val clean = raw?.trim()?.replace('：', ':') ?: return null
        val parts = clean.split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return "%02d:%02d".format(h, m)
    }

    private suspend fun putRaw(key: String, value: String) {
        val existing = db.thresholds().get(key) ?: UserThreshold(key, value, "STRING", key, null, System.currentTimeMillis())
        db.thresholds().upsert(existing.copy(value = value, updatedAt = System.currentTimeMillis()))
    }

    private suspend fun raw(key: String) = db.thresholds().get(key)?.value.orEmpty()

    companion object {
        fun todayString(): String = LocalDate.now().toString()
        fun violationLabel(type: String) = when (type) {
            "ESCAPE_LOCK" -> "遮罩离开前台"
            "REBOOT_DURING_LOCK" -> "锁屏时段重启"
            "GAVE_UP_TONIGHT" -> "今晚放弃"
            "LATE_SLEEP" -> "越过入睡红线"
            "DRIVING_NOT_MET" -> "驾考未完成"
            "DATA_NOT_LOGGED" -> "数据没在当天记"
            else -> type
        }
        private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        private fun LocalTime.hhmm() = "%02d:%02d".format(hour, minute)
        private fun weekdayLabel(date: LocalDate) = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[date.dayOfWeek.value - 1]
        fun bootInstant(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()
    }
}
