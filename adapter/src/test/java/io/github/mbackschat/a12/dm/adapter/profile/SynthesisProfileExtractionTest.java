package io.github.mbackschat.a12.dm.adapter.profile;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/** {@link SynthesisProfile} — the domain-blind complexity profile that powers {@code model profile}. */
class SynthesisProfileExtractionTest {

    private static final String FEE = "/Subscription/Addons/MonthlyFee";  // under the repeatable Addons group
    private static final String TIER = "/Subscription/Tier";              // under the root group (a walk-up target)

    /** subscription + a per-row rule that walks up to Tier + an aggregate rule over the repeatable group. */
    private static String modelWithRules() {
        BoundModel m = BoundModel.ofJson(readResource("models/subscription.dm.json"));
        NumberRef fee = m.num(FEE);
        // per-repeatable-row rule (scoped to Addons) that references a root-group field -> a walk-up
        var walkUp = m.rule(m.group("/Subscription/Addons"))
                .name("FeeVsTierRule").errorField(fee)
                .when(fee.gt(0).and(m.enumOf(TIER).is("PRO")))
                .code("FEE_TIER").severity(Severity.ERROR)
                .message(en("Fee/tier mismatch."), de("Gebühr/Stufe passt nicht.")).build();
        // aggregate rule: Sum over the repeatable group's flattened fee (error field referenced via the flatten)
        var agg = m.rule(m.group("/Subscription/Addons"))
                .name("TotalFeeRule").errorField(fee)
                .when(Dsl.sum(m.flatten("/Subscription/Addons*/MonthlyFee")).gt("1000.00"))
                .code("TOTAL_FEE").severity(Severity.ERROR)
                .message(en("Total too high."), de("Summe zu hoch.")).build();
        return m.add(walkUp).add(agg).toJson();
    }

    @Test
    void profilesStructureAndRepeatability() {
        SynthesisProfile p = SynthesisProfile.of(modelWithRules());
        assertTrue(p.structure().fieldCount() > 0, "fields counted");
        assertTrue(p.structure().repeatability().repeatableGroupCount() >= 1, "Addons is repeatable");
        assertTrue(p.structure().fieldKinds().values().stream().mapToInt(Integer::intValue).sum()
                == p.structure().fieldCount(), "every field is classified by kind");
    }

    @Test
    void countsRulesAndClassifiesScope() {
        SynthesisProfile p = SynthesisProfile.of(modelWithRules());
        assertEquals(2, p.rules().count());
        assertEquals(0, p.rules().opaqueCount());
        assertTrue(p.rules().scope().perRepeatableRow() >= 1, "the Addons-scoped rule is per-row");
    }

    @Test
    void detectsTheAggregateConstruct() {
        SynthesisProfile p = SynthesisProfile.of(modelWithRules());
        assertTrue(p.rules().constructs().getOrDefault("AGGREGATE", 0) >= 1, "the Sum rule is an aggregate");
        assertTrue(p.rules().operators().containsKey("Sum"), "Sum appears in the operator histogram");
    }

    @Test
    void measuresTheWalkUpAndRepeatableLevels() {
        SynthesisProfile p = SynthesisProfile.of(modelWithRules());
        // the Addons-scoped rule reads /Subscription/Tier (one group level up)
        assertEquals(1, p.rules().walkUpDistance().n(), "exactly one rule walks up");
        assertEquals(1, p.rules().walkUpDistance().max(), "one group level up");
        // the fee it guards lives under the repeatable Addons group
        assertTrue(p.rules().repeatableLevelsCrossed().max() >= 1, "a ref crosses a repeatable level");
    }

    @Test
    void ruleJointsCaptureCoOccurrenceAndConstructByScope() {
        SynthesisProfile p = SynthesisProfile.of(modelWithRules());
        // co-occurrence JOINT: the Sum rule is AGGREGATE-only; the walk-up rule carries no construct
        assertTrue(p.rules().constructCombos().getOrDefault("AGGREGATE", 0) >= 1, "an AGGREGATE-only rule");
        assertTrue(p.rules().constructCombos().getOrDefault("NONE", 0) >= 1, "a plain (construct-free) rule");
        // construct × scope JOINT: both rules are scoped at the repeatable Addons group, so AGGREGATE lands per-row
        assertEquals(3, p.rules().constructsByScope().size(), "the three scope buckets always present");
        assertTrue(p.rules().constructsByScope().get("perRepeatableRow").getOrDefault("AGGREGATE", 0) >= 1,
                "the aggregate is evaluated per repeatable row — the O(N²) surface");
    }

