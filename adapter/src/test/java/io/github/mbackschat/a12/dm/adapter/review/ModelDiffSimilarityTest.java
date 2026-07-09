package io.github.mbackschat.a12.dm.adapter.review;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ChangeKind;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ElementChange;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ElementKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * MODEL-REVIEW MR3 (similarity upgrade) — pairs a rename that ALSO edited the element, which the
 * exact-fingerprint fast path (MR3-v1) can't. The boundary laws pin the thresholds: a genuine rename+edit
 * clears BOTH bars ({@code PAIR_MIN} and the {@code PAIR_MARGIN}), while near-duplicates fail the MARGIN
 * <b>specifically</b> (each candidate clears {@code PAIR_MIN}, but no clear winner) — so an ambiguous set is
 * never mis-paired. A generic same-type match with no distinctive link is gated out.
 */
class ModelDiffSimilarityTest {

    private static final String ADDONS = "/Subscription/Addons";
    private static final String FEE = "/Subscription/Addons/MonthlyFee";
    private static final Set<String> FEE_REF = Set.of(FEE);

    // ---- boundary laws over the pure score (robust: asserted relative to the named thresholds) ----

    @Test
    void aGenuineRenamePlusEditClearsBothBars() {
        // same referenced field, edited threshold, renamed → a clear, single winner
        double score = ModelDiff.similarityScore(ElementKind.RULE,
                Map.of("condition", "[MonthlyFee] > 1000", "severity", "ERROR", "code", "CAP"),
                Map.of("condition", "[MonthlyFee] > 2000", "severity", "ERROR", "code", "CAP"),
                FEE_REF, FEE_REF, "FeeCapRule", "FeeLimitRule");
        assertTrue(score >= ModelDiff.PAIR_MIN,
                () -> "a genuine rename+edit must clear PAIR_MIN=" + ModelDiff.PAIR_MIN + ", got " + score);
    }

    @Test
    void nearDuplicatesFailTheMarginRuleSpecifically() {
        // R against two near-identical candidates (differ only by name/code) — both clear MIN, but their
        // scores are within the margin, so the matcher must NOT pick either (the MARGIN is what refuses it)
        Map<String, String> base = Map.of("condition", "[MonthlyFee] > 1000", "severity", "ERROR", "code", "CAP");
        double s1 = ModelDiff.similarityScore(ElementKind.RULE, base,
                Map.of("condition", "[MonthlyFee] > 1000", "severity", "ERROR", "code", "CAP1"),
                FEE_REF, FEE_REF, "FeeCapRule", "FeeCapRuleOne");
        double s2 = ModelDiff.similarityScore(ElementKind.RULE, base,
                Map.of("condition", "[MonthlyFee] > 1000", "severity", "ERROR", "code", "CAP2"),
                FEE_REF, FEE_REF, "FeeCapRule", "FeeCapRuleTwo");
        assertTrue(s1 >= ModelDiff.PAIR_MIN && s2 >= ModelDiff.PAIR_MIN,
                () -> "each candidate individually clears PAIR_MIN (so it is not MIN that refuses): " + s1 + ", " + s2);
        assertTrue(Math.abs(s1 - s2) < ModelDiff.PAIR_MARGIN,
                () -> "near-duplicates are within PAIR_MARGIN=" + ModelDiff.PAIR_MARGIN + " → no clear winner: " + s1 + " vs " + s2);
    }

    @Test
    void aGenericSameTypeMatchWithNoDistinctiveLinkIsGatedOut() {
        // two plain NUMBER fields with dissimilar names — identical fingerprint, but no distinctive link
        double score = ModelDiff.similarityScore(ElementKind.FIELD,
                Map.of("kind", "NUMBER", "scale", "0"), Map.of("kind", "NUMBER", "scale", "0"),
                Set.of(), Set.of(), "Surcharge", "Discount");
        assertTrue(score < ModelDiff.PAIR_MIN,
                () -> "a generic type match with a dissimilar name must not qualify: " + score);
    }

