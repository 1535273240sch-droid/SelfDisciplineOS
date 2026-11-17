package os.aether.discipline.data

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
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
import os.aether.discipline.data.db.CalisthenicsLog
import os.aether.discipline.data.db.CalisthenicsProgress
import os.aether.discipline.data.db.DietRecord
import os.aether.discipline.data.db.DrivingAttempt
import os.aether.discipline.data.db.DrivingExamPlan
import os.aether.discipline.data.db.DrivingQuestion
import os.aether.discipline.data.db.DrivingSession
import os.aether.discipline.data.db.EnglishLog
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
import os.aether.discipline.domain.decideDay
import os.aether.discipline.domain.examPhase
import os.aether.discipline.domain.inLockWindow
import os.aether.discipline.domain.isLateSleep
import os.aether.discipline.domain.lockTime
import os.aether.discipline.domain.minutesLate
import os.aether.discipline.domain.paceLabel
import os.aether.discipline.domain.parseUtterance
import os.aether.discipline.domain.pruneBackups
import os.aether.discipline.domain.sleepConclusion
import os.aether.discipline.domain.untilLock
import os.aether.discipline.domain.wakeTime
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
)

data class Bound(val provider: AiProviderConfig, val model: String, val key: String, val path: String)

data class EmergencyResult(val ok: Boolean, val message: String, val until: Long = 0)

class AetherRepository(context: Context) {
    private val app = context.applicationContext
    val db: AppDatabase = AppDatabase.get(app)
    val ai = OpenAiClient()
    val messages = db.ai().observeMessages()
    val providers = db.ai().observeProviders()
    val thresholdRows = db.thresholds().observe()

    suspend fun ensureSeed() = withContext(Dispatchers.IO) {
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
        syncPlan()
    }

    suspend fun today(): TodayUi = withContext(Dispatchers.IO) { buildToday() }

    suspend fun saveThreshold(key: String, value: String) = withContext(Dispatchers.IO) {
        val existing = db.thresholds().get(key) ?: return@withContext
        db.thresholds().upsert(existing.copy(value = value.trim(), updatedAt = System.currentTimeMillis()))
        if (key == "exam_date" || key == "daily_driving_target") syncPlan()
        recompute(todayString(), false)
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
        db.weight().insert(WeightRecord(date = date, timestamp = System.currentTimeMillis(), weightKg = kg, isFasting = fasting, source = source, isBackfill = backfill))
        putRaw("current_weight_kg", kg.toString())
        recompute(date, false)
    }

