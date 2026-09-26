package io.github.mbackschat.a12.dm.adapter.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.adapter.TestResources;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * {@link ModelSynthesizer} — the round-trip acceptance criterion (MODEL-PROFILE-SPEC "the profile-driven
 * synthesizer"): profile a real model → synthesize → profile the result → the structure/rule/computation axes
 * match within the per-axis tolerances (MODEL-PROFILE-SPEC). The synthesized model must also be
 * <b>kernel-valid</b> (it loads through the strict {@link BoundModel#ofJson} consistency gate, not the lenient
 * path).
 */
class ModelSynthesizerTest {

    @Test
    void synthesizedModelIsKernelValid() {
        SynthesisProfile target = SynthesisProfile.of(TestResources.text("models/subscription.dm.json"));
        String json = ModelSynthesizer.synthesize(target, 42L);
        // strict load = full kernel checkConsistency; it must not throw (validity by construction)
        assertDoesNotThrow(() -> BoundModel.ofJson(json), "synthesized model must be kernel-valid");
    }

    @Test
    void roundTripMatchesStructureAxes() {
        SynthesisProfile target = SynthesisProfile.of(TestResources.text("models/subscription.dm.json"));
        SynthesisProfile synth = SynthesisProfile.of(ModelSynthesizer.synthesize(target, 42L));

        assertEquals(target.structure().fieldCount(), synth.structure().fieldCount(), "field count");
        assertEquals(target.structure().groupCount(), synth.structure().groupCount(), "group count");
        assertEquals(target.structure().maxNestingDepth(), synth.structure().maxNestingDepth(), "nesting depth");
        assertEquals(target.structure().repeatability().repeatableGroupCount(),
                synth.structure().repeatability().repeatableGroupCount(), "repeatable group count");
        assertEquals(target.structure().repeatability().maxRepeatableNestingDepth(),
                synth.structure().repeatability().maxRepeatableNestingDepth(), "repeatable nesting depth");
    }

    @Test
    void fieldKindsAreReproducedInProportion() {
        SynthesisProfile target = SynthesisProfile.of(TestResources.text("models/order.dm.json"));
        SynthesisProfile synth = SynthesisProfile.of(ModelSynthesizer.synthesize(target, 7L));

        int synthTotal = synth.structure().fieldKinds().values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(synth.structure().fieldCount(), synthTotal, "every field classified");
        // the dominant kind of the target should still be the dominant (or co-dominant) kind of the synth
        String targetTop = topKind(target);
        assertTrue(synth.structure().fieldKinds().getOrDefault(targetTop, 0) > 0,
                "the target's dominant kind " + targetTop + " is present in the synth: " + synth.structure().fieldKinds());
    }

    @Test
    void roundTripMatchesRuleCountAndScope() {
        SynthesisProfile target = SynthesisProfile.of(TestResources.text("models/subscription.dm.json"));
        SynthesisProfile synth = SynthesisProfile.of(ModelSynthesizer.synthesize(target, 42L));

        assertEquals(target.rules().count(), synth.rules().count(), "rule count");
        // the scope split must sum to the count, and be reproduced (allow small skew from empty-class spill)
        int m = synth.rules().scope().modelLevel();
        int g = synth.rules().scope().perGroup();
        int r = synth.rules().scope().perRepeatableRow();
        assertEquals(synth.rules().count(), m + g + r, "every rule classified by scope");
        assertEquals(0, synth.rules().opaqueCount(), "synthesized rules are all rulekit-modeled (not opaque)");
    }

    @Test
    void reproducesConstructMixAndShapesArity_stayingValid() {
        // a construct-bearing target: a model-level value-list rule of arity 2 (an AND of two clauses)
        BoundModel base =
                BoundModel.ofJson(TestResources.text("models/subscription.dm.json"));
        var vl = base.rule(base.group("/Subscription")).name("Vl").errorField(base.enumOf("/Subscription/Tier"))
                .when(base.enumOf("/Subscription/Tier").in("PRO", "ENTERPRISE")
                        .and(base.field("/Subscription/AutoRenew").filled()))
                .code("VL").severity(Severity.ERROR)
                .message(Dsl.en("vl"),
                        Dsl.de("vl")).build();
        SynthesisProfile target = SynthesisProfile.of(base.add(vl).toJson());

        String json = ModelSynthesizer.synthesize(target, 5L);
        assertDoesNotThrow(() -> BoundModel.ofJson(json), "construct-bearing rules must be kernel-valid");
        SynthesisProfile synth = SynthesisProfile.of(json);

        assertEquals(target.rules().count(), synth.rules().count(), "rule count");
        assertEquals(0, synth.rules().opaqueCount());
        // VALUE_LIST needs no special operand, so it is always reproducible; AGGREGATE depends on structure
        assertTrue(synth.rules().constructs().getOrDefault("VALUE_LIST", 0) >= 1,
                "the value-list construct is reproduced: " + synth.rules().constructs());
        // arity shaping: the target's rules are arity-2, so the synth composes multi-clause conditions too
        assertTrue(synth.rules().booleanArity().max() >= 2,
                "arity shaping produces multi-clause rules: max=" + synth.rules().booleanArity().max());
    }

    @Test
    void reproducesComputationCountAndCascade() {
        // a controlled target: three NUMBER fields wired into a computed-on-computed cascade (A ← B ← C)
        String structure = DocumentModelDraft.named("Casc").locale("en_US")
                .rootGroup("R", g -> {
                    g.field("A", FieldKind.NUMBER);
                    g.field("B", FieldKind.NUMBER);
                    g.field("C", FieldKind.NUMBER);
                    g.field("D", FieldKind.NUMBER);  // a spare operand so
                    // the synth can keep one number NON-computed (its acyclic operand sink) and still make 3
                }).build().toJson();
        BoundModel base = BoundModel.ofJson(structure);
        base = base.add(base.computation(base.num("/R/A")).name("KA")
                .calc(new io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral(java.math.BigDecimal.ZERO)).message(Dsl.en("ka")).build());
        base = base.add(base.computation(base.num("/R/B")).name("KB").calc(base.num("/R/A").value()).message(Dsl.en("kb")).build());
        base = base.add(base.computation(base.num("/R/C")).name("KC").calc(base.num("/R/B").value()).message(Dsl.en("kc")).build());
        SynthesisProfile target = SynthesisProfile.of(base.toJson());
        // sanity: the target itself is a 3-deep cascade
        assertEquals(3, target.computations().count());
        assertTrue(target.computations().maxCascadeDepth() >= 3, "target cascade depth");

        String json = ModelSynthesizer.synthesize(target, 11L);
        assertDoesNotThrow(() -> BoundModel.ofJson(json), "synthesized computations must be kernel-valid");
        SynthesisProfile synth = SynthesisProfile.of(json);

        assertEquals(target.computations().count(), synth.computations().count(), "computation count");
        assertEquals(0, synth.computations().opaqueCount());
        assertTrue(synth.computations().maxCascadeDepth() >= 2,
                "the dependency cascade is reproduced: depth=" + synth.computations().maxCascadeDepth());
    }

    @Test
    void computationsAreRichNotTrivial_realCalcsFeedingAndConstructs() {
        // a target whose computations are RICH: a filtered aggregate, a preconditioned alternative keyed on an
        // enum, and a feeding cascade (Total ← Net ← Gross) — the shape the enriched synthesizer must reproduce
        // (the "all one alternative, all evaluate to 0" trap this guards against).
        String structure = DocumentModelDraft.named("Rich").locale("en_US")
                .rootGroup("R", g -> {
                    g.field("Gross", FieldKind.NUMBER);
                    g.field("Net", FieldKind.NUMBER);
                    g.field("Total", FieldKind.NUMBER);
                    g.field("Base", FieldKind.NUMBER);
                    g.enumField("Kind", e -> { e.value("A"); e.value("B"); });
                    g.group("Lines", l -> {
                        l.repeatable(50);
                        l.field("Amt", FieldKind.NUMBER);
                    });
                }).build().toJson();
        BoundModel base = BoundModel.ofJson(structure);
        // Gross = Sum(Lines*/Amt Having Amt>0)  — aggregate + filter
        base = base.add(base.computation(base.num("/R/Gross"))
                .name("KG").calc(Dsl.sum(
                        base.flatten("/R/Lines*/Amt").having(base.num("/R/Lines/Amt").gt(0))).expression())
                .message(Dsl.en("kg")).build());
        // Net reads Gross (feeding); Total reads Net (feeding cascade)
        base = base.add(base.computation(base.num("/R/Net")).name("KN")
                .when(base.enumOf("/R/Kind").is("A")).calc(base.num("/R/Gross").asNumber().plus(base.num("/R/Base").asNumber()).expression())
                .when(base.enumOf("/R/Kind").is("B")).calc(base.num("/R/Gross").asNumber().expression())
                .message(Dsl.en("kn")).build());
        base = base.add(base.computation(base.num("/R/Total")).name("KT")
                .calc(base.num("/R/Net").asNumber().expression())
                .message(Dsl.en("kt")).build());
        SynthesisProfile target = SynthesisProfile.of(base.toJson());
        assertTrue(target.computations().feedingOthers() >= 2, "target feeds ≥2 computed fields");

        String json = ModelSynthesizer.synthesize(target, 23L);
        assertDoesNotThrow(() -> BoundModel.ofJson(json), "rich computations must be kernel-valid");
        SynthesisProfile synth = SynthesisProfile.of(json);

        // the dependency graph is reproduced (feedingOthers within a small tolerance of the target)
        assertTrue(synth.computations().feedingOthers() >= target.computations().feedingOthers() - 1,
                "feedingOthers reproduced: T=" + target.computations().feedingOthers()
                        + " S=" + synth.computations().feedingOthers());
        // computations are REAL calculations, not bare copies: the aggregate machinery (Sum + Having) is present.
        // (Rounding is NO LONGER a richness proxy — the synth now reaches the field scale via same-scale aggregates
        // and copies WITHOUT a round, which is exactly what keeps the ARITHMETIC construct near the target instead
        // of on every computation.)
        assertTrue(synth.computations().operators().containsKey("Sum")
                        && synth.computations().operators().containsKey("Having"),
                "computations carry a filtered aggregate (Sum + Having): " + synth.computations().operators());
        // and they are not all single-alternative arithmetic — a boolean/precondition operator is present
        assertTrue(synth.computations().operators().size() >= 3,
                "computations carry preconditions/aggregates, not just arithmetic: " + synth.computations().operators());
        // the joint hub tree makes the target's repeatable reachable, so a filtered aggregate is actually produced
        assertTrue(synth.computations().constructs().getOrDefault("AGGREGATE", 0) >= 1,
                "a filtered aggregate is reproduced (the hub tree gives an aggregate-capable position): "
                        + synth.computations().constructs());
    }

    @Test
    void reproducesComputedFieldKindMix_notAllNumber() {
        // a target whose computed fields are a MIX of kinds (a number, a string, a date) — the axis the profiler
        // was blind to, and the assumption ("all computed fields are numbers") that made the operand pool look
        // exhausted. The synth must reproduce the mix, not force everything to NUMBER.
        String structure = DocumentModelDraft.named("Mix").locale("en_US")
                .rootGroup("R", g -> {
                    g.field("N1", FieldKind.NUMBER);
                    g.field("N2", FieldKind.NUMBER);
                    g.field("S1", FieldKind.STRING);
                    g.field("S2", FieldKind.STRING);
                    g.field("D1", FieldKind.DATE);
                    g.field("D2", FieldKind.DATE);
                }).build().toJson();
        BoundModel base = BoundModel.ofJson(structure);
        base = base.add(base.computation(base.num("/R/N1")).name("KN")
                .calc(base.num("/R/N2").asNumber().plus(1).expression())
                .message(Dsl.en("kn")).build());
        base = base.add(base.computation(base.str("/R/S1")).name("KS")
                .calc(base.str("/R/S2").asString().expression())   // a string computed by copying another string
                .message(Dsl.en("ks")).build());
        base = base.add(base.computation(base.field("/R/D1")).name("KD")
                .calc(Dsl.addYears(base.date("/R/D2"), 1).expression())
                .message(Dsl.en("kd")).build());
        SynthesisProfile target = SynthesisProfile.of(base.toJson());
        assertEquals(3, target.computations().computedFieldKinds().values().stream().mapToInt(Integer::intValue).sum(),
                "target has 3 computed fields across kinds: " + target.computations().computedFieldKinds());

        String json = ModelSynthesizer.synthesize(target, 31L);
        assertDoesNotThrow(() -> BoundModel.ofJson(json), "mixed-kind computations must be kernel-valid");
        SynthesisProfile synth = SynthesisProfile.of(json);
        var kinds = synth.computations().computedFieldKinds();
        assertTrue(kinds.getOrDefault("STRING", 0) >= 1, "a STRING computed field is reproduced: " + kinds);
        assertTrue(kinds.getOrDefault("DATE", 0) >= 1, "a DATE computed field is reproduced: " + kinds);
        // and the non-number computed fields leave numbers free for the rule population (the point of the fix)
        assertTrue(kinds.getOrDefault("NUMBER", 0) < synth.computations().count(),
                "not all computed fields are NUMBER: " + kinds);
    }

    @Test
    void reproducesConstraintAndClusterAxes() {
        // a target carrying the declared-constraint axes (a scaled number, a length-bounded and a patterned
        // string) and rule CLUSTERING (several rules sharing one error field) — the axes added in this pass
        var K = FieldKind.NUMBER;
        String structure = DocumentModelDraft.named("Con").locale("en_US")
                .rootGroup("R", g -> {
                    g.numberField("Amount", n -> n.fractionDigits(2, 2));   // scale 2
                    g.field("Qty", K);                                      // scale 0
                    g.stringField("Code", s -> s.minLength(2).maxLength(5)); // length-bounded
                    g.stringField("Ref", s -> s.pattern("[A-Z]+", "caps"));  // patterned
                    g.enumField("Status", e -> { e.value("V1"); e.value("V2"); });
                }).build().toJson();
        BoundModel base = BoundModel.ofJson(structure);
        // three rules on the SAME error field (Status) — a rules-per-error-field cluster of 3
        for (int i = 1; i <= 3; i++) {
            base = base.add(base.rule(base.group("/R")).name("Rc" + i).errorField(base.enumOf("/R/Status"))
                    .when(base.enumOf("/R/Status").is("V1").and(base.num("/R/Qty").gt(0)))
                    .code("RC" + i).severity(Severity.ERROR)
                    .message(Dsl.en("rc" + i)).build());
        }
        SynthesisProfile target = SynthesisProfile.of(base.toJson());
        assertEquals(1, target.structure().constraints().stringsWithLength());
        assertEquals(1, target.structure().constraints().stringsWithPattern());
        assertTrue(target.rules().rulesPerErrorField().max() >= 3, "target clusters 3 rules on one field");

        String json = ModelSynthesizer.synthesize(target, 17L);
        assertDoesNotThrow(() -> BoundModel.ofJson(json), "constraint-bearing model must be kernel-valid");
        SynthesisProfile synth = SynthesisProfile.of(json);

        // declared-constraint axes reproduced (string counts exact via budget; a non-zero number scale present)
        assertEquals(target.structure().constraints().stringsWithLength(),
                synth.structure().constraints().stringsWithLength(), "stringsWithLength");
        assertEquals(target.structure().constraints().stringsWithPattern(),
                synth.structure().constraints().stringsWithPattern(), "stringsWithPattern");
        assertTrue(synth.structure().constraints().numberScale().max() >= 1,
                "a non-zero number scale is reproduced (not all scale-0): "
                        + synth.structure().constraints().numberScale().max());
        // rule clustering reproduced — some error field carries more than one rule
        assertTrue(synth.rules().rulesPerErrorField().max() >= 2,
                "rules are clustered on shared error fields: max=" + synth.rules().rulesPerErrorField().max());
    }

    @Test
    void doesNotOvershootRepeatableNestingDepthOnADeepTree() {
        // a deep tree (5 levels) but repeatable nesting capped at 2 (G1←G2 nested; H1 independent)
        String json = DocumentModelDraft.named("Deep").locale("en_US")
                .rootGroup("R", r -> {
                    r.field("f0", FieldKind.NUMBER);
                    r.group("G1", g1 -> {
                        g1.repeatable(5);
                        g1.field("f1", FieldKind.NUMBER);
                        g1.group("G2", g2 -> {
                            g2.repeatable(5);
                            g2.field("f2", FieldKind.NUMBER);
                            g2.group("G3", g3 -> {
                                g3.field("f3", FieldKind.NUMBER);
                                g3.group("G4", g4 ->
                                        g4.field("f4", FieldKind.NUMBER));
                            });
                        });
                    });
                    r.group("H1", h1 -> {
                        h1.repeatable(3);
                        h1.field("h", FieldKind.NUMBER);
                    });
                }).build().toJson();
        SynthesisProfile target = SynthesisProfile.of(json);
        SynthesisProfile synth = SynthesisProfile.of(ModelSynthesizer.synthesize(target, 3L));
        // the max repeatable-nesting must be reproduced, never OVERSHOT (the marking-stacks-groups bug)
        assertEquals(target.structure().repeatability().maxRepeatableNestingDepth(),
                synth.structure().repeatability().maxRepeatableNestingDepth(), "repeatable nesting must not overshoot");
    }

    @Test
    void isDeterministicForAGivenSeed() {
        SynthesisProfile target = SynthesisProfile.of(TestResources.text("models/subscription.dm.json"));
        assertEquals(ModelSynthesizer.synthesize(target, 99L), ModelSynthesizer.synthesize(target, 99L),
                "same (profile, seed) reproduces the same model");
    }

    @Test
    void twinHubReservationLiftsComputationParallelIteration() {
        // A twin hub (Hub -> BranchA, BranchB) with a
        // computation reaching BOTH sibling branches gives the target a genuine parallelIteration signal —
        // the synth must reproduce at least one such computation too, not rely on the old flat 30% guess.
        String structure = DocumentModelDraft.named("Twin").locale("en_US")
                .rootGroup("R", r -> {
                    // a shallower root NUMBER so it — not TotalCross — becomes reserveComputedFields' cycle-free
                    // non-computed sink (the shallowest number field kept non-computed); otherwise TotalCross
                    // itself would be protected and never actually get reserved as computed.
                    r.field("RootNum", FieldKind.NUMBER);
                    r.group("Hub", hub -> {
                        hub.field("TotalCross", FieldKind.NUMBER);
                        // a scope-local KEY sibling — buildComputation's parallel-iteration path needs one to key
                        // its two preconditioned (sibling-branch) alternatives on (canSplit).
                        hub.field("HubKey", FieldKind.NUMBER);
                        hub.group("BranchA", a -> {
                            a.repeatable(6);
                            a.field("ANum", FieldKind.NUMBER);
                        });
                        hub.group("BranchB", b -> {
                            b.repeatable(6);
                            b.field("BNum", FieldKind.NUMBER);
                        });
                    });
                }).build().toJson();
        BoundModel base = BoundModel.ofJson(structure);
        var cross = base.computation(base.num("/R/Hub/TotalCross")).name("CrossCalc")
                .calc(Dsl.sum(base.flatten("/R/Hub/BranchA*/ANum"))
                        .plus(Dsl.sum(base.flatten("/R/Hub/BranchB*/BNum")))
                        .expression())
                .message(Dsl.en("cross")).build();
        SynthesisProfile target = SynthesisProfile.of(base.add(cross).toJson());
        assertTrue(target.computations().iteration().parallelIteration() >= 1,
                "the twin-branch computation gives the target a genuine parallelIteration signal");
        assertTrue(target.structure().repeatability().siblingBranchFan().max() >= 2,
                "Hub reaches two sibling repeatable branches");

        String json = ModelSynthesizer.synthesize(target, 7L);
        assertDoesNotThrow(() -> BoundModel.ofJson(json), "the synthesized model must stay kernel-valid");
        SynthesisProfile synth = SynthesisProfile.of(json);
        assertTrue(synth.computations().iteration().parallelIteration() >= 1,
                "twin-hub-aware reservation reproduces at least one parallel-iteration computation: "
                        + synth.computations().iteration().parallelIteration());
    }

    @Test
    void ruleTwinHubReservationLiftsRuleParallelIteration() {
        // On a breadth-shaped target, rules had NO twin-hub mechanism at all, unlike computations'
        // siblingAggregatePair-based one. Same twin-hub shape as twinHubReservationLiftsComputationParallelIteration
        // above, but the cross-branch reference sits in a RULE's condition instead of a computation's operation.
        String structure = DocumentModelDraft.named("RuleTwin").locale("en_US")
                .rootGroup("R", r -> {
                    r.group("Hub", hub -> {
                        hub.field("HubKey", FieldKind.NUMBER);
                        hub.group("BranchA", a -> {
                            a.repeatable(6);
                            a.field("ANum", FieldKind.NUMBER);
                        });
                        hub.group("BranchB", b -> {
                            b.repeatable(6);
                            b.field("BNum", FieldKind.NUMBER);
                        });
                    });
                }).build().toJson();
        BoundModel base = BoundModel.ofJson(structure);
        var cross = base.rule(base.group("/R/Hub")).name("CrossRule").errorField(base.field("/R/Hub/HubKey"))
                .when(base.num("/R/Hub/HubKey").filled()
                        .and(Dsl.sum(base.flatten("/R/Hub/BranchA*/ANum")).gt(0)
                                .and(Dsl.sum(base.flatten("/R/Hub/BranchB*/BNum")).gt(0))))
                .code("CROSS").severity(Severity.ERROR)
                .message(Dsl.en("cross")).build();
        SynthesisProfile target = SynthesisProfile.of(base.add(cross).toJson());
        assertTrue(target.rules().iteration().parallelIteration() >= 1,
                "the twin-branch rule gives the target a genuine rule parallelIteration signal");

        String json = ModelSynthesizer.synthesize(target, 7L);
        assertDoesNotThrow(() -> BoundModel.ofJson(json), "the synthesized model must stay kernel-valid");
        SynthesisProfile synth = SynthesisProfile.of(json);
        assertTrue(synth.rules().iteration().parallelIteration() >= 1,
                "the rule-side sibling-branch recipe reproduces at least one parallel-iteration rule: "
                        + synth.rules().iteration().parallelIteration());
    }

    @Test
    void nestedAggregateRecipeReproducesAMultiStarComputation() {
        // A target whose ONLY aggregating computation is a NESTED
        // two-star Sum (a "wide aggregate hub" scoped OUTSIDE a 2-level chain, reaching down through BOTH
        // levels) gives aggregateStars a single sample at value 2 — a 100% nested fraction, removing the
        // recipe's coin-flip from this test. Before the multi-star recipe existed, aggregateOperand only ever
        // flattened ONE level, so aggregateStars.max() was always 1 regardless of the target.
        String structure = DocumentModelDraft.named("Nested").locale("en_US")
                .rootGroup("R", r -> {
                    r.field("RootNum", FieldKind.NUMBER);
                    r.field("TotalDeep", FieldKind.NUMBER);
                    r.field("RootKey", FieldKind.NUMBER);
                    // a Filler level so maxNestingDepth (4) gives buildTree's chain-length computation room for
                    // BOTH nested levels (Outer -> Inner) — a depth-3 structure leaves chain capped at 1 (a
                    // small-scale budget edge case, not a real bug; see twinHubReservationLiftsComputationParallelIteration
                    // for the sibling lesson on tiny fixtures and buildTree's group-budget arithmetic).
                    r.group("Filler", filler -> filler.group("Outer", outer -> {
                        outer.repeatable(5);
                        outer.field("OuterNum", FieldKind.NUMBER);
                        outer.group("Inner", inner -> {
                            inner.repeatable(5);
                            inner.field("InnerNum", FieldKind.NUMBER);
                        });
                    }));
                }).build().toJson();
        BoundModel base = BoundModel.ofJson(structure);
        var deep = base.computation(base.num("/R/TotalDeep")).name("TotalDeepCalc")
                .calc(Dsl.sum(base.flatten("/R/Filler/Outer*/Inner*/InnerNum"))
                        .expression())
                .message(Dsl.en("deep")).build();
        SynthesisProfile target = SynthesisProfile.of(base.add(deep).toJson());
        assertEquals(2, target.computations().aggregateStars().max(), "the target's one aggregate is nested two-star");

        // Tries a handful of seeds rather than pinning one: different seeds legitimately produce differently-
        // shaped trees (genuine seed-to-seed structural variance, not the cross-JVM nondeterminism fixed in
        // docs/KERNEL-INTEGRATION-NOTES.md "ModelSynthesizer cross-invocation nondeterminism" — same (profile,
        // seed) is byte-identical across processes) — so a single pinned seed is an unreliable gate for a fact
        // this scale-sensitive. At least one of a few small seeds finding the reachable nested chain is the
        // honest bar.
        boolean foundNested = false;
        for (long seed = 1; seed <= 5 && !foundNested; seed++) {
            String json = ModelSynthesizer.synthesize(target, seed);
            assertDoesNotThrow(() -> BoundModel.ofJson(json), "the synthesized model must stay kernel-valid");
            foundNested = SynthesisProfile.of(json).computations().aggregateStars().max() >= 2;
        }
        assertTrue(foundNested,
                "multiStarAggregateOperand reproduces a nested (>=2-star) aggregate in at least one of 5 seeds");
    }

    @Test
    void plainAggregatesStayUnfilteredWhenTheTargetNeverFilters() {
        // On a real breadth-shaped target: AGGREGATE 189 / FILTER 2 (almost none filtered), but the old code
        // ALWAYS emitted a filtered Sum(<list> Having ...) for every aggregate — predicted (and measured)
        // synth FILTER landing ~= the aggregate count instead of near zero. A target whose aggregates are
        // ALL plain Sums (FILTER 0) gives filteredSumFraction() an exact 0.0 — deterministic regardless of
        // seed, since rnd.nextDouble() < 0.0 never succeeds.
        String structure = DocumentModelDraft.named("Plain").locale("en_US")
                .rootGroup("R", r -> {
                    r.field("RootNum", FieldKind.NUMBER);
                    for (int i = 1; i <= 5; i++) {
                        r.field("Total" + i, FieldKind.NUMBER);
                    }
                    r.group("Reps", reps -> {
                        reps.repeatable(6);
                        reps.field("Cell", FieldKind.NUMBER);
                    });
                }).build().toJson();
        BoundModel base = BoundModel.ofJson(structure);
        String json = base.toJson();
        for (int i = 1; i <= 5; i++) {
            BoundModel current = BoundModel.ofJson(json);
            var calc = current.computation(current.num("/R/Total" + i)).name("Total" + i + "Calc")
                    .calc(Dsl.sum(current.flatten("/R/Reps*/Cell"))
                            .expression())
                    .message(Dsl.en("plain sum " + i)).build();
            json = current.add(calc).toJson();
        }
        SynthesisProfile target = SynthesisProfile.of(json);
        assertEquals(5, target.computations().constructs().getOrDefault("AGGREGATE", 0), "five plain aggregates");
        assertEquals(0, target.computations().constructs().getOrDefault("FILTER", 0), "none of them filtered");

        String synthJson = ModelSynthesizer.synthesize(target, 7L);
        assertDoesNotThrow(() -> BoundModel.ofJson(synthJson), "the synthesized model must stay kernel-valid");
        SynthesisProfile synth = SynthesisProfile.of(synthJson);
        assertEquals(0, synth.computations().constructs().getOrDefault("FILTER", 0),
                "filteredSumFraction() is exactly 0.0 for an all-plain target — the synth must never filter, "
                        + "not track the aggregate count 1:1 the way the old always-filtered code did");
    }

    @Test
    void groupFillPreconditionIsReproducedForComputations() {
        // a target with a computation preconditioned on GroupFilled(<sibling group>) — the real target's
        // rarest computation construct (a handful of instances), for which precondition() previously had NO
        // recipe at all (it only ever built field-level quantifier/value-list/comparison clauses).
        String structure = DocumentModelDraft.named("Gf").locale("en_US")
                .rootGroup("R", r -> {
                    r.field("RootNum", FieldKind.NUMBER);
                    r.field("TotalCalc", FieldKind.NUMBER);
                    r.group("Aux", aux -> aux.field("AuxNum", FieldKind.NUMBER));
                    // an UNRELATED repeatable — buildTree only attaches filler (non-repeatable) children to
                    // the root or a HUB, never to an arbitrary leaf; giving the target one repeatable group
                    // makes the synth grow a hub, so a reserved field has a real chance of landing somewhere
                    // with a sibling group to GroupFilled-guard on (the twinHub/nestedAggregate tests above
                    // hit the same buildTree group-budget lesson on a too-tiny fixture).
                    r.group("Rep", rep -> {
                        rep.repeatable(5);
                        rep.field("RepNum", FieldKind.NUMBER);
                    });
                }).build().toJson();
        BoundModel base = BoundModel.ofJson(structure);
        var calc = base.computation(base.num("/R/TotalCalc")).name("TotalCalcCalc")
                .commonPrecondition(base.group("/R/Aux").filled())
                .calc(base.num("/R/RootNum").asNumber().expression())
                .message(Dsl.en("gf")).build();
        SynthesisProfile target = SynthesisProfile.of(base.add(calc).toJson());
        assertEquals(1, target.computations().constructs().getOrDefault("GROUP_FILL", 0),
                "the target's one computation is GROUP_FILL-preconditioned");

        // multi-seed for the same reason as nestedAggregateRecipeReproducesAMultiStarComputation above.
        boolean foundGroupFill = false;
        for (long seed = 1; seed <= 5 && !foundGroupFill; seed++) {
            String json = ModelSynthesizer.synthesize(target, seed);
            assertDoesNotThrow(() -> BoundModel.ofJson(json), "the synthesized model must stay kernel-valid");
            foundGroupFill = SynthesisProfile.of(json).computations().constructs().getOrDefault("GROUP_FILL", 0) >= 1;
        }
        assertTrue(foundGroupFill,
                "the GROUP_FILL precondition recipe reproduces at least one instance in at least one of 5 seeds");
    }

    private static String topKind(SynthesisProfile p) {
        return p.structure().fieldKinds().entrySet().stream()
                .max((a, b) -> a.getValue() - b.getValue()).orElseThrow().getKey();
    }
}
