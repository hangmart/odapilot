import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import java.time.Instant

private val logger = KotlinLogging.logger {}

class OrderSync(
    private val client: OdaClient,
    private val db: Database,
    private val maxPages: Int = 1,
) {
    suspend fun syncOrders() {
        logger.info { "Starting order sync..." }
        var throughDate: String? = null
        var page = 0

        while (page < maxPages) {
            page++
            logger.info { "Fetching order page $page${throughDate?.let { " (through $it)" } ?: ""}" }

            val response = client.getOrders(throughDate)
            var newOrders = 0

            for (group in response.results) {
                for (order in group.orders) {
                    if (db.orderExists(order.orderNumber)) continue
                    newOrders++

                    delay((4000L..8000L).random())
                    syncOrderDetail(order)
                }
            }

            logger.info { "Page $page: $newOrders new orders synced" }

            if (!response.hasMore) break

            throughDate = response.getMoreUrl?.let {
                Regex("through-date=([^&]+)").find(it)?.groupValues?.get(1)
            }
            if (throughDate == null) break

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
