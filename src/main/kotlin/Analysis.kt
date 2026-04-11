import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.sqrt

private val logger = KotlinLogging.logger {}

// Metrics per normalized product name:
//   orderCount      — total number of orders containing this product
//   freqAllTime     — fraction of all orders (0–1)
//   weightedFreq    — recency-weighted order count (exp. decay, 90-day half-life)
//   freqRecent30d   — number of orders in the last 30 days
//   avgGapDays      — mean days between consecutive purchases
//   avgGapPerUnit   — mean gap divided by quantity (key replenishment cycle indicator)
//   weightedAvgGap  — recency-weighted average gap
//   cv              — coefficient of variation of gaps (low = regular, high = sporadic)
//   daysSinceLast   — days since most recent purchase
//   lastOrderDate   — timestamp of most recent purchase
//   lastQty         — quantity in the most recent order
//   avgQtyPerOrder  — mean quantity per order
//   urgency         — daysSinceLast / (avgGapPerUnit × lastQty); >1.0 = overdue
data class ProductStats(
    val normalizedName: String,
    val orderCount: Int,
    val freqAllTime: Double,
    val weightedFreq: Double,
    val freqRecent30d: Int,
    val avgGapDays: Double,
    val avgGapPerUnit: Double,
    val weightedAvgGap: Double,
    val cv: Double,
    val daysSinceLast: Long,
    val lastOrderDate: Instant,
    val lastQty: Int,
    val avgQtyPerOrder: Double,
    val urgency: Double,
)

object Analysis {
    private const val HALF_LIFE_DAYS = 90.0
    private const val MIN_ORDERS = 5

    fun computeStats(
        history: Map<String, List<Purchase>>,
        totalOrders: Int,
        now: Instant = Instant.now(),
    ): List<ProductStats> {
        logger.info { "Computing stats for ${history.size} products, $totalOrders total orders" }

        return history.mapNotNull { (name, entries) ->
            if (entries.size < MIN_ORDERS) return@mapNotNull null
            computeProduct(name, entries, totalOrders, now)
        }.sortedByDescending { it.urgency }
    }

    private fun computeProduct(
        name: String,
        entries: List<Purchase>,
        totalOrders: Int,
        now: Instant,
    ): ProductStats {
        val orderCount = entries.size
        val freqAllTime = orderCount.toDouble() / totalOrders

        val lambda = Math.log(2.0) / HALF_LIFE_DAYS
        val weightedFreq = entries.sumOf { p ->
            val daysAgo = ChronoUnit.DAYS.between(p.orderedAt, now).toDouble()
            Math.exp(-lambda * daysAgo)
        }

        val cutoff30d = now.minus(30, ChronoUnit.DAYS)
        val freqRecent30d = entries.count { it.orderedAt.isAfter(cutoff30d) }

        val gaps = entries.zipWithNext().map { (a, b) ->
            ChronoUnit.DAYS.between(a.orderedAt, b.orderedAt).toDouble()
        }
        val avgGapDays = gaps.average()

        val gapsPerUnit = entries.zipWithNext().map { (a, b) ->
            ChronoUnit.DAYS.between(a.orderedAt, b.orderedAt).toDouble() / a.quantity
        }
        val avgGapPerUnit = gapsPerUnit.average()

        val weightedAvgGap = if (gaps.isNotEmpty()) {
            val midpoints = entries.zipWithNext().map { (a, b) ->
                val mid = a.orderedAt.plusMillis(ChronoUnit.MILLIS.between(a.orderedAt, b.orderedAt) / 2)
                ChronoUnit.DAYS.between(mid, now).toDouble()
            }
            val weights = midpoints.map { Math.exp(-lambda * it) }
            val totalWeight = weights.sum()
            gaps.zip(weights).sumOf { (g, w) -> g * w } / totalWeight
        } else avgGapDays

        val cv = if (gaps.size >= 2) {
            val mean = gaps.average()
            val stddev = sqrt(gaps.sumOf { (it - mean) * (it - mean) } / gaps.size)
            stddev / mean
        } else 0.0

        val last = entries.last()
        val daysSinceLast = ChronoUnit.DAYS.between(last.orderedAt, now)
        val avgQtyPerOrder = entries.sumOf { it.quantity }.toDouble() / orderCount

        val expectedCycle = avgGapPerUnit * last.quantity
        val urgency = if (expectedCycle > 0) daysSinceLast.toDouble() / expectedCycle else 0.0

        return ProductStats(
            normalizedName = name,
            orderCount = orderCount,
            freqAllTime = freqAllTime,
            weightedFreq = weightedFreq,
            freqRecent30d = freqRecent30d,
            avgGapDays = avgGapDays,
            avgGapPerUnit = avgGapPerUnit,
            weightedAvgGap = weightedAvgGap,
            cv = cv,
            daysSinceLast = daysSinceLast,
            lastOrderDate = last.orderedAt,
            lastQty = last.quantity,
            avgQtyPerOrder = avgQtyPerOrder,
            urgency = urgency,
        )
    }
}
