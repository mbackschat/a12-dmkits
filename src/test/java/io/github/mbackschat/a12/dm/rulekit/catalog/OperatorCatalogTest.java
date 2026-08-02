package io.github.mbackschat.a12.dm.rulekit.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * The operator catalog (PROJECT-DESIGN §9) is the language-neutral spine: a bundled JSON resource the
 * builder, renderer, docs, and LLM context all read from. These are structural self-checks — the
 * grammar-parity coverage test (catalog ↔ lexer terminals) and the CI example validation (N7) are
 * separate, later anchors.
 */
class OperatorCatalogTest {

    private static final OperatorCatalog CATALOG = OperatorCatalog.load();

    @Test
    void loadsSeededEntries() {
        assertFalse(CATALOG.entries().isEmpty(), "catalog should load at least the seed entries");
    }

    @Test
    void discoveryQueriesExploreTheCatalog() {
        // by keyword — either language resolves to the one operator
        assertEquals("FieldFilled", CATALOG.byKeyword("FieldFilled").orElseThrow().id());
        assertEquals("FieldFilled", CATALOG.byKeyword("FeldAngegeben").orElseThrow().id(), "the DE keyword resolves too");
        assertTrue(CATALOG.byKeyword("NotAKeyword").isEmpty());

        // by kind — a family of operators
        assertTrue(CATALOG.byKind(OperatorKind.PREDICATE).stream().anyMatch(e -> e.id().equals("FieldFilled")));
        assertTrue(CATALOG.byKind(OperatorKind.PREDICATE).stream().allMatch(e -> e.kind() == OperatorKind.PREDICATE));

        // by return type
        assertTrue(CATALOG.byReturns("boolean").stream().anyMatch(e -> e.id().equals("And")));
        assertTrue(CATALOG.byReturns("boolean").stream().allMatch(e -> e.returns().equals("boolean")));
    }

    @Test
    void operatorIdsAreUnique() {
        long distinct = CATALOG.entries().stream().map(OperatorEntry::id).distinct().count();
        assertEquals(CATALOG.size(), distinct, "operator ids must be unique");
    }

    @Test
    void everyEntryIsWellFormed() {
        for (OperatorEntry e : CATALOG.entries()) {
            assertFalse(isBlank(e.id()), "entry missing id");
            assertNotNull(e.kind(), () -> e.id() + ": missing kind");
            assertNotNull(e.keywords(), () -> e.id() + ": missing keywords");
            assertFalse(isBlank(e.keywords().en()), () -> e.id() + ": missing en keyword");
            assertFalse(isBlank(e.keywords().de()), () -> e.id() + ": missing de keyword");
            assertFalse(isBlank(e.returns()), () -> e.id() + ": missing returns");
            assertFalse(isBlank(e.meaning()), () -> e.id() + ": missing meaning");
            if (e.invalidExample() != null) {
                assertFalse(isBlank(e.invalidExample().dsl()), () -> e.id() + ": invalid example missing dsl");
                assertFalse(isBlank(e.invalidExample().error()), () -> e.id() + ": invalid example missing error");
                assertFalse(isBlank(e.invalidExample().fix()), () -> e.id() + ": invalid example missing fix");
            }
        }
    }

    @Test
    void polymorphicOperatorsPublishTheirMeasuredOperandAndResultDomains() {
        OperatorEntry date = CATALOG.byId("Date").orElseThrow();
        assertTrue(date.operands().stream().allMatch(operand -> operand.type().equals("temporal-component")));

        for (String id : List.of("MaxValue", "MinValue")) {
            OperatorEntry aggregate = CATALOG.byId(id).orElseThrow();
            assertEquals(List.of("sortable-field-list"),
                    aggregate.operands().stream().map(OperatorEntry.Operand::type).toList());
            assertEquals("the operands' common NUMBER, DATE, TIME, or DATETIME kind", aggregate.returns());
            assertEquals(AggregateIdentity.ZERO,
                    aggregate.aggregateIdentityClaim().identityFor("number").orElseThrow());
            assertEquals(AggregateIdentity.NO_VALUE,
                    aggregate.aggregateIdentityClaim().identityFor("date").orElseThrow());
        }

        assertEquals("number-or-date", CATALOG.byId("BaseYear").orElseThrow().returns());
        for (String id : List.of("ValidType", "InvalidType")) {
            assertEquals("string-or-enumeration-field",
                    CATALOG.byId(id).orElseThrow().operands().getFirst().type());
        }
    }

