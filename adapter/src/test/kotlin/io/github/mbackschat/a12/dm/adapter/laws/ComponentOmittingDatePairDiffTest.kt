package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Two component-omitting Dates of DIFFERENT component sets compared directly (KERNEL-FINDINGS KF93, a12-kernel-lean
 * SPEC-2026-10-30-03): each operand completes on its own to its earliest date, a missing month or day to `01` and a
 * missing year from the Base Year, and the completed dates compare. The rival reading, comparing only the components
 * both operands carry, predicts equality for `2023-06` against `06-11`, `06` against `06-11` and `2023` against
 * `2023-06`; A12 orders every one of them.
 *
 * The `firsts` document is the liveness control, every pair completing to one date, so a silent equality elsewhere
 * cannot be a rule that never runs. The `year ahead` document separates the Base Year from the sibling's year: `06-11`
 * stays in 2023 beside a 2024 operand. Plain `DateType` fields under Base Year 2023, every document on dynamic Groovy,
 * static Java and the interpreter, the fired codes pinned from dynamic Groovy. Own domain (an insurance cover window).
 */
class ComponentOmittingDatePairDiffTest {

    private val model: String = run {
        val session = EditSession.read(DocumentModelDraft.named("cover-window").locale("en_US").baseYear(2023)
            .rootGroup("Cover") { c ->
                c.stringField("Holder") { }
                FORMATS.forEach { (field, format) -> c.dateField(field) { it.format(format) } }
                c
            }.build().toJson())
        val cover = session.group("/Cover")
        fun rule(code: String, condition: Condition) = cover.addRule {
            it.name(code).code(code).errorField(cover.str("Holder"))
                .`when`(cover.str("Holder").filled().and(condition)).message(Dsl.en("Check the cover window.")).build()
        }
        val (ym, md, m, y, iso) = FORMATS.keys.map(cover::date)
        rule("YM_EQ_MD", ym.eq(md)); rule("YM_LT_MD", ym.before(md)); rule("YM_GT_MD", ym.after(md))
        rule("M_EQ_MD", m.eq(md)); rule("M_LT_MD", m.before(md))
        rule("Y_EQ_YM", y.eq(ym)); rule("Y_LT_YM", y.before(ym))
        rule("Y_EQ_MD", y.eq(md)); rule("Y_LT_MD", y.before(md))
        rule("YM_EQ_ISO", ym.eq(iso))
        session.toJson()
    }

    private fun cover(vararg values: Pair<String, String>) =
        """{"Cover":{"Holder":"Northwind Mutual",${values.joinToString(",") { (f, v) -> "\"$f\":\"$v\"" }}}}"""

    private val documents = linkedMapOf(
        "june" to cover("YM" to "2023-06", "MD" to "06-11", "M" to "06", "Y" to "2023"),
        "firsts" to cover("YM" to "2023-01", "MD" to "01-01", "M" to "01", "Y" to "2023", "Iso" to "2023-01-01"),
        "year ahead" to cover("YM" to "2024-06", "MD" to "06-11", "Y" to "2024"),
        "month after" to cover("MD" to "06-11", "M" to "07"),
        "months apart" to cover("MD" to "06-11", "M" to "05"),
    )

    /** Every pair differs in its component set and is admitted under the Base Year, by A12 and the portable checker. */
    @Test
    fun a12AndThePortableCheckerAdmitEveryPair() {
        assertEquals(emptyList<String>(), MessageParameterProbes.kernel(model))
        assertEquals(emptyList<String>(), rejectingCodes(model))
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() = DocumentDifferential.assertAgree(model, documents)

    /** Every operand completes on its own; the pairs sharing a component are ordered, not equal. */
    @Test
    fun eachOperandCompletesToItsOwnEarliestDate() {
        assertEquals(setOf("YM_LT_MD", "M_LT_MD", "Y_LT_YM", "Y_LT_MD"), fired("june"))
        assertEquals(setOf("YM_EQ_MD", "M_EQ_MD", "Y_EQ_YM", "Y_EQ_MD", "YM_EQ_ISO"), fired("firsts"))
    }

    /** The yearless operand takes the Base Year, not its partner's year, whichever side of it the partner lies. */
    @Test
    fun theMissingYearIsTheBaseYearsNotThePartners() {
        assertEquals(setOf("YM_GT_MD", "Y_LT_YM"), fired("year ahead"))
        assertEquals(emptySet<String>(), fired("month after"))
        assertEquals(setOf("M_LT_MD"), fired("months apart"))
    }

    private fun fired(document: String): Set<String> =
        DocumentDifferential.kernel(model, documents.getValue(document), dynamic = true).messages.map { it.code }.toSet()

    private companion object {
        val FORMATS = linkedMapOf("YM" to "yyyy-MM", "MD" to "MM-dd", "M" to "MM", "Y" to "yyyy", "Iso" to "yyyy-MM-dd")
    }
}
