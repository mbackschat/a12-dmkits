package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.FORMAL_RULE_PATH
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tri-engine lock for the pre-1900 check on its two OTHER carriers — `DateFragmentType` and `DateRangeType` both
 * declare `youngerThan1900Check`, and [DateBefore1900DiffTest] covers `DateType`
 * ([KERNEL-FINDINGS](../../../../../../../../../../../docs/KERNEL-FINDINGS.md) KF253).
 *
 * <p>**Since kernel 31.2 the check fires on no carrier** (A12K-4073, KF677). Through 31.1.1 the checked rows below
 * drew `datumFalsch` / `datumBereichDatumFalsch`, the year-free range read its base-year completion, and each was
 * twinned with a **plain** sibling identical but for the `rejectBefore1900()` builder call. The twins stay: they
 * now show the checked field behaves exactly like the plain one, which is the whole claim. The always-on Gregorian
 * floor still fires on both, the liveness control that the formal path is running at all.
 */
class TemporalAdditionalCheckCarrierDiffTest {

    @Test
    fun aFragmentDeclarationWithTheCheckBehavesAsItsPlainTwin() {
        val model = fragmentModel()
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model))

        agree(model, "/Cal/FragCheck", "1899-12", null)
        agree(model, "/Cal/FragPlain", "1899-12", null)
        agree(model, "/Cal/FragCheck", "1900-01", null)
        agree(model, "/Cal/FragPlain", "1900-01", null)
    }

    /** The always-on Gregorian floor fires on BOTH siblings: the liveness control for the silent rows above. */
    @Test
    fun theAlwaysOnFloorStillFiresOnAFragmentWithoutTheCheck() {
        val model = fragmentModel()
        agree(model, "/Cal/FragPlain", "1500-06", "datumFalsch")
        agree(model, "/Cal/FragCheck", "1500-06", "datumFalsch")
    }

    @Test
    fun aRangeDeclarationWithTheCheckBehavesAsItsPlainTwin() {
        val model = rangeModel(baseYear = 2024)
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model))

        agree(model, "/Cal/RangeCheck", "31.12.1899-31.12.1899", null)
        agree(model, "/Cal/RangePlain", "31.12.1899-31.12.1899", null)
        agree(model, "/Cal/RangeCheck", "01.01.1900-01.01.1900", null)
        agree(model, "/Cal/RangeCheck", "31.12.1899-01.01.1901", null)
        agree(model, "/Cal/RangePlain", "31.12.1899-01.01.1901", null)
    }

    /**
     * A year-free range with the check, completed from a base year below 1900: through 31.1.1 the check read that
     * completion and fired; now neither twin does. Two models differing in exactly the `baseYear` builder call.
     */
    @Test
    fun aYearFreeRangeIgnoresTheCheckOnEitherSideOfTheBaseYear() {
        val below = yearFreeRangeModel(baseYear = 1899)
        val above = yearFreeRangeModel(baseYear = 1901)
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(below))
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(above))

        agree(below, "/Cal/RangeCheck", "01.06-01.09", null)
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
    fun aPureMonthRangeReachesNeitherTheFloorNorTheCheck() {
        for (baseYear in listOf(1500, 1899, 1901)) {
            val model = monthRangeModel(baseYear)
            assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model))
            agree(model, "/Cal/RangeCheck", "06/09", null)
            agree(model, "/Cal/RangePlain", "06/09", null)
            agree(model, "/Cal/RangeCheck", "09/06", "datumBereichNichtGueltig")
        }
    }

    @Suppress("DEPRECATION") // the retired check is what this measures
    private fun fragmentModel() = DocumentModelDraft.named("fragment-additional-check").locale("en_US")
        .rootGroup("Cal") { g ->
            g.dateFragmentField("FragPlain") { it.fragmentFormat("yyyy-MM") }
                .dateFragmentField("FragCheck") { it.fragmentFormat("yyyy-MM").rejectBefore1900() }
        }
        .build().toJson()

    @Suppress("DEPRECATION")
    private fun rangeModel(baseYear: Int) = DocumentModelDraft.named("range-additional-check").locale("en_US")
        .baseYear(baseYear)
        .rootGroup("Cal") { g ->
            g.dateRangeField("RangePlain") { it.format("dd.MM.yyyy").separator("-") }
                .dateRangeField("RangeCheck") { it.format("dd.MM.yyyy").separator("-").rejectBefore1900() }
        }
        .build().toJson()

    @Suppress("DEPRECATION")
    private fun yearFreeRangeModel(baseYear: Int) =
        DocumentModelDraft.named("year-free-range-$baseYear").locale("en_US")
            .baseYear(baseYear)
            .rootGroup("Cal") { g ->
                g.dateRangeField("RangePlain") { it.format("dd.MM").separator("-") }
                    .dateRangeField("RangeCheck") { it.format("dd.MM").separator("-").rejectBefore1900() }
            }
            .build().toJson()

    @Suppress("DEPRECATION")
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
