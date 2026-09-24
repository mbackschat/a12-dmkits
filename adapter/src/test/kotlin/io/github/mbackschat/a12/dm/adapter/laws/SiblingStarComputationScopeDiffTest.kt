package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — a computation into a REPEATABLE target may consume a STARRED field under a SIBLING group when
 * both sit below the same enclosing repeatable parent, and the starred source binds to the target row's OWN
 * parent (SPEC-2026-08-27-01, reconciled from a12-kernel-lean).
 *
 * Why this needed its own class: [ImplicitComputationValidationScopeDiffTest] locks that a repeatable target is
 * codegen-rejected `MVK_ERROR_FIELD_NOT_IN_RULEGROUP`, and the prose called that shape "cross-group" — which
 * reads as "the operand crosses groups" but MEASURES "the computation is DECLARED outside its target's group"
 * (its `capacityModel` declares in `/HrRules` and targets `/Depots/Depot/Capacity`). The variable that decides
 * is the declaration relation, not the operand's: declared at its target's own parent, the sibling-star operand
 * is admitted, measured 2026-08-28 for scale-0 Number, complete DateTime, whole-second Time and one DateRange
 * profile. That placement is one admitted choice of several rather than the only one — the gate is containment,
 * so any ancestor of the target is admitted too, and `dmtool` authors any of them since the computation spec
 * gained its own `group` key (corrected 2026-08-29 from a12-kernel-lean's `SPEC-2026-08-29-02`; the accept side
 * is `validate.laws.ComputationDeclaringGroupLawsTest`). The runtime arms below are unaffected: they measure
 * where the star BINDS, which this fixture holds fixed at the target's own parent.
 *
 * The runtime arms separate PARENT-LOCAL correlation from a global scan: two parents each carrying their own
 * source extents, so a global scan would leak parent 1's value into parent 2's target rows. They also separate
 * exact target identity from a scalar result (two target rows per parent), authored extent order from row
 * zipping (the first extent is exhausted before the second is entered), a hidden malformed tail from a reached
 * one, and a bounded-target rejection from a clear.
 *
 * Both kernel strategies plus the interpreter must agree; codegen and the compiled engine are hoisted to lazy
 * fields because this class shares `:adapter:test` with timing floors.
 *
 * Own-domain (an insurance portfolio whose endorsement premium is selected from the policy's quote extents).
 */
class SiblingStarComputationScopeDiffTest {

    private companion object {
        const val SELECTED = "/Portfolio/Policy/Endorsements/SelectedPremium"
        const val CAPPED = "/Portfolio/Policy/Capped/CappedPremium"
    }

    private val base = DocumentModelDraft.named("portfolio").locale("en_US")
        .rootGroup("Portfolio") { p ->
            p.group("Policy") { policy ->
                policy.repeatable(5)
                    // the two authored source extents, each its own sibling repetition list
                    .group("Primary") { g -> g.repeatable(5).numberField("Candidate") { it.fractionDigits(0) } }
                    .group("Secondary") { g -> g.repeatable(5).numberField("Fallback") { it.fractionDigits(0) } }
                    // the target extents: one unbounded, one bounded at 9 for the target-rejection arm
                    .group("Endorsements") { g -> g.repeatable(5).numberField("SelectedPremium") { it.fractionDigits(0) } }
                    .group("Capped") { g -> g.repeatable(5).numberField("CappedPremium") { it.fractionDigits(0).max("9") } }
            }
        }.build().toJson()

    /**
     * Both computations are declared in their OWN target's parent group — the placement under measurement.
     * `computedFieldRelPath` resolves against the COMPUTATION ELEMENT (`<group>/<name>`), so reaching a field of
     * the declaring group takes one `..`; the operation's own paths resolve against the rule group instead.
     */
    private val model: String by lazy {
        RuntimeLaws.withComputation(
            RuntimeLaws.withComputation(base, "/Portfolio/Policy/Endorsements", "SelectedComp", "../SelectedPremium",
                "FirstFilledValue(../Primary*/Candidate, ../Secondary*/Fallback)"),
            "/Portfolio/Policy/Capped", "CappedComp", "../CappedPremium",
            "FirstFilledValue(../Primary*/Candidate)")
    }

    private val def by lazy { ModelLoader.load(model) }
    private val javaEngine by lazy { RuntimeLaws.kernelJavaEngine(model) }

    private fun agree(msg: String, expect: Map<String, String>, build: (Both) -> Unit) {
        val b = Both(RuntimeLaws.doc(), def.newDocument())
        build(b)
        val groovy = RuntimeLaws.compute(model, b.kDoc).associate { "${it.path()}@${it.pointer()}" to kSig(it) }
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

    private fun selectedAt(policy: Int, row: Int) = "$SELECTED@/Portfolio[1]/Policy[$policy]/Endorsements[$row]/SelectedPremium"
    private fun cappedAt(policy: Int, row: Int) = "$CAPPED@/Portfolio[1]/Policy[$policy]/Capped[$row]/CappedPremium"

    /** Seed policy [p] with [endorsements] target rows (each carrying [stale]) and no Capped rows. */
    private fun Both.policy(p: Int, endorsements: Int, stale: String? = null): Both {
        group("/Portfolio/Policy", 1, p)
        for (r in 1..endorsements) {
            group("/Portfolio/Policy/Endorsements", 1, p, r)
            stale?.let { field(SELECTED, it, 1, p, r, 1) }
        }
        return this
    }

    private fun Both.primary(p: Int, row: Int, candidate: String?): Both {
        group("/Portfolio/Policy/Primary", 1, p, row)
        candidate?.let { field("/Portfolio/Policy/Primary/Candidate", it, 1, p, row, 1) }
        return this
    }

    private fun Both.secondary(p: Int, row: Int, fallback: String?): Both {
        group("/Portfolio/Policy/Secondary", 1, p, row)
        fallback?.let { field("/Portfolio/Policy/Secondary/Fallback", it, 1, p, row, 1) }
        return this
    }

    // ── the load-bearing arm: the starred source is PARENT-LOCAL, not a global scan ────────────────────────

    @Test fun eachTargetRowSelectsFromItsOwnParentsExtent() =
        agree("policy 1 selects 100, policy 2 selects 200 — a global scan would give both 100",
            mapOf(selectedAt(1, 1) to "VALUE|100", selectedAt(2, 1) to "VALUE|200")) { d ->
            d.policy(1, 1).primary(1, 1, "100")
            d.policy(2, 1).primary(2, 1, "200")
        }

    @Test fun twoTargetRowsUnderOneParentShareThatParentsResult() =
        agree("both endorsement rows of policy 1 select 100, each at its OWN address",
            mapOf(selectedAt(1, 1) to "VALUE|100", selectedAt(1, 2) to "VALUE|100")) { d ->
            d.policy(1, 2).primary(1, 1, "100")
        }

    // ── authored extent ORDER: the first extent is exhausted before the second is entered ──────────────────

    @Test fun anEmptyFirstExtentReachesTheSecond() =
        agree("Primary has an empty row → the selection falls through to Secondary's 7",
            mapOf(selectedAt(1, 1) to "VALUE|7")) { d ->
            d.policy(1, 1).primary(1, 1, null).secondary(1, 1, "7")
        }

    @Test fun theCompleteFirstExtentIsExhaustedBeforeTheSecond() =
        agree("Primary row 2 carries 5 while Secondary row 1 carries 7 → 5 wins (not row-zipped)",
            mapOf(selectedAt(1, 1) to "VALUE|5")) { d ->
            d.policy(1, 1).primary(1, 1, null).primary(1, 2, "5").secondary(1, 1, "7")
        }

    @Test fun bothEmptyExtentsComputeTheEmptySelectionZero() =
        agree("no filled source cell anywhere in either extent → the empty-selection identity 0",
            mapOf(selectedAt(1, 1) to "VALUE|0")) { d ->
            d.policy(1, 1, stale = "3").primary(1, 1, null).secondary(1, 1, null)
        }

    // ── exhaustion and placement: a policy with no source rows clears only its own stale target ────────────

    /** Number carries the empty-selection identity 0, so exhaustion OVERWRITES a stale target rather than
     *  clearing it. That bounds SPEC-2026-08-27-01's "clears only source-filled targets on exhaustion" to a
     *  carrier this arm does not exercise; WHICH carriers clear is unmeasured here, so read this as a limit on
     *  the Number row and not as a claim about the temporal ones. Independence is the second half of the lock:
     *  policy 1's value must not follow policy 2's exhaustion. */
    @Test fun anExhaustedParentOverwritesItsOwnStaleTargetAndLeavesTheOtherAlone() =
        agree("policy 2 has no source rows → its stale 42 becomes the identity 0; policy 1 still selects 100",
            mapOf(selectedAt(1, 1) to "VALUE|100", selectedAt(2, 1) to "VALUE|0")) { d ->
            d.policy(1, 1).primary(1, 1, "100")
            d.policy(2, 1, stale = "42")
        }

    @Test fun noPhysicalTargetRowProducesNoOutcome() =
        agree("policy 1 has a source but NO endorsement row → no target outcome is invented", emptyMap()) { d ->
            d.group("/Portfolio/Policy", 1, 1).primary(1, 1, "100")
        }

    // ── the GENERATED rule: it anchors at the exact repeated target address ───────────────────────────────

    /** The implicit validation compares each target row against its own parent's selection, so its error
     *  entity is the exact repeated address — not the group and not a single scalar. */
    private fun fires(msg: String, expect: List<String>, build: (Both) -> Unit) {
        val b = Both(RuntimeLaws.doc(), def.newDocument())
        build(b)
        val groovy = RuntimeLaws.messages(model, b.kDoc).filter { it.code() == "SelectedComp" }
            .map { "${it.field()}|${it.type().name}" }.sorted()
        val kDoc2 = RuntimeLaws.doc()
        build(Both(kDoc2, def.newDocument()))
        val javaStatic = javaEngine.messagesModern(kDoc2, null, java.util.Locale.US)
            .filter { it.code() == "SelectedComp" }.map { "${it.field()}|${it.type().name}" }.sorted()
        val interp = DmInterpreter(def).validateFull(b.iDoc).filter { it.code == "SelectedComp" }
            .map { "${it.errorPath}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" }
            .sorted()
        assertEquals(expect.sorted(), groovy, "$msg — the kernel groovy-dynamic fire set")
        assertEquals(groovy, javaStatic, "$msg — groovy vs java-static (tri-check)")
        assertEquals(groovy, interp, "$msg — kernel vs interpreter")
    }

    @Test fun theGeneratedRuleFiresAtTheExactStaleTargetRow() =
        fires("row 1 agrees with the selected 5 and is silent; row 2's stale 9 fires at ITS OWN address",
            expect = listOf("/Portfolio[1]/Policy[1]/Endorsements[2]/SelectedPremium|OMISSION_ERROR")) { d ->
            d.policy(1, 2)
            d.field(SELECTED, "5", 1, 1, 1, 1)
            d.field(SELECTED, "9", 1, 1, 2, 1)
            d.primary(1, 1, null).primary(1, 2, "5")
        }

    /** The polarity control, and the reason the arm above seeds a stale value rather than leaving the row empty:
     *  an EMPTY target row against a computed selection reports nothing, so "fires at the stale row" would pass
     *  vacuously on a build where the rule never fired at all. */
    @Test fun anEmptyTargetRowAgainstAComputedSelectionIsSilent() =
        fires("row 2 is empty rather than stale → the generated comparison stays silent", expect = emptyList()) { d ->
            d.policy(1, 2)
            d.field(SELECTED, "5", 1, 1, 1, 1)
            d.primary(1, 1, null).primary(1, 2, "5")
        }

    // ── the bounded target: a selected value the target cannot hold is an ERROR, not a clear ───────────────

    // ── malformed source cells: hidden BEHIND a selection, reached BEFORE one ─────────────────────────────

    @Test fun aMalformedTailAfterTheSelectionIsHidden() =
        agree("Primary row 1 selects 5, so row 2's malformed 1.5 is never reached",
            mapOf(selectedAt(1, 1) to "VALUE|5")) { d ->
            d.policy(1, 1).primary(1, 1, "5").primary(1, 2, "1.5")
        }

    @Test fun aMalformedTailInTheSECONDExtentIsAlsoHiddenByAFirstExtentValue() =
        agree("Primary's 5 is selected, so Secondary's malformed 2.5 is never reached",
            mapOf(selectedAt(1, 1) to "VALUE|5")) { d ->
            d.policy(1, 1).primary(1, 1, "5").secondary(1, 1, "2.5")
        }

    @Test fun aMalformedPrefixReachedBeforeAnyValuePoisonsTheTarget() =
        agree("Primary row 1 is malformed and reached first → the target takes no value from row 2's 5",
            mapOf(selectedAt(1, 1) to "CLEARED")) { d ->
            d.policy(1, 1, stale = "3").primary(1, 1, "1.5").primary(1, 2, "5")
        }

    @Test fun aSelectedValueOverTheTargetsMaximumIsErroredNotCleared() =
        agree("Capped's max is 9 and the selection is 12 → the target-local rejection is ERRORED",
            mapOf(selectedAt(1, 1) to "VALUE|12", cappedAt(1, 1) to "ERRORED")) { d ->
            d.policy(1, 1).primary(1, 1, "12")
            d.group("/Portfolio/Policy/Capped", 1, 1, 1)
        }
}
