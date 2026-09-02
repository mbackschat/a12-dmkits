package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.DateExpr
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Tri-engine differential — a bare Date CONSTANT is not exempt from the target's opt-in pre-1900 policy.
 * a12-kernel-lean's `SPEC-2026-09-01-01`, which supersedes their earlier "a Date constant has no target check at
 * either time" claim, reproduced here on an independently authored carrier.
 *
 * **What was and was not already ours.** [DateBefore1900DiffTest] locks the check on a nonrepeatable target and
 * [RepeatableCheckedDateTargetDiffTest] locks it per instance on a repeatable one — but both drive the target
 * from a FIELD (a copy, and a `FirstFilledValue` selection). A constant is a different operand class: it reaches
 * the target through the model's own admission gate rather than through a document cell, and our
 * [ConstantTargetFormatLawsTest] rows show that gate has its own rules — the declared FORMAT decides admission
 * and the store, and components the format omits are dropped with no signal at all. Whether a target's *value*
 * policy still applies after that rendering is therefore not a corollary of either lock.
 *
 * **The pair is the whole instrument.** Both targets are ISO Date declarations differing in exactly one builder
 * call, and the identical constant goes into both from two computations of one model on one document. A single
 * guarded target answering ERRORED would be consistent with the constant simply not being storable; the
 * unguarded twin answering a value on the same run is what makes it the policy.
 *
 * **The bucket is asserted beside the retained attempt, not instead of it.** The claim is that the guarded target
 * is ERRORED and still carries `1899-12-31` — never cleared — so a signature that dropped either half would pass
 * on a clear. A planted mutant found exactly that hole in the sibling file on 2026-08-28.
 *
 * Own domain (consumer-electronics warranty registration).
 */
class DateConstantTargetPolicyDiffTest {

    private companion object {
        const val ISO = "yyyy-MM-dd"
        const val WARRANTY = "/Warranty"
        const val CLAIMS = "/Warranty/Claims"
        const val GUARDED = "/Warranty/Claims/GuardedOn"
        const val OPEN = "/Warranty/Claims/OpenOn"
        const val BOUNDARY = "/Warranty/Claims/BoundaryOn"
        const val REFERENCE = "/Warranty/Reference"

        /** Below 1900 and well above the always-on 1583-10-16 Gregorian floor, so only the opt-in check can bite. */
        val PRE_1900: LocalDate = LocalDate.of(1899, 12, 31)

        /** The threshold itself — the control that keeps the guard from reading as a blanket refusal. */
        val AT_1900: LocalDate = LocalDate.of(1900, 1, 1)

        const val ROWS = 2
    }

    /** Two ISO Date targets differing in exactly one builder call, plus the boundary control beside them. */
    private val base = DocumentModelDraft.named("warranty-date-constant-policy").locale("en_US")
        .rootGroup("Warranty") { warranty ->
            warranty.stringField("Reference") { }
            warranty.group("Claims") { claims ->
                claims.repeatable(ROWS)
                claims.dateField("GuardedOn") { it.format(ISO).rejectBefore1900() }
                claims.dateField("OpenOn") { it.format(ISO) }
                claims.dateField("BoundaryOn") { it.format(ISO).rejectBefore1900() }
            }
        }.build().toJson()

    private val bound = BoundModel.ofJson(base)

    private fun assign(target: String, name: String, constant: DateExpr) =
        bound.computation(bound.date(target).field()).name(name).calc(constant.expression())
            .message(Dsl.en("The warranty reference date this claim inherits.")).build()

    private val modelJson = listOf(
        assign(GUARDED, "GuardedAssign", Dsl.dateValue(PRE_1900)),
        assign(OPEN, "OpenAssign", Dsl.dateValue(PRE_1900)),
        assign(BOUNDARY, "BoundaryAssign", Dsl.dateValue(AT_1900)),
    ).fold(base) { json, computation -> TypedRules.inject(json, computation) }

    private val definition = ModelLoader.load(modelJson)
    private val javaEngine by lazy { RuntimeLaws.kernelJavaEngine(modelJson) }

