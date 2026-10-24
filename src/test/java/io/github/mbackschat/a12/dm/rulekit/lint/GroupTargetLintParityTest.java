package io.github.mbackschat.a12.dm.rulekit.lint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.ErrorEntity;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.StringRef;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;

/**
 * The text overload the CLI lints through and the typed {@link Rule} overload advise alike on a group error
 * target, and both pre-warn exactly where A12 refuses with {@code MVK_NEG_CONDITION_IN_ITERATION} (DEV-API-GAPS
 * DG153). The oracle is the kernel itself, asked per row: until 2026-10-24 the typed overload ran the AST lints
 * only on a group target and stayed silent on the two rows A12 refuses.
 *
 * <p>The guarded rows are the controls that the lint does not warn on every negative beside a group target, and
 * the field-target row that the text overload keeps linting a field.
 */
class GroupTargetLintParityTest {

    private static final BoundModel M = BoundModel.ofJson(
            DocumentModelDraft.named("lint-group-target").locale("en_US")
                    .rootGroup("Order", o -> o
                            .field("Reference", FieldKind.STRING)
                            .group("Items", i -> i.repeatable(20)
                                    .field("Sku", FieldKind.STRING)
                                    .field("Note", FieldKind.STRING)
                                    .group("Parts", p -> p.repeatable(5)
                                            .field("PartNo", FieldKind.STRING))))
                    .build().toJson());

    // Nested under the repeating Items, so the text overload's iteration test holds for the target's path.
    private static final GroupRef PARTS = M.group("/Order/Items/Parts");
    private static final StringRef SKU = M.str("/Order/Items/Sku");
    private static final StringRef NOTE = M.str("/Order/Items/Note");
    private static final StringRef PART_NO = M.str("/Order/Items/Parts/PartNo");

    private static List<String> codes(List<Diagnostic> diagnostics) {
        return diagnostics.stream().map(d -> d.code().orElse("(none)")).sorted().toList();
    }

    private static Rule rule(ErrorEntity target, Condition condition) {
        return M.rule(M.group("/Order")).name("ItemsCheck").errorEntity(target).when(condition)
                .code("ITEMS_CHECK").message(Dsl.en("Review the items.")).build();
    }

    private static List<String> typed(Condition condition) {
        return codes(new Linter().lint(M, rule(PARTS, condition)));
    }

    private static List<String> text(String errorPath, Condition condition) {
        return codes(new Linter().lint(M, errorPath, condition));
    }

    /** Whether A12 refuses the rule with the class the lint pre-warns. */
    private static boolean kernelRefusesTheNegative(Rule rule) {
        var result = new RuleValidator().check(M.toJson(), new DraftRule("/Order", "ItemsCheck",
                rule.errorEntityRelPath(), rule.render(), "ITEMS_CHECK", "ERROR"));
        return result.diagnostics().stream().anyMatch(d -> d.code().filter("MVK_NEG_CONDITION_IN_ITERATION"::equals).isPresent());
    }

    @Test
    void bothOverloadsAdviseAlikeOnAGroupTarget() {
        for (Condition condition : List.of(
                PARTS.notFilled(),
                PARTS.filled().and(PART_NO.notFilled()),
                PARTS.notFilled().or(NOTE.notFilled()),
                PARTS.filled().and(SKU.notFilled()))) {
            boolean refused = kernelRefusesTheNegative(rule(PARTS, condition));
            assertEquals(refused, typed(condition).contains("RK_NEG_CONDITION_IN_ITERATION"), "typed: " + condition);
            assertEquals(typed(condition), text("/Order/Items/Parts", condition), "text: " + condition);
        }
    }

    /** KF601's group-target topologies: an unkeyed twice-repeating group, the keyed licence, and the repeating target itself. */
    private static final BoundModel GIFTS = BoundModel.ofJson(
            DocumentModelDraft.named("lint-group-topologies").locale("en_US")
                    .rootGroup("Order", o -> o
                            .group("Lines", l -> l.repeatable(50).field("Sku", FieldKind.STRING))
                            .group("Wraps", w -> w.repeatable(2).field("Paper", FieldKind.STRING)
                                    .group("Ribbon", r -> r.field("Colour", FieldKind.STRING)))
                            .group("Gifts", g -> g.repeatable(2).indexField("GiftNo").field("GiftNo", FieldKind.STRING)
                                    .group("Card", c -> c.field("Text", FieldKind.STRING))))
                    .build().toJson());

    @Test
    void theLintFollowsA12OnEveryGroupTargetTopology() {
        Map<String, Boolean> kernel = new LinkedHashMap<>();
        Map<String, Boolean> lint = new LinkedHashMap<>();
        for (String target : List.of("/Order/Wraps/Ribbon", "/Order/Gifts/Card", "/Order/Lines")) {
            GroupRef group = GIFTS.group(target);
            Rule rule = GIFTS.rule(GIFTS.group("/Order")).name("GroupCheck").errorEntity(group).when(group.notFilled())
                    .code("GROUP_CHECK").message(Dsl.en("Review the group.")).build();
            kernel.put(target, new RuleValidator().check(GIFTS.toJson(), rule.draft()).diagnostics().stream()
                    .anyMatch(d -> d.code().filter("MVK_NEG_CONDITION_IN_ITERATION"::equals).isPresent()));
            lint.put(target, codes(new Linter().lint(GIFTS, rule)).contains("RK_NEG_CONDITION_IN_ITERATION"));
        }
        assertEquals(Map.of("/Order/Wraps/Ribbon", true, "/Order/Gifts/Card", false, "/Order/Lines", true), kernel,
                "KF601's pinned verdicts");
        assertEquals(kernel, lint);
    }

    @Test
    void theTextOverloadStillRunsTheModelContextLintsOnAFieldTarget() {
        assertTrue(kernelRefusesTheNegative(rule(SKU.field(), SKU.notFilled())));
        assertTrue(text("/Order/Items/Sku", SKU.notFilled()).contains("RK_NEG_CONDITION_IN_ITERATION"));
    }
}
