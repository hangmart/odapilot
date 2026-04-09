import io.github.oshai.kotlinlogging.KotlinLogging
import java.sql.Connection
import java.sql.DriverManager

private val logger = KotlinLogging.logger {}

class Database(private val path: String = "odapilot.db") {
    fun connect(): Connection {
        logger.info { "Connecting to SQLite database: $path" }
        return DriverManager.getConnection("jdbc:sqlite:$path")
    }

    fun initialize(conn: Connection) {
        logger.info { "Database.initialize() — stub, no tables yet" }
    }
}
