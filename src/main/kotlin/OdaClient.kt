import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate

private val logger = KotlinLogging.logger {}

private val NORWEGIAN_MONTHS = mapOf(
    "jan" to 1, "feb" to 2, "mars" to 3, "mar" to 3,
    "apr" to 4, "mai" to 5, "jun" to 6,
    "jul" to 7, "aug" to 8, "sep" to 9,
    "okt" to 10, "nov" to 11, "des" to 12,
)

fun parseNorwegianDate(raw: String): Instant? {
    val match = Regex("""(\d{1,2})\.\s*(\w+),?\s*(\d{2}):(\d{2})""").find(raw) ?: return null
    val (dayStr, monthStr, hourStr, minStr) = match.destructured
    val month = NORWEGIAN_MONTHS[monthStr.lowercase()] ?: return null
    val day = dayStr.toInt()

    val today = LocalDate.now()
    var year = today.year
    val candidate = LocalDate.of(year, month, day)
    if (candidate.isAfter(today)) year--

    val iso = "%04d-%02d-%02dT%02d:%02d:00Z".format(year, month, day, hourStr.toInt(), minStr.toInt())
    return Instant.parse(iso)
}

@Serializable
private data class LoginRequest(
    val username: String,
    val password: String,
)

class OdaClient(
    private val email: String,
    private val password: String,
) {
    private val baseUrl = "https://oda.com"
    private var authenticated = false

    private val client = HttpClient(CIO) {
        install(HttpCookies)
        install(ContentNegotiation) {
            json(odaJson)
        }
        install(DefaultRequest) {
            header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36")
            header("Origin", "https://oda.com")
            header("Referer", "https://oda.com/")
            header("X-Client-App", "tienda-web")
            header("X-Country", "no")
            header("X-Language", "nb")
            header("X-Requested-Case", "camel")
        }
    }

    private suspend fun csrfToken(): String {
        return client.cookies(baseUrl)
            .firstOrNull { it.name == "csrftoken" }?.value
            ?: throw IllegalStateException("No CSRF token in cookies")
    }

    suspend fun login() {
        logger.info { "Logging in to Oda..." }

        // Step 1: GET login page to pick up csrftoken cookie
        client.get("$baseUrl/no/user/login/")

        // Step 2: POST credentials with CSRF token
        val response = client.post("$baseUrl/api/v1/user/login/") {
            header("X-CSRFToken", csrfToken())
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username = email, password = password))
        }

        if (!response.status.isSuccess()) {
            throw IllegalStateException("Login failed: ${response.status}")
        }

        authenticated = true
        logger.info { "Login successful" }
    }

    private suspend inline fun <reified T> authenticatedGet(path: String, block: HttpRequestBuilder.() -> Unit = {}): T {
        val response = client.get("$baseUrl$path") {
            block()
        }

        if (response.status == HttpStatusCode.Unauthorized && authenticated) {
            logger.info { "Session expired, re-authenticating..." }
            authenticated = false
            login()
            return client.get("$baseUrl$path") { block() }.body()
        }

        if (!response.status.isSuccess()) {
            throw IllegalStateException("GET $path failed: ${response.status}")
        }

        return response.body()
    }

    suspend fun getOrders(throughDate: String? = null): OdaOrdersResponse {
        val response: OdaOrdersResponse = authenticatedGet("/api/v1/orders/") {
            if (throughDate != null) {
                parameter("through-date", throughDate)
            }
        }

        // Parse Norwegian delivery times to Instant
        return response.copy(
            results = response.results.map { group ->
                group.copy(
                    orders = group.orders.map { order ->
                        val parsed = order.delivery?.deliveryTime?.let { parseNorwegianDate(it) }
                        order.copy(parsedDeliveryTime = parsed)
                    }
                )
            }
        )
    }

    suspend fun getOrderDetail(orderId: String): OdaOrderDetail {
        return authenticatedGet("/api/v1/orders/$orderId/")
    }

    suspend fun refreshSession(): Boolean {
        return try {
            val response = client.get("$baseUrl/api/v1/user/refresh/")
            response.status.isSuccess()
        } catch (e: Exception) {
            false
        }
    }

    fun close() {
        client.close()
    }
}