    @Test
    void rejectionOwnershipDoesNotDependOnCorrectiveText() {
        OperatorEntry.InvalidExample evidence = new OperatorEntry.InvalidExample("Bad()", "MVK_X", "");
        assertTrue(OperatorCatalog.documentsError(evidence, List.of(), "MVK_X"),
                "the kernel-rejected code establishes ownership even before a corrective is authored");
    }

    @Test
    void anOperandSpecificCorrectivePrecedesTheGeneralFallback() {
        Corrective general = new Corrective("MVK_X", List.of(), "general", null, null);
        Corrective specific = new Corrective("MVK_X", List.of("number", "string"), "specific", null, null);

        assertEquals("specific", OperatorCatalog.selectCorrectiveFix(
                List.of(general, specific), List.of("string", "number")).orElseThrow());
    }

    /**
     * The stub-entries policy (PROJECT-DESIGN §9): a {@code SUPPORTED} operator must carry a runnable example so
     * it can't silently escape the kernel-validation harness — a condition {@code validExample} (N7) or a
     * {@code computationExample} for an operation-valued use site (its N7 peer). Only {@code PLANNED} stubs are exempt.
     */
    @Test
    void everySupportedEntryHasARunnableExample() {
        for (OperatorEntry e : CATALOG.entries()) {
            if (!e.supported()) {
                continue;
            }
            boolean hasCondition = e.validExample() != null && !isBlank(e.validExample().dsl());
            boolean hasComputation = e.computationExample() != null
                    && !isBlank(e.computationExample().operation()) && !isBlank(e.computationExample().computedField());
            assertTrue(hasCondition || hasComputation,
                    () -> e.id() + ": SUPPORTED entry needs a validExample (condition) or a computationExample");
        }
    }

    /**
     * A PLANNED stub (if any) is described but example-free. DateRange exercises both runnable lanes:
     * equality as a condition and assignment as a computation.
     */
    @Test
    void plannedStubsCarryNoExampleAndDateRangeCoversBothUseSites() {
        for (OperatorEntry e : CATALOG.entries()) {
            if (!e.supported()) {
                assertNull(e.validExample(), () -> e.id() + ": a PLANNED stub carries no validExample");
                assertNull(e.computationExample(), () -> e.id() + ": a PLANNED stub carries no computationExample");
            }
        }
        OperatorEntry dateRange = CATALOG.byId("DateRange").orElseThrow();
        assertTrue(dateRange.supported(), "the DateRange constructor is built (2026-06-08)");
        assertEquals("DateRange", dateRange.keywords().en());
        assertEquals("DatumBereich", dateRange.keywords().de());
        assertNotNull(dateRange.validExample(), "DateRange equality needs a condition example");
        assertNotNull(dateRange.computationExample(), "DateRange assignment needs a computation example");
    }

    @Test
    void exposesVerifiedBilingualKeywords() {
        OperatorEntry and = CATALOG.byId("And").orElseThrow();
        assertEquals("And", and.keywords().en());
        assertEquals("Und", and.keywords().de());

        OperatorEntry length = CATALOG.byId("Length").orElseThrow();
        assertEquals("Laenge", length.keywords().de());

        OperatorEntry fieldFilled = CATALOG.byId("FieldFilled").orElseThrow();
        assertEquals(OperatorKind.PREDICATE, fieldFilled.kind());
        assertEquals("FeldAngegeben", fieldFilled.keywords().de());
    }

