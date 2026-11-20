package os.aether.discipline.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class RoutingTest {
    @Test
    fun deepSeekBaseWithOrWithoutV1HitsTheSameEndpoints() {
        val withVersion = "https://api.deepseek.com/v1"
        val bare = "https://api.deepseek.com"
        val sloppy = "https://api.deepseek.com/v1/"
        assertEquals("https://api.deepseek.com/v1/models", modelsUrl(withVersion))
        assertEquals("https://api.deepseek.com/v1/models", modelsUrl(providerBaseCandidates(bare).first()))
        assertEquals("https://api.deepseek.com/v1/models", modelsUrl(sloppy))
        assertEquals("https://api.deepseek.com/v1/chat/completions", chatCompletionsUrl(withVersion))
        assertEquals("https://api.deepseek.com/v1/chat/completions", chatCompletionsUrl(providerBaseCandidates(bare).first()))
        assertEquals("https://api.deepseek.com/v1/chat/completions", chatCompletionsUrl(sloppy))
    }

    @Test
    fun pastedEndpointAndMissingSchemeAreNormalized() {
        assertEquals(
            "https://api.deepseek.com/v1/models",
            modelsUrl(requireProviderBase("https://api.deepseek.com/v1/chat/completions")),
        )
        assertEquals(
            "https://api.deepseek.com/v1/models",
            modelsUrl(requireProviderBase("  api.deepseek.com/v1/models  ")),
        )
        assertEquals(
            "https://api.groq.com/openai/v1/chat/completions",
            chatCompletionsUrl("https://api.groq.com/openai/v1/"),
        )
    }

    @Test
    fun gatewayPathsKeepTheirPrefixAndTryV1OnlyWhenTheRootIsAmbiguous() {
        val host = "https://example.com"
        assertEquals(listOf("$host/v1", host), providerBaseCandidates(host))
        assertEquals(listOf("$host/gateway", "$host/gateway/v1"), providerBaseCandidates("$host/gateway/"))
        assertEquals(listOf("$host/gateway"), providerBaseCandidates("$host/gateway/chat/completions"))
        assertEquals(listOf(host), providerBaseCandidates("$host/models"))
        assertEquals(listOf("$host/v1beta/openai"), providerBaseCandidates("$host/v1beta/openai/"))
        assertEquals("$host/v1beta/openai/models", modelsUrl("$host/v1beta/openai"))
        assertEquals("$host/gateway/v1", legacyProviderBase("$host/gateway"))
        assertEquals("$host/v1", legacyProviderBase(host))
        assertEquals("$host/api/v4", legacyProviderBase("$host/api/v4"))
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
        assertTrue(readableApiError(404, "").contains("OpenAI 兼容地址"))
        assertTrue(readableApiError(500, "").contains("HTTP 500"))
        assertTrue(describeTransportFailure(IllegalStateException("timeout")).contains("连接超时"))
        assertTrue(describeTransportFailure(IllegalStateException("Cleartext HTTP traffic not permitted")).contains("明文"))
    }

    @Test
    fun modelPageChecksShapeAndPreservesCursorOrder() {
        val body = """
            {"data":[{"id":"deepseek-reasoner"},{"id":""},{"id":"deepseek-chat"},{"id":"deepseek-chat"}]}
        """.trimIndent()
        assertEquals(listOf("deepseek-reasoner", "deepseek-chat", "deepseek-chat"), parseModelPage(body)?.ids)
        assertNull(parseModelPage("not-json"))
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
    fun baseValidationAndAbsoluteAudioRouteDoNotReuseKeysAcrossHosts() {
        assertEquals("https://open.bigmodel.cn/api/paas/v4", requireProviderBase(" HTTPS://open.bigmodel.cn/api/paas/v4/models/ "))
        assertEquals("https://example.com/v1/audio/speech", audioEndpoint("https://example.com/v1", "/audio/speech"))
        assertEquals("https://example.com/tools/asr", audioEndpoint("https://example.com/v1", "https://example.com/tools/asr"))
        assertThrows(IllegalArgumentException::class.java) { audioEndpoint("https://example.com/v1", "https://other.example/tools/asr") }
        assertThrows(IllegalArgumentException::class.java) { requireProviderBase("http://example.com") }
        assertThrows(IllegalArgumentException::class.java) { requireProviderBase("https://secret@example.com/v1") }
        assertThrows(IllegalArgumentException::class.java) { requireProviderBase("https://example.com?v=1") }
        assertThrows(IllegalArgumentException::class.java) { requireProviderBase("ftp://example.com") }
    }
}
