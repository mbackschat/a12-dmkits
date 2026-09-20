package io.github.mbackschat.a12.dm.bridge

import io.github.mbackschat.a12.dm.core.ModelIndex
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.interpreter.model.ConditionLanguage
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.ComparisonCondition
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The date-range equality peer of [TemporalFormatGateTest]. Two range operands must expose the same declared
 * date-component SET, and the rule is CONTEXTUAL — the same operand pair is admissible or not depending on the
 * model — so it lives on `BoundModel.rangesEqual`/`rangesNotEqual` rather than on `DateRangeOperand.eq`.
 *
 * The bridge reads condition TEXT and must reach the checked form: `eq`/`ne` stay ungated for `:adapter`'s
 * converter, which rebuilds a kernel-ACCEPTED model and must never refuse. This bridge's contract is the
 * opposite one — "the mapping is the validation", with a rejection among its legitimate outcomes — so a
 * rulekit refusal surfacing here is the design (`dsl.ReadPathFormatGateIsolationTest`).
 *
 * Scope: this is the STORED-versus-STORED and stored-versus-agreeing-construction law
 * (`MVK_INVALID_COMPARE_TO_DATE_RANGE`). A construction whose own endpoints disagree is a different law with a
 * different code, measured in `validate.laws.DateRangeConstructionEndpointComponentLawsTest`.
 */
class DateRangeComparabilityGateTest {

    private val json = DocumentModelDraft.named("bridge-range-gate").locale("en_US")
        .rootGroup("Cover") { c ->
            c.field("Ref", FieldKind.STRING)
                .dateFragmentField("FromYear") { it.fragmentFormat("yyyy") }
                .dateFragmentField("ToYear") { it.fragmentFormat("yyyy") }
                .dateField("FromDate") { it.format("yyyy-MM-dd") }
                .dateField("ToDate") { it.format("yyyy-MM-dd") }
                .dateRangeField("YearWindow") { it.format("yyyy").separator("/") }
                .dateRangeField("DateWindow") { it.format("yyyy-MM-dd").separator("/") }
        }
        .build().toJson()

    private val bridge = ConditionBridge(
        BoundModel.ofJson(json),
        ModelIndex.of(json, ReferenceResolvers.selfContained()),
    )

    private fun translate(condition: String) =
        bridge.translate(condition, "/Cover", ConditionLanguage.EN)

    @Test
    fun `two stored ranges of the same component set convert`() {
        assertInstanceOf(ComparisonCondition::class.java, translate("DateWindow == DateWindow"))
        assertInstanceOf(ComparisonCondition::class.java, translate("YearWindow != YearWindow"))
    }

    /** A construction whose endpoints agree with the stored range's components is admitted. */
    @Test
    fun `a matching construction converts`() {
        assertInstanceOf(
            ComparisonCondition::class.java,
            translate("YearWindow == DateRange(FromYear, ToYear)"),
        )
        assertInstanceOf(
            ComparisonCondition::class.java,
            translate("DateWindow != DateRange(FromDate, ToDate)"),
        )
    }

    @Test
    fun `two stored ranges of different component sets are refused`() {
        val refusal = assertThrows<IllegalArgumentException> { translate("YearWindow == DateWindow") }
        assertTrue(refusal.message!!.contains("MVK_INVALID_COMPARE_TO_DATE_RANGE"), refusal.message)
    }

    /** The gate reads both operands, so swapping the authored sides does not admit the same mismatch. */
    @Test
    fun `the refusal does not depend on operand order`() {
        for (condition in listOf("DateWindow != YearWindow", "YearWindow != DateWindow")) {
            val refusal = assertThrows<IllegalArgumentException>(condition) { translate(condition) }
            assertTrue(refusal.message!!.contains("MVK_INVALID_COMPARE_TO_DATE_RANGE"), refusal.message)
        }
    }

    /** A construction whose components differ from the stored range's is refused on either side. */
    @Test
    fun `a mismatched construction is refused`() {
        for (condition in listOf(
            "DateWindow == DateRange(FromYear, ToYear)",
            "DateRange(FromYear, ToYear) == DateWindow",
        )) {
            val refusal = assertThrows<IllegalArgumentException>(condition) { translate(condition) }
            assertTrue(refusal.message!!.contains("MVK_INVALID_COMPARE_TO_DATE_RANGE"), refusal.message)
        }
    }

    /**
     * The OTHER law, and the one the comparison gate cannot reach: a construction whose two endpoints disagree
     * with each other. The kernel refuses it at the construction under a different code, and
     * `BoundModel.rangeComponents` yields no profile for such an operand — so the comparison gate delegates it
     * and only the construction gate catches it.
     */
    @Test
    fun `a construction whose own endpoints disagree is refused under the construction code`() {
        val refusal = assertThrows<IllegalArgumentException> {
            translate("DateWindow == DateRange(FromYear, ToDate)")
        }
        assertTrue(refusal.message!!.contains("MVK_WRONG_DATE_FORMAT_FOR_OP"), refusal.message)
    }

    /** The same construction is refused irrespective of what it is compared against. */
    @Test
    fun `the endpoint refusal does not depend on the stored operand`() {
        val refusal = assertThrows<IllegalArgumentException> {
            translate("YearWindow != DateRange(FromYear, ToDate)")
        }
        assertTrue(refusal.message!!.contains("MVK_WRONG_DATE_FORMAT_FOR_OP"), refusal.message)
    }
}
