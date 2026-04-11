import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*

private val logger = KotlinLogging.logger {}

class Odapilot : CliktCommand() {
    override fun help(context: com.github.ajalt.clikt.core.Context) = "Oda grocery API gateway and analysis service"

    private val email by option("--email", help = "Oda account email").required()
    private val password by option("--password", help = "Oda account password").required()
    private val port by option("--port", help = "HTTP server port").int().default(3021)

    override fun run() {
        logger.info { "Odapilot starting — email=$email, port=$port" }

        val db = Database()
        val odaClient = OdaClient(email, password)
        val sync = OrderSync(odaClient, db)
        try {
            db.connect()
            db.initialize()

            embeddedServer(Netty, port = port) {
                install(ContentNegotiation) { json() }
                configureRoutes(db, sync)
            }.start(wait = true)
        } finally {
            logger.info { "Shutting down..." }
            odaClient.close()
            db.close()
        }
    }
}

fun main(args: Array<String>) = Odapilot().main(args)
