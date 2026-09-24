package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
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
 * **Every arm runs on BOTH declared Date formats.** The entry states its delta for a FULL **ISO** profile, and the
 * peer's own ISO probe reached the two kernel strategies but not our interpreter; our first pass measured the
 * dotted profile only. Rather than reason about whether the declared format can matter — it decides the rendering
 * of the retained attempt, so it demonstrably can — each arm is measured twice, once per declaration.
 *
 * Own-domain (an insurance portfolio whose endorsement promise date is selected from the policy's quotes).
 */
class RepeatableCheckedDateTargetDiffTest {

    /** The dates one arm needs, spelled in the declaring shape's own format. */
    private class Dates(
        val stale: String,
        val pre1900: String,
        val ordinary: String,
        val boundary: String,
        val malformed: String,
    )

    /**
     * The shape declared at one Date format. Codegen, the in-memory Java compile and the model load are held here
     * and reached through the lazy CLASS fields below, so a further arm costs one document rather than another
     * kernel compile — this class shares its Gradle task with timing floors.
     */
    private class Shape(private val name: String, val format: String, val dates: Dates) {

        val json: String by lazy {
            val base = DocumentModelDraft.named(name).locale("en_US")
                .rootGroup("Portfolio") { p ->
                    p.group("Policy") { policy ->
                        policy.repeatable(5)
                            // the SOURCE does not carry the check: a pre-1900 promise is storable here
                            .group("Quotes") { g -> g.repeatable(5).dateField("PromiseDate") { it.format(format) } }
                            // the TARGET carries it
                            .group("Endorsements") { g ->
                                g.repeatable(5).dateField("SelectedPromise") { it.format(format).rejectBefore1900() }
                            }
                    }
                }.build().toJson()
            RuntimeLaws.withComputation(base, "/Portfolio/Policy/Endorsements", "PromiseComp", "../SelectedPromise",
                "FirstFilledValue(../Quotes*/PromiseDate)")
        }

        val def by lazy { ModelLoader.load(json) }
        val javaEngine by lazy { RuntimeLaws.kernelJavaEngine(json) }
    }

    private companion object {
        const val SELECTED = "/Portfolio/Policy/Endorsements/SelectedPromise"
        const val PROMISE = "/Portfolio/Policy/Quotes/PromiseDate"

        /** The profile our first pass measured and the handback published. */
        val DOTTED = Shape(
            "promise-dates-dotted", "dd.MM.yyyy",
            Dates(
                stale = "01.06.2024", pre1900 = "31.12.1899", ordinary = "15.03.2024",
                boundary = "01.01.1900", malformed = "31.02.2024",
            ),
        )

        /** The profile SPEC-2026-08-27-02 states its delta for. */
        val ISO = Shape(
            "promise-dates-iso", "yyyy-MM-dd",
            Dates(
                stale = "2024-06-01", pre1900 = "1899-12-31", ordinary = "2024-03-15",
                boundary = "1900-01-01", malformed = "2024-02-31",
            ),
        )
    }

    /** Run one arm against both declarations, so no arm rests on the format it happened to be written in. */
    private fun onBothDeclarations(arm: (Shape) -> Unit) {
        arm(DOTTED)
        arm(ISO)
    }

    private fun agree(shape: Shape, msg: String, expect: Map<String, String>, build: (Both) -> Unit) {
        val b = Both(RuntimeLaws.doc(), shape.def.newDocument())
        build(b)
        val groovy = RuntimeLaws.compute(shape.json, b.kDoc).associate { "${it.path()}@${it.pointer()}" to kSig(it) }
        val kDoc2 = RuntimeLaws.doc()
        build(Both(kDoc2, shape.def.newDocument()))
        val javaStatic = shape.javaEngine.compute(shape.javaEngine.instance(kDoc2))
            .associate { "${it.path()}@${it.pointer()}" to kSig(it) }
        val interp = DmInterpreter(shape.def).compute(b.iDoc).associate {
            "${it.path}@${formatPointer(it.path, it.indices)}" to iSig(it)
        }
        assertEquals(expect, groovy, "[${shape.format}] $msg — the kernel groovy-dynamic outcome")
        assertEquals(groovy, javaStatic, "[${shape.format}] $msg — groovy vs java-static (tri-check)")
        assertEquals(groovy, interp, "[${shape.format}] $msg — kernel vs interpreter")
    }

    private fun kSig(c: RuntimeLaws.Computed): String =
        if (c.cleared()) "CLEARED" else if (c.errored()) "ERRORED" else "VALUE|${c.value().orElse("")}"

    /**
     * [kSig] drops the value of a non-VALUE bucket, which is exactly what the retained-attempt arm needs to keep —
     * and it must keep the BUCKET too: hard-coding an `ERRORED|` prefix there once let a CLEARED outcome render as
     * `ERRORED|<none>`, so only the value half of that arm discriminated (found by a planted mutant, 2026-08-28).
     */
    private fun kBucketAndAttempt(c: RuntimeLaws.Computed): String {
        val bucket = if (c.cleared()) "CLEARED" else if (c.errored()) "ERRORED" else "VALUE"
        return "$bucket|${c.value().orElse("<none>")}"
    }

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

    @Test fun aPre1900SelectionIsErroredAtTheExactRepeatedTarget() = onBothDeclarations { s ->
        agree(s, "the source admits ${s.dates.pre1900}; the checked target rejects it per instance",
            mapOf(selectedAt(1, 1) to "ERRORED")) { d ->
            d.policy(1, stale = s.dates.stale, promise = s.dates.pre1900)
        }
    }

    // ── controls ────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun anOrdinaryPost1900SelectionIsAValue() = onBothDeclarations { s ->
        agree(s, "value control — the same shape with an admissible date computes",
            mapOf(selectedAt(1, 1) to "VALUE|${s.dates.ordinary}")) { d ->
            d.policy(1, stale = s.dates.stale, promise = s.dates.ordinary)
        }
    }

    @Test fun theBoundaryItselfIsAValue() = onBothDeclarations { s ->
        agree(s, "boundary control — ${s.dates.boundary} is admissible, so the rejection is strictly below it",
            mapOf(selectedAt(1, 1) to "VALUE|${s.dates.boundary}")) { d ->
            d.policy(1, stale = s.dates.stale, promise = s.dates.boundary)
        }
    }

    @Test fun aMalformedSourceIsSourcePoisonNotATargetRejection() = onBothDeclarations { s ->
        agree(s, "source-poison control — a malformed quote clears rather than erroring the target",
            mapOf(selectedAt(1, 1) to "CLEARED")) { d ->
            d.policy(1, stale = s.dates.stale, promise = s.dates.malformed)
        }
    }

    /**
     * The entry asks for the attempted stored Date "if exposed", and it is — on the dynamic strategy, whose
     * ERRORED instance still carries the rendered attempt, **rendered in the target's own declared format**
     * (source and target declare the same format here, so the attempt is the source text verbatim; this is the
     * one observable in the matrix the declaration actually moves). Generated Java collapses an errored instance
     * to a bucket with no value (the same limitation [DateBefore1900DiffTest] works around), so this arm is
     * deliberately dynamic + interpreter rather than a tri-check, and says so instead of pretending otherwise.
     */
    @Test fun theRejectedTargetRetainsTheExactAttemptAndRaisesNoSourceFormalError() = onBothDeclarations { s ->
        val doc = RuntimeLaws.doc()
        val iDoc = s.def.newDocument()
        Both(doc, iDoc).policy(1, stale = s.dates.stale, promise = s.dates.pre1900)

        val detail = RuntimeLaws.computeWithFormalErrors(s.json, doc)
        assertEquals(
            listOf("ERRORED|${s.dates.pre1900}"),
            detail.outcomes().filter { it.path() == SELECTED }
                .map { kBucketAndAttempt(it) },
            "[${s.format}] the checked target retains the exact rendered pre-1900 attempt",
        )
        assertEquals(
            emptyList<String>(),
            detail.formalErrorsInOperands().map { "${it.code()}|${it.field()}" },
            "[${s.format}] the SOURCE is unchecked, so a target-policy rejection raises no formal operand error "
                + "— the distinction SPEC-2026-08-27-02 asks not to collapse",
        )
        assertEquals(
            listOf("ERRORED|${s.dates.pre1900}"),
            DmInterpreter(s.def).compute(iDoc).filter { it.path == SELECTED }
                .map { "${it.outcome}|${it.value}" },
            "[${s.format}] the interpreter retains the same attempt",
        )
    }

    @Test fun aRejectedTargetInOnePolicyLeavesTheOtherAlone() = onBothDeclarations { s ->
        agree(s, "policy 1 is rejected, policy 2 still computes — the rejection is per instance",
            mapOf(selectedAt(1, 1) to "ERRORED", selectedAt(2, 1) to "VALUE|${s.dates.ordinary}")) { d ->
            d.policy(1, stale = s.dates.stale, promise = s.dates.pre1900)
            d.policy(2, stale = s.dates.stale, promise = s.dates.ordinary)
        }
    }
}
