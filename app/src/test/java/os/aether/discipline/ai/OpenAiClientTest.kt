package os.aether.discipline.ai

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiClientTest {
    @Test
    fun listsEveryPageAndUsesReturnedModelForRealChat(): Unit = runBlocking {
        MockWebServer().use { server ->
            val first = (1..85).joinToString(",") { """{"id":"model-$it"}""" }
            server.enqueue(MockResponse().setBody("""{"data":[$first],"has_more":true,"last_id":"model-85"}"""))
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"model-85"},{"id":"text-after-page"}],"has_more":false}"""))
            server.enqueue(chatReply("OK"))
            val base = server.url("/api/v4").toString()
            val client = OpenAiClient()
            val models = client.listModels(base, "fake-test")
            assertEquals(86, models.size)
            val selected = models.single { it.endsWith("page") }
            client.verifyModel("companion", base, "fake-test", selected, "")
            assertEquals("/api/v4/models", server.takeRequest().path)
            assertEquals("/api/v4/models?after=model-85", server.takeRequest().path)
            val request = server.takeRequest()
            assertEquals("/api/v4/chat/completions", request.path)
            assertEquals("Bearer fake-test", request.getHeader("Authorization"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals(selected, body.getString("model"))
            assertFalse(body.has("temperature"))
        }
    }

    @Test
    fun emptyListCanUseManualModelButMalformedPageFails(): Unit = runBlocking {
        MockWebServer().use { server ->
            val base = server.url("/").toString()
            val client = OpenAiClient()
            server.enqueue(MockResponse().setBody("""{"data":[]}"""))
            server.enqueue(chatReply("manual works"))
            server.enqueue(MockResponse().setBody("""{"data":{"id":"not-an-array"}}"""))
            assertTrue(client.listModels(base, "fake").isEmpty())
            client.verifyModel("companion", base, "fake", "manual-id", "")
            assertThrows(IllegalStateException::class.java) { runBlocking { client.listModels(base, "fake") } }
            server.takeRequest()
            assertEquals("manual-id", JSONObject(server.takeRequest().body.readUtf8()).getString("model"))
        }
    }

    @Test
    fun returnsFailureForRepeatedPaginationAndKeepsKeysOutOfErrors(): Unit = runBlocking {
        MockWebServer().use { server ->
            val base = server.url("/").toString()
            val client = OpenAiClient()
            val key = "never-display-this-test-token"
            repeat(2) { server.enqueue(MockResponse().setBody("""{"data":[{"id":"same"}],"has_more":true}""")) }
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Invalid key $key"}}"""))
            val cursor = assertThrows(IllegalStateException::class.java) { runBlocking { client.listModels(base, key) } }
            assertTrue(cursor.message.orEmpty().contains("重复"))
            val auth = assertThrows(IllegalStateException::class.java) { runBlocking { client.listModels(base, key) } }
            assertTrue(auth.message.orEmpty().contains("401"))
            assertFalse(auth.message.orEmpty().contains(key))
        }
    }

    @Test
    fun textOnlyModelsCannotPassTheQuickRecordProbe(): Unit = runBlocking {
        MockWebServer().use { server ->
            val base = server.url("/").toString()
            val client = OpenAiClient()
            server.enqueue(chatReply("I cannot call tools"))
            server.enqueue(MockResponse().setBody(
                """{"choices":[{"message":{"content":null,"tool_calls":[{"id":"probe","function":{"name":"verify_recording","arguments":"{}"}}]}}]}""",
            ))
            assertThrows(IllegalStateException::class.java) {
                runBlocking { client.verifyModel("fast", base, "fake", "tool-model", "") }
            }
            client.verifyModel("fast", base, "fake", "tool-model", "")
            assertTrue(JSONObject(server.takeRequest().body.readUtf8()).has("tools"))
        }
    }

    @Test
    fun inferenceRetriesUnsupportedTemperatureAndRejectsFalseSuccess(): Unit = runBlocking {
        MockWebServer().use { server ->
            val base = server.url("/").toString()
            val client = OpenAiClient()
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Unsupported parameter temperature"}}"""))
            server.enqueue(chatReply("OK"))
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":null}}]}"""))
            val messages = JSONArray("""[{"role":"user","content":"probe"}]""")
            assertEquals("OK", client.chat(base, "fake", "strict", messages).content)
            assertTrue(JSONObject(server.takeRequest().body.readUtf8()).has("temperature"))
            assertFalse(JSONObject(server.takeRequest().body.readUtf8()).has("temperature"))
            assertThrows(IllegalStateException::class.java) {
                runBlocking { client.verifyModel("companion", base, "fake", "strict", "") }
            }
        }
    }

    @Test
    fun usesTheSameVoiceAndVisionEndpointsAsTheFeatures(): Unit = runBlocking {
        MockWebServer().use { server ->
            val base = server.url("/v1").toString()
            val client = OpenAiClient()
            server.enqueue(chatReply("蓝色"))
            server.enqueue(MockResponse().setBody("""{"text":""}"""))
            server.enqueue(MockResponse().addHeader("Content-Type", "audio/mpeg").setBody("ID3" + "a".repeat(48)))
            server.enqueue(MockResponse().addHeader("Content-Type", "application/json").setBody("""{"status":"not audio"}"""))
            client.verifyModel("vision", base, "fake", "vision-id", "")
            client.verifyModel("transcribe", base, "fake", "transcribe-id", "/audio/transcriptions")
            client.verifyModel("tts", base, "fake", "voice-id", "/audio/speech")
            val vision = server.takeRequest()
            assertEquals("/v1/chat/completions", vision.path)
            assertTrue(vision.body.readUtf8().contains("data:image/jpeg;base64,"))
            val transcript = server.takeRequest()
            assertEquals("/v1/audio/transcriptions", transcript.path)
            assertTrue(transcript.body.readUtf8().contains("transcribe-id"))
            val tts = server.takeRequest()
            assertEquals("/v1/audio/speech", tts.path)
            assertEquals("voice-id", JSONObject(tts.body.readUtf8()).getString("model"))
            assertThrows(IllegalStateException::class.java) {
                runBlocking { client.verifyModel("tts", base, "fake", "voice-id", "") }
            }
        }
    }

    @Test
    fun listModelsHitsV1AndSurfacesTheErrorBody() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"message":"Authentication Fails, Your api key: ****test is invalid"}}"""),
        )
        server.start()
        try {
            val base = server.url("/v1").toString()
            val failure = runCatching {
                runBlocking { OpenAiClient().listModels(base, "sk-test") }
            }.exceptionOrNull()
            val message = failure?.message.orEmpty()
            assertTrue(message, message.contains("密钥无效"))
            assertTrue(message, message.contains("Authentication Fails"))
            val request = server.takeRequest()
            assertEquals("/v1/models", request.path)
            assertEquals("Bearer sk-test", request.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun listModelsParsesIdsWhenTheHostHasNoVersionSuffix() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"data":[{"id":"zeta"},{"id":"alpha"},{"id":"alpha"}]}"""),
        )
        server.start()
        try {
            val models = runBlocking { OpenAiClient().listModels(server.url("/").toString(), "sk-live") }
            assertEquals(listOf("alpha", "zeta"), models)
            assertEquals("/v1/models", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }

    private fun chatReply(content: String) =
        MockResponse().setBody("""{"choices":[{"message":{"content":${JSONObject.quote(content)}}}]}""")
}
