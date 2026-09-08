package io.github.mbackschat.a12.dm.bridge

import io.github.mbackschat.a12.dm.core.ModelIndex
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.interpreter.model.ConditionLanguage
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.ComparisonCondition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * `BaseYear` is legal only in a model that DECLARES a base year, and the kernel refuses every carrier of it
 * otherwise (`MVK_NO_BASE_YEAR`, measured over all six in
 * `validate.laws.BaseYearDeclarationLawsTest`). A kernel-free client used to get silence for the whole class.
 *
 * The rule is exactly decidable — a model either declares one or it does not — which is what separates it from
 * a FORMAT gate over the same constant. That one is measured to be an invention: the kernel admits month, day
 * and quarter extraction over `BaseYear` while refusing all three over a `yyyy` field
 * (`validate.laws.BaseYearExtractionFormatLawsTest`), so gating on a format would refuse legal models.
 */
class BaseYearDeclarationGateTest {

    private fun json(baseYear: Int?): String {
        val builder = DocumentModelDraft.named(
            if (baseYear == null) "bridge-no-base-year" else "bridge-base-year",
        ).locale("en_US")
        if (baseYear != null) {
            builder.baseYear(baseYear)
        }
        return builder
            .rootGroup("Cover") { c ->
                c.field("Ref", FieldKind.STRING)
                    .numberField("N") { it.fractionDigits(0) }
                    .dateField("FiledOn") { it.format("yyyy-MM-dd") }
                    .dateTimeField("Stamp") { it.format("yyyy-MM-dd'T'HH:mm:ss") }
                    .dateRangeField("Window") { it.format("yyyy-MM-dd").separator("/") }
            }
            .build().toJson()
    }

    private fun bridge(modelJson: String) = ConditionBridge(
        BoundModel.ofJson(modelJson),
        ModelIndex.of(modelJson, ReferenceResolvers.selfContained()),
    )

    private val unconfigured = json(null)
    private val configured = json(2024)

    private fun translate(modelJson: String, condition: String) =
        bridge(modelJson).translate(condition, "/Cover", ConditionLanguage.EN)

    /** Every carrier the condition-text surface can reach. */
    private val carriers = listOf(
        "FiledOn < BaseYear",
        "YearFromDate(FiledOn) < BaseYear",
        "YearFromDate(BaseYear) > 2000",
        "FiledOn < StartOfDateRange(BaseYear)",
        "FiledOn < EndOfDateRange(BaseYear)",
        // The DATE-TIME role, which :adapter's converter also reconstructs — the kernel-side table covers it,
        // so the consumer-side list must too or its "every carrier" label is short by one.
        "Stamp < BaseYear",
    )

    @Test
    fun `every carrier is refused when the model declares no base year`() {
        for (condition in carriers) {
            val refusal = assertThrows<IllegalArgumentException>(condition) {
                translate(unconfigured, condition)
            }
            assertTrue(refusal.message!!.contains("MVK_NO_BASE_YEAR"), refusal.message)
        }
    }

    /** THE LIVENESS CONTROL: the same five convert once the model declares one. */
    @Test
    fun `every carrier converts when the model declares a base year`() {
        for (condition in carriers) {
            assertInstanceOf(ComparisonCondition::class.java, translate(configured, condition), condition)
        }
    }

    /**
     * What the refusal costs a CONSUMER — the question a throw does not answer. `readAll` catches per rule, so
     * a base-year-illegal condition becomes `Opaque` carrying the reason; the rest of the model still reads and
     * nothing is lost or unreadable.
     *
     * The fixture is assembled rather than corrupted textually: the rule is built against the CONFIGURED model,
     * where the gate admits it, and then added to the UNCONFIGURED one. The gate fires when the condition is
     * built, not when a rule is stored, so this produces exactly the shape a stored model can have and no
     * string surgery can get wrong.
     */
    @Test
    fun `a stored base-year-illegal condition reads back as Opaque, not as a failure`() {
        val declared = BoundModel.ofJson(configured)
        val rule = declared.rule(declared.group("/Cover")).name("BaseYearRule")
            .errorField(declared.field("/Cover/Ref"))
            .`when`(declared.baseYear().after(declared.date("/Cover/FiledOn")))
            .code("BY").severity(Severity.ERROR).message(Dsl.en("x")).build()

        val storedWithoutDeclaration = BoundModel.ofJson(unconfigured).add(rule).toJson()

        val read = InterpreterConditionReadBack()
            .readAll(storedWithoutDeclaration, ReferenceResolvers.selfContained())
        val opaque = assertInstanceOf(ReadResult.Opaque::class.java, read["/Cover/BaseYearRule"])
        assertTrue(opaque.reason().contains("MVK_NO_BASE_YEAR"), opaque.reason())
    }
}
