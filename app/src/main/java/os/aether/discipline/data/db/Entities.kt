package os.aether.discipline.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "weight_records")
data class WeightRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val timestamp: Long,
    val weightKg: Float,
    val bodyFatPercent: Float? = null,
    val isFasting: Boolean = true,
    val source: String,
    val isBackfill: Boolean = false,
)

@Entity(tableName = "sleep_records")
data class SleepRecord(
    @PrimaryKey val date: String,
    val actualSleepTime: String?,
    val wakeUpTime: String?,
    val isLateSleep: Boolean,
    val emergencyUnlockUsed: Boolean = false,
    val emergencyUnlockDurationMin: Int = 0,
    val lockDeadline: String,
    val gaveUpTonight: Boolean = false,
    val rebootedDuringLock: Boolean = false,
    val isBackfill: Boolean = false,
)

@Entity(tableName = "diet_records")
data class DietRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val timestamp: Long,
    val mealType: String,
    val description: String,
    val calories: Int,
    val proteinG: Float,
    val carbsG: Float,
    val fatG: Float,
    val imageUri: String? = null,
    val needsBackfill: Boolean = false,
    val isBackfill: Boolean = false,
)

@Entity(tableName = "calisthenics_progress")
data class CalisthenicsProgress(
    @PrimaryKey val moveCategory: String,
    val currentStep: Int,
    val currentStepName: String,
    val bestSets: Int,
    val bestReps: Int,
    val isReadyToLevelUp: Boolean,
)

@Entity(tableName = "calisthenics_logs")
data class CalisthenicsLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val timestamp: Long,
    val moveCategory: String,
    val stepNumber: Int,
    val sets: Int,
    val repsPerSet: Int,
    val notes: String? = null,
    val isBackfill: Boolean = false,
)

@Entity(tableName = "gps_workouts")
data class GpsWorkout(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val startTime: Long,
    val durationSec: Long,
    val workoutType: String,
    val distanceMeters: Float,
    val avgPaceSecPerKm: Int,
    val caloriesBurned: Int,
    val routeGeoJson: String,
    val avgCadenceSpm: Int = 0,
    val finished: Boolean = false,
)

@Entity(tableName = "driving_questions")
data class DrivingQuestion(
    @PrimaryKey val questionId: String,
    val subject: Int,
    val questionType: String,
    val content: String,
    val imageUrl: String? = null,
    val optionsJson: String,
    val correctAnswer: String,
    val officialExplanation: String,
    val ruleVersion: String,
    val manualReviewed: Boolean = false,
    val wrongCount: Int = 0,
    val lastPracticedDate: String? = null,
    val highYield: Boolean = false,
    val isBackfill: Boolean = false,
)

@Entity(tableName = "driving_attempts")
data class DrivingAttempt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val timestamp: Long,
    val questionId: String,
    val correct: Boolean,
    val mode: String,
    val isBackfill: Boolean = false,
)

@Entity(tableName = "driving_sessions")
data class DrivingSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val mode: String,
    val subject: Int,
    val total: Int,
    val correct: Int,
    val passed: Boolean = false,
    val isBackfill: Boolean = false,
)

@Entity(tableName = "driving_exam_plan")
data class DrivingExamPlan(
    @PrimaryKey val id: Int = 1,
    val examDate: String,
    val phase: String,
    val dailyTarget: Int = 100,
    val mockPassed: Int = 0,
    val mockBestScore: Int = 0,
)

@Entity(tableName = "ai_memory_profile")
data class AiMemoryProfile(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: Long,
)

@Entity(tableName = "ai_chat_messages")
data class AiChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val role: String,
    val textContent: String,
    val widgetType: String? = null,
    val widgetPayloadJson: String? = null,
)

@Entity(tableName = "ai_providers")
data class AiProviderConfig(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerName: String,
    val baseUrl: String,
    val apiKeyEncrypted: String,
    val transcriptionPath: String = "/audio/transcriptions",
    val cachedModelsJson: String = "[]",
)

@Entity(tableName = "self_discipline_streak", indices = [Index("status")])
data class SelfDisciplineStreak(
    @PrimaryKey val date: String,
    val weightLogged: Boolean = false,
    val dietLogged: Boolean = false,
    val sleepRecorded: Boolean = false,
    val sleepOnTime: Boolean = false,
    val drivingTargetMet: Boolean = false,
    val hardStudyTargetMet: Boolean = false,
    val allThreeHardMet: Boolean = false,
    val streakValue: Int = 0,
    val status: String,
)

@Entity(tableName = "violation_records")
data class ViolationRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val timestamp: Long,
    val type: String,
    val detail: String? = null,
    val breaksStreak: Boolean,
)

@Entity(tableName = "unlock_events")
data class UnlockEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val unlockType: String,
    val grantedAt: Long,
    val expiresAt: Long,
    val usedCount: Int = 1,
    val revokedAt: Long? = null,
)

@Entity(tableName = "user_thresholds")
data class UserThreshold(
    @PrimaryKey val key: String,
    val value: String,
    val valueType: String,
    val label: String,
    val unit: String? = null,
    val updatedAt: Long,
)

@Entity(tableName = "briefings")
data class Briefing(
    @PrimaryKey val date: String,
    val generatedAt: Long,
    val body: String,
    val unreachableCount: Int,
    val headlineCount: Int,
)

@Entity(tableName = "custom_learning_modules")
data class CustomLearningModule(
    @PrimaryKey val moduleId: String,
    val title: String,
    val subtitle: String,
    val templateType: String,
    val targetValue: Int,
    val currentValue: Int,
    val unit: String,
    val countsTowardsStreak: Boolean,
    val isArchived: Boolean = false,
    val sortOrder: Int,
    /** YYYY-MM-DD that [currentValue] belongs to. Empty means no progress yet. */
    @ColumnInfo(defaultValue = "''")
    val progressDate: String = "",
)

@Entity(tableName = "finance_quotes")
data class FinanceQuote(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val quoteText: String,
    val bookTitle: String,
    val author: String,
    val isCustom: Boolean = false,
)

@Entity(tableName = "desktop_widget_configs")
data class DesktopWidgetConfig(
    @PrimaryKey val widgetId: Int,
    val widgetType: String,
    val cryptoSymbolsJson: String,
    val showStreak: Boolean = true,
    val currentQuoteId: Long,
)

@Entity(tableName = "english_logs")
data class EnglishLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val scene: String,
    val sentence: String,
    val score: Int,
    val timestamp: Long,
)
