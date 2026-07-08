package io.github.mbackschat.a12.dm.rulekit.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

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
        }
    }

    /**
     * The stub-entries policy (PROJECT-DESIGN §9): a {@code SUPPORTED} operator must carry a runnable example so
     * it can't silently escape the kernel-validation harness — a condition {@code validExample} (N7) or,
     * for a <b>computation-only</b> operator with no condition form (the {@code DateRange} constructor), a
     * {@code computationExample} (its N7 peer). Only {@code PLANNED} stubs are exempt.
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
     * A PLANNED stub (if any) is described but example-free. The {@code DateRange} <em>constructor</em> is
     * the one built-but-condition-inexpressible operator: SUPPORTED via a {@code computationExample}, with
     * no {@code validExample} (a constructed date range has no condition use site).
     */
    @Test
    void plannedStubsCarryNoExampleAndDateRangeIsComputationOnly() {
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
        assertNull(dateRange.validExample(), "DateRange has no condition form — no validExample");
        assertNotNull(dateRange.computationExample(), "DateRange is verified via a computationExample");
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

    /** The catalog kind tokens the facet machinery speaks (CATALOG-SPEC §3). */
    private static final java.util.Set<String> KIND_TOKENS = java.util.Set.of(
            "number", "string", "enum", "boolean", "confirm", "date", "time", "date-time", "date-range");

    /** Operand-class tokens a corrective's {@code whenOperands} may use: a kind, or a literal marker. */
    private static final java.util.Set<String> OPERAND_CLASS_TOKENS = java.util.Set.of(
            "number", "string", "enum", "boolean", "confirm", "date", "time", "date-time", "date-range",
            "string-literal", "number-literal");

    /**
     * The header {@code emptyOperandDefaults} table states the per-kind empty behavior ONCE
     * (KERNEL-SEMANTICS §2's type asymmetry) — every comparable kind present, each with a basis and
     * at least one executable probe (the facet lane runs them against the real kernel).
     */
    @Test
    void headerDeclaresEmptyOperandDefaultsForEveryComparableKind() {
        var defaults = CATALOG.emptyOperandDefaults();
        for (String kind : java.util.List.of("number", "string", "enum", "boolean", "confirm",
                "date", "time", "date-time")) {
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
     * Every {@code correctives} entry is well-formed: a kernel code, a non-empty operand-class match,
     * the fix text, and a reject probe whose DSL the facet lane executes (must be rejected with the
     * code) — so an operand-conditional fix can't document a trigger that no longer exists.
     */
    @Test
    void everyCorrectiveIsWellFormedAndProbed() {
        for (OperatorEntry e : CATALOG.entries()) {
            for (Corrective c : e.correctives()) {
                String at = e.id() + " corrective " + c.code();
                assertFalse(isBlank(c.code()), () -> e.id() + ": corrective missing code");
                assertFalse(c.whenOperands().isEmpty(), () -> at + ": whenOperands must not be empty");
                c.whenOperands().forEach(t -> assertTrue(OPERAND_CLASS_TOKENS.contains(t),
                        () -> at + ": unknown operand-class token '" + t + "'"));
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
        // a per-kind split: the operand-list Min substitutes an empty number but skips an empty date
        assertEquals(java.util.Optional.of(EmptyBehavior.AS_ZERO),
                CATALOG.emptyOperandBehavior("Min", 0, io.github.mbackschat.a12.dm.core.ValueKind.NUMBER));
        assertEquals(java.util.Optional.of(EmptyBehavior.SKIPPED),
                CATALOG.emptyOperandBehavior("Min", 1, io.github.mbackschat.a12.dm.core.ValueKind.DATE));
        // the membership subject deviates from the number default (3VL — not substituted 0)
        assertEquals(java.util.Optional.of(EmptyBehavior.NOT_EVALUATED),
                CATALOG.emptyOperandBehavior("FieldValueIncludedInValueList", 0,
                        io.github.mbackschat.a12.dm.core.ValueKind.NUMBER));
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
