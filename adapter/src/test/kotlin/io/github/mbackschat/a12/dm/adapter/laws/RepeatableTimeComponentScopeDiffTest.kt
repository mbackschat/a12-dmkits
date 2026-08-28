package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — every `Time(...)` component reads at its OWN declaration's repetition scope inside the current
 * physical target row (SPEC-2026-08-27-04/-05/-06, reconciled from a12-kernel-lean).
 *
 * **One class, three carriers, on purpose.** The claim is about SCOPE, so measuring it on Number components
 * alone leaves the admitted rows equally well explained by "a numeric component is special". The same
 * root/enclosing/leaf prefix is therefore measured with plain Number fields, with checked digit-String fields,
 * and with matching direct temporal extractors — one model, one codegen, three targets.
 *
 * The load-bearing separation is between a root component (reaches every task), an enclosing-parent component
 * (reaches only its own parent's leaves) and a leaf component (row-local). A reader at the leaf path would lose
 * the first two; one reading the enclosing component globally would leak parent 1's minute into parent 2.
 *
 * Codegen and the compiled engine are hoisted to lazy fields because this class shares `:adapter:test` with
 * timing floors.
 *
 * Own-domain (an appointment probe whose slot clock is assembled from three scopes).
 */
class RepeatableTimeComponentScopeDiffTest {

    private companion object {
        const val ROOT = "/TimeProbe"
        const val PARENTS = "$ROOT/Parents"
        const val TASKS = "$PARENTS/Tasks"
        const val SLOT_NUM = "$TASKS/SlotTime"
        const val SLOT_TEXT = "$TASKS/SlotTimeText"
        const val SLOT_EXTRACT = "$TASKS/SlotTimeExtract"
        const val CLOCK = "HH:mm:ss"
        const val STAMP = "yyyy-MM-dd'T'HH:mm:ss"
        const val DIGITS = "[0-9]+"
        const val DIGIT_MSG = "Use up to two digits."
    }

    /**
     * Every Number component declares `maxLength 2` — its POSITION's width. The gate is on the DECLARATION,
     * not the value: omitting it is refused at codegen with `MVK_WRONG_DATE_FORMAT_FOR_OP` even for an
     * otherwise legal scale-0 field (SPEC-2026-07-27-10). `minLength` is deliberately NOT set; see
     * `aZeroPaddedNumberComponentUnderThisDeclaration` for what adding it costs.
     */
    private val base = DocumentModelDraft.named("appointment-probe").locale("en_US")
        .rootGroup("TimeProbe") { p ->
            p.numberField("RootHour") { it.fractionDigits(0).min("0").maxLength(2).maxIntegerDigits(2) }
                .stringField("RootHourText") { it.maxLength(2).pattern(DIGITS, DIGIT_MSG) }
                .dateTimeField("RootStamp") { it.format(STAMP) }
                .group("Parents") { parents ->
                    parents.repeatable(5)
                        .numberField("ParentMinute") { it.fractionDigits(0).min("0").maxLength(2).maxIntegerDigits(2) }
                        .stringField("ParentMinuteText") { it.maxLength(2).pattern(DIGITS, DIGIT_MSG) }
                        .timeField("ParentTime") { it.format(CLOCK) }
                        .group("Tasks") { tasks ->
                            tasks.repeatable(5)
                                .numberField("RowSecond") { it.fractionDigits(0).min("0").maxLength(2).maxIntegerDigits(2) }
                                .stringField("RowSecondText") { it.maxLength(2).pattern(DIGITS, DIGIT_MSG) }
                                .dateTimeField("RowStamp") { it.format(STAMP) }
                                .timeField("SlotTime") { it.format(CLOCK) }
                                .timeField("SlotTimeText") { it.format(CLOCK) }
                                .timeField("SlotTimeExtract") { it.format(CLOCK) }
                        }
                }
        }.build().toJson()

    /** All three carriers in ONE model: three targets, one codegen. Each is declared in the target's own group. */
    private val model: String by lazy {
        var m = RuntimeLaws.withComputation(base, TASKS, "NumComp", "../SlotTime",
            "Time(/TimeProbe/RootHour, ../ParentMinute, RowSecond)")
        m = RuntimeLaws.withComputation(m, TASKS, "TextComp", "../SlotTimeText",
            "Time(/TimeProbe/RootHourText, ../ParentMinuteText, RowSecondText)")
        RuntimeLaws.withComputation(m, TASKS, "ExtractComp", "../SlotTimeExtract",
            "Time(HoursFromTime(/TimeProbe/RootStamp), MinutesFromTime(../ParentTime), SecondsFromTime(RowStamp))")
    }

    private val def by lazy { ModelLoader.load(model) }
    private val javaEngine by lazy { RuntimeLaws.kernelJavaEngine(model) }

    private class Both(val kDoc: RuntimeLaws.Doc, val iDoc: Document) {
        fun group(path: String, vararg reps: Int): Both { kDoc.group(path, *reps); iDoc.group(path, *reps); return this }
        fun field(path: String, v: String, vararg reps: Int): Both { kDoc.field(path, v, *reps); iDoc.field(path, v, *reps); return this }
    }

    /** Compute outcomes for ONE target path, keyed by exact address, agreed across both strategies + interpreter. */
    private fun agree(target: String, msg: String, expect: Map<String, String>, build: (Both) -> Unit) {
        val b = Both(RuntimeLaws.doc(), def.newDocument())
        build(b)
        val groovy = RuntimeLaws.compute(model, b.kDoc).filter { it.path() == target }
            .associate { it.pointer() to kSig(it) }
        val kDoc2 = RuntimeLaws.doc()
        build(Both(kDoc2, def.newDocument()))
        val javaStatic = javaEngine.compute(javaEngine.instance(kDoc2)).filter { it.path() == target }
            .associate { it.pointer() to kSig(it) }
        val interp = DmInterpreter(def).compute(b.iDoc).filter { it.path == target }
            .associate { formatPointer(it.path, it.indices) to iSig(it) }
        assertEquals(expect, groovy, "$msg — the kernel groovy-dynamic outcome")
        assertEquals(groovy, javaStatic, "$msg — groovy vs java-static (tri-check)")
        assertEquals(groovy, interp, "$msg — kernel vs interpreter")
    }

    /** The formal operand channel of the same run, as `code|field`. */
    private fun formalErrors(build: (Both) -> Unit): List<String> {
        val doc = RuntimeLaws.doc()
        build(Both(doc, def.newDocument()))
        return RuntimeLaws.computeWithFormalErrors(model, doc).formalErrorsInOperands()
            .map { "${it.code()}|${it.field()}" }.sorted()
    }

    private fun kSig(c: RuntimeLaws.Computed): String =
        if (c.cleared()) "CLEARED" else if (c.errored()) "ERRORED" else "VALUE|${c.value().orElse("")}"

    private fun iSig(c: io.github.mbackschat.a12.dm.interpreter.model.Computed): String = when (c.outcome) {
        ComputeOutcome.CLEARED -> "CLEARED"
        ComputeOutcome.ERRORED -> "ERRORED"
        ComputeOutcome.VALUE -> "VALUE|${c.value}"
    }

    private fun at(target: String, parent: Int, task: Int) =
        "$ROOT[1]/Parents[$parent]/Tasks[$task]/${target.substringAfterLast('/')}"

    /** The topology every arm shares: two parents, two leaves under parent 1, one under parent 2. */
    private fun Both.topology(): Both {
        group(PARENTS, 1, 1); group(TASKS, 1, 1, 1); group(TASKS, 1, 1, 2)
        group(PARENTS, 1, 2); group(TASKS, 1, 2, 1)
        return this
    }

    private fun Both.numbers(root: String?, p1: String?, p2: String?,
                            s11: String?, s12: String?, s21: String?): Both {
        root?.let { field("$ROOT/RootHour", it, 1, 1) }
        p1?.let { field("$PARENTS/ParentMinute", it, 1, 1, 1) }
        p2?.let { field("$PARENTS/ParentMinute", it, 1, 2, 1) }
        s11?.let { field("$TASKS/RowSecond", it, 1, 1, 1, 1) }
        s12?.let { field("$TASKS/RowSecond", it, 1, 1, 2, 1) }
        s21?.let { field("$TASKS/RowSecond", it, 1, 2, 1, 1) }
        return this
    }

    private fun Both.texts(root: String?, p1: String?, p2: String?,
                           s11: String?, s12: String?, s21: String?): Both {
        root?.let { field("$ROOT/RootHourText", it, 1, 1) }
        p1?.let { field("$PARENTS/ParentMinuteText", it, 1, 1, 1) }
        p2?.let { field("$PARENTS/ParentMinuteText", it, 1, 2, 1) }
        s11?.let { field("$TASKS/RowSecondText", it, 1, 1, 1, 1) }
        s12?.let { field("$TASKS/RowSecondText", it, 1, 1, 2, 1) }
        s21?.let { field("$TASKS/RowSecondText", it, 1, 2, 1, 1) }
        return this
    }

    private fun Both.stamps(root: String?, p1: String?, p2: String?,
                            s11: String?, s12: String?, s21: String?): Both {
        root?.let { field("$ROOT/RootStamp", it, 1, 1) }
        p1?.let { field("$PARENTS/ParentTime", it, 1, 1, 1) }
        p2?.let { field("$PARENTS/ParentTime", it, 1, 2, 1) }
        s11?.let { field("$TASKS/RowStamp", it, 1, 1, 1, 1) }
        s12?.let { field("$TASKS/RowStamp", it, 1, 1, 2, 1) }
        s21?.let { field("$TASKS/RowStamp", it, 1, 2, 1, 1) }
        return this
    }

    // ── SPEC-04: Number components ─────────────────────────────────────────────────────────────────────────

    @Test fun numberComponentsEachReadAtTheirOwnScope() =
        agree(SLOT_NUM, "root 5 reaches every task; minutes 2/4 stay parent-local; seconds 9/10/9 stay leaf-local",
            mapOf(at(SLOT_NUM, 1, 1) to "VALUE|05:02:09",
                  at(SLOT_NUM, 1, 2) to "VALUE|05:02:10",
                  at(SLOT_NUM, 2, 1) to "VALUE|05:04:09")) { d ->
            d.topology().numbers("5", "2", "4", "9", "10", "9")
        }

    @Test fun aMissingNumberComponentClearsOnlyTheAffectedTarget() =
        agree(SLOT_NUM, "parent 2 has no minute → only its leaf clears; parent 1's leaves still compute",
            mapOf(at(SLOT_NUM, 1, 1) to "VALUE|05:02:09",
                  at(SLOT_NUM, 1, 2) to "VALUE|05:02:10",
                  at(SLOT_NUM, 2, 1) to "CLEARED")) { d ->
            d.topology().numbers("5", "2", null, "9", "10", "9")
            d.field(SLOT_NUM, "07:15:00", 1, 2, 1, 1)
        }

    @Test fun noPhysicalTargetRowProducesNoNumberOutcome() =
        agree(SLOT_NUM, "a parent with no task row invents no outcome", emptyMap()) { d ->
            d.group(PARENTS, 1, 1).numbers("5", "2", null, null, null, null)
        }

    /**
     * **A Number component's own LENGTH declaration decides which spellings can produce a clock, and it cost a
     * run to find.** The arms above use `maxLength 2` alone — the profile whose numbers match
     * SPEC-2026-08-27-04 exactly. Adding `minLength 2` (the profile `ComputedTimeTargetDiffTest` uses, whose
     * values are all naturally two-digit) makes BOTH single-digit spellings unusable: `"5"` is too short and the
     * zero-padded `"05"` yields no clock either, so the whole matrix goes silent and reads exactly like a scope
     * failure. The zero-padded spelling is refused **independently of `minLength`** — this arm measures it under
     * `maxLength` alone — so the Number and checked digit-String carriers genuinely differ on padded input,
     * which is what gives SPEC-2026-08-27-05 its point rather than being a restatement of SPEC-04.
     *
     * The stale targets are seeded on purpose: they turn the observation into `CLEARED` rather than an absent
     * outcome, so the arm cannot pass vacuously on a build where the computation disappeared entirely.
     */
    @Test fun aZeroPaddedNumberComponentProducesNoClockWhateverTheLengthDeclaration() =
        agree(SLOT_NUM, "the padded spelling the checked digit-String carrier converts yields no clock here",
            mapOf(at(SLOT_NUM, 1, 1) to "CLEARED",
                  at(SLOT_NUM, 1, 2) to "CLEARED",
                  at(SLOT_NUM, 2, 1) to "CLEARED")) { d ->
            d.topology().numbers("05", "02", "04", "09", "10", "09")
            d.field(SLOT_NUM, "07:15:00", 1, 1, 1, 1)
            d.field(SLOT_NUM, "07:15:00", 1, 1, 2, 1)
            d.field(SLOT_NUM, "07:15:00", 1, 2, 1, 1)
        }

    // ── SPEC-05: checked digit-String components ────────────────────────────────────────────────────────────

    @Test fun digitStringComponentsEachReadAtTheirOwnScopeAndConvert() =
        agree(SLOT_TEXT, "leading-zero text converts to the numeric clock part, at each component's own scope",
            mapOf(at(SLOT_TEXT, 1, 1) to "VALUE|05:02:09",
                  at(SLOT_TEXT, 1, 2) to "VALUE|05:02:10",
                  at(SLOT_TEXT, 2, 1) to "VALUE|05:04:09")) { d ->
            d.topology().texts("05", "02", "04", "09", "10", "09")
        }

    /**
     * The String declaration gate is `maxLength 2` plus the digit pattern — it does NOT impose the selected
     * component POSITION's maximum. `"99"` is pattern-valid, so it raises no formal finding, reaches clock
     * construction, produces no clock, and clears; a clean sibling still computes.
     */
    @Test fun aPatternValidImpossibleSecondClearsWithoutAFormalFinding() {
        val build: (Both) -> Unit = { d ->
            d.topology().texts("05", "02", "04", "99", "10", "09")
            d.field(SLOT_TEXT, "07:15:00", 1, 1, 1, 1)
        }
        agree(SLOT_TEXT, "the impossible second clears its own target only",
            mapOf(at(SLOT_TEXT, 1, 1) to "CLEARED",
                  at(SLOT_TEXT, 1, 2) to "VALUE|05:02:10",
                  at(SLOT_TEXT, 2, 1) to "VALUE|05:04:09"), build)
        assertEquals(emptyList<String>(), formalErrors(build),
            "a pattern-VALID impossible component is not a formal error — the gate is the declaration, "
                + "not the component position's maximum")
    }

    @Test fun aPatternInvalidStringComponentIsAFormalErrorAndClears() {
        val build: (Both) -> Unit = { d ->
            d.topology().texts("05", "02", "04", "xx", "10", "09")
            d.field(SLOT_TEXT, "07:15:00", 1, 1, 1, 1)
        }
        agree(SLOT_TEXT, "the pattern-invalid row clears; its clean sibling computes",
            mapOf(at(SLOT_TEXT, 1, 1) to "CLEARED",
                  at(SLOT_TEXT, 1, 2) to "VALUE|05:02:10",
                  at(SLOT_TEXT, 2, 1) to "VALUE|05:04:09"), build)
        assertEquals(
            listOf("stringFalschesMuster|$ROOT[1]/Parents[1]/Tasks[1]/RowSecondText"),
            formalErrors(build),
            "a pattern-invalid component rides the formal operand channel as stringFalschesMuster",
        )
    }

    // ── SPEC-06: matching direct temporal extractors ────────────────────────────────────────────────────────

    @Test fun extractorComponentsEachReadTheirSourceAtItsOwnScope() =
        agree(SLOT_EXTRACT, "each extractor projects its own scope's source component",
            mapOf(at(SLOT_EXTRACT, 1, 1) to "VALUE|05:02:09",
                  at(SLOT_EXTRACT, 1, 2) to "VALUE|05:02:10",
                  at(SLOT_EXTRACT, 2, 1) to "VALUE|05:04:09")) { d ->
            d.topology().stamps("2024-06-01T05:00:00", "00:02:00", "00:04:00",
                "2024-06-01T00:00:09", "2024-06-01T00:00:10", "2024-06-01T00:00:09")
        }

    @Test fun aMissingExtractorSourceClearsOnlyTheAffectedTarget() =
        agree(SLOT_EXTRACT, "parent 2 has no ParentTime → only its leaf clears",
            mapOf(at(SLOT_EXTRACT, 1, 1) to "VALUE|05:02:09",
                  at(SLOT_EXTRACT, 1, 2) to "VALUE|05:02:10",
                  at(SLOT_EXTRACT, 2, 1) to "CLEARED")) { d ->
            d.topology().stamps("2024-06-01T05:00:00", "00:02:00", null,
                "2024-06-01T00:00:09", "2024-06-01T00:00:10", "2024-06-01T00:00:09")
            d.field(SLOT_EXTRACT, "07:15:00", 1, 2, 1, 1)
        }

    @Test fun aMalformedExtractorSourceIsAFormalErrorAndClears() {
        val build: (Both) -> Unit = { d ->
            d.topology().stamps("2024-06-01T05:00:00", "00:02:00", "00:04:00",
                "2024-06-32T00:00:09", "2024-06-01T00:00:10", "2024-06-01T00:00:09")
            d.field(SLOT_EXTRACT, "07:15:00", 1, 1, 1, 1)
        }
        agree(SLOT_EXTRACT, "the malformed row DateTime clears its target; the clean sibling computes",
            mapOf(at(SLOT_EXTRACT, 1, 1) to "CLEARED",
                  at(SLOT_EXTRACT, 1, 2) to "VALUE|05:02:10",
                  at(SLOT_EXTRACT, 2, 1) to "VALUE|05:04:09"), build)
        assertEquals(
            listOf("datumFormatFalsch|$ROOT[1]/Parents[1]/Tasks[1]/RowStamp"),
            formalErrors(build),
            "PLACEHOLDER — the formal operand channel for a malformed extractor source",
        )
    }
}
