package os.aether.discipline.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface WeightDao {
    @Insert suspend fun insert(row: WeightRecord): Long
    @Query("SELECT * FROM weight_records ORDER BY timestamp DESC LIMIT 1")
    suspend fun latest(): WeightRecord?
    @Query("SELECT * FROM weight_records WHERE date = :date ORDER BY timestamp DESC")
    suspend fun forDate(date: String): List<WeightRecord>
    @Query("SELECT * FROM weight_records ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<WeightRecord>
    @Query("SELECT COUNT(*) FROM weight_records WHERE date = :date AND isBackfill = 0")
    suspend fun realtimeCount(date: String): Int
    @Query("SELECT COUNT(*) FROM weight_records WHERE date = :date AND isBackfill = 1")
    suspend fun backfillCount(date: String): Int
}

@Dao
interface SleepDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(row: SleepRecord)
    @Query("SELECT * FROM sleep_records WHERE date = :date")
    suspend fun get(date: String): SleepRecord?
    @Query("SELECT * FROM sleep_records ORDER BY date DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<SleepRecord>
}

@Dao
interface DietDao {
    @Insert suspend fun insert(row: DietRecord): Long
    @Query("SELECT * FROM diet_records WHERE date = :date ORDER BY timestamp ASC")
    suspend fun forDate(date: String): List<DietRecord>
    @Query("SELECT * FROM diet_records WHERE date >= :from ORDER BY date ASC")
    suspend fun since(from: String): List<DietRecord>
    @Update suspend fun update(row: DietRecord)
    @Query("SELECT * FROM diet_records WHERE needsBackfill = 1 ORDER BY timestamp ASC LIMIT 20")
    suspend fun pendingBackfill(): List<DietRecord>
}

@Dao
interface CalisthenicsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(row: CalisthenicsProgress)
    @Query("SELECT * FROM calisthenics_progress")
    suspend fun all(): List<CalisthenicsProgress>
    @Query("SELECT * FROM calisthenics_progress WHERE moveCategory = :category")
    suspend fun get(category: String): CalisthenicsProgress?
    @Insert suspend fun insertLog(row: CalisthenicsLog): Long
    @Query("SELECT * FROM calisthenics_logs WHERE date = :date")
    suspend fun logs(date: String): List<CalisthenicsLog>
    @Query("SELECT COUNT(*) FROM calisthenics_logs WHERE date = :date AND isBackfill = 0")
    suspend fun realtimeCount(date: String): Int
}

@Dao
interface GpsDao {
    @Insert suspend fun insert(row: GpsWorkout): Long
    @Update suspend fun update(row: GpsWorkout)
    @Query("SELECT * FROM gps_workouts WHERE id = :id")
    suspend fun get(id: Long): GpsWorkout?
    @Query("SELECT * FROM gps_workouts ORDER BY startTime DESC LIMIT 1")
    suspend fun latest(): GpsWorkout?
    @Query("SELECT * FROM gps_workouts WHERE date = :date ORDER BY startTime DESC")
    suspend fun forDate(date: String): List<GpsWorkout>
}

@Dao
interface DrivingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(rows: List<DrivingQuestion>)
    @Query("SELECT COUNT(*) FROM driving_questions")
    suspend fun count(): Int
    @Query("SELECT * FROM driving_questions")
    suspend fun all(): List<DrivingQuestion>
    @Query("SELECT * FROM driving_questions WHERE subject = :subject")
    suspend fun bySubject(subject: Int): List<DrivingQuestion>
    @Query("SELECT * FROM driving_questions WHERE questionId = :id")
    suspend fun get(id: String): DrivingQuestion?
    @Query("UPDATE driving_questions SET wrongCount = wrongCount + 1, lastPracticedDate = :date WHERE questionId = :id")
    suspend fun markWrong(id: String, date: String)
    @Query("UPDATE driving_questions SET lastPracticedDate = :date WHERE questionId = :id")
    suspend fun markSeen(id: String, date: String)
    @Insert suspend fun insertAttempt(row: DrivingAttempt): Long
    @Query("SELECT COUNT(*) FROM driving_attempts WHERE date = :date AND isBackfill = 0")
    suspend fun realtimeAttempts(date: String): Int
    @Query("SELECT COUNT(*) FROM driving_attempts WHERE date = :date AND isBackfill = 1")
    suspend fun backfillAttempts(date: String): Int
    @Insert suspend fun insertSession(row: DrivingSession): Long
    @Query("SELECT COUNT(*) FROM driving_sessions WHERE date = :date AND finishedAt IS NOT NULL AND isBackfill = 0")
    suspend fun finishedSessions(date: String): Int
    @Query("SELECT * FROM driving_exam_plan WHERE id = 1")
    suspend fun plan(): DrivingExamPlan?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertPlan(row: DrivingExamPlan)
}

