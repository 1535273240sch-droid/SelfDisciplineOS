package os.aether.discipline.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class RulesTest {
    private val t = Thresholds()

    @Test
    fun fridayNightLocksSaturdayAtOne() {
        val now = LocalDateTime.of(2026, 11, 20, 23, 0)
        assertEquals(LocalDateTime.of(2026, 11, 21, 1, 0), nextLock(now, t))
        assertFalse(inLockWindow(now, t))
    }

    @Test
    fun sundayNightLocksMondayAtMidnight() {
        val now = LocalDateTime.of(2026, 11, 22, 23, 30)
        assertEquals(LocalDateTime.of(2026, 11, 23, 0, 0), nextLock(now, t))
    }

    @Test
    fun mondayEarlyMorningIsInsideWindow() {
        val now = LocalDateTime.of(2026, 11, 16, 0, 30)
        assertTrue(inLockWindow(now, t))
        assertEquals(LocalTime.of(8, 30), releaseTime(now.toLocalDate(), t))
    }

    @Test
    fun saturdayBeforeOneIsNotLocked() {
        val now = LocalDateTime.of(2026, 11, 21, 0, 30)
        assertFalse(inLockWindow(now, t))
        assertTrue(inLockWindow(now.plusHours(1), t))
    }

    @Test
    fun lateSleepMath() {
        assertFalse(isLateSleep("23:40", "00:00"))
        assertTrue(isLateSleep("00:20", "00:00"))
        assertEquals(20, minutesLate("00:20", "00:00"))
        assertFalse(isLateSleep("00:40", "01:00"))
        assertTrue(isLateSleep("01:10", "01:00"))
        assertEquals("昨晚超过红线 20 分钟", sleepConclusion("00:20", "00:00", false))
    }

    @Test
    fun parserReadsTheSampleUtterance() {
        val parsed = parseUtterance("早啊，刚空腹上秤 72.4 公斤，昨晚 12 点 20 才睡，早饭吃了两个水煮蛋和一杯黑咖啡。", 8)
        assertEquals(72.4f, parsed.weight?.kg)
        assertEquals(true, parsed.weight?.fasting)
        assertEquals("00:20", parsed.sleep?.sleepHHmm)
        assertEquals("BREAKFAST", parsed.diet?.mealType)
    }

    @Test
    fun eveningSleepIsNotMidnight() {
        val parsed = parseSleep("昨晚11点半睡了")
        assertEquals("23:30", parsed?.sleepHHmm)
        assertFalse(isLateSleep(parsed!!.sleepHHmm, "00:00"))
    }

    @Test
    fun calisthenicsParse() {
        val parsed = parseCalisthenics("刚做了第五式标准俯卧撑，2 组每组 20 个")
        assertEquals("PUSHUP", parsed?.category)
        assertEquals(5, parsed?.step)
        assertEquals(2, parsed?.sets)
        assertEquals(20, parsed?.reps)
    }

    @Test
    fun streakContinuesAcrossBackfillAndBreaksOnEscape() {
        val met = decideDay(
            DayInput(Facet(true, false), Facet(true, false), Facet(true, false), Facet(true, false), true, emptyList(), null, 0, false),
            true,
        )
        assertEquals("MET", met.status)
        assertEquals(1, met.streakValue)
        val backfill = decideDay(
            DayInput(Facet(false, true), Facet(false, true), Facet(false, true), Facet(false, true), true, emptyList(), "MET", 1, false),
            true,
        )
        assertEquals("BACKFILL_ONLY", backfill.status)
        assertEquals(1, backfill.streakValue)
        val escape = decideDay(
            DayInput(Facet(true, false), Facet(true, false), Facet(true, false), Facet(true, false), true, listOf("ESCAPE_LOCK"), "BACKFILL_ONLY", 1, false),
            true,
        )
        assertEquals("BROKEN", escape.status)
        assertEquals(0, escape.streakValue)
    }

    @Test
    fun pendingUntilFinalize() {
        val pending = decideDay(
            DayInput(Facet(true, false), Facet(false, false), Facet(true, false), Facet(false, false), true, emptyList(), "MET", 4, false),
            false,
        )
        assertEquals("PENDING", pending.status)
        assertEquals(4, pending.streakValue)
    }

    @Test
    fun examPhaseWindow() {
        val exam = LocalDate.of(2026, 12, 1)
        assertEquals(ExamPhase.MOCK, examPhase(LocalDate.of(2026, 11, 28), exam))
        assertEquals(ExamPhase.DAILY_100, examPhase(LocalDate.of(2026, 11, 26), exam))
        assertEquals(ExamPhase.DAILY_100, examPhase(LocalDate.of(2026, 10, 1), exam))
        assertEquals(ExamPhase.DAILY_100, examPhase(LocalDate.of(2026, 11, 26), null))
    }

    @Test
    fun paperIsSeventyThirtyWhenPoolsAllow() {
        val pool = (1..70).map { QuestionRef("h$it", true, 0, 1) } +
            (1..30).map { QuestionRef("w$it", false, 2, 1) } +
            (1..20).map { QuestionRef("r$it", false, 0, 1) }
        val paper = buildDailyPaper(pool, 100, 7)
        assertEquals(100, paper.size)
        assertEquals(30, paper.count { it.wrongCount > 0 })
    }

    @Test
    fun backupKeepsFourteen() {
        val names = (1..16).map { "aether-202611%02d.db".format(it) }
        assertEquals(listOf(names[0], names[1]), pruneBackups(names, 14))
    }

    @Test
    fun speechScore() {
        assertTrue(similarity("The gate closes at noon", "the gate closes at noon") >= 95)
        assertTrue(similarity("The gate closes at noon", "gate closes") < 90)
    }
}