    /**
     * The {@code opposite} pairings (FEATURE-GAPS G3) serve the kernel's "no generic negation" model — each
     * predicate names the partner(s) to express its inverse. They must be (a) <b>resolvable</b> (every named
     * id is a real catalog entry — a dangling pointer would mislead an agent) and (b) <b>symmetric</b> (if A
     * names B as an opposite, B names A) so "express the inverse" is one reliable hop in either direction.
     */
    @Test
    void oppositePairingsAreResolvableAndSymmetric() {
        for (OperatorEntry e : CATALOG.entries()) {
            for (String oppId : e.opposite()) {
                OperatorEntry partner = CATALOG.byId(oppId).orElseThrow(() ->
                        new AssertionError(e.id() + ": opposite '" + oppId + "' is not a catalog operator"));
                assertTrue(partner.opposite().contains(e.id()),
                        () -> "opposite must be symmetric: " + e.id() + " names " + oppId
                                + " but " + oppId + " does not name " + e.id());
            }
        }
    }

    /** Spot-check the central negation pairs the BA-doc leans on, so the data is actually authored, not empty. */
    @Test
    void exposesTheCentralNegationPairs() {
        assertOpposite("FieldFilled", "FieldNotFilled");
        assertOpposite("AllFieldsFilled", "NotAllFieldsFilled");
        assertOpposite("NoFieldFilled", "AtLeastOneFieldFilled");
        assertOpposite("PatternMatched", "PatternViolated");
        assertOpposite("Equal", "NotEqual");
        assertOpposite("LessThan", "GreaterThanOrEqual");
        assertOpposite("GreaterThan", "LessThanOrEqual");
    }

    private static void assertOpposite(String a, String b) {
        assertTrue(CATALOG.byId(a).orElseThrow().opposite().contains(b),
                () -> a + " should name " + b + " as its opposite");
        assertTrue(CATALOG.byId(b).orElseThrow().opposite().contains(a),
                () -> b + " should name " + a + " as its opposite");
    }

    // ---- the semantics facets (CATALOG-SPEC §3–§6) ------------------------------------------------

    /** The facet vocabulary is derived from the live typed domain; adding a {@code ValueKind} cannot leave
     *  this guard accepting yesterday's hand-list. UNKNOWN deliberately has no public machine token. */
    private static final java.util.Set<String> KIND_TOKENS = java.util.Arrays.stream(ValueKind.values())
            .map(OperatorCatalog::kindToken)
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    /** Operand-class tokens a corrective's {@code whenOperands} may use: a kind, or a literal marker. */
    private static final java.util.Set<String> OPERAND_CLASS_TOKENS = java.util.stream.Stream.concat(
            KIND_TOKENS.stream(), java.util.stream.Stream.of("string-literal", "number-literal"))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    /**
     * The header {@code emptyOperandDefaults} table states the per-kind empty behavior ONCE
     * (KERNEL-SEMANTICS §2's type asymmetry) — every comparable kind present, each with a basis and
     * at least one executable probe (the facet lane runs them against the real kernel).
     */
    @Test
    void headerDeclaresEmptyOperandDefaultsForEveryComparableKind() {
        var defaults = CATALOG.emptyOperandDefaults();
        var expectedKinds = java.util.Set.of("number", "string", "enum", "boolean", "confirm",
                "date", "time", "date-time");
        assertEquals(expectedKinds, defaults.keySet(), "primitive default header must declare exactly its eight kinds");
        for (String kind : expectedKinds) {
            OperatorCatalog.EmptyOperandDefault d = defaults.get(kind);
            assertNotNull(d, () -> "emptyOperandDefaults missing kind '" + kind + "'");
            assertNotNull(d.behavior(), () -> kind + ": default missing behavior");
            assertFalse(isBlank(d.basis()), () -> kind + ": default missing basis");
            assertFalse(d.probes().isEmpty(), () -> kind + ": default needs at least one probe");
            d.probes().forEach(p -> assertProbeWellFormed(kind + " default", p));
        }
        // the canonical asymmetry: number reads 0, string is not evaluated (KERNEL-SEMANTICS §2)
        assertEquals(EmptyBehavior.AS_ZERO, defaults.get("number").behavior());
        assertEquals(EmptyBehavior.AS_FALSE, defaults.get("confirm").behavior());
        assertEquals(EmptyBehavior.NOT_EVALUATED, defaults.get("string").behavior());
    }