    // ---- integration: the flagship end-to-end (and the ambiguous set stays unpaired) ----

    @Test
    void ruleRenamePlusEditPairsEndToEndAndKeepsTheEditVisible() {
        String base = modelWithRule("FeeCapRule", "CAP", 1000);
        String head = modelWithRule("FeeLimitRule", "CAP", 2000);

        List<ElementChange> renames = ModelDiff.between(base, head).changes().stream()
                .filter(c -> c.change() == ChangeKind.RENAMED).toList();
        assertEquals(1, renames.size(), () -> "the rename+edit must pair: " + ModelDiff.between(base, head).changes());
        ElementChange r = renames.get(0);
        assertEquals(ADDONS + "/FeeCapRule", r.id());
        assertEquals(ADDONS + "/FeeLimitRule",
                r.deltas().stream().filter(d -> d.aspect().equals("path")).findFirst().orElseThrow().after());
        assertTrue(r.reasons().contains("ELEMENT_RENAMED") && r.reasons().contains("THRESHOLD_CHANGED"),
                () -> "the rename AND the edit are both reported: " + r.reasons());
        assertEquals(ModelDiff.Risk.MEDIUM, r.risk(), () -> "the edit lifts the risk above a bare rename: " + r);
        ModelDiff.Delta condition = r.deltas().stream().filter(d -> d.aspect().equals("condition")).findFirst()
                .orElseThrow(() -> new AssertionError("the edit must stay visible as a condition delta: " + r.deltas()));
        assertTrue(condition.before().contains("1000") && condition.after().contains("2000"),
                () -> "the threshold move is not hidden: " + condition);
    }

    @Test
    void anAmbiguousCandidateSetIsNotPaired() {
        String base = modelWithRule("FeeCapRule", "CAP", 1000);
        String head = headWithTwoSimilarRules();

        long relocations = ModelDiff.between(base, head).changes().stream()
                .filter(c -> c.change() == ChangeKind.RENAMED || c.change() == ChangeKind.MOVED).count();
        assertEquals(0, relocations, () -> "two equally-good candidates → no pair: " + ModelDiff.between(base, head).changes());
        assertTrue(ModelDiff.between(base, head).changes().stream().anyMatch(
                c -> c.change() == ChangeKind.REMOVED && c.id().equals(ADDONS + "/FeeCapRule")),
                "the removed rule stays REMOVED (not mis-paired)");
    }

    private static String modelWithRule(String name, String code, int threshold) {
        BoundModel m = BoundModel.ofJson(base());
        NumberRef fee = m.num(FEE);
        Rule r = m.rule(m.group(ADDONS)).name(name).errorField(fee).when(fee.gt(threshold))
                .code(code).severity(Severity.ERROR)
                .message(en("Monthly fee out of bounds."), de("Monatsgebühr außerhalb der Grenzen.")).build();
        return m.add(r).toJson();
    }

    /** {@code base} + two near-identical rules (same field/condition, different names+codes) — an ambiguous set. */
    private static String headWithTwoSimilarRules() {
        BoundModel m = BoundModel.ofJson(base());
        NumberRef fee = m.num(FEE);
        m = m.add(m.rule(m.group(ADDONS)).name("FeeCapRuleOne").errorField(fee).when(fee.gt(1000))
                .code("CAP1").severity(Severity.ERROR)
                .message(en("Monthly fee out of bounds."), de("Monatsgebühr außerhalb der Grenzen.")).build());
        NumberRef fee2 = m.num(FEE);
        return m.add(m.rule(m.group(ADDONS)).name("FeeCapRuleTwo").errorField(fee2).when(fee2.gt(1000))
                .code("CAP2").severity(Severity.ERROR)
                .message(en("Monthly fee out of bounds."), de("Monatsgebühr außerhalb der Grenzen.")).build()).toJson();
    }

    private static String base() {
        try (InputStream in = ModelDiffSimilarityTest.class.getClassLoader()
                .getResourceAsStream("models/subscription.dm.json")) {
            assertTrue(in != null, "missing test resource");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
