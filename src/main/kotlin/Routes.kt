import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureRoutes(db: Database) {
    routing {
        get("/health") {
            call.respondText("ok")
        }

        get("/stats") {
            val history = db.orderHistoryByProduct()
            val total = db.totalOrderCount()
            call.respond(Analysis.computeStats(history, total))
        }
    }
}
