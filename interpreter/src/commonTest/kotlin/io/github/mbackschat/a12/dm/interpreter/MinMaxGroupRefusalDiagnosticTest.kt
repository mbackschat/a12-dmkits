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
 * JVM + JS lock for the **CORRECTIVE the `Min`/`Max` group refusal carries**, which is a separate claim from the
 * one [KernelRefusedShapeReachabilityTest] pins.
 *
 * <p>That file asserts the exception TYPE and says so explicitly — "deliberately about the exception TYPE and not
 * about a message" — because its subject is the degrade net. It is therefore blind to this defect by design: a
 * refusal that throws the WRONG `UnsupportedConstructException` still degrades, so every row there stays green.
 *
 * **The defect this file was written to expose.** `refuseGroupOperandUnderMinMax` builds its message with
 * `pathOf(group)`, and `pathOf` **rejects an `Expr.Star` outright** — it exists for plain field refs and throws
 * "unsupported operand Star in a field-reference position" for anything else. So for the STARRED spelling the
 * corrective never renders: the author who typed `Min([Payouts*], 5)` into `rule eval --condition` is told about
 * an internal operand position instead of being told that `Min` takes no group and that `MinValue` is the
 * operator they wanted. The kernel refuses both spellings identically (`MVK_NO_GROUPS_ALLOWED`, `KF187`), so the
 * two are equally reachable through the no-pre-flight channels that file enumerates.
 *
 * This is the same root the `EntitySource.path` hoist closed elsewhere: a site that needed "the path of whichever
 * operand shape this is" reached for the accessor that only answers for one of them.
 */
class MinMaxGroupRefusalDiagnosticTest {

    private val model = ModelDef(
        fields = listOf(
            FieldDef(ANCHOR, Kind.STRING),
            FieldDef(GROSS, Kind.NUMBER),
            FieldDef(DEDUCTIBLE, Kind.NUMBER),
        ),
        rules = emptyList(),
    )

    /** The message of the [UnsupportedConstructException] [condition] raises, or a description of what else happened. */
    private fun refusalMessage(condition: String): String {
        val doc = model.newDocument().field(ANCHOR, "present", 1, 1)
            .field(GROSS, "3", 1, 1, 1).field(DEDUCTIBLE, "4", 1, 1)
        val ctx = doc.contextsFor(ROOT).single()
        return try {
            Interpreter(ctx).firedType(parseCondition(condition, ROOT) as Cond)
            "NO FAILURE — the shape was evaluated rather than refused"
        } catch (e: UnsupportedConstructException) {
            e.message ?: "<no message>"
        } catch (e: Throwable) {
            "ESCAPED as ${e::class.simpleName}"
        }
    }

    /**
     * The UNSTARRED spelling, which already worked — carried as the positive control. Without it, a fix that
     * broke both spellings equally would still satisfy the starred row below by making them consistently bad.
     */
    @Test
    fun theUnstarredGroupRefusalNamesTheGroupAndTheAlternative() {
        val message = refusalMessage("Min([$PAYOUTS], 5) > 0")
        assertTrue(PAYOUTS in message, "the refusal did not name the offending group: $message")
        assertTrue("MinValue" in message, "the refusal did not name the operator to use instead: $message")
    }

    /** **THE DISCRIMINATOR** — the starred spelling, where `pathOf` used to throw over the corrective. */
    @Test
    fun theStarredGroupRefusalAlsoNamesTheGroupAndTheAlternative() {
        val message = refusalMessage("Min([$PAYOUTS_STAR], 5) > 0")
        assertTrue(PAYOUTS in message,
            "the STARRED group's refusal does not name the offending group, so the author gets an internal " +
                "operand-position message instead of the corrective: $message")
        assertTrue("MinValue" in message,
            "the STARRED group's refusal does not name the operator to use instead: $message")
    }

    /** `Max` shares the refusal, so it shares the defect and must share the fix. */
    @Test
    fun theSameHoldsForMax() {
        val message = refusalMessage("Max([$PAYOUTS_STAR], 5) > 0")
        assertTrue(PAYOUTS in message, "Max's starred-group refusal does not name the group: $message")
    }

    private companion object {
        const val ROOT = "/Claim"
        const val ANCHOR = "/Claim/Anchor"
        const val DEDUCTIBLE = "/Claim/Deductible"
        const val PAYOUTS = "/Claim/Payouts"
        const val PAYOUTS_STAR = "/Claim/Payouts*"
        const val GROSS = "/Claim/Payouts/Gross"
    }
}
