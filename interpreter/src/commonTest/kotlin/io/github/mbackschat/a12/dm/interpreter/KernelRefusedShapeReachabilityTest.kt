package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.eval.Interpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * JVM + JS lock for **what happens when a shape the KERNEL refuses arrives anyway** — the disposition a cold
 * review demanded for two closures made on 2026-08-14.
 *
 * **The closures, and the gap in them.** `Min`/`Max` refuse a group operand and `DateRangesOverlap` refuses one
 * (`MVK_NO_GROUPS_ALLOWED`, [KF187](../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf187)), which was used to
 * close two candidate defects as unreachable. The review's objection is exact: a kernel refusal bounds what a
 * **stored, validated** model can contain and nothing more. Three channels bypass it —
 *
 * 1. `Expr` is a public sealed interface with public data classes, so a library consumer builds any tree it likes;
 * 2. this module's parser accepts a bare path as a function argument, so the refused spelling PARSES here;
 * 3. `dmtool rule eval --condition` evaluates an arbitrary non-stored condition with **no MVK pre-flight at all**,
 *    on EITHER artifact, because `RuleEvalCommand` reaches only `InterpreterEngine` and names no
 *    `ConsistencyOracle`, `checkConsistency` or `RuleValidator`.
 *
 *    *(Corrected 2026-09-16: this read "and the native binary carries no kernel to pre-flight with", which is
 *    false and also narrowed a bypass that holds on both artifacts. The native image's committed reachability
 *    metadata registers ~400 `com.mgmtp.a12.kernel` classes — a closed-world image registers only classes it
 *    contains — and `Cli.JVM_ONLY_VERBS` is empty, so it serves `model check --engine kernel` itself. What
 *    makes the eval path MVK-free is that it is the interpreter's path, not a missing kernel.)*
 *
 * — and the same finding that keeps the `bindDepth` normalizer as "defensive hardening for hand-built AST" cannot
 * consistently use the mirror argument to skip work in the same defect class.
 *
 * **What this file therefore pins is a CONTRACT, not an absence: the failure must be a DEGRADE, never a crash.**
 * `UnsupportedConstructException` is the one exception the evaluator's degrade net catches, so it fails that rule
 * alone and leaves the rest of the model evaluating. Any other exception type escapes and takes the whole
 * evaluation down — which is exactly how two crash-class defects reached a legal document earlier the same day
 * ([IF287](../../../../../../../../../../docs/INTERPRETER-FINDINGS.md#if287)'s tenth and fifteenth instances). So the
 * assertion is deliberately about the exception TYPE and not about a message or a value.
 */
class KernelRefusedShapeReachabilityTest {

    private val model = ModelDef(
        fields = listOf(
            FieldDef(ANCHOR, Kind.STRING),
            FieldDef(FIRST, Kind.NUMBER),
            FieldDef(SECOND, Kind.NUMBER),
            FieldDef(REPORTED, Kind.DATE),
            FieldDef(SETTLED, Kind.DATE),
        ),
        rules = emptyList(),
    )

    /** Evaluate [condition] against a one-row document and report how it failed, or that it did not. */
    private fun outcome(condition: String): String {
        val doc = model.newDocument().field(ANCHOR, "present", 1, 1)
            .field(FIRST, "3", 1, 1, 1).field(SECOND, "4", 1, 1, 1)
            .field(REPORTED, "2026-06-01", 1, 1, 1).field(SETTLED, "2026-06-05", 1, 1, 1)
        val ctx = doc.contextsFor(ROOT).single()
        return try {
            val parsed = parseCondition(condition, ROOT)
            Interpreter(ctx).firedType(parsed as Cond)
            "NO FAILURE"
        } catch (e: UnsupportedConstructException) {
            "DEGRADE"
        } catch (e: Throwable) {
            "ESCAPES: ${e::class.simpleName}: ${e.message}"
        }
    }

    /**
     * The operand-list `Min` over a GROUP, which the kernel refuses. It must not escape as an arbitrary exception:
     * either the evaluator handles it, or it degrades this one rule.
     */
    @Test fun theOperandListMinOverAGroupDoesNotCrashTheEvaluator() = assertDegradesOrWorks(
        "Min([$BILLING], 5) > 0", "the operand-list Min over a bracketed group")

    /** The same shape in the BARE spelling, which is the one the review pointed at as parsing here. */
    @Test fun theBareSpellingOfTheSameShapeDoesNotCrashTheEvaluator() = assertDegradesOrWorks(
        "Min($BILLING, 5) > 0", "the operand-list Min over a bare group path")

    /**
     * **The DATE-group spelling of the same shape, and the row a cold review had to add.** The refusal in
     * `minMaxOperandVals` is only reached on the NUMERIC branch: `FunctionOp.MIN` dispatches
     * `if (dateAggregate(f)) evalTemporal(f) else combine(minMaxOperandVals(f))`, and the same session's fix that
     * made `dateAggregate` resolve a GROUP's kind through its contained fields therefore routes a group of DATE
     * fields *past* the refusal into `evalTemporal`, where reading a value at the group path crashes. Two fixes
     * inside one slice interacting, with the later one's evidence built on the earlier one's blind spot — which is
     * why the original fixture (two NUMBER fields) could not see it.
     */
    @Test fun theSameShapeOverADATEGroupDoesNotCrashTheEvaluator() = assertDegradesOrWorks(
        "Min([$MILESTONES], 5) > 0", "the operand-list Min over a bracketed DATE group")

    /** …and its bare spelling, since the two reach `evalExpr` by different routes. */
    @Test fun theBareDATEGroupSpellingDoesNotCrashTheEvaluator() = assertDegradesOrWorks(
        "Min($MILESTONES, 5) > 0", "the operand-list Min over a bare DATE group path")

    /** `DateRangesOverlap` over a group — the other closure, and the arm whose sibling crashed once already. */
    @Test fun dateRangesOverlapOverAGroupDoesNotCrashTheEvaluator() = assertDegradesOrWorks(
        "DateRangesOverlap($BILLING)", "DateRangesOverlap over a group")

    /**
     * The CONTROL that keeps the three rows above honest: a legal condition on the same fixture must evaluate. Without
     * it, "does not crash" would also be satisfied by an evaluator that degraded absolutely everything.
     */
    @Test fun aLegalConditionOnTheSameFixtureStillEvaluates() {
        val result = outcome("FieldFilled($ANCHOR)")
        assertTrue(result == "NO FAILURE", "the control condition did not evaluate: $result")
    }

    private fun assertDegradesOrWorks(condition: String, what: String) {
        val result = outcome(condition)
        assertTrue(
            result == "DEGRADE" || result == "NO FAILURE",
            "$what escaped the degrade net, so one refused shape typed into `rule eval --condition` would take " +
                "the WHOLE evaluation down rather than failing its own rule — $result",
        )
    }

    private companion object {
        const val ROOT = "/Order"
        const val ANCHOR = "/Order/Anchor"
        const val BILLING = "/Order/Billing"
        const val FIRST = "/Order/Billing/First"
        const val SECOND = "/Order/Billing/Second"
        const val MILESTONES = "/Order/Milestones"
        const val REPORTED = "/Order/Milestones/ReportedOn"
        const val SETTLED = "/Order/Milestones/SettledOn"
    }
}
