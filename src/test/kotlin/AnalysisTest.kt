import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.time.Instant

class AnalysisTest : FunSpec({
    // Fixed "now" for deterministic tests
    val now = Instant.parse("2025-06-15T12:00:00Z")

    // Product bought every ~7 days, last purchase 3 days ago — should not be overdue
    val regular = (0..5).map { i ->
        Purchase(Instant.parse("2025-05-01T12:00:00Z").plusSeconds(i * 7L * 86400), 1)
    }
    // Last purchase: May 1 + 35 days = June 5. Days since last = 10. Avg gap = 7. Urgency ~1.4

    // Product bought 5 times over a long period, last purchase 60 days ago — should be overdue
    val overdue = listOf(
        Purchase(Instant.parse("2025-01-01T12:00:00Z"), 1),
        Purchase(Instant.parse("2025-02-01T12:00:00Z"), 1),
        Purchase(Instant.parse("2025-03-01T12:00:00Z"), 1),
        Purchase(Instant.parse("2025-03-15T12:00:00Z"), 1),
        Purchase(Instant.parse("2025-04-15T12:00:00Z"), 2),
    )
    // Last purchase: April 15, qty 2. Days since last = 61. Avg gap per unit ~26.

    // Product with only 3 orders — should be filtered out (min 5)
    val tooFew = (0..2).map { i ->
        Purchase(Instant.parse("2025-03-01T12:00:00Z").plusSeconds(i * 14L * 86400), 1)
    }

    val history = mapOf(
        "Banan" to regular,
        "Bleier" to overdue,
        "Sjokolade" to tooFew,
    )

    test("filters out products with fewer than 5 orders") {
        val stats = Analysis.computeStats(history, 50, now)
        stats.map { it.normalizedName }.toSet() shouldBe setOf("Banan", "Bleier")
    }

    test("sorted by urgency descending") {
        val stats = Analysis.computeStats(history, 50, now)
        stats[0].urgency shouldBeGreaterThan stats[1].urgency
    }

    test("computes correct order count and frequency") {
        val stats = Analysis.computeStats(history, 50, now)
        val banan = stats.first { it.normalizedName == "Banan" }

        banan.orderCount shouldBe 6
        banan.freqAllTime shouldBe 6.0 / 50
    }

    test("computes gap metrics") {
        val stats = Analysis.computeStats(history, 50, now)
        val banan = stats.first { it.normalizedName == "Banan" }

        banan.avgGapDays shouldBe 7.0
        banan.avgGapPerUnit shouldBe 7.0 // qty is always 1
        banan.cv shouldBeLessThan 0.01 // perfectly regular
    }

    test("urgency above 1 means overdue") {
        val stats = Analysis.computeStats(history, 50, now)
        val bleier = stats.first { it.normalizedName == "Bleier" }

        bleier.urgency shouldBeGreaterThan 1.0
        bleier.lastQty shouldBe 2
    }

    test("urgency below 2 for regular product") {
        val stats = Analysis.computeStats(history, 50, now)
        val banan = stats.first { it.normalizedName == "Banan" }

        // Last purchase 10 days ago, avg gap 7 → urgency ~1.4
        banan.urgency shouldBeGreaterThan 1.0
        banan.urgency shouldBeLessThan 2.0
        banan.daysSinceLast shouldBe 10
    }

    test("recent 30d count") {
        val stats = Analysis.computeStats(history, 50, now)
        val banan = stats.first { it.normalizedName == "Banan" }

        // Purchases on May 22, May 29, Jun 5 are within 30d of Jun 15
        banan.freqRecent30d shouldBe 3
    }
})
