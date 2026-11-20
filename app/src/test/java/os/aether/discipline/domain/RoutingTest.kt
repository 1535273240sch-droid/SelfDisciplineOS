package os.aether.discipline.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingTest {
    @Test
    fun deepSeekBaseWithOrWithoutV1HitsTheSameEndpoints() {
        val withVersion = "https://api.deepseek.com/v1"
        val bare = "https://api.deepseek.com"
        val sloppy = "https://api.deepseek.com/v1/"
        assertEquals("https://api.deepseek.com/v1/models", modelsUrl(withVersion))
        assertEquals("https://api.deepseek.com/v1/models", modelsUrl(bare))
        assertEquals("https://api.deepseek.com/v1/models", modelsUrl(sloppy))
        assertEquals("https://api.deepseek.com/v1/chat/completions", chatCompletionsUrl(withVersion))
        assertEquals("https://api.deepseek.com/v1/chat/completions", chatCompletionsUrl(bare))
        assertEquals("https://api.deepseek.com/v1/chat/completions", chatCompletionsUrl(sloppy))
    }

    @Test
    fun pastedEndpointAndMissingSchemeAreNormalized() {
        assertEquals(
            "https://api.deepseek.com/v1/models",
            modelsUrl("https://api.deepseek.com/v1/chat/completions"),
        )
        assertEquals(
            "https://api.deepseek.com/v1/models",
            modelsUrl("  api.deepseek.com/v1/models  "),
        )
        assertEquals(
            "https://api.groq.com/openai/v1/chat/completions",
            chatCompletionsUrl("https://api.groq.com/openai/v1/"),
        )
    }

    @Test
    fun versionedNonV1BaseIsNotRewritten() {
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/audio/transcriptions",
            endpointUrl("https://open.bigmodel.cn/api/paas/v4", "/audio/transcriptions"),
        )
        assertEquals(
            "https://api.deepseek.com/v1/tools/asr",
            endpointUrl("https://api.deepseek.com/v1", "/tools/asr"),
        )
        assertEquals(
            "https://api.deepseek.com/v1/tools/asr",
            endpointUrl("https://api.deepseek.com", "/v1/tools/asr"),
        )
        assertEquals(
            "http://127.0.0.1:11434/v1/models",
            modelsUrl("http://127.0.0.1:11434/v1"),
        )
    }

    @Test
    fun absolutePathIsLeftAlone() {
        assertEquals(
            "https://example.com/custom",
            endpointUrl("https://api.deepseek.com/v1", "https://example.com/custom"),
        )
    }

    @Test
    fun readableErrorsKeepTheProviderMessage() {
        val body = """{"error":{"message":"Authentication Fails, Your api key: ****test is invalid","type":"authentication_error"}}"""
        val text = readableApiError(401, body)
        assertTrue(text.contains("密钥无效或没有权限"))
        assertTrue(text.contains("Authentication Fails"))
        assertTrue(readableApiError(404, "").contains("不要再叠一层"))
        assertTrue(readableApiError(500, "").contains("HTTP 500"))
        assertTrue(describeTransportFailure(IllegalStateException("timeout")).contains("连接超时"))
        assertTrue(describeTransportFailure(IllegalStateException("Cleartext HTTP traffic not permitted")).contains("明文"))
    }

    @Test
    fun modelIdsAreDistinctAndSorted() {
        val body = """
            {"data":[{"id":"deepseek-reasoner"},{"id":""},{"id":"deepseek-chat"},{"id":"deepseek-chat"}]}
        """.trimIndent()
        assertEquals(listOf("deepseek-chat", "deepseek-reasoner"), parseModelIds(body))
        assertTrue(parseModelIds("not-json").isEmpty())
    }

    @Test
    fun planParsesJsonFenceStringItemsAndTimetable() {
        val fenced = """
            好，今天这样排：
            ```json
            {"items":["散步 20 分钟","驾考 80 题"],"note":"做不完告诉我"}
            ```
        """.trimIndent()
        val fromFence = parseDailyPlan(fenced, "2026-11-19")
        assertEquals(listOf("散步 20 分钟", "驾考 80 题"), fromFence?.items)
        assertEquals("做不完告诉我", fromFence?.note)
        assertEquals("ai", fromFence?.source)
        assertEquals("2026-11-19", fromFence?.date)

        val lines = parseDailyPlan("""{"items":"英语 15 分钟\n驾考 40 题","note":"轻一点"}""", "2026-11-19")
        assertEquals(listOf("英语 15 分钟", "驾考 40 题"), lines?.items)

        val schedule = parseDailyPlan(
            """
            08:30 英语 15 分钟
            09:00 驾考 40 题
            18:30 散步 20 分钟
            强度已经按睡眠下调。
            """.trimIndent(),
            "2026-11-19",
        )
        assertEquals(3, schedule?.items?.size)
        assertEquals("强度已经按睡眠下调。", schedule?.note)
        assertNull(parseDailyPlan("今天先这样。", "2026-11-19"))
    }

    @Test
    fun planJsonRoundTripAndLateSleepFallback() {
        val saved = DailyPlan("2026-11-19", listOf("驾考 40 题", "散步 20 分钟"), "轻一点", "ai")
        val back = planFromJson(planToJson(saved))
        assertEquals(saved, back)
        assertNull(planFromJson("""{"items":[]}"""))

        val late = localFallbackPlan(
            PlanContext(
                date = "2026-11-19",
                sleepLate = true,
                hardTitle = "驾考",
                hardTarget = 100,
                hardUnit = "题",
                calorieLimit = 2000,
                englishTarget = 5,
                lockHHmm = "00:00",
                trainingName = "俯卧撑",
            ),
        )
        assertEquals("local", late.source)
        assertTrue(late.items[0].contains("20 分钟散步"))
        assertTrue(late.items[1].contains("80"))
        assertTrue(late.items[1].contains("下调"))
        assertTrue(late.items[1].contains("达标仍需 100题"))
        assertTrue(late.items.any { it.contains("2000") })
        assertTrue(late.note.contains("睡眠"))

        val rested = localFallbackPlan(
            PlanContext("2026-11-19", false, "驾考", 100, "题", 0, 5, "00:00", "俯卧撑"),
        )
        assertFalse(rested.items[0].contains("散步"))
        assertTrue(rested.items[1].contains("100"))
    }

    @Test
    fun bindingChoosesManualModelAndSkipsOtherProviders() {
        assertEquals("deepseek-chat", chooseModel(" deepseek-chat ", listOf("other")))
        assertEquals("other", chooseModel("  ", listOf("other", "second")))
        assertEquals("", chooseModel("", emptyList()))

        val bound = mapOf("companion" to 7L, "vision" to 7L)
        assertEquals(listOf("fast", "briefing"), scenesToAssign(bound, providerId = 3L))
        assertEquals(listOf("companion", "fast", "briefing"), scenesToAssign(bound, providerId = 7L))
        assertEquals(listOf("companion", "fast", "briefing"), scenesToAssign(emptyMap(), providerId = 1L))
    }

    @Test
    fun testCopyExplainsSuccessFailureAndManualFallback() {
        val ok = modelTestCopy(null, 8, "deepseek-chat", listOf("搭档对话", "快速记录", "早报总结"))
        assertTrue(ok.connected)
        assertTrue(ok.text.contains("共拉取 8 个模型"))
        assertTrue(ok.text.contains("已绑定搭档对话、快速记录、早报总结"))

        val manual = modelTestCopy("密钥无效或没有权限（HTTP 401）", 0, "deepseek-chat", listOf("搭档对话"))
        assertFalse(manual.connected)
        assertTrue(manual.text.contains("已保留手动模型"))
        assertTrue(manual.text.contains("HTTP 401"))

        val blocked = modelTestCopy("先填写 API Key", 0, "", emptyList())
        assertEquals("先填写 API Key", blocked.text)
    }

    @Test
    fun greetingDoesNotNeedAName() {
        assertEquals("早上好", greetingForHour(7))
        assertEquals("中午好", greetingForHour(12))
        assertEquals("下午好", greetingForHour(15))
        assertEquals("晚上好", greetingForHour(23))
    }
}
