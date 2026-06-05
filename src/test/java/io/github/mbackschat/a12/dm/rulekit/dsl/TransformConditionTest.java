package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@link Condition#transform} — the in-session modify capability (#1, proposal §12): a bottom-up
 * rewrite over the immutable sealed tree. Untouched subtrees keep reference identity (N5's cheap
 * modify), and the rewriter sees already-rewritten children (bottom-up order).
 */
class TransformConditionTest {

    private static final FieldRef FEE = new FieldRef("/Subscription/Addons/MonthlyFee", ValueKind.NUMBER);
    private static final FieldRef TIER = new FieldRef("/Subscription/Tier", ValueKind.ENUMERATION);

    private static Condition tierIs(String value) {
        return new ComparisonCondition(TIER.value(), CompareOp.EQ, new EnumValueLiteral(value));
    }

    @Test
    void rewritesAMatchedSubtree() {
        // The study's example: widen "tier == PRO" to "tier == PRO Or tier == ENTERPRISE".
        Condition proNeedsFee = tierIs("PRO").and(FEE.notFilled());

        Condition widened = proNeedsFee.transform(node -> switch (node) {
            case ComparisonCondition c when c.operatorId().equals("Equal")
                    && c.right() instanceof EnumValueLiteral(String v) && v.equals("PRO")
                    -> tierIs("PRO").or(tierIs("ENTERPRISE"));
            default -> node;
        });

        assertEquals(
                "([/Subscription/Tier] == \"PRO\" Or [/Subscription/Tier] == \"ENTERPRISE\")"
                        + " And FieldNotFilled(/Subscription/Addons/MonthlyFee)",
                new DslRenderer().render(widened));
    }

    @Test
    void noOpTransformReturnsTheSameInstance() {
        Condition c = tierIs("PRO").and(FEE.notFilled());
        assertSame(c, c.transform(UnaryOperator.identity()));
    }

    @Test
    void untouchedSiblingSubtreeKeepsReferenceIdentity() {
        Condition left = tierIs("PRO");
        Condition right = FEE.notFilled();
        Condition c = left.and(right);

        Condition transformed = c.transform(node -> switch (node) {
            case FieldPresenceCondition f when !f.filled() -> f.field().filled();
            default -> node;
        });

        LogicCondition result = (LogicCondition) transformed;
        assertSame(left, result.left(), "untouched subtree must be reused, not rebuilt");
        assertEquals("FieldFilled(/Subscription/Addons/MonthlyFee)", new DslRenderer().render(result.right()));
    }

    @Test
    void rewriterSeesAlreadyRewrittenChildren() {
        List<String> visited = new ArrayList<>();
        Condition c = tierIs("PRO").and(FEE.notFilled());

        c.transform(node -> {
            visited.add(node.operatorId());
            return node;
        });

        assertEquals(List.of("Equal", "FieldNotFilled", "And"), visited, "children before parent (bottom-up)");
    }
}