    /**
     * Every {@code semantics.emptyOperand} claim is well-formed: a valid operand selector, exactly one
     * of {@code behavior} / {@code perKind}, a basis (the lock it derives from), and at least one
     * executable probe — a facet value without a probe cannot ship (the no-rot lever, CATALOG-SPEC §5).
     */
    @Test
    void everyEmptyOperandClaimIsWellFormedAndProbed() {
        for (OperatorEntry e : CATALOG.entries()) {
            for (EmptyOperandClaim claim : e.emptyOperandClaims()) {
                String at = e.id() + " emptyOperand[" + claim.operand() + "]";
                assertTrue("all".equals(claim.operand()) || isOperandIndex(claim.operand(), e),
                        () -> at + ": operand must be \"all\" or a 0-based index into operands");
                assertTrue(claim.behavior() != null ^ !claim.perKind().isEmpty(),
                        () -> at + ": exactly one of behavior / perKind");
                claim.perKind().keySet().forEach(k -> assertTrue(KIND_TOKENS.contains(k),
                        () -> at + ": unknown perKind token '" + k + "'"));
                assertFalse(isBlank(claim.basis()), () -> at + ": missing basis (the lock/finding it derives from)");
                assertFalse(claim.probes().isEmpty(), () -> at + ": a facet claim needs at least one probe");
                claim.probes().forEach(p -> assertProbeWellFormed(at, p));
            }
        }
    }

    /**
     * Every {@code correctives} entry is well-formed: a kernel code, an optional operand-class match,
     * the fix text, and a reject probe whose DSL the facet lane executes (must be rejected with the
     * code) — so a fix can't document a trigger that no longer exists. Empty operand selectors are the
     * operator/code-wide fallback and must therefore be unique.
     */
    @Test
    void everyCorrectiveIsWellFormedAndProbed() {
        for (OperatorEntry e : CATALOG.entries()) {
            java.util.Set<String> selectors = new java.util.HashSet<>();
            for (Corrective c : e.correctives()) {
                String at = e.id() + " corrective " + c.code();
                assertFalse(isBlank(c.code()), () -> e.id() + ": corrective missing code");
                c.whenOperands().forEach(t -> assertTrue(OPERAND_CLASS_TOKENS.contains(t),
                        () -> at + ": unknown operand-class token '" + t + "'"));
                if (c.whenOperands().isEmpty()) {
                    long general = e.correctives().stream()
                            .filter(peer -> c.code().equals(peer.code()) && peer.whenOperands().isEmpty())
                            .count();
                    assertEquals(1, general, () -> at + ": only one general corrective may exist per code");
                }
                String selector = c.code() + ":" + c.whenOperands().stream().sorted().toList();
                assertTrue(selectors.add(selector), () -> at + ": duplicate unordered operand selector");
                assertFalse(isBlank(c.fix()), () -> at + ": missing fix");
                assertNotNull(c.probe(), () -> at + ": missing reject probe");
                assertFalse(isBlank(c.probe().dsl()), () -> at + ": reject probe needs a dsl");
            }
        }
    }

    /**
     * Every {@code semantics.boundary} claim is well-formed: a value, a basis (the lock it derives
     * from), and at least one executable probe — the operator-level analogue of the emptyOperand
     * guard (CATALOG-SPEC §3/§5).
     */
    @Test
    void everyBoundaryClaimIsWellFormedAndProbed() {
        for (OperatorEntry e : CATALOG.entries()) {
            BoundaryClaim claim = e.boundaryClaim();
            if (claim == null) {
                continue;
            }
            String at = e.id() + " boundary";
            assertNotNull(claim.value(), () -> at + ": missing value");
            assertFalse(isBlank(claim.basis()), () -> at + ": missing basis (the lock/finding it derives from)");
            assertFalse(claim.probes().isEmpty(), () -> at + ": a facet claim needs at least one probe");
            claim.probes().forEach(p -> assertProbeWellFormed(at, p));
        }
    }

