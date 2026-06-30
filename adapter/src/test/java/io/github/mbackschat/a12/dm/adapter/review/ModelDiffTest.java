package io.github.mbackschat.a12.dm.adapter.review;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ChangeKind;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ElementChange;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ElementKind;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/** MODEL-REVIEW spike — TDD for the structural diff engine (task #3): load + element matching. */
class ModelDiffTest {

    private static final String FEE_CAP = "/Subscription/Addons/FeeCapRule";

    private static String base() {
        return readResource("models/subscription.dm.json");
    }

    /** A valid head: {@code base} + one rulekit-authored rule (the proven valid-model idiom). */
    private static String withRule(String json, int threshold) {
        return withRule(json, fee -> fee.gt(threshold));
    }

    /** {@code base} + the {@code FeeCapRule} guarding {@code MonthlyFee} with a caller-supplied condition. */
    private static String withRule(String json, Function<NumberRef, Condition> condOf) {
        BoundModel m = BoundModel.ofJson(json);
        NumberRef fee = m.num("/Subscription/Addons/MonthlyFee");
        Rule rule = m.rule(m.group("/Subscription/Addons"))
                .name("FeeCapRule").errorField(fee).when(condOf.apply(fee))
                .code("FEE_CAP").severity(Severity.ERROR)
                .message(en("Monthly fee too high."), de("Monatsgebühr zu hoch.")).build();
        return m.add(rule).toJson();
    }

    @Test
    void detectsAddedAndRemovedRule() {
        String base = base();
        String head = withRule(base, 1000);

        assertTrue(has(ModelDiff.between(base, head), ElementKind.RULE, ChangeKind.ADDED, FEE_CAP),
                "the new rule must be reported ADDED");
        // the inverse direction reports it REMOVED — diff is directional
        assertTrue(has(ModelDiff.between(head, base), ElementKind.RULE, ChangeKind.REMOVED, FEE_CAP),
                "base-from-head must report the rule REMOVED");
    }

    @Test
    void detectsAddedField() {
        String base = base();
        String head = EditSession.read(base)
                .addField("/Subscription/Addons", "Promo", FieldKind.NUMBER)
                .validate().toJson();

        assertTrue(has(ModelDiff.between(base, head), ElementKind.FIELD, ChangeKind.ADDED,
                "/Subscription/Addons/Promo"), "the new field must be reported ADDED");
    }

    @Test
    void identicalAndReformattedShowNoChanges() {
        String base = base();
        assertEquals(0, ModelDiff.between(base, base).changes().size(), "identical models must diff to nothing");

        // the whole point of a STRUCTURAL diff: a reformat (different whitespace, same model) is NOT a change
        String reformatted = io.github.mbackschat.a12.dm.modelkit.ModelDocument.read(base).toJson();
        assertEquals(0, ModelDiff.between(base, reformatted).changes().size(),
                "a pure reformat must not register as a change");
    }

    @Test
    void addedRuleCarriesItsConditionDetail() {
        ElementChange added = change(ModelDiff.between(base(), withRule(base(), 1000)), FEE_CAP);
        assertEquals(ChangeKind.ADDED, added.change());
        String condition = delta(added, "condition").after();
        assertTrue(condition.contains("MonthlyFee") && condition.contains("1000"),
                () -> "ADDED rule should carry its condition as the after-side, got: " + condition);
    }

    @Test
    void modifiedRuleReportsConditionBeforeAndAfter() {
        // same rule name, different threshold → MODIFIED with a before/after condition delta
        ElementChange modified = change(ModelDiff.between(withRule(base(), 1000), withRule(base(), 2000)), FEE_CAP);
        assertEquals(ChangeKind.MODIFIED, modified.change());
        ModelDiff.Delta condition = delta(modified, "condition");
        assertTrue(condition.before().contains("1000"), () -> "before should hold the old threshold: " + condition.before());
        assertTrue(condition.after().contains("2000"), () -> "after should hold the new threshold: " + condition.after());
    }

    @Test
    void removedRuleIsHighRiskAndAddedRuleIsMedium() {
        // a removed validation silently accepts bad data → HIGH; an added rule fails loudly → MEDIUM
        ElementChange removed = change(ModelDiff.between(withRule(base(), 1000), base()), FEE_CAP);
        assertEquals(ModelDiff.Risk.HIGH, removed.risk());
        assertTrue(removed.reasons().contains("RULE_REMOVED"), () -> "reasons: " + removed.reasons());

        ElementChange added = change(ModelDiff.between(base(), withRule(base(), 1000)), FEE_CAP);
        assertEquals(ModelDiff.Risk.MEDIUM, added.risk());
        assertTrue(added.reasons().contains("RULE_ADDED"), () -> "reasons: " + added.reasons());
    }

