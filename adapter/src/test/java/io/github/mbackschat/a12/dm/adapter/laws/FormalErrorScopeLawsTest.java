package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;

/**
 * Behavioral law — <b>formal-error suppression is branch-scoped, not rule-scoped</b>
 * (RT-SEMANTICS-LEDGER: {@code MainValidatorController}; refines the §3 finding — probe-corrected:
 * the naive reading of KERNEL-SEMANTICS §1, "a field in the unknown state removes the rules over it
 * from evaluation", expected rule-scoped suppression).
 *
 * <p>What actually happens: the unknown state makes the affected <em>comparison</em> evaluate to
 * false-or-unknown, and the ordinary {@code And}/{@code Or} three-state algebra decides the rule:
 *
 * <ul>
 *   <li>{@code healthyTrue Or brokenBranch} — <b>fires</b>: the healthy branch alone convicts; the
 *       formal error does not take the whole rule out;</li>
 *   <li>{@code healthyTrue And brokenBranch} — does <b>not</b> fire: the unknown branch absorbs the
 *       conjunction (this is the case the §3 "silently suppresses" finding observed — with a single
 *       needed branch, branch-scoped and rule-scoped look identical).</li>
 * </ul>
 */
class FormalErrorScopeLawsTest {

    private static final String ORDER = RuntimeLaws.model("models/order.dm.json");
    private static final Set<String> ORDER_ROW = Set.of("/Order");

    // Conditions are authored with the rulekit typed DSL (m) and rendered to the canonical string fed
    // to the kernel — no free-text condition literals. The kernel-law assertion is the safety net.
    private static final BoundModel m = BoundModel.ofJson(ORDER);

    private static String render(Condition c) {
        return RuntimeLaws.render(c);
    }

    @Test
    void anOrFiresViaItsHealthyBranchDespiteAFormalErrorInTheOther() {
        String model = RuntimeLaws.withRule(ORDER, "/Order", "QtyOrBackorder", "../Quantity", "QTY_OR_BACK",
                render(Dsl.fieldNotFilled(m.field("/Order/Quantity"))
                        .or(m.num("/Order/BackorderQuantity").gt(0))));

        // baseline: healthy second branch → fires via FieldNotFilled(Quantity)
        assertTrue(RuntimeLaws.fires(model, "QTY_OR_BACK", ORDER_ROW, Set.of("/Order/Quantity"),
                Map.of("/Order/CustomerName", "Acme", "/Order/BackorderQuantity", "0")),
                "FieldNotFilled(Quantity) holds → the Or fires");
        // the discriminating case: BackorderQuantity = "abc" (formal error) — STILL fires
        assertTrue(RuntimeLaws.fires(model, "QTY_OR_BACK", ORDER_ROW, Set.of("/Order/Quantity"),
                Map.of("/Order/CustomerName", "Acme", "/Order/BackorderQuantity", "abc")),
                "the formal error only unknowns its own branch — the healthy Or branch still convicts");
    }

    @Test
    void anAndIsAbsorbedByItsBrokenBranch() {
        String model = RuntimeLaws.withRule(ORDER, "/Order", "QtyAndBackorder", "../Quantity", "QTY_AND_BACK",
                render(Dsl.fieldNotFilled(m.field("/Order/Quantity"))
                        .and(m.num("/Order/BackorderQuantity").gt(0))));

        // baseline: healthy second branch (5 > 0) → fires
        assertTrue(RuntimeLaws.fires(model, "QTY_AND_BACK", ORDER_ROW, Set.of("/Order/Quantity"),
                Map.of("/Order/CustomerName", "Acme", "/Order/BackorderQuantity", "5")),
                "both branches hold → the And fires");
        // broken second branch → unknown absorbs the conjunction → no fire
        assertFalse(RuntimeLaws.fires(model, "QTY_AND_BACK", ORDER_ROW, Set.of("/Order/Quantity"),
                Map.of("/Order/CustomerName", "Acme", "/Order/BackorderQuantity", "abc")),
                "the unknown branch absorbs the And — this is the §3 'silent suppression' face");
    }
}