    /**
     * Every {@code semantics.iterationRange} claim is well-formed: a value, a basis, and at least one
     * executable probe (CATALOG-SPEC §3b/§5).
     */
    @Test
    void everyIterationRangeClaimIsWellFormedAndProbed() {
        for (OperatorEntry e : CATALOG.entries()) {
            IterationRangeClaim claim = e.iterationRangeClaim();
            if (claim == null) {
                continue;
            }
            String at = e.id() + " iterationRange";
            assertNotNull(claim.value(), () -> at + ": missing value");
            assertFalse(isBlank(claim.basis()), () -> at + ": missing basis (the lock/finding it derives from)");
            assertFalse(claim.probes().isEmpty(), () -> at + ": a facet claim needs at least one probe");
            claim.probes().forEach(p -> assertProbeWellFormed(at, p));
        }
    }

    @Test
    void aggregateIdentityKindCoverageRejectsMissingDuplicateAndUnknownBranches() {
        Set<String> branches = Set.of("number", "date");
        assertTrue(aggregateProbeKindViolations(branches, List.of("number", "date")).isEmpty());
        assertFalse(aggregateProbeKindViolations(branches, List.of("number")).isEmpty(),
                "a missing per-kind branch must fail");
        assertFalse(aggregateProbeKindViolations(branches, List.of("number", "date", "date")).isEmpty(),
                "a duplicate per-kind branch must fail");
        assertFalse(aggregateProbeKindViolations(branches, List.of("number", "date", "confirm")).isEmpty(),
                "an unclaimed per-kind branch must fail");
    }

    private static List<String> aggregateProbeKindViolations(Set<String> branches, List<String> probeKinds) {
        List<String> violations = new java.util.ArrayList<>();
        if (probeKinds.stream().anyMatch(OperatorCatalogTest::isBlank)) {
            violations.add("every perKind branch probe must name its kind");
        }
        Set<String> distinct = probeKinds.stream().filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        if (distinct.size() != probeKinds.size()) {
            violations.add("each perKind branch must have exactly one probe");
        }
        Set<String> missing = new java.util.TreeSet<>(branches);
        missing.removeAll(distinct);
        if (!missing.isEmpty()) {
            violations.add("missing probe kinds " + missing);
        }
        Set<String> unknown = new java.util.TreeSet<>(distinct);
        unknown.removeAll(branches);
        if (!unknown.isEmpty()) {
            violations.add("unknown probe kinds " + unknown);
        }
        return violations;
    }

    /** The IF49 deviations from the INSTANTIATED default (CATALOG-SPEC §3b): All/NotAll scan the DECLARED
     *  range, FieldsNotCollectivelyFilled MIXES; the count quantifiers carry no claim (default). */
    @Test
    void fieldFillQuantifierRangesFollowIF49() {
        assertEquals(IterationRange.DECLARED, CATALOG.byId("AllFieldsFilled").orElseThrow().iterationRangeClaim().value());
        assertEquals(IterationRange.DECLARED, CATALOG.byId("NotAllFieldsFilled").orElseThrow().iterationRangeClaim().value());
        assertEquals(IterationRange.MIXED, CATALOG.byId("FieldsNotCollectivelyFilled").orElseThrow().iterationRangeClaim().value());
        // the count quantifiers follow the default → no claim
        assertNull(CATALOG.byId("AtLeastOneFieldFilled").orElseThrow().iterationRangeClaim(),
                "AtLeastOneFieldFilled uses the INSTANTIATED default — no deviation claim");
        assertNull(CATALOG.byId("NoFieldFilled").orElseThrow().iterationRangeClaim(),
                "NoFieldFilled uses the INSTANTIATED default — no deviation claim");
    }

