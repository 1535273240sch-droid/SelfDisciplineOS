package os.aether.discipline.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import os.aether.discipline.domain.legacyProviderBase

@Database(
    entities = [
        WeightRecord::class,
        SleepRecord::class,
        DietRecord::class,
        CalisthenicsProgress::class,
        CalisthenicsLog::class,
        GpsWorkout::class,
        DrivingQuestion::class,
        DrivingAttempt::class,
        DrivingSession::class,
        DrivingExamPlan::class,
        AiMemoryProfile::class,
        AiChatMessage::class,
        AiProviderConfig::class,
        SelfDisciplineStreak::class,
        ViolationRecord::class,
        UnlockEvent::class,
        UserThreshold::class,
        Briefing::class,
        EnglishLog::class,
        CustomLearningModule::class,
        FinanceQuote::class,
        DesktopWidgetConfig::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun weight(): WeightDao
    abstract fun sleep(): SleepDao
    abstract fun diet(): DietDao
    abstract fun calisthenics(): CalisthenicsDao
    abstract fun gps(): GpsDao
    abstract fun driving(): DrivingDao
    abstract fun ai(): AiDao
    abstract fun streak(): StreakDao
    abstract fun violations(): ViolationDao
    abstract fun unlocks(): UnlockDao
    abstract fun thresholds(): ThresholdDao
    abstract fun briefings(): BriefingDao
    abstract fun english(): EnglishDao
    abstract fun learning(): LearningDao
    abstract fun quotes(): QuoteDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /** v2 -> v3: daily progress date on learning modules. Existing rows start with "". */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE custom_learning_modules ADD COLUMN progressDate TEXT NOT NULL DEFAULT ''")
            }
        }

        // Existing providers stored an input URL. Store the effective API root for subsequent calls.
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.query("SELECT id, baseUrl FROM ai_providers").use { rows ->
                    while (rows.moveToNext()) {
                        val base = rows.getString(1)
                        val root = legacyProviderBase(base)
                        if (root != base) db.execSQL(
                            "UPDATE ai_providers SET baseUrl = ? WHERE id = ?",
                            arrayOf<Any>(root, rows.getLong(0)),
                        )
                    }
                }
            }
        }

        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "aether.db",
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                    // v1 had no stable schema; v2+ keeps history through explicit migrations.
                    .fallbackToDestructiveMigrationFrom(true, 1)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
