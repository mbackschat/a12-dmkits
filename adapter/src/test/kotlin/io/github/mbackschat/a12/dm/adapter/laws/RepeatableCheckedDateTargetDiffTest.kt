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
 * Differential — the RUNTIME half of SPEC-2026-08-27-02, which a12-kernel-lean measured only statically and
 * explicitly asked us to close: a repeatable FULL Date target carrying `youngerThan1900Check` reads
 * `FirstFilledValue` over `../Quotes*` then `PromiseDate` from a sibling starred FULL Date source that does NOT carry the
 * check. So the pre-1900 value is admissible at the SOURCE and rejected at the TARGET — the point of the shape.
 *
 * [DateBefore1900DiffTest] already locks the check on a NONREPEATABLE target with a direct source. What is new
 * here is the target-policy-versus-source-policy split under repetition: the entry warns that collapsing target
 * rejection into source poison or clearing loses the per-instance error channel, so the arms separate
 * ERRORED-at-an-exact-address from CLEARED and from a formal source error.
 *
 * Own-domain (an insurance portfolio whose endorsement promise date is selected from the policy's quotes).
 */
class RepeatableCheckedDateTargetDiffTest {

    private companion object {
        const val FORMAT = "dd.MM.yyyy"
        const val SELECTED = "/Portfolio/Policy/Endorsements/SelectedPromise"
        const val PROMISE = "/Portfolio/Policy/Quotes/PromiseDate"
    }

    private val json: String by lazy {
        val base = DocumentModelDraft.named("promise-dates").locale("en_US")
            .rootGroup("Portfolio") { p ->
                p.group("Policy") { policy ->
                    policy.repeatable(5)
                        // the SOURCE does not carry the check: a pre-1900 promise is storable here
                        .group("Quotes") { g -> g.repeatable(5).dateField("PromiseDate") { it.format(FORMAT) } }
                        // the TARGET carries it
                        .group("Endorsements") { g ->
                            g.repeatable(5).dateField("SelectedPromise") { it.format(FORMAT).rejectBefore1900() }
                        }
                }
            }.build().toJson()
        RuntimeLaws.withComputation(base, "/Portfolio/Policy/Endorsements", "PromiseComp", "../SelectedPromise",
            "FirstFilledValue(../Quotes*/PromiseDate)")
    }

    private val def by lazy { ModelLoader.load(json) }
    private val javaEngine by lazy { RuntimeLaws.kernelJavaEngine(json) }

    private class Both(val kDoc: RuntimeLaws.Doc, val iDoc: Document) {
        fun group(path: String, vararg reps: Int): Both { kDoc.group(path, *reps); iDoc.group(path, *reps); return this }
        fun field(path: String, v: String, vararg reps: Int): Both { kDoc.field(path, v, *reps); iDoc.field(path, v, *reps); return this }
    }

    private fun agree(msg: String, expect: Map<String, String>, build: (Both) -> Unit) {
        val b = Both(RuntimeLaws.doc(), def.newDocument())
        build(b)
        val groovy = RuntimeLaws.compute(json, b.kDoc).associate { "${it.path()}@${it.pointer()}" to kSig(it) }
        val kDoc2 = RuntimeLaws.doc()
        build(Both(kDoc2, def.newDocument()))
        val javaStatic = javaEngine.compute(javaEngine.instance(kDoc2))
            .associate { "${it.path()}@${it.pointer()}" to kSig(it) }
        val interp = DmInterpreter(def).compute(b.iDoc).associate {
            "${it.path}@${formatPointer(it.path, it.indices)}" to iSig(it)
        }
        assertEquals(expect, groovy, "$msg — the kernel groovy-dynamic outcome")
        assertEquals(groovy, javaStatic, "$msg — groovy vs java-static (tri-check)")
        assertEquals(groovy, interp, "$msg — kernel vs interpreter")
    }

    private fun kSig(c: RuntimeLaws.Computed): String =
        if (c.cleared()) "CLEARED" else if (c.errored()) "ERRORED" else "VALUE|${c.value().orElse("")}"

    private fun iSig(c: io.github.mbackschat.a12.dm.interpreter.model.Computed): String = when (c.outcome) {
        ComputeOutcome.CLEARED -> "CLEARED"
        ComputeOutcome.ERRORED -> "ERRORED"
        ComputeOutcome.VALUE -> "VALUE|${c.value}"
    }

    private fun selectedAt(policy: Int, row: Int) =
        "$SELECTED@/Portfolio[1]/Policy[$policy]/Endorsements[$row]/SelectedPromise"

    /** One policy with a stale target row and one quote carrying [promise]. */
    private fun Both.policy(p: Int, stale: String?, promise: String?): Both {
        group("/Portfolio/Policy", 1, p)
        group("/Portfolio/Policy/Endorsements", 1, p, 1)
        stale?.let { field(SELECTED, it, 1, p, 1, 1) }
        group("/Portfolio/Policy/Quotes", 1, p, 1)
        promise?.let { field(PROMISE, it, 1, p, 1, 1) }
        return this
    }

    // ── the requested measurement: a pre-1900 selection against a stale CHECKED repeatable target ───────────

    @Test fun aPre1900SelectionIsErroredAtTheExactRepeatedTarget() =
        agree("the source admits 31.12.1899; the checked target rejects it per instance",
            mapOf(selectedAt(1, 1) to "ERRORED")) { d ->
            d.policy(1, stale = "01.06.2024", promise = "31.12.1899")
        }

    // ── controls ────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun anOrdinaryPost1900SelectionIsAValue() =
        agree("value control — the same shape with an admissible date computes",
            mapOf(selectedAt(1, 1) to "VALUE|15.03.2024")) { d ->
            d.policy(1, stale = "01.06.2024", promise = "15.03.2024")
        }

    @Test fun theBoundaryItselfIsAValue() =
        agree("boundary control — 01.01.1900 is admissible, so the rejection is strictly below it",
            mapOf(selectedAt(1, 1) to "VALUE|01.01.1900")) { d ->
            d.policy(1, stale = "01.06.2024", promise = "01.01.1900")
        }

    @Test fun aMalformedSourceIsSourcePoisonNotATargetRejection() =
        agree("source-poison control — a malformed quote clears rather than erroring the target",
            mapOf(selectedAt(1, 1) to "CLEARED")) { d ->
            d.policy(1, stale = "01.06.2024", promise = "31.02.2024")
        }

    /**
     * The entry asks for the attempted stored Date "if exposed", and it is — on the dynamic strategy, whose
     * ERRORED instance still carries the rendered attempt. Generated Java collapses an errored instance to a
     * bucket with no value (the same limitation [DateBefore1900DiffTest] works around), so this arm is
     * deliberately dynamic + interpreter rather than a tri-check, and says so instead of pretending otherwise.
     */
    @Test fun theRejectedTargetRetainsTheExactAttemptAndRaisesNoSourceFormalError() {
        val doc = RuntimeLaws.doc()
        val iDoc = def.newDocument()
        Both(doc, iDoc).policy(1, stale = "01.06.2024", promise = "31.12.1899")

        val detail = RuntimeLaws.computeWithFormalErrors(json, doc)
        assertEquals(
            listOf("ERRORED|31.12.1899"),
            detail.outcomes().filter { it.path() == SELECTED }
                .map { "ERRORED|${it.value().orElse("<none>")}" },
            "the checked target retains the exact rendered pre-1900 attempt",
        )
        assertEquals(
            emptyList<String>(),
            detail.formalErrorsInOperands().map { "${it.code()}|${it.field()}" },
            "the SOURCE is unchecked, so a target-policy rejection raises no formal operand error — the "
                + "distinction SPEC-2026-08-27-02 asks not to collapse",
        )
        assertEquals(
            listOf("ERRORED|31.12.1899"),
            DmInterpreter(def).compute(iDoc).filter { it.path == SELECTED }
                .map { "${it.outcome}|${it.value}" },
            "the interpreter retains the same attempt",
        )
    }

    @Test fun aRejectedTargetInOnePolicyLeavesTheOtherAlone() =
        agree("policy 1 is rejected, policy 2 still computes — the rejection is per instance",
            mapOf(selectedAt(1, 1) to "ERRORED", selectedAt(2, 1) to "VALUE|15.03.2024")) { d ->
            d.policy(1, stale = "01.06.2024", promise = "31.12.1899")
            d.policy(2, stale = "01.06.2024", promise = "15.03.2024")
        }
}