    /**
     * Every {@code semantics.aggregateIdentity} claim is well-formed: exactly one of {@code value} /
     * {@code perKind}, known perKind tokens, a basis, and at least one executable probe
     * (CATALOG-SPEC §3c/§5).
     */
    @Test
    void everyAggregateIdentityClaimIsWellFormedAndProbed() {
        for (OperatorEntry e : CATALOG.entries()) {
            AggregateIdentityClaim claim = e.aggregateIdentityClaim();
            if (claim == null) {
                continue;
            }
            String at = e.id() + " aggregateIdentity";
            assertTrue(claim.value() != null ^ !claim.perKind().isEmpty(),
                    () -> at + ": exactly one of value / perKind");
            claim.perKind().keySet().forEach(k -> assertTrue(KIND_TOKENS.contains(k),
                    () -> at + ": unknown perKind token '" + k + "'"));
            assertFalse(isBlank(claim.basis()), () -> at + ": missing basis (the lock/finding it derives from)");
            assertFalse(claim.probes().isEmpty(), () -> at + ": a facet claim needs at least one probe");
            claim.probes().forEach(p -> assertProbeWellFormed(at, p));
            if (!claim.perKind().isEmpty()) {
                List<String> violations = aggregateProbeKindViolations(
                        claim.perKind().keySet(), claim.probes().stream().map(FacetProbe::kind).toList());
                assertTrue(violations.isEmpty(), () -> at + ": " + String.join("; ", violations));
            }
        }
    }

    /** The ZERO-vs-NO_VALUE split (CATALOG-SPEC §3c): number aggregates fold to 0, a date-like fold yields no
     *  value; the per-kind operators (Min/Max/FirstFilledValue) carry both. */
    @Test
    void aggregateIdentitySplitsZeroVsNoValueByKind() {
        assertEquals(AggregateIdentity.ZERO, CATALOG.byId("Sum").orElseThrow().aggregateIdentityClaim().value());
        for (String operator : java.util.List.of("MaxValue", "MinValue", "Max", "Min")) {
            AggregateIdentityClaim claim = CATALOG.byId(operator).orElseThrow().aggregateIdentityClaim();
            assertEquals(java.util.Optional.of(AggregateIdentity.ZERO), claim.identityFor("number"), operator);
            for (String kind : java.util.List.of("date", "time", "date-time")) {
                assertEquals(java.util.Optional.of(AggregateIdentity.NO_VALUE), claim.identityFor(kind),
                        operator + " " + kind);
            }
        }
        AggregateIdentityClaim first = CATALOG.byId("FirstFilledValue").orElseThrow().aggregateIdentityClaim();
        assertEquals(java.util.Optional.of(AggregateIdentity.ZERO), first.identityFor("number"));
        for (String kind : java.util.List.of(
                "boolean", "custom", "string", "enum", "date", "date-fragment", "time", "date-time", "date-range")) {
            assertEquals(java.util.Optional.of(AggregateIdentity.NO_VALUE), first.identityFor(kind), kind);
        }
        assertEquals(EmptyBehavior.NOT_EVALUATED, CATALOG.defaultEmptyBehavior(ValueKind.DATE_FRAGMENT).orElseThrow(),
                "DATE_FRAGMENT keeps its own facet token but rides DATE's primitive empty default");
        assertEquals(java.util.Optional.of(AggregateIdentity.ZERO),
                CATALOG.byId("Min").orElseThrow().aggregateIdentityClaim().identityFor("number"));
        assertEquals(java.util.Optional.of(AggregateIdentity.NO_VALUE),
                CATALOG.byId("Min").orElseThrow().aggregateIdentityClaim().identityFor("date"));
        assertEquals(java.util.Optional.of(AggregateIdentity.NO_VALUE),
                CATALOG.byId("Min").orElseThrow().aggregateIdentityClaim().identityFor("time"));
        assertEquals(java.util.Optional.of(AggregateIdentity.NO_VALUE),
                CATALOG.byId("Min").orElseThrow().aggregateIdentityClaim().identityFor("date-time"));
    }

