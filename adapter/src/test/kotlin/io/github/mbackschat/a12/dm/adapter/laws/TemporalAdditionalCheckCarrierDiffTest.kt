package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.FORMAL_RULE_PATH
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tri-engine lock for the opt-in pre-1900 check on its two OTHER carriers — `DateFragmentType` and
 * `DateRangeType` both declare `youngerThan1900Check`, and [DateBefore1900DiffTest] covered only `DateType`
 * ([KERNEL-FINDINGS](../../../../../../../../../../../docs/KERNEL-FINDINGS.md) KF253).
 *
 * <p>Every firing row is twinned with a **plain** sibling in the same model, identical but for the
 * `rejectBefore1900()` builder call, so a reported class belongs to the check and not to the value. The
 * range's year-free rows additionally vary the model's declared base year and nothing else, which is what
 * separates "the check reads a supplemented year" from "the check reads the declared text".
 */
class TemporalAdditionalCheckCarrierDiffTest {

    @Test
    fun aFragmentDeclarationCarriesTheCheckAndItsPlainTwinDoesNot() {
        val model = fragmentModel()
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model))

        agree(model, "/Cal/FragCheck", "1899-12", "datumFalsch")
        agree(model, "/Cal/FragPlain", "1899-12", null)
        agree(model, "/Cal/FragCheck", "1900-01", null)
        agree(model, "/Cal/FragPlain", "1900-01", null)
    }

    /**
     * The always-on Gregorian floor is the separator: it fires on BOTH siblings, so a row that fired on both
     * would not have shown the opt-in check doing anything.
     */
    @Test
    fun theAlwaysOnFloorStillFiresOnAFragmentWithoutTheCheck() {
        val model = fragmentModel()
        agree(model, "/Cal/FragPlain", "1500-06", "datumFalsch")
        agree(model, "/Cal/FragCheck", "1500-06", "datumFalsch")
    }

    @Test
    fun aRangeDeclarationCarriesTheCheckOnItsStartDate() {
        val model = rangeModel(baseYear = 2024)
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model))

        agree(model, "/Cal/RangeCheck", "31.12.1899-31.12.1899", "datumBereichDatumFalsch")
        agree(model, "/Cal/RangePlain", "31.12.1899-31.12.1899", null)
        agree(model, "/Cal/RangeCheck", "01.01.1900-01.01.1900", null)
        // The END may sit well past 1900 and the pre-1900 START still decides — the kernel checks the start and
        // relies on end >= start for the other endpoint.
        agree(model, "/Cal/RangeCheck", "31.12.1899-01.01.1901", "datumBereichDatumFalsch")
        agree(model, "/Cal/RangePlain", "31.12.1899-01.01.1901", null)
    }

    /**
     * A year-free range format is admitted WITH the check — unlike DATE and DATEFRAGMENT, which the model check
     * refuses (`MVK_ADDITIONAL_CHECK_INVALID`; `TemporalAdditionalCheckCarrierLawsTest` owns that grid) — and
     * the check then reads the base-year completion. Two models differing in exactly the `baseYear` builder
     * call, with the plain twin silent in all four cells.
     */
    @Test
    fun aYearFreeRangeIsMeasuredAgainstTheDeclaredBaseYear() {
        val below = yearFreeRangeModel(baseYear = 1899)
        val above = yearFreeRangeModel(baseYear = 1901)
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(below))
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(above))

        agree(below, "/Cal/RangeCheck", "01.06-01.09", "datumBereichDatumFalsch")
        agree(below, "/Cal/RangePlain", "01.06-01.09", null)
        agree(above, "/Cal/RangeCheck", "01.06-01.09", null)
        agree(above, "/Cal/RangePlain", "01.06-01.09", null)
    }

    /**
     * The one year-free pair that reaches NEITHER date-position check, on any base year: a pure `MM` range. The
     * reversed value is the liveness control — two dates are produced and ordered, so this is not "no date
     * exists" — and a base year below the Gregorian floor shows the always-on floor is skipped as well. The
     * mechanism is unmeasured and deliberately not asserted; only the outcome is.
     */
    @Test
    fun aPureMonthRangeReachesNeitherTheFloorNorTheOptInCheck() {
        for (baseYear in listOf(1500, 1899, 1901)) {
            val model = monthRangeModel(baseYear)
            assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model))
            agree(model, "/Cal/RangeCheck", "06/09", null)
            agree(model, "/Cal/RangePlain", "06/09", null)
            agree(model, "/Cal/RangeCheck", "09/06", "datumBereichNichtGueltig")
        }
    }

    private fun fragmentModel() = DocumentModelDraft.named("fragment-additional-check").locale("en_US")
        .rootGroup("Cal") { g ->
            g.dateFragmentField("FragPlain") { it.fragmentFormat("yyyy-MM") }
                .dateFragmentField("FragCheck") { it.fragmentFormat("yyyy-MM").rejectBefore1900() }
        }
        .build().toJson()

    private fun rangeModel(baseYear: Int) = DocumentModelDraft.named("range-additional-check").locale("en_US")
        .baseYear(baseYear)
        .rootGroup("Cal") { g ->
            g.dateRangeField("RangePlain") { it.format("dd.MM.yyyy").separator("-") }
                .dateRangeField("RangeCheck") { it.format("dd.MM.yyyy").separator("-").rejectBefore1900() }
        }
        .build().toJson()

    private fun yearFreeRangeModel(baseYear: Int) =
        DocumentModelDraft.named("year-free-range-$baseYear").locale("en_US")
            .baseYear(baseYear)
            .rootGroup("Cal") { g ->
                g.dateRangeField("RangePlain") { it.format("dd.MM").separator("-") }
                    .dateRangeField("RangeCheck") { it.format("dd.MM").separator("-").rejectBefore1900() }
            }
            .build().toJson()

    private fun monthRangeModel(baseYear: Int) =
        DocumentModelDraft.named("month-range-$baseYear").locale("en_US")
            .baseYear(baseYear)
            .rootGroup("Cal") { g ->
                g.dateRangeField("RangePlain") { it.format("MM").separator("/") }
                    .dateRangeField("RangeCheck") { it.format("MM").separator("/").rejectBefore1900() }
            }
            .build().toJson()

    /** Groovy-dynamic (normative) == generated Java == interpreter, on the one field the row names. */
    private fun agree(model: String, path: String, value: String, expected: String?) {
        val definition = ModelLoader.load(model)
        val javaEngine = RuntimeLaws.kernelJavaEngine(model)
        val dynamic = RuntimeLaws.messages(model, doc(path, value)).map { it.code() }.sorted()
        val generated = javaEngine.messages(javaEngine.instance(doc(path, value))).map { it.code() }.sorted()
        val interpreted = DmInterpreter(definition)
            .validate(definition.newDocument().group(ROOT, 1).field(path, value, 1, 1))
            .messages.filter { it.rulePath == FORMAL_RULE_PATH }.map { it.code }.sorted()

        assertEquals(listOfNotNull(expected), dynamic, "$path = '$value' — dynamic Groovy is normative")
        assertEquals(dynamic, generated, "$path = '$value' — dynamic Groovy vs generated Java")
        assertEquals(dynamic, interpreted, "$path = '$value' — dynamic Groovy vs interpreter")
    }

    private fun doc(path: String, value: String) =
        RuntimeLaws.doc().group(ROOT, 1).field(path, value, 1, 1)

    private companion object {
        const val ROOT = "/Cal"
    }
}
