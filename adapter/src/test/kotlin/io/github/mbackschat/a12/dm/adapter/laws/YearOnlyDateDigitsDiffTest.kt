package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A `yyyy` date stored in another script's digits is a formal error to A12, so the year-only reading in a `Date(…)`
 * constructor only ever meets ASCII digits (KERNEL-FINDINGS KF593). The ASCII year draws the rule, which shows the
 * constructor read the stored year; the Arabic-Indic and fullwidth years draw the date-format error instead.
 * Own domain (a vehicle cover and its year of manufacture).
 */
class YearOnlyDateDigitsDiffTest {

    private val model = RuntimeLaws.withRule(
        DocumentModelDraft.named("cover").locale("en_US").rootGroup("Cover") { g ->
            g.dateField("Built") { it.format("yyyy") }.numberField("Value") { it.fractionDigits(0) }
        }.build().toJson(),
        "/Cover", "Vintage", "../Value", "VINTAGE",
        "FieldFilled(Value) And Date(\"01\", \"01\", Built) == Date(\"01\", \"01\", \"1963\")",
    )

    private val years = linkedMapOf("1963" to "1963", "\\u0661\\u0669\\u0666\\u0663" to "١٩٦٣",
        "\\uFF11\\uFF19\\uFF16\\uFF13" to "１９６３")

    private fun document(year: String) = """{"Cover":{"Built":"$year","Value":1}}"""

    @Test
    fun onlyAnAsciiYearReachesTheConstructor() {
        DocumentDifferential.assertAgree(model, years.mapValues { document(it.value) })
        val codes = years.mapValues { (_, year) -> DocumentDifferential.kernel(model, document(year), dynamic = true).messages.map { it.code } }
        assertEquals(mapOf("1963" to listOf("VINTAGE")), codes.filterValues { "VINTAGE" in it }, "only the ASCII year draws the rule")
        assertEquals(years.keys - "1963", codes.filterValues { it.isNotEmpty() && "VINTAGE" !in it }.keys, "the others are formal errors")
    }
}
