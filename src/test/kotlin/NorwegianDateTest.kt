import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.LocalDate

class NorwegianDateTest : FunSpec({
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
        // August is after June reference, so should resolve to 2024
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
})
