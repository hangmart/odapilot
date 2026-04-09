import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.cookies.*

private val logger = KotlinLogging.logger {}

class OdaClient(
    private val email: String,
    private val password: String,
) {
    private val client = HttpClient(CIO) {
        install(HttpCookies)
    }

    suspend fun login() {
        logger.info { "OdaClient.login() — stub, not connecting yet" }
    }

    fun close() {
        client.close()
    }
}