    /** subscription + a computed field that Sums the repeatable fees — an AGGREGATE-archetype computation. */
    private static String modelWithComputation() {
        BoundModel m = BoundModel.ofJson(readResource("models/subscription.dm.json"));
        var comp = m.computation(m.num("/Subscription/Billing/EffectiveFee"))
                .name("EffectiveFeeCalc")
                .calc(Dsl.sum(m.flatten("/Subscription/Addons*/MonthlyFee")).expression())
                .message(en("Effective fee."), de("Effektive Gebühr.")).build();
        return m.add(comp).toJson();
    }

    @Test
    void computationJointsClassifyEveryComputation() {
        SynthesisProfile p = SynthesisProfile.of(modelWithComputation());
        assertTrue(p.computations().count() >= 1, "the model has a computation");
        assertTrue(p.computations().archetypes().getOrDefault("AGGREGATE", 0) >= 1, "the Sum computation is AGGREGATE");
        // every non-opaque computation gets exactly one archetype label
        int archetyped = p.computations().archetypes().values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(p.computations().count() - p.computations().opaqueCount(), archetyped,
                "each converted computation is classified by exactly one archetype");
        assertEquals(3, p.computations().constructsByScope().size(), "the three scope buckets always present");
        assertTrue(p.computations().aggregateScaleMatch().matched() >= 0
                        && p.computations().aggregateScaleMatch().mismatched() >= 0,
                "the scale-match joint is populated");
    }

    @Test
    void iterationMetricsCaptureListScanCapacityAndNesting() {
        SynthesisProfile p = SynthesisProfile.of(modelWithComputation());
        // the aggregate Sums the repeatable Addons list → star-capture ≥ that list's maxRepetitions (a real element count)
        assertTrue(p.computations().iteration().listScanCapacity().max() >= 2,
                "a starred aggregate walks ≥ the list's rows: " + p.computations().iteration().listScanCapacity().max());
        // repeatability nesting-depth distribution is populated (Addons is a depth-1 repeatable)
        assertTrue(p.structure().repeatability().nestingDepth().n() >= 1, "each repeatable contributes a nesting depth");
        assertTrue(p.structure().repeatability().nestingDepth().max() >= 1);
        // the scope-local synthetic model uses no $ correlation / parallel branch — the metric is present and 0
        assertEquals(0, p.computations().iteration().outerCorrelation());
    }

