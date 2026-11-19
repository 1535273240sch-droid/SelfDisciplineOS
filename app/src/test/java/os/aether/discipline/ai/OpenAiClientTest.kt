package os.aether.discipline.ai

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiClientTest {
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
}