    /**
     * The kernel must ADMIT this model. A constant the admission gate refused would compute nothing, and reading
     * that absence as "the target rejected the value" would report the same thing whether the policy exists or
     * not — the trap [TemporalConstantLiteralWitnessLawsTest] raises the same guard against.
     */
    @Test
    fun theModelIsAdmittedSoAnAbsentOutcomeWouldBeARejectionAndNotARefusal() =
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(modelJson),
            "a refused model computes nothing, which is indistinguishable from a rejected value")

    @Test
    fun theSameConstantIsErroredOnTheGuardedTargetAndAValueOnItsUnguardedTwin() {
        val expected = (1..ROWS).flatMap { row ->
            listOf(
                outcomeKey(GUARDED, row) to "ERRORED|1899-12-31",
                outcomeKey(OPEN, row) to "VALUE|1899-12-31",
                outcomeKey(BOUNDARY, row) to "VALUE|1900-01-01",
            )
        }.toMap()
        assertAgreement(
            expected,
            "one constant, two targets differing only in rejectBefore1900, and the threshold control",
        )
    }

    /**
     * The rejected target is ERRORED rather than CLEARED and keeps the exact attempt, rendered in its own
     * declared format. Asserted separately from the pair above so the distinction has a row of its own: a
     * consumer that collapsed the rejection into a clear would lose the value the user typed nothing to produce.
     */
    @Test
    fun theRejectedConstantIsRetainedAsAnErroredAttemptRatherThanCleared() {
        val guarded = RuntimeLaws.compute(modelJson, kernelDocument())
            .filter { it.path() == GUARDED }
        assertEquals(ROWS, guarded.size, "one outcome per instantiated row")
        assertEquals(
            List(ROWS) { "errored=true cleared=false value=1899-12-31" },
            guarded.map { "errored=${it.errored()} cleared=${it.cleared()} value=${it.value().orElse("<none>")}" },
            "the guarded target must retain the attempt in its ISO declaration, and must not be a clear",
        )
    }

    // ---- the instrument ----

    private fun assertAgreement(expected: Map<String, String>, label: String) {
        val groovy = RuntimeLaws.compute(modelJson, kernelDocument())
            .associate { outcomeKey(it.path(), it.pointer()) to signature(it) }
        val static = javaEngine.compute(javaEngine.instance(kernelDocument()))
            .associate { outcomeKey(it.path(), it.pointer()) to signature(it) }
        val interpreter = DmInterpreter(definition).compute(interpreterDocument())
            .associate { outcomeKey(it.path, formatPointer(it.path, it.indices)) to signature(it) }

        assertEquals(expected, groovy, "$label — the normative dynamic-Groovy outcome")
        assertEquals(groovy, static, "$label — generated Java")
        assertEquals(groovy, interpreter, "$label — interpreter")
    }

    private fun signature(c: RuntimeLaws.Computed): String {
        val bucket = if (c.cleared()) "CLEARED" else if (c.errored()) "ERRORED" else "VALUE"
        return "$bucket|${c.value().orElse("<none>")}"
    }

    private fun signature(c: io.github.mbackschat.a12.dm.interpreter.model.Computed): String {
        val bucket = when (c.outcome) {
            ComputeOutcome.CLEARED -> "CLEARED"
            ComputeOutcome.ERRORED -> "ERRORED"
            ComputeOutcome.VALUE -> "VALUE"
        }
        return "$bucket|${c.value ?: "<none>"}"
    }

    private fun outcomeKey(path: String, pointer: String) = "$path@$pointer"

    private fun outcomeKey(path: String, row: Int) =
        "$path@/Warranty[1]/Claims[$row]/${path.substringAfterLast('/')}"

    private fun kernelDocument(): RuntimeLaws.Doc {
        val d = RuntimeLaws.doc().group(WARRANTY, 1).field(REFERENCE, "WR-40318", 1, 1)
        for (row in 1..ROWS) d.group(CLAIMS, 1, row)
        return d
    }

    private fun interpreterDocument(): Document {
        val d = definition.newDocument().group(WARRANTY, 1).field(REFERENCE, "WR-40318", 1, 1)
        for (row in 1..ROWS) d.group(CLAIMS, 1, row)
        return d
    }
}