    /**
     * A purpose-built fixture for the M1–M5 joint metrics: a nested repeatable chain {@code Outer(3) ->
     * Inner(5)} (chainProduct {3, 15}; a field in each of root/Outer/Inner gives fieldChainProduct
     * {1, 3, 15}), a twin hub {@code Hub -> {BranchA(2), BranchB(2)}} (siblingBranchFan(Hub) = 2), a rule
     * scoped at Inner (scopeCapacity = 15), and two Sum-over-flatten computations — one single-star
     * ({@code Outer*}{@code /OuterNum}, aggregateStars = 1) and one nested two-star
     * ({@code Outer*}{@code /Inner*}{@code /InnerNum}, aggregateStars = 2) — plus a plain-copy computation
     * scoped at Inner (scopeCapacity = 15, no scan), and a walk-up computation scoped at Inner that references
     * only the model-level {@code RootNum}. The latter two prove scalar references do not become list scans.
     */
    private static String chainProductModel() {
        String structure = DocumentModelDraft.named("Chain").locale("en_US")
                .rootGroup("R", r -> {
                    r.field("RootNum", FieldKind.NUMBER);
                    r.field("TotalOuter", FieldKind.NUMBER);
                    r.field("TotalInner", FieldKind.NUMBER);
                    r.group("Outer", outer -> {
                        outer.repeatable(3);
                        outer.field("OuterNum", FieldKind.NUMBER);
                        outer.group("Inner", inner -> {
                            inner.repeatable(5);
                            inner.field("InnerNum", FieldKind.NUMBER);
                            inner.field("InnerCalc", FieldKind.NUMBER);
                            inner.field("WalkUpCalc", FieldKind.NUMBER);
                        });
                    });
                    r.group("Hub", hub -> {
                        hub.field("HubNum", FieldKind.NUMBER);
                        hub.group("BranchA", a -> {
                            a.repeatable(2);
                            a.field("ANum", FieldKind.NUMBER);
                        });
                        hub.group("BranchB", b -> {
                            b.repeatable(2);
                            b.field("BNum", FieldKind.NUMBER);
                        });
                    });
                }).build().toJson();
        BoundModel m = BoundModel.ofJson(structure);

        NumberRef innerNum = m.num("/R/Outer/Inner/InnerNum");
        var deepRule = m.rule(m.group("/R/Outer/Inner")).name("DeepRule").errorField(innerNum)
                .when(innerNum.gt(0))
                .code("DEEP").severity(Severity.ERROR)
                .message(en("Deep.")).build();
        m = m.add(deepRule);

        var totalOuter = m.computation(m.num("/R/TotalOuter")).name("TotalOuterCalc")
                .calc(Dsl.sum(m.flatten("/R/Outer*/OuterNum")).expression())
                .message(en("Total outer.")).build();
        m = m.add(totalOuter);

        var totalInner = m.computation(m.num("/R/TotalInner")).name("TotalInnerCalc")
                .calc(Dsl.sum(m.flatten("/R/Outer*/Inner*/InnerNum")).expression())
                .message(en("Total inner.")).build();
        m = m.add(totalInner);

        var innerCalc = m.computation(m.num("/R/Outer/Inner/InnerCalc")).name("InnerCalcCalc")
                .calc(innerNum.asNumber().expression())
                .message(en("Inner copy.")).build();
        m = m.add(innerCalc);

        // scoped at Inner (scopeCapacity 15) but reads ONLY the model-level RootNum (listScanCapacity 1). Unlike a rule (whose
        // error field must lie under its own scope group), a computation's read has no such containment
        // constraint, so this is the natural place to fixture a walk-up.
        NumberRef rootNum = m.num("/R/RootNum");
        var walkUpCalc = m.computation(m.num("/R/Outer/Inner/WalkUpCalc")).name("WalkUpCalcCalc")
                .calc(rootNum.asNumber().expression())
                .message(en("Walk-up copy.")).build();
        m = m.add(walkUpCalc);

        return m.toJson();
    }

    @Test
    void chainProductMeasuresNestedRepeatableProducts() {
        SynthesisProfile p = SynthesisProfile.of(chainProductModel());
        // repeatable groups: Outer(3), Inner(3*5=15), BranchA(2), BranchB(2)
        Distribution chain = p.structure().repeatability().chainProduct();
        assertEquals(4, chain.n(), "one chainProduct sample per repeatable group");
        assertEquals(2, chain.min(), "the shallow twin branches");
        assertEquals(15, chain.max(), "the nested Outer(3) x Inner(5) chain is the deepest product");
    }

    @Test
    void fieldChainProductMeasuresPerFieldRepeatableMass() {
        SynthesisProfile p = SynthesisProfile.of(chainProductModel());
        Distribution fcp = p.structure().repeatability().fieldChainProduct();
        assertEquals(p.structure().fieldCount(), fcp.n(), "one sample per field, including root fields (=1)");
        assertEquals(1, fcp.min(), "a root/Hub field carries no repeatable ancestor");
        assertEquals(15, fcp.max(), "InnerNum/InnerCalc sit under Outer(3) x Inner(5)");
    }

    @Test
    void siblingBranchFanCountsTwinHubBranches() {
        SynthesisProfile p = SynthesisProfile.of(chainProductModel());
        Distribution fan = p.structure().repeatability().siblingBranchFan();
        // Outer reaches only its child Inner (fan=1); Hub reaches its twin BranchA/BranchB (fan=2);
        // the root R reaches Outer PLUS Hub's two branches transitively (fan=3)
        assertEquals(3, fan.n(), "Outer, Hub, and the root R each reach >=1 repeatable branch");
        assertEquals(1, fan.min(), "Outer reaches only its own child Inner");
        assertEquals(3, fan.max(), "the root sees Outer + Hub's two branches");
        assertEquals(2, fan.buckets().getOrDefault("2-3", 0),
                "Hub (fan=2) and the root (fan=3) share the 2-3 bucket — the twin/multi-branch hubs");
    }

