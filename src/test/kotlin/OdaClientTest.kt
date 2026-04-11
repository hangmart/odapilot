import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json
import java.time.LocalDate

class OdaClientTest : FunSpec({

    context("parseNorwegianDate") {
        val ref = LocalDate.of(2025, 6, 15) // fixed reference date

        test("parses standard format") {
            val result = parseNorwegianDate("fre 20. mars, 18:59", ref)
            result shouldNotBe null
            result.toString() shouldBe "2025-03-20T18:59:00Z"
        }

        test("parses different months") {
            parseNorwegianDate("man 5. jan, 10:00", ref).toString() shouldBe "2025-01-05T10:00:00Z"
            parseNorwegianDate("tir 15. mai, 08:30", ref).toString() shouldBe "2025-05-15T08:30:00Z"
            parseNorwegianDate("ons 1. jun, 20:00", ref).toString() shouldBe "2025-06-01T20:00:00Z"
        }

        test("date after reference resolves to previous year") {
            parseNorwegianDate("tir 15. aug, 08:30", ref).toString() shouldBe "2024-08-15T08:30:00Z"
            parseNorwegianDate("fre 25. des, 12:00", ref).toString() shouldBe "2024-12-25T12:00:00Z"
        }

        test("works with through-date from pagination") {
            val oldPage = LocalDate.of(2023, 10, 31)
            parseNorwegianDate("fre 20. mars, 18:59", oldPage).toString() shouldBe "2023-03-20T18:59:00Z"
            parseNorwegianDate("ons 15. nov, 10:00", oldPage).toString() shouldBe "2022-11-15T10:00:00Z"
        }

        test("returns null for unparseable input") {
            parseNorwegianDate("invalid date", ref) shouldBe null
            parseNorwegianDate("2025-03-20", ref) shouldBe null
            parseNorwegianDate("", ref) shouldBe null
        }

        test("returns null for unknown month") {
            parseNorwegianDate("fre 20. marchh, 18:59", ref) shouldBe null
        }
    }

    context("odaJson deserialization") {
        test("ignores unknown fields in order response") {
            val json = """{"hasMore":true,"getMoreUrl":"/api/v1/orders/?through-date=2025-01-01","results":[],"someNewField":"ignored"}"""
            val response = odaJson.decodeFromString<OdaOrdersResponse>(json)
            response.hasMore shouldBe true
            response.getMoreUrl shouldBe "/api/v1/orders/?through-date=2025-01-01"
            response.results shouldBe emptyList()
        }

        test("parses order with delivery time") {
            val json = """{"orderNumber":"12345","delivery":{"deliveryTime":"fre 20. mars, 18:59"}}"""
            val order = odaJson.decodeFromString<OdaOrder>(json)
            order.orderNumber shouldBe "12345"
            order.delivery?.deliveryTime shouldBe "fre 20. mars, 18:59"
        }

        test("parses order without delivery") {
            val json = """{"orderNumber":"12345"}"""
            val order = odaJson.decodeFromString<OdaOrder>(json)
            order.orderNumber shouldBe "12345"
            order.delivery shouldBe null
        }

        test("parses order detail with item groups") {
            val json = """{
                "items": {
                    "itemGroups": [{
                        "type": "category",
                        "name": "Frukt og grønt",
                        "items": [{"productId": 42, "description": "Bananer", "quantity": 2.0}]
                    }]
                }
            }"""
            val detail = odaJson.decodeFromString<OdaOrderDetail>(json)
            val group = detail.items!!.itemGroups.first()
            group.type shouldBe "category"
            group.name shouldBe "Frukt og grønt"
            group.items.first().productId shouldBe 42
            group.items.first().description shouldBe "Bananer"
            group.items.first().quantity shouldBe 2.0
        }

        test("handles empty order detail") {
            val json = """{}"""
            val detail = odaJson.decodeFromString<OdaOrderDetail>(json)
            detail.items shouldBe null
        }
    }
})