    /**
     * Every {@code semantics.polarity} claim is well-formed: a value, a basis, and at least one probe —
     * and each polarity probe pins an {@code outcome} (VALUE/OMISSION) with {@code fires: true} (a
     * polarity claim is about HOW it fires, so firing alone is not a verification — CATALOG-SPEC §3d/§5a).
     */
    @Test
    void everyPolarityClaimIsWellFormedAndOutcomeProbed() {
        for (OperatorEntry e : CATALOG.entries()) {
            PolarityClaim claim = e.polarityClaim();
            if (claim == null) {
                continue;
            }
            String at = e.id() + " polarity";
            assertNotNull(claim.value(), () -> at + ": missing value");
            assertFalse(isBlank(claim.basis()), () -> at + ": missing basis (the lock/finding it derives from)");
            assertFalse(claim.probes().isEmpty(), () -> at + ": a facet claim needs at least one probe");
            for (FacetProbe p : claim.probes()) {
                assertProbeWellFormed(at, p);
                assertNotNull(p.outcome(), () -> at + ": a polarity probe must pin an outcome (VALUE/OMISSION)");
                assertEquals(Boolean.TRUE, p.fires(), () -> at + ": an outcome-typed probe must fire");
            }
        }
    }

    /** The IF73 fill classes (CATALOG-SPEC §3d): the date/time value functions are SYMMETRIC, Length and
     *  the fill-count family are GROW_ONLY; the compositional-fill operators carry no claim. */
    @Test
    void fillPolarityClassesFollowIF73() {
        assertEquals(FillPolarity.SYMMETRIC, CATALOG.byId("DifferenceInDays").orElseThrow().polarityClaim().value());
        assertEquals(FillPolarity.SYMMETRIC, CATALOG.byId("YearFromDate").orElseThrow().polarityClaim().value());
        assertEquals(FillPolarity.GROW_ONLY, CATALOG.byId("Length").orElseThrow().polarityClaim().value());
        assertEquals(FillPolarity.GROW_ONLY, CATALOG.byId("NumberOfFilledFields").orElseThrow().polarityClaim().value());
        // the compositional-fill operators carry no scalar claim (truth-table / per-eval fill)
        assertNull(CATALOG.byId("Arithmetic").orElseThrow().polarityClaim(),
                "arithmetic fill is a truth table (IF69) — no per-operator scalar");
        assertNull(CATALOG.byId("Sum").orElseThrow().polarityClaim(),
                "a starred Sum's fill is sign-dependent — no per-operator scalar");
    }

    /** The two non-obvious boundary facts the dimension exists to carry (CATALOG-SPEC §3): the tolerance
     *  band is STRICT (fires only beyond N) and the date-range overlap predicates are INCLUSIVE (closed). */
    @Test
    void toleranceBandIsStrictAndDateRangeOverlapIsInclusive() {
        assertEquals(Boundary.STRICT, CATALOG.byId("DiffersWithToleranceRange1").orElseThrow().boundaryClaim().value());
        assertEquals(Boundary.STRICT, CATALOG.byId("LessThan").orElseThrow().boundaryClaim().value());
        assertEquals(Boundary.INCLUSIVE, CATALOG.byId("LessThanOrEqual").orElseThrow().boundaryClaim().value());
        assertEquals(Boundary.INCLUSIVE, CATALOG.byId("DateRangesOverlap").orElseThrow().boundaryClaim().value());
        assertEquals(Boundary.INCLUSIVE, CATALOG.byId("AtLeastOneDateRangeOverlaps").orElseThrow().boundaryClaim().value());
    }

    /** The worked seed (CATALOG-SPEC §4): {@code Length} deviates from the string default — empty reads 0. */
    @Test
    void lengthCarriesTheEmptyAsZeroFacet() {
        OperatorEntry length = CATALOG.byId("Length").orElseThrow();
        assertEquals(1, length.emptyOperandClaims().size(), "Length carries one emptyOperand claim");
        EmptyOperandClaim claim = length.emptyOperandClaims().get(0);
        assertEquals(EmptyBehavior.AS_ZERO, claim.behavior(),
                "Length of an empty string is 0 — the string family's empty-as-0 backdoor");
        assertTrue(claim.appliesTo(0));
    }

