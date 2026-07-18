package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.conformance.Placement
import io.github.mbackschat.a12.dm.interpreter.conformance.PlacementKind
import io.github.mbackschat.a12.dm.interpreter.conformance.kernelComputeGranularity
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.model.ComputationAlternative
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check (groovy-dynamic == java-static == interpreter) for the **final-empty-String store
 * boundary** (IG69/feedback F10, IF123): a concatenation whose FINAL result is empty text stores NO value —
 * the kernel's second root-store decision (`CalculationController.handleBerechnetenWert(VkString…)`:
 * `!hasValue()` → "do like no calculation was done") — while clean empty operands still contribute `""`
 * INSIDE the expression (IF81, the controls). Locks BOTH interpreter surfaces: the raw report-all outcome
 * (CLEARED, never `VALUE ""`) and the [kernelComputeGranularity]-projected delta (CLEARED only for an
 * input-filled target; silence for an absent one — the shape the a12-kernel-lean receipt recorded).
 */
class EmptyConcatStoreDiffTest {

    private companion object {
        const val HOLDER = "/Policy/HolderName"
        const val RIDER = "/Policy/RiderCode"
        const val LABEL = "/Policy/Label"
        const val NOTE = "/Policy/Note"

        fun policy(labelMinLength: Int? = null): String = DocumentModelDraft.named("policy").locale("en_US")
            .rootGroup("Policy") { g ->
                g.stringField("HolderName") {}.stringField("RiderCode") {}
                    .stringField("Label") { l -> labelMinLength?.let { l.minLength(it) } }
                    .stringField("Note") {}
            }
            .build().toJson()
    }

    /** One document shape: the field values present in the input (null = absent), on the shared model. */
    private data class Case(
        val holder: String? = null, val rider: String? = null, val target: String? = null, val note: String? = null)

    private fun concatOp(json: String): String {
        val m = BoundModel.ofJson(json)
        return render(m.str(HOLDER).asString().plus(m.str(RIDER).asString()).expression())
    }

    private fun suffixOp(json: String): String {
        val m = BoundModel.ofJson(json)
        return render(m.str(HOLDER).asString().plus("-GOLD").expression())
    }

    private fun render(c: Calculation): String = DslRenderer(ConditionLanguage.EN, "/Policy").render(c)

    private fun kernelModel(json: String, op: String): String =
        RuntimeLaws.withComputation(json, "/Policy", "LabelComp", "../Label", op)

    private fun seed(c: Case): List<Pair<String, String>> = buildList {
        c.holder?.let { add(HOLDER to it) }
        c.rider?.let { add(RIDER to it) }
        c.target?.let { add(LABEL to it) }
        c.note?.let { add(NOTE to it) }
    }

    private fun kOutcome(json: String, op: String, c: Case): String {
        val doc = RuntimeLaws.doc().group("/Policy", 1)
        for ((p, v) in seed(c)) doc.field(p, v, 1, 1)
        val r = RuntimeLaws.compute(kernelModel(json, op), doc).firstOrNull { it.path() == LABEL }
        return when {
            r == null -> "NONE"
            r.cleared() -> "CLEARED"
            r.errored() -> "ERRORED"
            else -> "VALUE|${r.value().orElse("")}"
        }
    }

    private fun jOutcome(json: String, op: String, c: Case): String {
        val engine = RuntimeLaws.kernelJavaEngine(kernelModel(json, op))
        val doc = RuntimeLaws.doc().group("/Policy", 1)
        for ((p, v) in seed(c)) doc.field(p, v, 1, 1)
        val raw = engine.computeOutcomesSorted(engine.instance(doc)).firstOrNull { it.startsWith("$LABEL|") }
            ?.substringAfter("$LABEL|") ?: return "NONE"
        return when (raw) { "ERR" -> "ERRORED"; "CLEARED" -> "CLEARED"; else -> "VALUE|$raw" }
    }

    private fun interpModel(labelMinLength: Int? = null, op: String) = ModelDef(
        listOf(FieldDef(HOLDER, Kind.STRING), FieldDef(RIDER, Kind.STRING),
            FieldDef(LABEL, Kind.STRING, minLength = labelMinLength), FieldDef(NOTE, Kind.STRING)),
        emptyList(),
        computations = listOf(ComputationDef(LABEL, "/Policy", listOf(ComputationAlternative(null, op)))),
    )

