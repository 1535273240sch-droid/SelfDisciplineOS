package os.aether.discipline.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
    ],
    version = 1,
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

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "aether.db",
                ).fallbackToDestructiveMigration(true).build().also { instance = it }
            }
        }
    }
}