@Dao
interface AiDao {
    @Insert suspend fun insertMessage(row: AiChatMessage): Long
    @Query("SELECT * FROM ai_chat_messages ORDER BY timestamp ASC")
    fun observeMessages(): Flow<List<AiChatMessage>>
    @Query("SELECT * FROM ai_memory_profile")
    suspend fun memory(): List<AiMemoryProfile>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertMemory(row: AiMemoryProfile)
    @Insert suspend fun insertProvider(row: AiProviderConfig): Long
    @Update suspend fun updateProvider(row: AiProviderConfig)
    @Query("DELETE FROM ai_providers WHERE id = :id")
    suspend fun deleteProvider(id: Long)
    @Query("SELECT * FROM ai_providers ORDER BY id ASC")
    fun observeProviders(): Flow<List<AiProviderConfig>>
    @Query("SELECT * FROM ai_providers WHERE id = :id")
    suspend fun provider(id: Long): AiProviderConfig?
    @Query("SELECT * FROM ai_providers")
    suspend fun providers(): List<AiProviderConfig>
}

@Dao
interface StreakDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(row: SelfDisciplineStreak)
    @Query("SELECT * FROM self_discipline_streak WHERE date = :date")
    suspend fun get(date: String): SelfDisciplineStreak?
    @Query("SELECT * FROM self_discipline_streak WHERE date < :date AND status != 'PENDING' ORDER BY date DESC LIMIT 1")
    suspend fun previousFinal(date: String): SelfDisciplineStreak?
    @Query("SELECT COALESCE(MAX(streakValue), 0) FROM self_discipline_streak WHERE status = 'MET'")
    suspend fun maxStreak(): Int
    @Query("SELECT * FROM self_discipline_streak ORDER BY date DESC LIMIT 1")
    suspend fun latest(): SelfDisciplineStreak?
}

@Dao
interface ViolationDao {
    @Insert suspend fun insert(row: ViolationRecord): Long
    @Query("SELECT * FROM violation_records WHERE date = :date ORDER BY timestamp ASC")
    suspend fun forDate(date: String): List<ViolationRecord>
    @Query("SELECT COUNT(*) FROM violation_records WHERE date = :date AND type = :type")
    suspend fun countType(date: String, type: String): Int
}

@Dao
interface UnlockDao {
    @Insert suspend fun insert(row: UnlockEvent): Long
    @Query("SELECT * FROM unlock_events WHERE date = :date ORDER BY grantedAt DESC")
    suspend fun forDate(date: String): List<UnlockEvent>
    @Query("UPDATE unlock_events SET revokedAt = :at WHERE date = :date AND revokedAt IS NULL")
    suspend fun revokeOpen(date: String, at: Long)
}

@Dao
interface ThresholdDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(row: UserThreshold)
    @Query("SELECT * FROM user_thresholds ORDER BY key ASC")
    fun observe(): Flow<List<UserThreshold>>
    @Query("SELECT * FROM user_thresholds")
    suspend fun all(): List<UserThreshold>
    @Query("SELECT * FROM user_thresholds WHERE key = :key")
    suspend fun get(key: String): UserThreshold?
}

@Dao
interface BriefingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(row: Briefing)
    @Query("SELECT * FROM briefings WHERE date = :date")
    suspend fun get(date: String): Briefing?
    @Query("SELECT * FROM briefings ORDER BY date DESC LIMIT 1")
    suspend fun latest(): Briefing?
}

@Dao
interface LearningDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(row: CustomLearningModule)
    @Query("SELECT * FROM custom_learning_modules ORDER BY sortOrder ASC")
    suspend fun ordered(): List<CustomLearningModule>
    @Query("SELECT * FROM custom_learning_modules WHERE moduleId = :id")
    suspend fun get(id: String): CustomLearningModule?
    @Query("UPDATE custom_learning_modules SET countsTowardsStreak = 0")
    suspend fun clearHardFlags()
}

@Dao
interface QuoteDao {
    @Insert suspend fun insert(row: FinanceQuote): Long
    @Query("SELECT COUNT(*) FROM finance_quotes")
    suspend fun count(): Int
    @Query("SELECT * FROM finance_quotes ORDER BY id ASC")
    suspend fun all(): List<FinanceQuote>
    @Query("SELECT * FROM finance_quotes WHERE id = :id")
    suspend fun get(id: Long): FinanceQuote?
}

@Dao
interface EnglishDao {
    @Insert suspend fun insert(row: EnglishLog): Long
    @Query("SELECT COUNT(*) FROM english_logs WHERE date = :date")
    suspend fun count(date: String): Int
    @Query("SELECT * FROM english_logs WHERE date = :date ORDER BY timestamp ASC")
    suspend fun forDate(date: String): List<EnglishLog>
}
