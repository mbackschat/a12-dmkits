package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * KERNEL-FINDINGS §9 — the axis the guard-level grid could not vary: the RULE'S OWN GROUP.
 *
 * <p>{@code IterationGuardErrorFieldLevelLawsTest} shows the error field cannot move off the rule group while
 * the rule stays legal, and it holds the rule group at one level throughout. So "a positive guard must
 * reference the negative's level" was measured with the negative always at a level the rule does NOT itself
 * iterate. This file varies that: a negative over the group the rule already iterates needs nothing to bound
 * it, because the rule's own iteration bounds it.
 *
 * <p>Raised by an independent differential over real models, predicting such a negative is ADMITTED because
 * the rule already iterates that group. <b>Measured: it is not.</b> Bare, it draws the iteration code exactly
 * as a field negative does; what admits it is a positive over the SAME group, which is the existing level rule
 * and needs no addition. The reported shape — a guard over a DIFFERENT repeatable group — is refused too, but
 * under {@code MVK_NO_WILDCARD}: a cross-group reference from this rule group is illegal for its own reason,
 * so that shape never reaches the iteration gate.
 *
 * <p>The mechanism offered with the prediction does not hold either, traced in source: a rule-group negative
 * reports {@code GroupNotFilled}/{@code RuleGroup} and so IS substituted, and an absolute reference to the
 * rule's own repeating group resolves to that same level through {@code groupLevelOf}. The advisory does not
 * warn on the admitted row above ({@code lint.IterationGuardLintLevelTest}).
 */
class IterationGuardOwnGroupLawsTest {

    private static final String NEG = "MVK_NEG_CONDITION_IN_ITERATION";

    /** Three independent repeatable groups, none an ancestor of another. */
    private static final String MODEL = DocumentModelDraft.named("iteration-own-group").locale("en_US")
            .rootGroup("Order", o -> o
                    .field("Reference", FieldKind.STRING)
                    .group("Items", i -> i.repeatable(20)
                            .field("Sku", FieldKind.STRING))
                    .group("Notes", n -> n.repeatable(20)
                            .field("Text", FieldKind.STRING))
                    .group("Audits", a -> a.repeatable(20)
                            .field("Actor", FieldKind.STRING)))
            .build().toJson();

    /**
     * NO conjunct is appended to satisfy the error-field reference, and that is the point: the usual
     * {@code And FieldFilled(<error field>)} trick IS a positive presence guard at the negative's own level,
     * which is the very thing under test — appending it admits every row and the table reads as a law.
     */
    private static String verdict(String ruleGroup, String errorField, String condition) {
        var result = KernelLaws.checkModel(MODEL, ruleGroup, errorField, condition);
        if (result.ok()) {
            return KernelLaws.ADMITTED;
        }
        return result.diagnostics().stream()
                .map(d -> d.code().orElse("NO_CODE"))
                .distinct()
                .sorted()
                .collect(java.util.stream.Collectors.joining("+"));
    }

    /** One table, so a kernel shift shows every cell rather than aborting at the first. */
    @Test
    void aNegativeOverTheRulesOwnGroupNeedsNoGuard() {
        var actual = new LinkedHashMap<String, String>();

        // THE PREDICTION: the rule iterates Items and the negative is over Items itself, so the rule's own
        // iteration bounds it and nothing else needs to.
        actual.put("rule group Items, negative over Items",
                verdict("/Order/Items", "../Sku", "GroupNotFilled(/Order/Items)"));

        // THE CONTROL: the same bare negative from a THIRD repeatable group that is not Items' ancestor, so
        // the rule's own iteration cannot be what bounds it. Only the rule group differs.
        actual.put("rule group Audits, negative over Items",
                verdict("/Order/Audits", "../Actor", "GroupNotFilled(/Order/Items)"));

        // The reported shape: the same negative WITH a positive over another repeatable group, unstarred.
        actual.put("rule Items, negative over Items, guard over Notes",
                verdict("/Order/Items", "../Sku",
                        "GroupNotFilled(/Order/Items) And GroupFilled(/Order/Notes)"));

        // The discriminator for it: the guard over the negative's OWN group instead of a foreign one.
        actual.put("rule Items, negative over Items, guard over Items",
                verdict("/Order/Items", "../Sku",
                        "GroupNotFilled(/Order/Items) And GroupFilled(/Order/Items)"));

        // LIVENESS for the iteration gate on this fixture: a bare field negative at the rule's own level is
        // refused, so an admitted row above is not this fixture admitting everything.
        actual.put("rule group Items, bare FIELD negative",
                verdict("/Order/Items", "../Sku", "FieldNotFilled(Sku)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("rule group Items, negative over Items", NEG);
        expected.put("rule group Audits, negative over Items", "MVK_NO_WILDCARD");
        expected.put("rule Items, negative over Items, guard over Notes", "MVK_NO_WILDCARD");
        expected.put("rule Items, negative over Items, guard over Items", KernelLaws.ADMITTED);
        expected.put("rule group Items, bare FIELD negative", NEG);

        assertEquals(expected, actual);
    }
}
