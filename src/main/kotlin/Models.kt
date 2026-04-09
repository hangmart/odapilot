import kotlinx.serialization.Serializable

@Serializable
data class Product(
    val id: Int = 0,
    val odaProductId: Int,
    val name: String,
    val category: String? = null,
    val productType: String? = null,
)

@Serializable
data class OrderSummary(
    val id: Int = 0,
    val odaOrderId: String,
    val orderedAt: String,
    val orderedAtIso: String? = null,
)