    /** The interpreter's (raw report-all, kernel-granularity-projected) outcome pair for [c]. */
    private fun iOutcomes(def: ModelDef, c: Case): Pair<String, String> {
        val doc = def.newDocument()
            .group("/Policy", 1)
        for ((p, v) in seed(c)) doc.field(p, v, 1, 1)
        val computed = DmInterpreter(def).compute(doc)
        val raw = computed.firstOrNull { it.path == LABEL }?.let {
            when (it.outcome) {
                ComputeOutcome.CLEARED -> "CLEARED"; ComputeOutcome.ERRORED -> "ERRORED"
                ComputeOutcome.VALUE -> "VALUE|${it.value}"
            }
        } ?: "NONE"
        val signatures = computed.map {
            val p = formatPointer(it.path, it.indices)
            when (it.outcome) {
                ComputeOutcome.VALUE -> "$p|VALUE|${it.value}"
                ComputeOutcome.CLEARED -> "$p|CLEARED"
                ComputeOutcome.ERRORED -> "$p|ERRORED"
            }
        }
        val placements = listOf(Placement(PlacementKind.GROUP, "/Policy", listOf(1))) +
            seed(c).map { (p, v) -> Placement(PlacementKind.FIELD, p, listOf(1, 1), v) }
        val projected = kernelComputeGranularity(signatures, placements, def)
            .firstOrNull { it.startsWith(formatPointer(LABEL, listOf(1, 1)) + "|") }
            ?.substringAfter("|")?.let { if (it.startsWith("VALUE|")) it else it.substringBefore("|") } ?: "NONE"
        return raw to projected
    }

    /**
     * Assert all three engines agree and return the kernel outcome; [expectRaw] pins the interpreter's
     * report-all side, the projected side must equal the kernel's delta ([expectKernel]).
     */
    private fun check(c: Case, expectKernel: String, expectRaw: String, minLength: Int? = null,
        op: (String) -> String = ::concatOp, label: String) {
        val json = policy(minLength)
        val operation = op(json)
        assertEquals(expectKernel, kOutcome(json, operation, c), "$label: groovy-dynamic")
        assertEquals(expectKernel, jOutcome(json, operation, c), "$label: java-static")
        val (raw, projected) = iOutcomes(interpModel(minLength, operation), c)
        assertEquals(expectRaw, raw, "$label: interpreter raw (report-all)")
        assertEquals(expectKernel, projected, "$label: interpreter projected (kernel granularity)")
    }

    // Control — the store decision is the FINAL-text boundary, not operand substitution: a filled + empty
    // concat stores the filled text.
    @Test fun filledPlusEmptyStores() =
        check(Case(holder = "ALPHA"), expectKernel = "VALUE|ALPHA", expectRaw = "VALUE|ALPHA", label = "filled+empty")

    // Control — an empty operand + a nonempty literal stores the literal (the receipt's nonempty control).
    @Test fun emptyPlusNonemptyLiteralStores() = check(Case(target = "STALE"),
        expectKernel = "VALUE|-GOLD", expectRaw = "VALUE|-GOLD", op = ::suffixOp, label = "empty+literal")

    // The receipt's divergent shape 1: all-empty concat over a STALE target → the kernel stores no value and
    // the previously-filled target is CLEARED (both surfaces agree).
    @Test fun allEmptyConcatWithStaleTarget() = check(Case(target = "STALE"),
        expectKernel = "CLEARED", expectRaw = "CLEARED", label = "all-empty stale target")

    // The receipt's divergent shape 2: all-empty concat, ABSENT target, content-bearing row → the kernel is
    // silent; the interpreter reports CLEARED raw and projects to silence.
    @Test fun allEmptyConcatWithAbsentTargetContentRow() = check(Case(note = "keep"),
        expectKernel = "NONE", expectRaw = "CLEARED", label = "all-empty absent target, content row")

    // The receipt's divergent shape 3: same, on an otherwise EMPTY row.
    @Test fun allEmptyConcatWithAbsentTargetEmptyRow() = check(Case(),
        expectKernel = "NONE", expectRaw = "CLEARED", label = "all-empty absent target, empty row")

    // Gate ORDER: the no-value decision precedes the target's format check — an empty final text into a
    // minLength target is CLEARED, never ERRORED (the kernel never checks a value it does not store).
    @Test fun allEmptyConcatIntoMinLengthTargetClearsNotErrors() = check(Case(target = "ST"),
        expectKernel = "CLEARED", expectRaw = "CLEARED", minLength = 2, label = "all-empty into minLength target")
}