    /** The consumer query (CATALOG-SPEC §8): claim wins over default; default fills the gaps; per-kind splits. */
    @Test
    void emptyOperandBehaviorResolvesClaimThenDefault() {
        // a deviation: Length's string operand reads 0, though the string DEFAULT is not-evaluated
        assertEquals(java.util.Optional.of(EmptyBehavior.AS_ZERO),
                CATALOG.emptyOperandBehavior("Length", 0, io.github.mbackschat.a12.dm.core.ValueKind.STRING));
        // no claim → the kind default (a comparison operand)
        assertEquals(java.util.Optional.of(EmptyBehavior.AS_ZERO),
                CATALOG.emptyOperandBehavior("LessThan", 0, io.github.mbackschat.a12.dm.core.ValueKind.NUMBER));
        assertEquals(java.util.Optional.of(EmptyBehavior.NOT_EVALUATED),
                CATALOG.emptyOperandBehavior("LessThan", 0, io.github.mbackschat.a12.dm.core.ValueKind.STRING));
        // a per-kind split: the operand-list Min substitutes an empty number but skips every date-like kind
        assertEquals(java.util.Optional.of(EmptyBehavior.AS_ZERO),
                CATALOG.emptyOperandBehavior("Min", 0, io.github.mbackschat.a12.dm.core.ValueKind.NUMBER));
        assertEquals(java.util.Optional.of(EmptyBehavior.SKIPPED),
                CATALOG.emptyOperandBehavior("Min", 1, io.github.mbackschat.a12.dm.core.ValueKind.DATE));
        assertEquals(java.util.Optional.of(EmptyBehavior.SKIPPED),
                CATALOG.emptyOperandBehavior("Min", 1, io.github.mbackschat.a12.dm.core.ValueKind.TIME));
        assertEquals(java.util.Optional.of(EmptyBehavior.SKIPPED),
                CATALOG.emptyOperandBehavior("Min", 1, io.github.mbackschat.a12.dm.core.ValueKind.DATE_TIME));
        // the membership subject deviates from the number default (3VL — not substituted 0)
        assertEquals(java.util.Optional.of(EmptyBehavior.NOT_EVALUATED),
                CATALOG.emptyOperandBehavior("FieldValueIncludedInValueList", 0,
                        io.github.mbackschat.a12.dm.core.ValueKind.NUMBER));
    }

    @Test
    void temporalShiftAmountsAreNumericRatherThanInventedIntegerAdmissions() {
        for (String id : List.of("AddDays", "AddMonths", "AddYears", "AddHours", "AddMinutes", "AddSeconds")) {
            OperatorEntry entry = CATALOG.byId(id).orElseThrow();
            String constraints = String.join(" ", entry.constraints());
            assertEquals("number", entry.operands().get(1).type(),
                    () -> id + " must publish the kernel's Number operand shape");
            assertTrue(constraints.contains("numeric amount"), () -> id + " must describe the kernel's Number operand");
            assertFalse(constraints.contains("integer amount"),
                    () -> id + " must not reject the fractional amounts the kernel truncates");
        }
    }

    private static boolean isOperandIndex(String operand, OperatorEntry e) {
        try {
            int i = Integer.parseInt(operand);
            return i >= 0 && i < e.operands().size();
        } catch (NumberFormatException notANumber) {
            return false;
        }
    }

    private static void assertProbeWellFormed(String at, FacetProbe p) {
        assertFalse(isBlank(p.dsl()), () -> at + ": probe missing dsl");
        assertFalse(isBlank(p.errorField()), () -> at + ": probe missing errorField (explicit by contract)");
        assertNotNull(p.fires(), () -> at + ": probe missing fires");
        p.rows().forEach(r -> assertTrue(r.startsWith("/Order"),
                () -> at + ": probe row '" + r + "' must be an absolute /Order path"));
        p.given().keySet().forEach(g -> assertTrue(g.startsWith("/Order/"),
                () -> at + ": probe given path '" + g + "' must be an absolute /Order path"));
        p.empty().forEach(f -> assertTrue(f.startsWith("/Order/"),
                () -> at + ": probe empty path '" + f + "' must be an absolute /Order path"));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
