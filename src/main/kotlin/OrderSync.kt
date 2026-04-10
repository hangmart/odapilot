import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate

private val logger = KotlinLogging.logger {}

class OrderSync(
    private val client: OdaClient,
    private val db: Database,
    private val cutoffYear: Int = 2025,
) {
    suspend fun syncOrders() {
        val latestKnown = db.latestOrderId()
        if (latestKnown != null) {
            logger.info { "Most recent order in DB: $latestKnown" }
        }

        logger.info { "Starting order sync..." }
        var throughDate = LocalDate.now().toString()
        var page = 0
        var reachedKnown = false

        while (!reachedKnown) {
            page++
            logger.info { "Fetching order page $page (through $throughDate)" }

            val response = client.getOrders(throughDate)
            var newOrders = 0

            for (group in response.results) {
                for (order in group.orders) {
                    if (order.orderNumber == latestKnown) {
                        reachedKnown = true
                        logger.info { "Reached latest known order $latestKnown, stopping" }
                        break
                    }
                    if (db.orderExists(order.orderNumber)) continue
                    newOrders++

                    delay((4000L..8000L).random())
                    syncOrderDetail(order)
                }
                if (reachedKnown) break
            }

            logger.info { "Page $page: $newOrders new orders synced" }

            if (!response.hasMore) break

            val nextDate = response.getMoreUrl?.let {
                Regex("through-date=([^&]+)").find(it)?.groupValues?.get(1)
            } ?: break

            if (LocalDate.parse(nextDate).year < cutoffYear) {
                logger.info { "Next page ($nextDate) is before $cutoffYear, stopping" }
                break
            }
            throughDate = nextDate

            delay((3000L..6000L).random())
        }

        logger.info { "Order sync complete" }
    }

    private suspend fun syncOrderDetail(order: OdaOrder) {
        logger.info { "Syncing order ${order.orderNumber}" }

        val detail = client.getOrderDetail(order.orderNumber)
        val orderedAt = order.parsedDeliveryTime ?: Instant.now()

        val orderId = db.insertOrder(order.orderNumber, orderedAt)

        for (group in detail.items?.itemGroups.orEmpty()) {
            if (group.type != "category") continue

            for (item in group.items) {
                val productId = db.upsertProduct(
                    odaProductId = item.productId,
                    name = item.description,
                    category = group.name,
                    normalizedName = null,
                )
                db.insertOrderItem(orderId, productId, item.quantity.toInt())
            }
        }
    }
}