    @Test
    void scopeCapacityMeasuresRuleAndComputationScopeDepth() {
        SynthesisProfile p = SynthesisProfile.of(chainProductModel());
        // the rule and the InnerCalc copy are both scoped at Inner: Outer(3) x Inner(5) = 15
        assertEquals(15, p.rules().iteration().scopeCapacity().max(), "the deep rule is scoped at Inner");
        assertEquals(15, p.computations().iteration().scopeCapacity().max(), "InnerCalc is scoped at Inner");
        assertEquals(1, p.computations().iteration().scopeCapacity().min(),
                "TotalOuter/TotalInner are scoped at the (non-repeatable) root");
    }

    @Test
    void separatesScopeListScanAndCombinedWorkCapacity() {
        SynthesisProfile p = SynthesisProfile.of(chainProductModel());
        assertEquals(1, p.rules().iteration().listScanCapacity().max(),
                "a scalar reference in a repeatable scope is not an explicit list scan");
        assertEquals(15, p.rules().iteration().workCapacity().max(),
                "work is scope capacity multiplied by the neutral scan capacity");

        Distribution scans = p.computations().iteration().listScanCapacity();
        assertEquals(1, scans.min(), "plain copies carry the neutral scan capacity");
        assertEquals(15, scans.max(), "the nested Sum scans Outer(3) × Inner(5)");
        Distribution work = p.computations().iteration().workCapacity();
        assertEquals(3, work.min(), "the shallow Sum has scope 1 × scan 3");
        assertEquals(15, work.max(), "deep scalar placement and the nested Sum both cost 15");
    }

    @Test
    void scannedScopeCapacityRestrictsScopeCapacityToReachingElementsOnly() {
        SynthesisProfile p = SynthesisProfile.of(chainProductModel());
        // DeepRule has no explicit collection operand.
        Distribution ruleReachScope = p.rules().iteration().scannedScopeCapacity();
        assertEquals(0, ruleReachScope.n(), "the single rule doesn't reach beyond its own scope");

        // TotalOuterCalc/TotalInnerCalc are the only scanning computations and both are root-scoped.
        Distribution compReachScope = p.computations().iteration().scannedScopeCapacity();
        assertEquals(2, compReachScope.n(), "only the two root-scoped Sum computations reach beyond their scope");
        assertEquals(1, compReachScope.max(), "both reaching computations are scoped at the (non-repeatable) root");
    }

    @Test
    void nestingDepthExactCountsEachRepeatableAtItsPreciseDepth() {
        // Unlike nestingDepth's log-bucketed histogram (which would conflate depths 2 and 3 into one
        // "2-3" bucket count on a bigger model), nestingDepthExact is keyed by the ACTUAL integer depth, so it
        // never loses which reps are truly at which level. Outer(1), BranchA(1), BranchB(1) share depth 1;
        // Inner (nested under Outer) is depth 2.
        SynthesisProfile p = SynthesisProfile.of(chainProductModel());
        assertEquals(Map.of("1", 3, "2", 1), p.structure().repeatability().nestingDepthExact(),
                "three depth-1 repeatables (Outer, BranchA, BranchB) and one depth-2 (Inner)");
    }

    @Test
    void aggregateStarsMeasuresNestedStarDepth() {
        SynthesisProfile p = SynthesisProfile.of(chainProductModel());
        Distribution stars = p.computations().aggregateStars();
        assertEquals(2, stars.n(), "only the two Sum-over-flatten computations carry a starred operand");
        assertEquals(1, stars.min(), "the single-star Sum(Outer*/OuterNum)");
        assertEquals(2, stars.max(), "the nested two-star Sum(Outer*/Inner*/InnerNum)");
    }

    @Test
    void fromJsonRoundTripsAProfileCarryingTheNewAxes() throws Exception {
        SynthesisProfile profile = SynthesisProfile.of(chainProductModel());
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(profile);
        SynthesisProfile parsed = ModelProfileJson.read(json);
        assertEquals(profile, parsed, "fromJson round-trips a profile carrying the new M1-M5 axes");
    }

    @Test
    void distributionsCaptureConditionShape() {
        SynthesisProfile p = SynthesisProfile.of(modelWithRules());
        assertTrue(p.rules().depth().max() >= 2, "the And-of-comparisons rule nests");
        assertTrue(p.rules().nodeCount().max() >= 3, "conditions have operand leaves");
        assertTrue(p.rules().booleanArity().max() >= 2, "the And rule has fan-out 2");
    }

    private static String readResource(String path) {
        try (InputStream in = SynthesisProfileExtractionTest.class.getClassLoader().getResourceAsStream(path)) {
            assertTrue(in != null, "missing test resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
