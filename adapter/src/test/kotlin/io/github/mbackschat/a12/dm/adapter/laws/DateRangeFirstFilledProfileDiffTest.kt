package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — a starred `FirstFilledValue` over a repeatable DATE_RANGE column, computed into a DATE_RANGE
 * target of the SAME declaration profile, across all six profiles the kernel admits here (SPEC-2026-08-19-03):
 * the two exact ones and the four component subsets `yyyy`, `yyyy-MM`, `MM`, `MM-dd`.
 *
 * The point of the matrix is the *stored form*. A year-bearing profile round-trips its exact endpoints; a
 * yearless one keeps the ordered component labels it declared and gains no synthetic year, because the target
 * renders through its own declared format and never through the completed instant the evaluator carries
 * internally. The model declares no Base Year, so the two yearless rows are measured where there is nothing to
 * complete a year from. Crossed profiles are refused before evaluation and are locked as static rows in
 * `rulekit.validate.laws.DateRangeComparabilityLawsTest`.
 *
 * Own domain (seasonal insurance cover).
 */
class DateRangeFirstFilledProfileDiffTest {

    /** A declared DATE_RANGE profile: the kernel `format` plus the separator its declaration owns. */
    private enum class Profile(val format: String, val separator: String) {
        EXACT_ISO("yyyy-MM-dd", "/"),
        EXACT_EUROPEAN("dd.MM.yyyy", "-"),
        YEAR("yyyy", "/"),
        YEAR_MONTH("yyyy-MM", "/"),
        MONTH("MM", "/"),
        MONTH_DAY("MM-dd", "/"),
    }

    private fun modelJson(profile: Profile): String {
        val base = DocumentModelDraft.named("cover-${profile.name.lowercase()}").locale("en_US")
            .rootGroup("Cover") { cover ->
                cover.dateRangeField("FirstWindow") { it.format(profile.format).separator(profile.separator) }
                cover.group("Rows") { rows ->
                    rows.repeatable(20).indexField("Key")
                        .stringField("Key") { }
                        .dateRangeField("RowWindow") { it.format(profile.format).separator(profile.separator) }
                }
            }.build().toJson()
        val m = BoundModel.ofJson(base)
        val operation = DslRenderer(ConditionLanguage.EN, ROOT)
            .render(Dsl.firstFilledDateRange(m.flatten("$ROOT/Rows*/RowWindow")).expression())
        return RuntimeLaws.withComputation(base, ROOT, "FirstWindowComp", "../FirstWindow", operation)
    }

    private val models = Profile.entries.associateWith { modelJson(it) }

    /** `outcome|value` for the computed target on all three engines, each pinned to [expected]. A [seed] on the
     *  target makes a clear observable: an untouched target reports no change at all rather than CLEARED. */
    private fun agree(profile: Profile, expected: String, vararg rows: String?, seed: String? = null) {
        val json = models.getValue(profile)
        val kernelDocument = RuntimeLaws.doc().group(ROOT, 1)
        seed?.let { kernelDocument.field(TARGET, it, 1, 1) }
        rows.forEachIndexed { i, value ->
            kernelDocument.group("$ROOT/Rows", 1, i + 1).field("$ROOT/Rows/Key", "K${i + 1}", 1, i + 1, 1)
            value?.let { kernelDocument.field(ROW_WINDOW, it, 1, i + 1, 1) }
        }
        val dynamic = signature(RuntimeLaws.compute(json, kernelDocument).firstOrNull { it.path() == TARGET })
        val engine = RuntimeLaws.kernelJavaEngine(json)
        val generated = signature(engine.compute(engine.instance(kernelDocument)).firstOrNull { it.path() == TARGET })

        val definition = ModelLoader.load(json)
        val document = definition.newDocument().group(ROOT, 1)
        seed?.let { document.field(TARGET, it, 1, 1) }
        rows.forEachIndexed { i, value ->
            document.group("$ROOT/Rows", 1, i + 1).field("$ROOT/Rows/Key", "K${i + 1}", 1, i + 1, 1)
            value?.let { document.field(ROW_WINDOW, it, 1, i + 1, 1) }
        }
        val computed = DmInterpreter(definition).compute(document).firstOrNull { it.path == TARGET }
        val interpreted = when (computed?.outcome) {
            null -> "NONE"
            ComputeOutcome.CLEARED -> "CLEARED"
            ComputeOutcome.ERRORED -> "ERRORED|${computed.value}"
            ComputeOutcome.VALUE -> "VALUE|${computed.value}"
        }

        assertEquals(expected, dynamic, "normative dynamic Groovy ($profile)")
        assertEquals(dynamic, generated, "generated Java ($profile)")
        assertEquals(dynamic, interpreted, "interpreter ($profile)")
    }

    private fun signature(c: RuntimeLaws.Computed?): String = when {
        c == null -> "NONE"
        c.cleared() -> "CLEARED"
        c.errored() -> "ERRORED|${c.value().orElse("")}"
        else -> "VALUE|${c.value().orElse("")}"
    }

    @Test fun anExactIsoWindowRetainsItsEndpoints() =
        agree(Profile.EXACT_ISO, "VALUE|2024-06-01/2024-06-30", null, "2024-06-01/2024-06-30")

    @Test fun anExactEuropeanWindowRetainsItsEndpoints() =
        agree(Profile.EXACT_EUROPEAN, "VALUE|01.06.2024-30.06.2024", null, "01.06.2024-30.06.2024")

    @Test fun aYearWindowRetainsItsYears() =
        agree(Profile.YEAR, "VALUE|2024/2025", null, "2024/2025")

    @Test fun aYearMonthWindowRetainsItsMonths() =
        agree(Profile.YEAR_MONTH, "VALUE|2024-02/2024-03", null, "2024-02/2024-03")

    /** No Base Year: the pair keeps the two month labels and gains no year. */
    @Test fun aYearlessMonthWindowRetainsItsComponentPair() =
        agree(Profile.MONTH, "VALUE|06/09", null, "06/09")

    /** No Base Year: the pair keeps month and day on both sides and gains no year. */
    @Test fun aYearlessMonthDayWindowRetainsItsComponentPair() =
        agree(Profile.MONTH_DAY, "VALUE|06-01/09-30", null, "06-01/09-30")

    @Test fun theFirstFilledRowWinsOverALaterOne() =
        agree(Profile.MONTH, "VALUE|06/09", null, "06/09", "01/12")

    @Test fun anAllEmptyColumnClearsASeededTarget() =
        agree(Profile.MONTH, "CLEARED", null, null, seed = "01/02")

    /** A formally invalid source is not a value: the empty-prefix scan reaches it and the target still clears. */
    @Test fun aMalformedRowClearsASeededTarget() =
        agree(Profile.MONTH, "CLEARED", null, "xx/09", seed = "01/02")

    private companion object {
        const val ROOT = "/Cover"
        const val TARGET = "/Cover/FirstWindow"
        const val ROW_WINDOW = "/Cover/Rows/RowWindow"
    }
}
