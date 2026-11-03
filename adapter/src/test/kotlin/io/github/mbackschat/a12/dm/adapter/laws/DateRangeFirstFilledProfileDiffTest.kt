package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — a starred `FirstFilledValue` over a repeatable DATE_RANGE column, computed into a DATE_RANGE
 * target of the SAME declaration profile, across ALL SEVEN `(format, separator)` pairs the kernel admits for a
 * range DECLARATION (SPEC-2026-08-19-03; the enumerated declaration grid is KERNEL-FINDINGS KF81): the two
 * exact profiles, the four component subsets `yyyy`, `yyyy-MM`, `MM`, `MM-dd`, and `dd.MM`+`-`. Kernel 30.8.1
 * admitted an eighth, the halved `MM`+`""`; on 31.1.1 an empty separator reads as `/` (KERNEL-FINDINGS KF658), so
 * that row is the `MM` row and was retired on the upgrade.
 *
 * **An admitted DECLARATION pair is not thereby an admitted COMPUTATION profile — that is a separate claim, and
 * this matrix is what measures it:** [everyProfileAndTheCrossedTargetAreKernelAdmitted] runs the kernel's MVK
 * gate on all eight models this class executes, and the runtime rows put each through both kernel strategies.
 * The admission half used to be asserted by nobody — `BoundModel.ofJson` gates the BASE and the computation is
 * spliced in after it (TESTING-SPEC "the admission gate"). The matrix held only the first six for a while and read
 * exactly like the whole class; running the remaining pairs is what found IF306, in the model LOADER rather than the
 * evaluator.
 *
 * The point of the matrix is the *stored form*. A year-bearing profile round-trips its exact endpoints; a
 * yearless one keeps the ordered component labels it declared and gains no synthetic year, because the target
 * renders through its own declared format and never through the completed instant the evaluator carries
 * internally. The model declares no Base Year, so the four yearless rows are measured where there is nothing to
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
        DAY_MONTH("dd.MM", "-"),
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
        val operation = RuntimeLaws.render(ROOT, Dsl.firstFilledDateRange(m.flatten("$ROOT/Rows*/RowWindow")).expression())
        return RuntimeLaws.withComputation(base, ROOT, "FirstWindowComp", "../FirstWindow", operation)
    }

    /** The one CROSSED model: an `yyyy-MM-dd` target fed from a `dd.MM.yyyy` column, same component set. */
    private fun crossedModelJson(): String {
        val base = DocumentModelDraft.named("cover-crossed").locale("en_US")
            .rootGroup("Cover") { cover ->
                cover.dateRangeField("FirstWindow") { it.format("yyyy-MM-dd").separator("/") }
                cover.group("Rows") { rows ->
                    rows.repeatable(20).indexField("Key")
                        .stringField("Key") { }
                        .dateRangeField("RowWindow") { it.format("dd.MM.yyyy").separator("-") }
                }
            }.build().toJson()
        val m = BoundModel.ofJson(base)
        val operation = RuntimeLaws.render(ROOT, Dsl.firstFilledDateRange(m.flatten("$ROOT/Rows*/RowWindow")).expression())
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

    /**
     * The ADMISSION half, asserted rather than inferred: the kernel's `checkConsistency` reports NOTHING for
     * each of the eight models this class executes. Codegen succeeding is not this claim — the MVK gate is a
     * separate route, and until it was called here the "all of which pass `checkConsistency`" line rested on
     * the runtime rows, which cannot see it (TESTING-SPEC "the admission gate").
     */
    @Test
    fun everyProfileAndTheCrossedTargetAreKernelAdmitted() {
        for (profile in Profile.entries) {
            assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(models.getValue(profile)),
                "the kernel admits the $profile computation profile")
        }
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(crossedModelJson()),
            "the kernel admits the crossed lexical profile")
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

    /** No Base Year: the pair keeps day and month on both sides and gains no year. */
    @Test fun aYearlessDayMonthWindowRetainsItsComponentPair() =
        agree(Profile.DAY_MONTH, "VALUE|01.06-30.09", null, "01.06-30.09")

    @Test fun theFirstFilledRowWinsOverALaterOne() =
        agree(Profile.MONTH, "VALUE|06/09", null, "06/09", "01/12")

    @Test fun anAllEmptyColumnClearsASeededTarget() =
        agree(Profile.MONTH, "CLEARED", null, null, seed = "01/02")

    /** A formally invalid source is not a value: the empty-prefix scan reaches it and the target still clears. */
    @Test fun aMalformedRowClearsASeededTarget() =
        agree(Profile.MONTH, "CLEARED", null, "xx/09", seed = "01/02")

    /**
     * The row that makes "the TARGET's declared format decides the stored form" a claim rather than a
     * description. Every other row here has source profile == target profile, so a renderer keyed on the
     * SOURCE would produce byte-identical output on all of them and the mechanism would be untested.
     *
     * The kernel's comparability gate reads the component SET, not the lexical profile
     * (`DateFormat.hasSameComponents`), so `dd.MM.yyyy` and `yyyy-MM-dd` — both `{YEAR, MONTH, DAY}` — are
     * freely assignable to each other. Measured 2026-08-19: the model is accepted and the computation is
     * admitted. A source-keyed renderer would store `01.06.2024-30.06.2024`.
     */
    @Test
    fun theTargetFormatDecidesTheStoredForm() {
        val json = crossedModelJson()

        val kernelDocument = RuntimeLaws.doc().group(ROOT, 1)
            .group("$ROOT/Rows", 1, 1).field("$ROOT/Rows/Key", "K1", 1, 1, 1)
            .field(ROW_WINDOW, "01.06.2024-30.06.2024", 1, 1, 1)
        val dynamic = signature(RuntimeLaws.compute(json, kernelDocument).firstOrNull { it.path() == TARGET })
        val engine = RuntimeLaws.kernelJavaEngine(json)
        val generated = signature(engine.compute(engine.instance(kernelDocument)).firstOrNull { it.path() == TARGET })

        val definition = ModelLoader.load(json)
        val document = definition.newDocument().group(ROOT, 1)
            .group("$ROOT/Rows", 1, 1).field("$ROOT/Rows/Key", "K1", 1, 1, 1)
            .field(ROW_WINDOW, "01.06.2024-30.06.2024", 1, 1, 1)
        val computed = DmInterpreter(definition).compute(document).firstOrNull { it.path == TARGET }
        val interpreted = when (computed?.outcome) {
            null -> "NONE"
            ComputeOutcome.CLEARED -> "CLEARED"
            ComputeOutcome.ERRORED -> "ERRORED|${computed.value}"
            ComputeOutcome.VALUE -> "VALUE|${computed.value}"
        }

        assertEquals("VALUE|2024-06-01/2024-06-30", dynamic, "normative dynamic Groovy (crossed lexical profile)")
        assertEquals(dynamic, generated, "generated Java (crossed lexical profile)")
        assertEquals(dynamic, interpreted, "interpreter (crossed lexical profile)")
    }

    private companion object {
        const val ROOT = "/Cover"
        const val TARGET = "/Cover/FirstWindow"
        const val ROW_WINDOW = "/Cover/Rows/RowWindow"
    }
}
