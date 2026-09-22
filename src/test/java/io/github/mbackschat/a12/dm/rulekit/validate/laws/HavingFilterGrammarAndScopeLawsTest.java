package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListRef;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §9 — what a {@code Having} filter's CONDITION may contain, and which reference in it satisfies
 * the filter's binding requirement. The accept/reject oracle is the kernel itself, so every row here is terminal.
 *
 * <p><b>Why this file exists.</b> a12-kernel-lean's {@code SPEC-2026-09-03-02}, {@code -04}, {@code -05} and the
 * static half of {@code -07} each ask this project to confirm a filter-admission rule against its own estate or
 * to name the row where it diverges. Their rows were measured on the Kernel through this repository's own
 * {@code dmtool}; the rows below re-derive them here through the typed builder, which measures two things at
 * once — whether this project can AUTHOR the shape, and whether the kernel ADMITS it. A shape our builder
 * refused but the kernel accepted would be an over-strict client-side refusal, a defect on sight.
 *
 * <p><b>The gate, stated once.</b> A filter must carry an UNMARKED reference into a level its operand's star
 * reopens. Everything below is a property of that one sentence: it is an occurrence check rather than a
 * satisfiability analysis (the {@code Or} row), it accepts ANY reopened level rather than the innermost (the
 * two-star row), a {@code $}-marked reference does not count because the marker moves the reference out of the
 * filtered level (the marked rows), and it counts REFERENCES rather than field pointers (the
 * {@code CurrentRepetition} rows, whose operands contribute no field pointer at all).
 *
 * <p><b>Two shapes are unrepresentable here rather than refused, and that is this project's answer for them.</b>
 * a12-kernel-lean measured {@code MVK_BRACKET_MISSING} for an unbracketed {@code And}/{@code Or} mix inside a
 * filter, and a comparison-type refusal for a cross-kind leaf. Neither can be authored through the typed
 * builder: {@link DslRenderer} parenthesizes a nested logic node whose operator differs from its parent, so an
 * unbracketed mix has no rendering ({@link #aMixedConnectiveFilterIsAlwaysBracketed}), and the comparison verbs
 * are typed per value kind, so a Number-against-String leaf has no method. Both are deliberate — illegal states
 * unrepresentable — and the standing rule that a client-side erasure needs a MEASURED kernel basis is satisfied
 * by our own run rather than by the peer's report: {@code TemporalConstantAdmissionGateLawsTest
 * .theTwoShapesOurBuilderCannotAuthorAreRefusedByTheKernel} puts both strings to {@code checkModel} and gets
 * {@code MVK_BRACKET_MISSING} and {@code MVK_INVALID_TYPE_FOR_COMPARISON}, with the bracketed spelling admitted
 * as the control.
 *
 * <p><b>Every row carries the rule's error-field conjunct</b> outside the filter. Without it the whole file
 * collapses to {@code MVK_ERROR_FIELD_NOT_REFERENCED} and establishes nothing.
 */
class HavingFilterGrammarAndScopeLawsTest {

    private static final String ADMITTED = "admitted";
    private static final String NO_ITERATION = "MVK_NO_ITERATION_FOR_WILDCARD";
    private static final String NO_WILDCARD = "MVK_NO_WILDCARD";
    private static final String INVALID_ITERATION_IN_FILTER = "MVK_INVALID_ITERATION_IN_FILTER_CONDITION";

    private static final String CATALOG = "/Catalog";
    private static final String LINES = "/Catalog/Lines";
    private static final String ITEMS = "/Catalog/Lines/Items";

    /**
     * Two nested repeatable levels so a filter can name a level the operand reopens, a level only the enclosing
     * rule iteration binds, and — with a two-star operand — an outer level that IS reopened. {@code Marker} and
     * {@code Label} are the two error fields, one per rule site.
     */
    private static final String MODEL = DocumentModelDraft.named("catalog-filter-scope").locale("en_US")
            .rootGroup("Catalog", root -> root
                    .stringField("Marker", s -> { })
                    .field("Total", FieldKind.NUMBER)
                    .group("Lines", lines -> lines.repeatable(50)
                            .stringField("Label", s -> { })
                            .field("Cap", FieldKind.NUMBER)
                            .group("Items", items -> items.repeatable(50)
                                    .stringField("Sku", s -> { })
                                    .stringField("Note", s -> { })
                                    .field("Qty", FieldKind.NUMBER))))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(MODEL);

    /** The in-scope predicate every control rests on — a reference into the level the operand's star reopens. */
    private static Condition inScope() {
        return M.num(ITEMS + "/Qty").gt(1);
    }

    /** A reference into the enclosing rule's iterated level, which the single-star operand does NOT reopen. */
    private static Condition outerScope() {
        return M.num(LINES + "/Cap").gt(0);
    }

    /** The same outer reference under the {@code $} marker. */
    private static Condition markedOuter() {
        return M.num(LINES + "/Cap").outer().gt(0);
    }

    /**
     * The ordinary condition grammar inside a filter ({@code SPEC-2026-09-03-02}), plus the two scope rows that
     * belong to the same operand shape. Every leaf is measured in the position the entry claims for it: those
     * claimed admitted as a sole condition are measured alone, and the outer-origin leaves are measured both
     * alone and beside an in-scope conjunct, since admitting a shape in one position says nothing about the
     * other.
     */
    @Test
    void theFilterConditionIsTheOrdinaryGrammar() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("Number comparison — the well-formedness control", verdict(inScope()));
        actual.put("String equality against a literal", verdict(M.str(ITEMS + "/Sku").eq("premium")));
        actual.put("String equality against another String field",
                verdict(M.str(ITEMS + "/Sku").eq(M.str(ITEMS + "/Note"))));
        actual.put("FieldFilled", verdict(Dsl.fieldFilled(M.field(ITEMS + "/Sku"))));
        actual.put("FieldNotFilled — no iteration-negativity refusal",
                verdict(Dsl.fieldNotFilled(M.field(ITEMS + "/Sku"))));
        actual.put("Number !=", verdict(M.num(ITEMS + "/Qty").ne(1)));
        actual.put("And", verdict(inScope().and(M.str(ITEMS + "/Sku").eq("premium"))));
        actual.put("Or of two in-scope leaves", verdict(inScope().or(M.str(ITEMS + "/Sku").eq("premium"))));
        actual.put("bracketed And/Or mix",
                verdict(inScope().and(M.str(ITEMS + "/Sku").eq("premium").or(M.str(ITEMS + "/Note").eq("gift")))));
        actual.put("a bare outer scalar reference beside an in-scope conjunct",
                verdict(inScope().and(outerScope())));
        actual.put("a bare outer scalar reference as the WHOLE condition", verdict(outerScope()));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Number comparison — the well-formedness control", ADMITTED);
        expected.put("String equality against a literal", ADMITTED);
        expected.put("String equality against another String field", ADMITTED);
        expected.put("FieldFilled", ADMITTED);
        expected.put("FieldNotFilled — no iteration-negativity refusal", ADMITTED);
        expected.put("Number !=", ADMITTED);
        expected.put("And", ADMITTED);
        expected.put("Or of two in-scope leaves", ADMITTED);
        expected.put("bracketed And/Or mix", ADMITTED);
        expected.put("a bare outer scalar reference beside an in-scope conjunct", ADMITTED);
        expected.put("a bare outer scalar reference as the WHOLE condition", NO_ITERATION);
        assertEquals(expected, actual,
                "a Having condition is the ordinary production; the only refusal reached is the general "
                        + "binding gate, which is about scope rather than about the leaf's shape");
    }

    /**
     * The {@code $} marker's two rules ({@code SPEC-2026-09-03-04}, {@code -05}, {@code -07}). The marker moves a
     * reference out of the filtered level, so it cannot be the reference that binds it; and it may only name a
     * level the ENCLOSING iteration binds, so marking a level the operand's own star reopens is a different
     * refusal with a different code.
     *
     * <p>The pair that isolates the level from the marker is rows two and three: the marker is held fixed, only
     * the level varies, and the verdict follows the level's binding rather than the marker's presence.
     */
    @Test
    void theMarkerNeitherBindsTheFilteredLevelNorReachesAReopenedOne() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a $-marked outer reference as the WHOLE condition", verdict(markedOuter()));
        actual.put("a $-marked outer reference beside an in-scope conjunct", verdict(inScope().and(markedOuter())));
        actual.put("$ on the level the operand's own star reopens",
                verdict(inScope().and(M.num(ITEMS + "/Qty").outer().gt(0))));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a $-marked outer reference as the WHOLE condition", NO_ITERATION);
        expected.put("a $-marked outer reference beside an in-scope conjunct", ADMITTED);
        expected.put("$ on the level the operand's own star reopens", NO_WILDCARD);
        assertEquals(expected, actual,
                "a marked reference does not satisfy the binding gate, and may only name a level the enclosing "
                        + "iteration binds");
    }

    /**
     * The gate is a SYNTACTIC OCCURRENCE check ({@code SPEC-2026-09-03-05}), and each row kills a different rival
     * reading. The {@code Or} row refuses a satisfiability or dominance analysis: the filter can be satisfied
     * without ever consulting the in-scope disjunct, and it is still admitted. The {@code CurrentRepetition} rows
     * refuse an implementation over the reference EXPANSION: a12-kernel-lean REPORTS that such an operand
     * contributes no field pointer at all ({@code spec/01} — their measurement, not re-derived here), and the
     * rows below add that the unmarked one nonetheless binds the level. What this file establishes on its own is
     * the admission; the pointer-free premise is cited as theirs.
     *
     * <p><b>The pointer-free rows need the SELF-EXCLUSION topology</b> — rule and operand at the same repeatable
     * level — and that is not a fixture convenience. A marked reference may only name a level the enclosing
     * iteration binds, so both the unmarked and the marked spelling can be legal in one condition ONLY where a
     * single level is at once reopened by the operand's star and bound by the enclosing rule. The last row
     * measures the other topology on the same carrier and shows the marker rule governing a pointer-free operand
     * exactly as it governs a field one.
     */
    @Test
    void theGateCountsOccurrencesRatherThanPointersOrSatisfiability() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("in-scope reference under Or, beside an out-of-scope disjunct",
                verdict(outerScope().or(inScope())));
        actual.put("CurrentRepetition(Lines) != CurrentRepetition($Lines) — pointer-free, one side marked",
                selfVerdict(Dsl.currentRepetition(M.group(LINES)).ne(Dsl.currentRepetition(M.group(LINES), true))));
        actual.put("CurrentRepetition(Lines) != CurrentRepetition(Lines) — reflexive, neither marked",
                selfVerdict(Dsl.currentRepetition(M.group(LINES)).ne(Dsl.currentRepetition(M.group(LINES)))));
        actual.put("CurrentRepetition($Lines) != CurrentRepetition($Lines) — both marked",
                selfVerdict(Dsl.currentRepetition(M.group(LINES), true)
                        .ne(Dsl.currentRepetition(M.group(LINES), true))));
        actual.put("CurrentRepetition($Items) under an Items star — marked level the operand reopens",
                verdict(Dsl.currentRepetition(M.group(ITEMS)).ne(Dsl.currentRepetition(M.group(ITEMS), true))));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("in-scope reference under Or, beside an out-of-scope disjunct", ADMITTED);
        expected.put("CurrentRepetition(Lines) != CurrentRepetition($Lines) — pointer-free, one side marked",
                ADMITTED);
        expected.put("CurrentRepetition(Lines) != CurrentRepetition(Lines) — reflexive, neither marked", ADMITTED);
        expected.put("CurrentRepetition($Lines) != CurrentRepetition($Lines) — both marked", NO_ITERATION);
        expected.put("CurrentRepetition($Items) under an Items star — marked level the operand reopens",
                NO_WILDCARD);
        assertEquals(expected, actual,
                "the gate turns on an unmarked occurrence, not on the comparison being non-trivial and not on "
                        + "the filter needing the reference to decide");
    }

    /**
     * The gate accepts a reference at ANY level the operand reopens, not the innermost one
     * ({@code SPEC-2026-09-03-05}). The operand stars both levels and the rule sits at the root, so
     * {@code Lines} is reopened by the operand rather than bound by an enclosing iteration — the same textual
     * reference that was out-of-scope above is in-scope here, which is what makes the row a measurement of the
     * LEVEL rather than of the path.
     */
    @Test
    void aTwoStarOperandsFilterMayBindOnlyTheOuterStarredLevel() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("filter names the OUTER starred level only", twoStarVerdict(outerScope()));
        actual.put("filter names the INNER starred level only", twoStarVerdict(inScope()));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("filter names the OUTER starred level only", ADMITTED);
        expected.put("filter names the INNER starred level only", ADMITTED);
        assertEquals(expected, actual, "any reopened level satisfies the gate");
    }

    /**
     * The client-side half of the two shapes this project cannot author. An {@code And}/{@code Or} mix is always
     * parenthesized by the renderer, so {@code MVK_BRACKET_MISSING} has no representation through the typed
     * builder — asserted on the rendered text rather than described, since that text is the whole claim.
     */
    @Test
    void aMixedConnectiveFilterIsAlwaysBracketed() {
        String rendered = KernelLaws.render(
                inScope().and(M.str(ITEMS + "/Sku").eq("premium").or(M.str(ITEMS + "/Note").eq("gift"))));
        assertEquals("[/Catalog/Lines/Items/Qty] > 1 And ([/Catalog/Lines/Items/Sku] == \"premium\""
                        + " Or [/Catalog/Lines/Items/Note] == \"gift\")", rendered,
                "the nested Or carries its own parentheses, so the refused unbracketed mix is unrepresentable");
    }

    /**
     * Whether a repeatable COMPUTATION binds {@code CurrentRepetition} the way an iterating RULE does. This
     * project's operator catalog says "the rule must iterate over that group", which is the same narrowness
     * a12-kernel-lean's {@code SPEC-2026-09-03-06} corrected for the {@code $} marker — and they reported on
     * 2026-09-04 that they do not hold this row either (their {@code SG17}). It is one probe, and the catalog
     * line is a SHIPPED surface served by {@code dmtool operators CurrentRepetition}, so it is measured here
     * rather than handed back.
     *
     * <p>Two computation targets, identical calc: one repeatable, one at the root. If iteration is what binds
     * the operand, the repeatable target admits it and the root target does not.
     */
    @Test
    void aRepeatableComputationBindsCurrentRepetitionAsAnIteratingRuleDoes() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("repeatable computation target", computationVerdict(LINES + "/Cap", LINES));
        actual.put("root computation target — the control", computationVerdict(CATALOG + "/Total", CATALOG));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("repeatable computation target", ADMITTED);
        expected.put("root computation target — the control", NO_WILDCARD);
        assertEquals(expected, actual,
                "a repeatable computation instance binds CurrentRepetition, so the catalog's \"the rule must "
                        + "iterate\" is narrow in the same way SPEC-2026-09-03-06 found for the $ marker");
    }

    /**
     * <b>Which star binds an unmarked {@code CurrentRepetition} — the discriminating row the shipped catalog
     * line needs.</b> `KF241` and the catalog say "an operand's own star <em>that reopens the group</em>", but
     * every row that established it sat on a carrier with exactly ONE starrable level relative to the operand,
     * so "the star that reopens G binds it" and "any position inside a filter binds an unmarked operand" fit
     * all of them equally — the confound that is constant across a whole row set, here in the fixture.
     *
     * <p>This carrier separates them because it has two nested repeatable levels. The host is the ROOT, so no
     * enclosing iteration binds anything, and the operand stars {@code Lines} only:
     *
     * <ul>
     *   <li>naming {@code Lines} — the level the operand reopens — must be ADMITTED (which also transfers
     *       `KF241`'s claim to a second carrier, since it was measured on {@code /Policy});</li>
     *   <li>naming {@code Items} — a repeatable level neither starred nor iterated — is where the accounts
     *       part: the narrow one predicts a refusal, the broad one admission;</li>
     *   <li>the two-star operand naming {@code Items} is the positive control: the same leaf, now reopened,
     *       tells a refusal about the BINDING apart from a refusal about the leaf.</li>
     * </ul>
     *
     * <p><b>The narrow account holds, and the refusal is a THIRD code neither account predicted.</b> This row
     * draws {@code MVK_INVALID_ITERATION_IN_FILTER_CONDITION} — the filter-scope gate, whose meaning our record
     * already held for a FIELD reference (`KF §9`, "why `$` exists") and which turns out to receive a group
     * operand too — not the {@code MVK_NO_WILDCARD} the two other unbound positions draw (the marked form in a
     * filter, and an unmarked operand outside any filter, both in
     * {@link CurrentRepetitionBindingSourceLawsTest}). So the unbound code depends on WHY it is unbound, not
     * only on where it sits, and `KF241` plus the shipped catalog line are corrected accordingly.
     *
     * <p>Measured 2026-09-04, after a cold review pointed out the catalog was shipping the narrow mechanism on
     * rows that could not distinguish it — and the measurement then found the catalog's code wrong for this
     * position as well.
     */
    @Test
    void onlyTheStarThatReopensTheNamedGroupBindsAnUnmarkedOperand() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("names the level the operand's star reopens",
                rootHostSingleStarVerdict(Dsl.currentRepetition(M.group(LINES)).gt(0)));
        actual.put("names a repeatable level neither starred nor iterated",
                rootHostSingleStarVerdict(Dsl.currentRepetition(M.group(ITEMS)).gt(0)));
        actual.put("that same leaf under a TWO-star operand — the positive control",
                twoStarVerdict(Dsl.currentRepetition(M.group(ITEMS)).gt(0)));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("names the level the operand's star reopens", ADMITTED);
        expected.put("names a repeatable level neither starred nor iterated", INVALID_ITERATION_IN_FILTER);
        expected.put("that same leaf under a TWO-star operand — the positive control", ADMITTED);
        assertEquals(expected, actual,
                "the binding source is the star that reopens the NAMED group, not the filter position");
    }

    // ---- fixtures --------------------------------------------------------------------------------

    /** Rule at the ROOT — no enclosing iteration — with an operand starring only {@code Lines}. */
    private static String rootHostSingleStarVerdict(Condition filter) {
        FieldListRef caps = M.flatten(LINES + "*/Cap");
        String condition = KernelLaws.render(Dsl.fieldFilled(M.field(CATALOG + "/Marker"))
                .and(Dsl.numberOfFilledFields(caps.having(filter)).gt(0)));
        return codes(KernelLaws.checkModel(MODEL, CATALOG, "../Marker", condition));
    }

    /** {@code CurrentRepetition(Lines)} assigned to the given computed field, hosted in the given group. */
    private static String computationVerdict(String target, String hostGroup) {
        String relative = "../" + target.substring(target.lastIndexOf('/') + 1);
        String operation = KernelLaws.render(Dsl.currentRepetition(M.group(LINES)).expression());
        return codes(new RuleValidator().check(MODEL, new DraftComputation(hostGroup, "RepComp", relative, null,
                java.util.List.of(new DraftComputation.Alternative(null, operation)))));
    }


    /** Rule at the repeatable {@code Lines} level, operand starring only {@code Items}. */
    private static String verdict(Condition filter) {
        FieldListRef qtys = M.flatten(ITEMS + "*/Qty");
        String condition = KernelLaws.render(Dsl.fieldFilled(M.field(LINES + "/Label"))
                .and(Dsl.numberOfFilledFields(qtys.having(filter)).gt(0)));
        return codes(KernelLaws.checkModel(MODEL, LINES, "../Label", condition));
    }

    /**
     * The self-exclusion topology — rule and operand at the SAME repeatable level, so {@code Lines} is at once
     * reopened by the operand's star and bound by the enclosing rule iteration. It is the only topology in which
     * a marked and an unmarked reference to one level can both be legal in one filter.
     */
    private static String selfVerdict(Condition filter) {
        FieldListRef caps = M.flatten(LINES + "*/Cap");
        String condition = KernelLaws.render(Dsl.fieldFilled(M.field(LINES + "/Label"))
                .and(Dsl.numberOfFilledFields(caps.having(filter)).gt(0)));
        return codes(KernelLaws.checkModel(MODEL, LINES, "../Label", condition));
    }

    /** Rule at the root, operand starring BOTH levels, so each is reopened by the operand itself. */
    private static String twoStarVerdict(Condition filter) {
        FieldListRef qtys = M.flatten("/Catalog/Lines*/Items*/Qty");
        String condition = KernelLaws.render(Dsl.fieldFilled(M.field(CATALOG + "/Marker"))
                .and(Dsl.numberOfFilledFields(qtys.having(filter)).gt(0)));
        return codes(KernelLaws.checkModel(MODEL, CATALOG, "../Marker", condition));
    }

    /** {@code admitted}, or the COMPLETE emitted code list — a partial list would hide a co-firing gate. */
    private static String codes(ValidationResult r) {
        return r.ok() ? ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")")).collect(Collectors.joining(" + "));
    }
}
