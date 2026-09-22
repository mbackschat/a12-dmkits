package io.github.mbackschat.a12.dm.rulekit.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

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
    void describeSurfacesDeclaredFieldBounds() {
        // a model carrying the full declared config — read back via FieldInfo and shown in the card.
        String dmJson = io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.named("permit").locale("en_US")
                .rootGroup("Permit", p -> p
                        .stringField("Code", s -> s.minLength(3).maxLength(12).pattern("[A-Z][A-Z0-9]+", "Code rule."))
                        .numberField("Fee", n -> n.fractionDigits(2).min("0").max("10000")))
                .build().toJson();
        BoundModel m = BoundModel.ofJson(dmJson);

        // FieldInfo exposes the declared config (the gap this fix closed)
        io.github.mbackschat.a12.dm.rulekit.dsl.FieldInfo code = m.fieldAt("/Permit/Code").orElseThrow();
        assertEquals(OptionalInt.of(3), code.minLength());
        assertEquals(OptionalInt.of(12), code.maxLength());
        assertEquals(Optional.of("[A-Z][A-Z0-9]+"), code.pattern());
        io.github.mbackschat.a12.dm.rulekit.dsl.FieldInfo fee = m.fieldAt("/Permit/Fee").orElseThrow();
        assertEquals(0, fee.numberMin().orElseThrow().compareTo(new BigDecimal("0")));
        assertEquals(0, fee.numberMax().orElseThrow().compareTo(new BigDecimal("10000")));

        // and the describe card JSON carries them
        String json = new ModelContext(m).describe().toJson();
        assertTrue(json.contains("\"minLength\" : 3") && json.contains("\"maxLength\" : 12"), json);
        assertTrue(json.contains("\"pattern\" : \"[A-Z][A-Z0-9]+\""), json);
        assertTrue(json.contains("\"min\" : 0") && json.contains("\"max\" : 10000"), json);
    }

    @Test
    void findResolvesAnExactName() {
        List<FieldMatch> hits = ctx.find("Tier");
        assertEquals(1, hits.size(), () -> "hits: " + hits);
        assertEquals("/Subscription/Tier", hits.get(0).path());
        assertEquals(FieldMatch.How.EXACT_NAME, hits.get(0).how());
        assertEquals(io.github.mbackschat.a12.dm.core.ValueKind.ENUMERATION, hits.get(0).kind(),
                "kind is the typed ValueKind (matching FieldInfo.kind()), not a stringly value");
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
        assertEquals(Optional.of("/Subscription/Addons"), ex.iterationGroup());
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