    suspend fun recordSleep(sleep: String, wake: String?, sourceBackfill: Boolean = false, date: String = todayString()) {
        val day = LocalDate.parse(date)
        val t = loadThresholds()
        val deadline = lockTime(day, t).hhmm()
        val late = isLateSleep(sleep, deadline)
        val existing = db.sleep().get(date)
        db.sleep().upsert(
            SleepRecord(
                date = date,
                actualSleepTime = sleep,
                wakeUpTime = wake ?: existing?.wakeUpTime ?: wakeTime(day, t).hhmm(),
                isLateSleep = late,
                emergencyUnlockUsed = existing?.emergencyUnlockUsed ?: false,
                emergencyUnlockDurationMin = existing?.emergencyUnlockDurationMin ?: 0,
                lockDeadline = deadline,
                gaveUpTonight = existing?.gaveUpTonight ?: false,
                rebootedDuringLock = existing?.rebootedDuringLock ?: false,
                isBackfill = sourceBackfill,
            ),
        )
        if (late) addViolation(date, "LATE_SLEEP", "超过红线 ${minutesLate(sleep, deadline)} 分钟", true)
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
                description = description,
                calories = calories,
                proteinG = protein,
                carbsG = carbs,
                fatG = fat,
                imageUri = image,
                needsBackfill = needsBackfill,
                isBackfill = backfill,
            ),
        )
        recompute(date, false)
    }

    suspend fun recordCalisthenics(category: String, step: Int, sets: Int, reps: Int, backfill: Boolean = false, date: String = todayString()): String {
        val current = db.calisthenics().get(category)
        val useStep = if (step in 1..10) step else current?.currentStep ?: 1
        db.calisthenics().insertLog(
            CalisthenicsLog(
                date = date,
                timestamp = System.currentTimeMillis(),
                moveCategory = category,
                stepNumber = useStep,
                sets = sets,
                repsPerSet = reps,
                isBackfill = backfill,
            ),
        )
        val gate = stepOf(category, useStep)
        val ready = sets >= gate.upSets && reps >= gate.upReps
        val next = if (ready && useStep < 10) useStep + 1 else useStep
        val nextDef = stepOf(category, next)
        db.calisthenics().upsert(
            CalisthenicsProgress(
                moveCategory = category,
                currentStep = next,
                currentStepName = nextDef.name,
                bestSets = maxOf(current?.bestSets ?: 0, sets),
                bestReps = maxOf(current?.bestReps ?: 0, reps),
                isReadyToLevelUp = ready && useStep == 10,
            ),
        )
        return if (ready && next != useStep) "达到升级标准，${categoryLabel(category)}进入第 $next 式 ${nextDef.name}" else "已记下 ${categoryLabel(category)} 第 $useStep 式"
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
            "record_weight" -> {
                recordWeight(args.optDouble("weight_kg").toFloat(), args.optBoolean("is_fasting", true), "VOICE")
                "已记录体重"
            }
            "record_sleep" -> {
                recordSleep(args.optString("sleep_time"), args.optString("wake_time").ifBlank { null })
                "已记录作息"
            }
            "record_diet" -> {
                recordDiet(
                    args.optString("meal_type", "SNACK"),
                    args.optString("items"),
                    args.optInt("calories"),
                    args.optDouble("protein_g").toFloat(),
                    args.optDouble("carbs_g").toFloat(),
                    args.optDouble("fat_g").toFloat(),
                    needsBackfill = args.optInt("calories") == 0,
                )
                "已记录饮食"
            }
            "record_calisthenics" -> recordCalisthenics(
                args.optString("category", "PUSHUP"),
                args.optInt("step"),
                args.optInt("sets", 1),
                args.optInt("reps"),
            )
            "render_analytics_widget" -> analyticsPayload(args.optInt("days", 14))
            "remember" -> {
                db.ai().upsertMemory(AiMemoryProfile(args.optString("key"), args.optString("value"), System.currentTimeMillis()))
                "已记住"
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
        val latest = weights.firstOrNull()
        val previous = weights.drop(1).firstOrNull { it.date != latest?.date }
        val sleep = db.sleep().get(key)
        val diets = db.diet().forDate(key)
        val phase = examPhase(date, t.examDate)
        val attempts = db.driving().realtimeAttempts(key)
        val mocks = db.driving().finishedSessions(key)
        val drivingTarget = if (phase == ExamPhase.MOCK) 1 else t.dailyDriving
        val drivingDone = if (phase == ExamPhase.MOCK) mocks else attempts
        val drivingMet = drivingDone >= drivingTarget
        val weightOk = db.weight().realtimeCount(key) > 0
        val dietOk = diets.any { !it.isBackfill }
        val sleepOk = sleep != null && !sleep.isBackfill
        val dataMet = weightOk && dietOk && sleepOk
        val sleepPass = sleep?.let { !it.isLateSleep }
        val hard = listOf(sleepPass == true, drivingMet, dataMet).count { it }
        val streakRow = db.streak().get(key)
        val displayStreak = when (streakRow?.status) {
            "BROKEN" -> 0
            "MET", "BACKFILL_ONLY" -> streakRow.streakValue
            else -> db.streak().previousFinal(key)?.streakValue ?: 0
        }
        val delta = if (latest != null && previous != null) {
            val diff = latest.weightKg - previous.weightKg
            if (diff > 0) "↑${"%.1f".format(diff)}" else "↓${"%.1f".format(-diff)}"
        } else ""
        val briefing = db.briefings().get(key)
        val steps = db.calisthenics().all().joinToString("  ") { "${categoryLabel(it.moveCategory)} ${it.currentStep}式" }
        val reasons = db.violations().forDate(key).map { violationLabel(it.type) }.distinct()
        val locked = inLockWindow(now, t)
        val remain = untilLock(now, t)
        return TodayUi(
            date = key,
            weekday = weekdayLabel(date),
            streak = displayStreak,
            maxStreak = db.streak().maxStreak(),
            status = streakRow?.status ?: "PENDING",
            countdown = if (locked) "锁屏中" else "${remain.toHours()}小时${remain.toMinutes() % 60}分",
            inLock = locked,
            sleepText = sleepConclusion(sleep?.actualSleepTime, sleep?.lockDeadline ?: lockTime(date, t).hhmm(), !t.excludePrivacy),
            sleepOk = sleepPass,
            drivingDone = drivingDone,
            drivingTarget = drivingTarget,
            drivingMet = drivingMet,
            phase = phase.name,
            weightText = latest?.let { "${it.weightKg} kg" } ?: "未记录",
            weightDelta = delta,
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
            steps = steps,
            examText = t.examDate?.let { "距考试 ${java.time.temporal.ChronoUnit.DAYS.between(date, it)} 天" } ?: "还没填考试日期",
            travelText = t.travelDate?.let { "距出发 ${java.time.temporal.ChronoUnit.DAYS.between(date, it)} 天" } ?: "还没填出发日期",
            brokenReason = reasons.joinToString("、"),
        )
    }

    private suspend fun recompute(date: String, finalize: Boolean) {
        val t = loadThresholds()
        val day = LocalDate.parse(date)
        val phase = examPhase(day, t.examDate)
        val drivingRealtime = if (phase == ExamPhase.MOCK) db.driving().finishedSessions(date) > 0 else db.driving().realtimeAttempts(date) >= t.dailyDriving
        val drivingBackfill = db.driving().backfillAttempts(date) > 0
        val sleep = db.sleep().get(date)
        val deadline = sleep?.lockDeadline ?: lockTime(day, t).hhmm()
        val sleepOnTime = sleep?.isLateSleep == false
        val violations = db.violations().forDate(date).filter { it.breaksStreak }.map { it.type }
        val previous = db.streak().previousFinal(date)
        val decision = decideDay(
            DayInput(
                weight = Facet(db.weight().realtimeCount(date) > 0, db.weight().backfillCount(date) > 0),
                diet = Facet(db.diet().forDate(date).any { !it.isBackfill }, db.diet().forDate(date).any { it.isBackfill }),
                sleep = Facet(sleep != null && !sleep.isBackfill, sleep?.isBackfill == true),
                driving = Facet(drivingRealtime, drivingBackfill),
                sleepOnTime = sleepOnTime && sleep != null,
                breakingViolations = violations,
                previousStatus = previous?.status,
                previousStreak = previous?.streakValue ?: 0,
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
