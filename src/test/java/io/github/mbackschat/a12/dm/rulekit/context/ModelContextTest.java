package io.github.mbackschat.a12.dm.rulekit.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;

/**
 * F9a model-context surface — {@code describe()} (the agent's map of the model, without raw DM-JSON)
 * and {@code find()} (resolve a name to fields over the model index). A projection over the index;
 * nothing else depends on it.
 */
class ModelContextTest {

    private static final ModelContext ctx = new ModelContext(Fixtures.subscription());

    @Test
    void describeMapsFieldsGroupsAndEnumDomains() {
        ModelCard card = ctx.describe();

        ModelCard.Field tier = card.fields().stream()
                .filter(f -> f.path().equals("/Subscription/Tier")).findFirst().orElseThrow();
        assertEquals("ENUMERATION", tier.kind());
        assertTrue(tier.enumValues().contains("PRO"), () -> "enum domain: " + tier.enumValues());

        assertTrue(card.groups().stream()
                        .anyMatch(g -> g.path().equals("/Subscription/Addons") && g.repeatable()),
                "Addons should be listed as a repeatable group");

        String json = card.toJson();
        assertTrue(json.contains("Tier") && json.contains("BASIC"), json);
    }

    @Test
    void findResolvesAnExactName() {
        List<FieldMatch> hits = ctx.find("Tier");
        assertEquals(1, hits.size(), () -> "hits: " + hits);
        assertEquals("/Subscription/Tier", hits.get(0).path());
        assertEquals(FieldMatch.How.EXACT_NAME, hits.get(0).how());
    }

    @Test
    void findIsCaseInsensitiveAndMatchesSubstrings() {
        List<String> paths = ctx.find("fee").stream().map(FieldMatch::path).toList();
        assertTrue(paths.containsAll(List.of(
                "/Subscription/Addons/MonthlyFee",
                "/Subscription/Billing/BaseFee",
                "/Subscription/Billing/EffectiveFee")), () -> "paths: " + paths);
    }

    @Test
    void findFallsBackToFuzzyForATypo() {
        List<FieldMatch> hits = ctx.find("Tierr");
        assertEquals(1, hits.size(), () -> "hits: " + hits);
        assertEquals("/Subscription/Tier", hits.get(0).path());
        assertEquals(FieldMatch.How.FUZZY, hits.get(0).how());
    }

    @Test
    void explainProjectsABuiltCondition() {
        BoundModel m = Fixtures.subscription();
        Condition c = m.num("/Subscription/Addons/MonthlyFee").gt(1000);

        RuleExplanation ex = new ModelContext(m).explain(c);

        assertTrue(ex.operators().stream().anyMatch(o -> o.id().equals("GreaterThan")),
                () -> "operators: " + ex.operators());
        assertTrue(ex.references().stream().anyMatch(
                        r -> r.path().equals("/Subscription/Addons/MonthlyFee") && r.kind().equals("NUMBER")),
                () -> "references: " + ex.references());
        // MonthlyFee lives in the repeatable Addons group → the condition iterates
        assertTrue(ex.iterates());
        assertEquals(java.util.Optional.of("/Subscription/Addons"), ex.iterationGroup());
        assertTrue(ex.toJson().contains("\"references\""), ex.toJson());
    }

    @Test
    void errorFieldCandidatesAreReferencedFieldsInsideTheIterationScope() {
        BoundModel m = Fixtures.subscription();
        // references a field in the repeatable Addons AND one in the non-repeatable Billing → iterates Addons.
        Condition c = Dsl.allFieldsFilled(
                m.field("/Subscription/Addons/MonthlyFee"), m.field("/Subscription/Billing/BaseFee"));

        // only the in-scope field is a valid error field — BaseFee (outside the iterated group) is excluded,
        // exactly the MVK_ERROR_FIELD_NOT_REFERENCED law the kernel would otherwise reject after the round-trip.
        assertEquals(List.of("/Subscription/Addons/MonthlyFee"),
                new ModelContext(m).errorFieldCandidates(c));
    }

    @Test
    void errorFieldCandidatesAreAllReferencedFieldsWhenTheConditionDoesNotIterate() {
        BoundModel m = Fixtures.subscription();
        // both fields are in the non-repeatable Billing group → no iteration scope → both qualify (sorted).
        Condition c = Dsl.allFieldsFilled(
                m.field("/Subscription/Billing/EffectiveFee"), m.field("/Subscription/Billing/BaseFee"));

        assertEquals(List.of("/Subscription/Billing/BaseFee", "/Subscription/Billing/EffectiveFee"),
                new ModelContext(m).errorFieldCandidates(c));
    }
}
