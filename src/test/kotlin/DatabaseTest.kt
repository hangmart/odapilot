import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.sql.Connection
import java.time.Instant

class DatabaseTest : FunSpec({
    lateinit var db: Database
    lateinit var conn: Connection

    beforeTest {
        db = Database(":memory:")
        conn = db.connect()
        db.initialize()
    }

    afterTest {
        db.close()
    }

    test("initialize creates all tables") {
        val rs = conn.metaData.getTables(null, null, "%", arrayOf("TABLE"))
        val tables = mutableListOf<String>()
        while (rs.next()) tables.add(rs.getString("TABLE_NAME"))

        tables.sorted() shouldBe listOf(
            "meal_ingredients", "meal_plan_entries", "meal_plans",
            "meals", "order_items", "orders", "plan_feedback", "products"
        )
    }

    test("insertOrder and orderExists") {
        val now = Instant.parse("2025-03-20T18:59:00Z")

        db.orderExists("ORD-001") shouldBe false

        val id = db.insertOrder("ORD-001", now)
        id shouldBeGreaterThan 0

        db.orderExists("ORD-001") shouldBe true
    }

    test("insertOrder is idempotent") {
        val now = Instant.parse("2025-03-20T18:59:00Z")

        val id1 = db.insertOrder("ORD-001", now)
        val id2 = db.insertOrder("ORD-001", now)

        id1 shouldBe id2
    }

    test("upsertProduct inserts new product") {
        val id = db.upsertProduct(1001, "Bananer", "Frukt og grønt", null)
        id shouldBeGreaterThan 0
    }

    test("upsertProduct updates name and category") {
        db.upsertProduct(1001, "Bananer", "Frukt", null)
        db.upsertProduct(1001, "Bananer Økologisk", "Frukt og grønt", null)

        val rs = conn.prepareStatement("SELECT name, category FROM products WHERE oda_product_id = 1001")
            .executeQuery()
        rs.next() shouldBe true
        rs.getString("name") shouldBe "Bananer Økologisk"
        rs.getString("category") shouldBe "Frukt og grønt"
    }

    test("upsertProduct preserves existing normalized_name") {
        db.upsertProduct(1001, "Bananer", "Frukt", "Banan")
        db.upsertProduct(1001, "Bananer", "Frukt", null)

        val rs = conn.prepareStatement("SELECT normalized_name FROM products WHERE oda_product_id = 1001")
            .executeQuery()
        rs.next() shouldBe true
        rs.getString("normalized_name") shouldBe "Banan"
    }

    test("insertOrderItem links order and product") {
        val now = Instant.parse("2025-03-20T18:59:00Z")
        val orderId = db.insertOrder("ORD-001", now)
        val productId = db.upsertProduct(1001, "Bananer", "Frukt", null)

        db.insertOrderItem(orderId, productId, 3)

        val rs = conn.prepareStatement("SELECT quantity FROM order_items WHERE order_id = ? AND product_id = ?")
            .apply { setInt(1, orderId); setInt(2, productId) }
            .executeQuery()
        rs.next() shouldBe true
        rs.getInt("quantity") shouldBe 3
    }

    test("orderHistoryByProduct groups by normalized name") {
        val t1 = Instant.parse("2025-01-10T10:00:00Z")
        val t2 = Instant.parse("2025-02-15T10:00:00Z")

        val o1 = db.insertOrder("ORD-001", t1)
        val o2 = db.insertOrder("ORD-002", t2)
        val p1 = db.upsertProduct(1001, "Bananer i klase", "Frukt", "Banan")
        val p2 = db.upsertProduct(1002, "Økologiske bananer", "Frukt", "Banan")
        val p3 = db.upsertProduct(2001, "Melk", "Meieri", "Melk")

        db.insertOrderItem(o1, p1, 2)
        db.insertOrderItem(o2, p2, 1)
        db.insertOrderItem(o1, p3, 3)

        val history = db.orderHistoryByProduct()
        history.keys shouldBe setOf("Banan", "Melk")

        history["Banan"]!!.size shouldBe 2
        history["Banan"]!![0] shouldBe Purchase(t1, 2)
        history["Banan"]!![1] shouldBe Purchase(t2, 1)

        history["Melk"]!!.size shouldBe 1
        history["Melk"]!![0] shouldBe Purchase(t1, 3)
    }

    test("orderHistoryByProduct excludes unclassified products") {
        val t1 = Instant.parse("2025-01-10T10:00:00Z")
        val o1 = db.insertOrder("ORD-001", t1)
        val p1 = db.upsertProduct(1001, "Bananer", "Frukt", null)
        db.insertOrderItem(o1, p1, 1)

        db.orderHistoryByProduct() shouldBe emptyMap()
    }

    test("totalOrderCount") {
        db.totalOrderCount() shouldBe 0

        db.insertOrder("ORD-001", Instant.parse("2025-01-10T10:00:00Z"))
        db.insertOrder("ORD-002", Instant.parse("2025-02-15T10:00:00Z"))

        db.totalOrderCount() shouldBe 2
    }

    test("insertOrderItem is idempotent") {
        val now = Instant.parse("2025-03-20T18:59:00Z")
        val orderId = db.insertOrder("ORD-001", now)
        val productId = db.upsertProduct(1001, "Bananer", "Frukt", null)

        db.insertOrderItem(orderId, productId, 3)
        db.insertOrderItem(orderId, productId, 5) // should be ignored

        val rs = conn.prepareStatement("SELECT quantity FROM order_items WHERE order_id = ? AND product_id = ?")
            .apply { setInt(1, orderId); setInt(2, productId) }
            .executeQuery()
        rs.next() shouldBe true
        rs.getInt("quantity") shouldBe 3
    }
})
