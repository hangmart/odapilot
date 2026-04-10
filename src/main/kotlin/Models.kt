import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

val odaJson = Json { ignoreUnknownKeys = true }

// --- Domain models (DB rows) ---

@Serializable
data class Product(
    val id: Int = 0,
    val odaProductId: Int,
    val name: String,
    val category: String? = null,
    val productType: String? = null,
)

data class OrderSummary(
    val id: Int = 0,
    val odaOrderId: String,
    val orderedAt: Instant,
)

// --- Oda API response models ---

@Serializable
data class OdaOrdersResponse(
    val hasMore: Boolean = false,
    val getMoreUrl: String? = null,
    val results: List<OdaOrderMonthGroup> = emptyList(),
)

@Serializable
data class OdaOrderMonthGroup(
    val orders: List<OdaOrder> = emptyList(),
)

@Serializable
data class OdaOrder(
    val orderNumber: String,
    val delivery: OdaDelivery? = null,
    @kotlinx.serialization.Transient
    val parsedDeliveryTime: Instant? = null,
)

@Serializable
data class OdaDelivery(
    val deliveryTime: String? = null,
)

@Serializable
data class OdaOrderDetail(
    val items: OdaOrderItems? = null,
)

@Serializable
data class OdaOrderItems(
    val itemGroups: List<OdaItemGroup> = emptyList(),
)

@Serializable
data class OdaItemGroup(
    val type: String? = null,
    val name: String? = null,
    val items: List<OdaOrderItem> = emptyList(),
)

@Serializable
data class OdaOrderItem(
    val productId: Int,
    val description: String,
    val quantity: Double = 1.0,
)