    @Test
    void polarityInversionIsHighRiskNotJustAConditionChange() {
        // gt → le is the clean complement: the rule now fires on the complementary set, silently
        // accepting what it used to reject → POLARITY_INVERTED (HIGH), per the loosening>tightening taste.
        String base = withRule(base(), fee -> fee.gt(1000));
        String head = withRule(base(), fee -> fee.le(1000));
        ElementChange modified = change(ModelDiff.between(base, head), FEE_CAP);
        assertEquals(ChangeKind.MODIFIED, modified.change());
        assertEquals(ModelDiff.Risk.HIGH, modified.risk(), () -> "reasons: " + modified.reasons());
        assertTrue(modified.reasons().contains("POLARITY_INVERTED"), () -> "reasons: " + modified.reasons());
    }

    @Test
    void aThresholdChangeIsMediumThresholdChanged() {
        // same operator + structure, different literal threshold — not a polarity flip; MEDIUM, classified
        ElementChange modified = change(ModelDiff.between(withRule(base(), 1000), withRule(base(), 2000)), FEE_CAP);
        assertEquals(ModelDiff.Risk.MEDIUM, modified.risk(), () -> "reasons: " + modified.reasons());
        assertTrue(modified.reasons().contains("THRESHOLD_CHANGED"), () -> "reasons: " + modified.reasons());
        assertFalse(modified.reasons().contains("POLARITY_INVERTED"), () -> "reasons: " + modified.reasons());
    }

    @Test
    void anOperatorChangeIsMediumOperatorChanged() {
        // gt → ge keeps refs/structure but swaps the operator (and is not the le complement)
        ElementChange modified = change(
                ModelDiff.between(withRule(base(), f -> f.gt(1000)), withRule(base(), f -> f.ge(1000))), FEE_CAP);
        assertEquals(ModelDiff.Risk.MEDIUM, modified.risk(), () -> "reasons: " + modified.reasons());
        assertTrue(modified.reasons().contains("OPERATOR_CHANGED"), () -> "reasons: " + modified.reasons());
    }

    @Test
    void addingAClauseIsMediumConditionRestructured() {
        ElementChange modified = change(
                ModelDiff.between(withRule(base(), f -> f.gt(1000)),
                        withRule(base(), f -> f.gt(1000).and(f.gt(0)))), FEE_CAP);
        assertEquals(ModelDiff.Risk.MEDIUM, modified.risk(), () -> "reasons: " + modified.reasons());
        assertTrue(modified.reasons().contains("CONDITION_RESTRUCTURED"), () -> "reasons: " + modified.reasons());
    }

    @Test
    void addedFieldCarriesItsKindDetail() {
        String head = EditSession.read(base())
                .addField("/Subscription/Addons", "Promo", FieldKind.NUMBER)
                .validate().toJson();
        ElementChange added = change(ModelDiff.between(base(), head), "/Subscription/Addons/Promo");
        assertEquals(ChangeKind.ADDED, added.change());
        assertEquals("NUMBER", delta(added, "kind").after());
    }

    private static boolean has(ModelDiff diff, ElementKind kind, ChangeKind change, String id) {
        return diff.changes().stream().anyMatch(
                (ElementChange c) -> c.element() == kind && c.change() == change && c.id().equals(id));
    }

    /** The single change for {@code id} (fails if absent or ambiguous). */
    private static ElementChange change(ModelDiff diff, String id) {
        return diff.changes().stream().filter(c -> c.id().equals(id)).reduce((a, b) -> {
            throw new AssertionError("ambiguous change for " + id);
        }).orElseThrow(() -> new AssertionError("no change for " + id + " in " + diff.changes()));
    }

    /** The {@code aspect} delta of a change (fails if absent). */
    private static ModelDiff.Delta delta(ElementChange change, String aspect) {
        return change.deltas().stream().filter(d -> d.aspect().equals(aspect)).findFirst()
                .orElseThrow(() -> new AssertionError("no '" + aspect + "' delta in " + change.deltas()));
    }

    private static String readResource(String path) {
        try (InputStream in = ModelDiffTest.class.getClassLoader().getResourceAsStream(path)) {
            assertTrue(in != null, "missing test resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
