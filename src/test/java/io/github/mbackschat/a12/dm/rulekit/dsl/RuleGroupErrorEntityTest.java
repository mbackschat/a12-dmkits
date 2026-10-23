package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.lint.Linter;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.PrecheckException;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;

/**
 * A typed rule that reports on a GROUP, A12's other kind of error entity (KERNEL-FINDINGS KF601 for its admission,
 * KF603 for the pointer it fires at).
 *
 * <p>The deferred reference check is pinned against the kernel on the shapes KF324 and KF601 measured: the target
 * group named, an enclosing group named, the {@code RuleGroup} shorthand, and only a field inside the target or
 * only a field of an enclosing group named. A field inside is the trap — it does not reference the target, which
 * the field form's {@code covers} would have said it does.
 *
 * <p>Own domain: an order's lines and the parts each line is packed from.
 */
class RuleGroupErrorEntityTest {

    private static final String MODEL = DocumentModelDraft.named("order-group-target").locale("en_US")
            .rootGroup("Order", o -> o
                    .group("Lines", l -> l.repeatable(5)
                            .stringField("Sku", s -> { })
                            .group("Parts", p -> p.repeatable(5)
                                    .stringField("PartNo", s -> { })))
                    .group("Notes", n -> n.stringField("Text", s -> { })))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(MODEL);
    private final GroupRef order = M.group("/Order");
    private final GroupRef lines = M.group("/Order/Lines");
    private final GroupRef parts = M.group("/Order/Lines/Parts");
    private final StringRef sku = M.str("/Order/Lines/Sku");
    private final StringRef partNo = M.str("/Order/Lines/Parts/PartNo");
    private final RuleValidator validator = new RuleValidator();

    private Rule onParts(Condition condition) {
        return M.rule(order).name("PartsCheck").errorEntity(parts).when(condition)
                .code("PARTS").message(Dsl.en("Check the parts")).build();
    }

    private List<String> kernelCodes(Rule rule) {
        return validator.check(MODEL, rule.draft()).diagnostics().stream()
                .map(d -> d.code().orElse("(no code)")).toList();
    }

    private static List<String> codes(List<Diagnostic> diagnostics) {
        return diagnostics.stream().map(d -> d.code().orElse("(no code)")).toList();
    }

    @Test
    void aRuleReportsOnTheGroupItNames() {
        // tag::rule-group-target[]
        Rule rule = M.rule(order).name("PartsCheck")
                .errorEntity(parts)                           // a GroupRef: the rule reports on the group itself
                .when(parts.filled().and(sku.notFilled()))    // the group, or one enclosing it, references it
                .code("PARTS").message(Dsl.en("Check the parts"))
                .build();
        rule.errorEntityRelPath();                            // "../Lines/Parts"
        // end::rule-group-target[]

        assertEquals(parts, rule.errorEntity());
        assertEquals("../Lines/Parts", rule.errorEntityRelPath());
        assertEquals(List.of(), kernelCodes(rule));
        assertEquals(List.of(), rule.deferredChecks());
    }

    @Test
    void anEnclosingGroupReferencesTheTarget() {
        Rule rule = onParts(lines.filled());

        assertEquals(List.of(), kernelCodes(rule));
        assertEquals(List.of(), rule.deferredChecks());
    }

    /** The rival reading — a field below the target references it, as a group enclosing a field target does —
     *  predicts no warning here, and the kernel refuses the rule. */
    @Test
    void aFieldInsideTheTargetDoesNotReferenceIt() {
        Rule rule = onParts(partNo.filled());

        assertEquals(List.of("MVK_ERROR_FIELD_NOT_REFERENCED"), kernelCodes(rule));
        assertEquals(List.of("MVK_ERROR_FIELD_NOT_REFERENCED"), codes(rule.deferredChecks()));
    }

    @Test
    void aFieldOfAnEnclosingGroupDoesNotReferenceTheTarget() {
        Rule rule = onParts(sku.filled());

        assertEquals(List.of("MVK_ERROR_FIELD_NOT_REFERENCED"), kernelCodes(rule));
        assertEquals(List.of("MVK_ERROR_FIELD_NOT_REFERENCED"), codes(rule.deferredChecks()));
    }

    @Test
    void theRuleGroupShorthandReferencesAGroupTarget() {
        Rule rule = M.rule(lines).name("PartsCheck").errorEntity(parts).when(Dsl.ruleGroup().filled())
                .code("PARTS").message(Dsl.en("Check the parts")).build();

        assertEquals(List.of(), kernelCodes(rule));
        assertEquals(List.of(), rule.deferredChecks());
    }

    /** The model-context lints read a field target's iteration, so a group target gets the condition's lints only. */
    @Test
    void aGroupTargetGetsTheConditionLintsOnly() {
        Rule rule = onParts(parts.notFilled());
        Linter linter = new Linter();

        assertEquals(linter.lint(rule.group().path() + "/" + rule.name(), rule.condition()), linter.lint(M, rule));
    }

    /**
     * A group outside the rule's group is refused, as a field there is, although A12 admits such a rule while it
     * iterates nothing (KERNEL-FINDINGS KF212): the deliberate over-strictness FEATURE-GAPS §4 records (was G63).
     */
    @Test
    void aGroupOutsideTheRulesGroupIsRefusedWithItsCode() {
        PrecheckException ex = assertThrows(PrecheckException.class, () -> M.rule(lines).name("Stray")
                .errorEntity(M.group("/Order/Notes")).when(sku.filled())
                .code("STRAY").message(Dsl.en("Stray")).build());

        assertEquals("RK_ERROR_FIELD_OUTSIDE_GROUP", ex.diagnostic().code().orElseThrow());
        DraftRule stray = DraftRule.builder().groupPath("/Order/Lines").name("Stray").errorEntityRelPath("../../Notes")
                .errorCondition("GroupFilled(/Order)").errorCode("STRAY").severity("ERROR")
                .messages(List.of(Dsl.en("Stray"))).build();
        assertEquals(List.of(), validator.check(MODEL, stray).diagnostics(), "A12 admits the non-iterating twin");
    }

    /**
     * The rule's own group is refused too, and A12 cannot build a model whose rule reports on it: both spellings
     * that name it, {@code ../} and the climbing {@code ../../Lines}, fail the model's construction (kernel 30.8.1,
     * 2026-10-23), so the strict placement refuses nothing A12 accepts.
     */
    @Test
    void theRulesOwnGroupIsRefusedAndA12CannotBuildIt() {
        assertThrows(PrecheckException.class, () -> M.rule(lines).name("Own")
                .errorEntity(lines).when(sku.filled())
                .code("OWN").message(Dsl.en("Own")).build());

        for (String ownGroup : List.of("../", "../../Lines")) {
            DraftRule own = DraftRule.builder().groupPath("/Order/Lines").name("Own").errorEntityRelPath(ownGroup)
                    .errorCondition("FieldFilled(Sku)").errorCode("OWN").severity("ERROR")
                    .messages(List.of(Dsl.en("Own"))).build();
            List<Diagnostic> answer = validator.check(MODEL, own).diagnostics();
            assertEquals(1, answer.size(), ownGroup);
            assertTrue(answer.get(0).summary().contains("could not build this model"), ownGroup + ": " + answer);
        }
    }
}
