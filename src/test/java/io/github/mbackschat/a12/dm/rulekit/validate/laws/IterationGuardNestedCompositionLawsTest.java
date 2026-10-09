package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * KERNEL-FINDINGS §9 — the composition the advisory treats permissively without a measurement: a positive
 * presence guard reached through a NESTED {@code Or}, conjoined with the negative.
 *
 * <p>{@code IterationGuardCompositionLawsTest} measures the DIRECT {@code Or} sibling (refused) and
 * {@code IterationGuardLevelLawsTest} the guard's level. Neither takes the shape in between —
 * {@code (P Or Q) And N} — and {@code Linter.collectPositiveLevels} recurses through a {@code LogicCondition}
 * whatever its operator, so a positive on ONE arm of the {@code Or} currently suppresses the warning. Whether
 * that is right is this file's question: under {@code Or} neither arm holds on its own, so the guard may not be
 * available to the negative at all.
 *
 * <p><b>Measured answer: an {@code Or} lends only what every arm lends.</b> The three nested shapes with an
 * arm that guards nothing are refused, including the two where a same-level positive sits on the other arm, and
 * the shape whose both arms are same-level positives is admitted (added 2026-10-09; before it, the refusals read
 * as "an {@code Or} lends nothing", which they could not distinguish). The advisory's permissive treatment of this shape was therefore a
 * false NEGATIVE, and the control row is what shows the refusals are not merely the negative's own: it differs
 * from the other two in nothing but whether a same-level positive appears inside the {@code Or}.
 *
 * <p>Conditions are built typed and rendered, never hand-spelled, and the negative is the error field so
 * {@code MVK_ERROR_FIELD_NOT_REFERENCED} cannot mask a row.
 */
class IterationGuardNestedCompositionLawsTest {

    private static final String NEG = "MVK_NEG_CONDITION_IN_ITERATION";

    /** Two independent repeatable groups plus a non-repeating root field, as the sibling locks use. */
    private static final String MODEL = DocumentModelDraft.named("iteration-nested-composition")
            .locale("en_US")
            .rootGroup("Order", o -> o
                    .field("Reference", FieldKind.STRING)
                    .group("Items", i -> i.repeatable(20)
                            .field("Sku", FieldKind.STRING)
                            .field("Count", FieldKind.NUMBER))
                    .group("Notes", n -> n.repeatable(20)
                            .field("Text", FieldKind.STRING)))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(MODEL);

    private static Condition sameLevelPositive() {
        return Dsl.fieldFilled(M.field("/Order/Items/Count"));
    }

    private static Condition otherSameLevelPositive() {
        return Dsl.fieldFilled(M.field("/Order/Items/Sku"));
    }

    private static Condition offLevelPositive() {
        return Dsl.fieldFilled(M.field("/Order/Reference"));
    }

    private static Condition negative() {
        return Dsl.fieldNotFilled(M.field("/Order/Items/Sku"));
    }

    private static String verdict(Condition condition) {
        Rule rule = M.rule(M.group("/Order/Items"))
                .name("NestedComposition")
                .errorField(M.field("/Order/Items/Sku"))
                .when(condition)
                .code("NESTED")
                .severity(Severity.ERROR)
                .message(Dsl.en("Nested composition probe."))
                .build();
        var result = new io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator()
                .check(MODEL, rule.draft());
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
    void aGuardInsideANestedOrDoesNotLicenseTheConjoinedNegative() {
        var actual = new LinkedHashMap<String, String>();

        // The two established shapes, as the liveness controls for this fixture.
        actual.put("same-level guard, conjoined", verdict(sameLevelPositive().and(negative())));
        actual.put("unguarded", verdict(negative()));

        // THE QUESTION. A same-level positive on one arm of an Or, the whole Or conjoined with the negative.
        actual.put("(same-level Or off-level) And negative",
                verdict(sameLevelPositive().or(offLevelPositive()).and(negative())));
        actual.put("(off-level Or same-level) And negative",
                verdict(offLevelPositive().or(sameLevelPositive()).and(negative())));

        // THE CONTROL that makes those two mean something: the same shape with NO same-level positive
        // anywhere in the Or, so only the presence of one inside it varies.
        actual.put("(off-level Or off-level) And negative",
                verdict(offLevelPositive().or(offLevelPositive()).and(negative())));

        // Every arm a same-level positive: the Or as a whole then holds only where one of them does.
        actual.put("(same-level Or same-level) And negative",
                verdict(sameLevelPositive().or(otherSameLevelPositive()).and(negative())));

        // A presence arm and a comparison arm on the same level: the two ways a positive references it, under one Or.
        actual.put("(same-level presence Or same-level comparison) And negative",
                verdict(sameLevelPositive().or(M.num("/Order/Items/Count").gt(0)).and(negative())));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("same-level guard, conjoined", KernelLaws.ADMITTED);
        expected.put("unguarded", NEG);
        expected.put("(same-level Or off-level) And negative", NEG);
        expected.put("(off-level Or same-level) And negative", NEG);
        expected.put("(off-level Or off-level) And negative", NEG);
        expected.put("(same-level Or same-level) And negative", KernelLaws.ADMITTED);
        expected.put("(same-level presence Or same-level comparison) And negative", KernelLaws.ADMITTED);

        assertEquals(expected, actual);
    }
}
