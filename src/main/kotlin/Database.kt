import io.github.oshai.kotlinlogging.KotlinLogging
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

private val logger = KotlinLogging.logger {}

class Database(private val path: String = "odapilot.db") {
    private lateinit var conn: Connection

    fun connect(): Connection {
        logger.info { "Connecting to SQLite database: $path" }
        conn = DriverManager.getConnection("jdbc:sqlite:$path")
        conn.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
        conn.createStatement().use { it.execute("PRAGMA foreign_keys=ON") }
        return conn
    }

    fun initialize() {
        logger.info { "Initializing database schema" }
        conn.autoCommit = false
        try {
            val stmt = conn.createStatement()

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS products (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    oda_product_id INTEGER UNIQUE,
                    name TEXT NOT NULL,
                    category TEXT,
                    product_type TEXT
                )
            """)

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS meals (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL
                )
            """)

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS meal_ingredients (
                    meal_id INTEGER REFERENCES meals(id),
                    product_id INTEGER REFERENCES products(id),
                    quantity REAL,
                    unit TEXT,
                    PRIMARY KEY (meal_id, product_id)
                )
            """)

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS orders (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    oda_order_id TEXT UNIQUE NOT NULL,
                    ordered_at TEXT NOT NULL
                )
            """)

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS order_items (
                    order_id INTEGER REFERENCES orders(id),
                    product_id INTEGER REFERENCES products(id),
                    quantity INTEGER NOT NULL,
                    PRIMARY KEY (order_id, product_id)
                )
            """)

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS meal_plans (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    planned_at TEXT NOT NULL,
                    week_start TEXT NOT NULL
                )
            """)

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS meal_plan_entries (
                    meal_plan_id INTEGER REFERENCES meal_plans(id),
                    meal_id INTEGER REFERENCES meals(id),
                    day TEXT,
                    is_new_suggestion INTEGER DEFAULT 0
                )
            """)

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS plan_feedback (
                    meal_plan_id INTEGER REFERENCES meal_plans(id),
                    product_id INTEGER REFERENCES products(id),
                    suggested_quantity INTEGER,
                    ordered_quantity INTEGER
                )
            """)

            conn.commit()
            logger.info { "Database schema initialized" }
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    fun orderExists(odaOrderId: String): Boolean {
        val ps = conn.prepareStatement("SELECT 1 FROM orders WHERE oda_order_id = ?")
        ps.setString(1, odaOrderId)
        return ps.executeQuery().next()
    }

    fun insertOrder(odaOrderId: String, orderedAt: java.time.Instant): Int {
        val ps = conn.prepareStatement("INSERT OR IGNORE INTO orders (oda_order_id, ordered_at) VALUES (?, ?)")
        ps.setString(1, odaOrderId)
        ps.setString(2, orderedAt.toString())
        ps.executeUpdate()

        val lookup = conn.prepareStatement("SELECT id FROM orders WHERE oda_order_id = ?")
        lookup.setString(1, odaOrderId)
        val rs = lookup.executeQuery()
        return rs.getInt(1)
    }

    fun upsertProduct(odaProductId: Int, name: String, category: String?, productType: String?): Int {
        val ps = conn.prepareStatement(
            """
            INSERT INTO products (oda_product_id, name, category, product_type)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(oda_product_id) DO UPDATE SET
                name = excluded.name,
                category = excluded.category,
                product_type = COALESCE(products.product_type, excluded.product_type)
            """
        )
        ps.setInt(1, odaProductId)
        ps.setString(2, name)
        ps.setString(3, category)
        ps.setString(4, productType)
        ps.executeUpdate()

        val lookup = conn.prepareStatement("SELECT id FROM products WHERE oda_product_id = ?")
        lookup.setInt(1, odaProductId)
        val rs = lookup.executeQuery()
        return rs.getInt(1)
    }

    fun insertOrderItem(orderId: Int, productId: Int, quantity: Int) {
        val ps = conn.prepareStatement(
            "INSERT OR IGNORE INTO order_items (order_id, product_id, quantity) VALUES (?, ?, ?)"
        )
        ps.setInt(1, orderId)
        ps.setInt(2, productId)
        ps.setInt(3, quantity)
        ps.executeUpdate()
    }

    fun close() {
        if (::conn.isInitialized && !conn.isClosed) {
            conn.close()
            logger.info { "Database connection closed" }
        }
    }
}
