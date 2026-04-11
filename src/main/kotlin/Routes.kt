import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.sync.Mutex

private val syncMutex = Mutex()

fun Application.configureRoutes(db: Database, sync: OrderSync) {
    routing {
        get("/health") {
            call.respondText("ok")
        }

        get("/stats") {
            val history = db.orderHistoryByProduct()
            val total = db.totalOrderCount()
            call.respond(Analysis.computeStats(history, total))
        }

        post("/sync") {
            if (!syncMutex.tryLock()) {
                call.respond(HttpStatusCode.Conflict)
                return@post
            }
            try {
                sync.syncOrders()
                call.respond(HttpStatusCode.NoContent)
            } finally {
                syncMutex.unlock()
            }
        }
    }
}
