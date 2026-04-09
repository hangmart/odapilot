import kotlinx.serialization.Serializable
import java.time.Instant

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
