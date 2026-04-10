import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.Instant
import java.time.LocalDate

class NorwegianDateTest : FunSpec({

    test("parses standard format") {
        val result = parseNorwegianDate("fre 20. mars, 18:59")
        result shouldNotBe null
        val str = result!!.toString()
        str.contains("03-20T18:59:00Z") shouldBe true
    }

    test("parses different months") {
        val jan = parseNorwegianDate("man 5. jan, 10:00")!!
        jan.toString().contains("01-05T10:00:00Z") shouldBe true

        val aug = parseNorwegianDate("tir 15. aug, 08:30")!!
        aug.toString().contains("08-15T08:30:00Z") shouldBe true
    }

    test("result is never in the future") {
        val result = parseNorwegianDate("fre 25. des, 12:00")
        result shouldNotBe null
        result!!.isBefore(Instant.now()) shouldBe true
    }

    test("past dates resolve to current year") {
        // January is in the past relative to April 2026
        val result = parseNorwegianDate("man 5. jan, 10:00")!!
        val year = LocalDate.now().year
        result.toString().startsWith("$year-") shouldBe true
    }

    test("returns null for unparseable input") {
        parseNorwegianDate("invalid date") shouldBe null
        parseNorwegianDate("2025-03-20") shouldBe null
        parseNorwegianDate("") shouldBe null
    }

    test("returns null for unknown month") {
        parseNorwegianDate("fre 20. marchh, 18:59") shouldBe null
    }
})
