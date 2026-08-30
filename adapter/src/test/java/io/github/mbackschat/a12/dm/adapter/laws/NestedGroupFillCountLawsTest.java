package io.github.mbackschat.a12.dm.adapter.laws;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;

/**
 * Behavioral law — <b>what the plain multi-group {@code NumberOfFilledGroups} COUNTS when a group's only
 * content lies in a descendant subgroup.</b>
 *
 * <p>a12-kernel-lean measured the static half ({@code SPEC-2026-08-30-01}: such a group is an admitted
 * operand at any position and arity) and left the counting rule open, having no runtime route: <i>"does a
 * nested-descendant operand count through the whole subtree, or over direct children only?"</i> Admission
 * cannot answer it — an operand can be legal and contribute nothing.
 *
 * <p><b>The discriminator</b> is one document in which the two accounts predict different numbers: the flat
 * group filled and the shell's descendant filled. Whole-subtree says 2, direct-children says 1, because under
 * that account a shell has no direct field and so can never be filled. The second document empties the
 * descendant, where both accounts say 1 — without it, a count stuck at 1 would satisfy the direct-children
 * prediction for the wrong reason.
 *
 * <p>Measured on BOTH arms, because they are separately specified and the count is documented as the stricter
 * of the two (KF120): the computation arm reads the value the kernel stores, the validation arm reads a
 * comparison against it.
 *
 * <p>Own domain (a supplier catalogue).
 */
class NestedGroupFillCountLawsTest {

    private static final String TALLY = "/Catalogue/SectionTally";

    /**
     * {@code Contact} is flat — a direct field. {@code Warranty} is a SHELL: no direct field at all, its only
     * field two levels down in {@code Terms}. That asymmetry is the whole fixture.
     */
    private static final String CATALOGUE = DocumentModelDraft.named("catalogue").locale("en_US")
            .rootGroup("Catalogue", c -> c
                    .field("Reference", FieldKind.STRING)
                    .numberField("SectionTally", n -> n.fractionDigits(0))
                    .numberField("TallyFixed", n -> n.fractionDigits(0))
                    .numberField("TallyStar", n -> n.fractionDigits(0))
                    .numberField("TallySum", n -> n.fractionDigits(0))
                    .group("Contact", g -> g
                            .field("Email", FieldKind.STRING)
                            .numberField("Rating", n -> n.fractionDigits(0)))
                    .group("Warranty", w -> w.group("Terms", t -> t
                            .field("Clause", FieldKind.STRING)
                            .numberField("Months", n -> n.fractionDigits(0))))
                    .group("Coverage", cv -> cv.group("Riders", r -> r.repeatable(5)
                            .field("RiderName", FieldKind.STRING)
                            .numberField("RiderAmount", n -> n.fractionDigits(0))))
                    .group("Bundle", b -> b
                            .group("Notes", n -> n.field("Text", FieldKind.STRING))
                            .group("Items", i -> i.repeatable(5).field("ItemName", FieldKind.STRING))))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(CATALOGUE);

    private static final String COMPUTATION_MODEL = computationModel();

    private static String computationModel() {
        Computation comp = M.computation(M.field(TALLY)).name("SectionTallyComp")
                .calc(Dsl.numberOfFilledGroups(M.group("/Catalogue/Contact"), M.group("/Catalogue/Warranty"))
                        .expression())
                .message(en("Section tally."))
                .build();
        return M.add(comp).toJson();
    }

    /**
     * The conjunct is not decoration: an operand-only condition draws {@code MVK_ERROR_FIELD_NOT_REFERENCED},
     * because the count names groups and the error field sits outside all of them. {@code Reference} is filled
     * in both documents below, so the conjunct is true throughout and only the count moves.
     */
    private static String ruleModel(int expected) {
        String condition = new DslRenderer().render(
                Dsl.fieldFilled(M.field("/Catalogue/Reference")).and(
                        Dsl.numberOfFilledGroups(M.group("/Catalogue/Contact"), M.group("/Catalogue/Warranty"))
                                .eq(expected)));
        return RuntimeLaws.withRule(CATALOGUE, "/Catalogue", "TallyCheck", "../Reference", "TALLY", condition);
    }

    /** Both groups carry content — the flat one directly, the shell one two levels down. */
    private static RuntimeLaws.Doc bothFilled() {
        return RuntimeLaws.doc().group("/Catalogue", 1)
                .field("/Catalogue/Reference", "CAT-1", 1, 1)
                .field("/Catalogue/Contact/Email", "buyer@example.com", 1, 1, 1)
                .field("/Catalogue/Warranty/Terms/Clause", "24 months", 1, 1, 1, 1);
    }

    /** The shell's descendant is empty, so only the flat group is filled under either account. */
    private static RuntimeLaws.Doc onlyFlatFilled() {
        return RuntimeLaws.doc().group("/Catalogue", 1)
                .field("/Catalogue/Reference", "CAT-1", 1, 1)
                .field("/Catalogue/Contact/Email", "buyer@example.com", 1, 1, 1);
    }

    private static Optional<BigDecimal> tally(RuntimeLaws.Doc doc) {
        List<RuntimeLaws.Computed> out = RuntimeLaws.compute(COMPUTATION_MODEL, doc);
        return RuntimeLaws.computedValue(out, TALLY).map(BigDecimal::new);
    }

    /**
     * <b>THE DISCRIMINATOR.</b> A shell whose only field lies in a descendant subgroup contributes to the
     * count, so the operator reads content over the whole subtree.
     */
    @Test
    void aShellWhoseOnlyFieldIsADescendantCountsAsFilled() {
        Optional<BigDecimal> counted = tally(bothFilled());
        assertTrue(counted.isPresent(), "the computation must produce a value at all");
        assertEquals(0, counted.get().compareTo(BigDecimal.valueOf(2)),
                () -> "whole-subtree predicts 2, direct-children predicts 1; got " + counted.get());
    }

    /**
     * <b>THE CONTROL.</b> Emptying the descendant drops the count to 1. Without it, a count that ignored the
     * shell entirely would be indistinguishable from one that read it and found it empty.
     */
    @Test
    void emptyingThatDescendantDropsTheCount() {
        Optional<BigDecimal> counted = tally(onlyFlatFilled());
        assertTrue(counted.isPresent(), "the computation must produce a value at all");
        assertEquals(0, counted.get().compareTo(BigDecimal.ONE),
                () -> "only the flat group is filled; got " + counted.get());
    }

    /**
     * The VALIDATION arm reaches the same number. The two arms are separately specified — the plain
     * multi-group count is documented as the stricter of the two on erroneous operands (KF120) — so their
     * agreement on the nested shape is measured rather than assumed.
     */
    @Test
    void theValidationArmCountsTheNestedDescendantTheSameWay() {
        assertTrue(RuntimeLaws.fires(ruleModel(2), "TALLY", bothFilled()),
                "the rule arm also sees 2 filled groups");
        assertFalse(RuntimeLaws.fires(ruleModel(1), "TALLY", bothFilled()),
                "and does not see 1 — the arms agree on the nested shape");
    }

    /** A malformed value in a DIRECT child of an operand group: formally invalid, kernel-detected. */
    private static RuntimeLaws.Doc malformedDirectChild() {
        return RuntimeLaws.doc().group("/Catalogue", 1)
                .field("/Catalogue/Reference", "CAT-1", 1, 1)
                .field("/Catalogue/Contact/Rating", "abc", 1, 1, 1)
                .field("/Catalogue/Warranty/Terms/Clause", "24 months", 1, 1, 1, 1);
    }

    /** The same malformed value one level deeper, in a nested descendant. */
    private static RuntimeLaws.Doc malformedNestedDescendant() {
        return RuntimeLaws.doc().group("/Catalogue", 1)
                .field("/Catalogue/Reference", "CAT-1", 1, 1)
                .field("/Catalogue/Contact/Email", "buyer@example.com", 1, 1, 1)
                .field("/Catalogue/Warranty/Terms/Months", "abc", 1, 1, 1, 1);
    }

    /**
     * <b>The arms DIVERGE on a formally invalid operand, and depth is not what decides it.</b>
     *
     * <p>[KF120] states the plain multi-group count is the strict carrier — <i>"any erroneous group makes the
     * count unavailable rather than silently counting only the admitted subset"</i> — and locks it on the
     * VALIDATION arm. Measured here on the COMPUTATION arm, that is not what happens: with a kernel-detected
     * formal error in an operand group ({@code zahlHatUngueltigeZeichen}, VALUE_ERROR, reported by
     * {@code computeWithFormalErrors} on the same run) the computation still stores a value, and stores the
     * group as counted.
     *
     * <p>Both placements behave identically, which is the point for the nested question: whatever the arms do
     * with an erroneous operand, they do it the same at depth 1 and at depth 2 — the subtree reading is not
     * where they differ.
     */
    @Test
    void theComputationArmStillCountsAnOperandGroupHoldingOnlyAMalformedValue() {
        assertEquals(0, tally(malformedDirectChild()).orElseThrow().compareTo(BigDecimal.valueOf(2)),
                () -> "measured: the computation arm counts it, contra a strict reading of KF120");
        assertEquals(0, tally(malformedNestedDescendant()).orElseThrow().compareTo(BigDecimal.valueOf(2)),
                () -> "and identically one level deeper, so depth is not the variable");
    }

    /**
     * The other arm on the identical document — the discriminator that scopes [KF120] rather than
     * contradicting it. The rule is suppressed, so the count never reaches a comparison.
     */
    @Test
    void theValidationArmIsSuppressedByTheSameMalformedOperand() {
        assertFalse(RuntimeLaws.fires(ruleModel(2), "TALLY", malformedDirectChild()),
                "a formal error in a referenced group suppresses the rule");
        assertFalse(RuntimeLaws.fires(ruleModel(1), "TALLY", malformedDirectChild()),
                "and it is suppression, not a different count: no comparison value fires");
    }

    /** The clean control for all four rows above: the same shapes, well-formed, still compute. */
    @Test
    void theSameShapesWellFormedStillCompute() {
        assertTrue(tally(bothFilled()).isPresent(), "the fixture computes when nothing is malformed");
    }

    private static final String COVERAGE_MODEL = coverageModel();

    private static String coverageModel() {
        Computation comp = M.computation(M.field(TALLY)).name("CoverageTallyComp")
                .calc(Dsl.numberOfFilledGroups(M.group("/Catalogue/Contact"), M.group("/Catalogue/Coverage"))
                        .expression())
                .message(en("Coverage tally."))
                .build();
        return M.add(comp).toJson();
    }

    private static Optional<BigDecimal> coverageTally(RuntimeLaws.Doc doc) {
        return RuntimeLaws.computedValue(RuntimeLaws.compute(COVERAGE_MODEL, doc), TALLY).map(BigDecimal::new);
    }

    /**
     * <b>a12-kernel-lean's {@code SPEC-2026-08-30-03} discriminator, on our own fixture.</b> {@code Coverage}
     * owns no field anywhere — its only content is the repeatable {@code Riders}. One INSTANTIATED BUT EMPTY
     * row: a cell-content account predicts 1, since no cell is filled in the whole subtree.
     */
    @Test
    void anInstantiatedButEmptyRepeatableRowIsItselfContentForTheCount() {
        Optional<BigDecimal> counted = coverageTally(RuntimeLaws.doc().group("/Catalogue", 1)
                .field("/Catalogue/Reference", "CAT-1", 1, 1)
                .field("/Catalogue/Contact/Email", "buyer@example.com", 1, 1, 1)
                .group("/Catalogue/Coverage/Riders", 1, 1, 1));
        assertEquals(0, counted.orElseThrow().compareTo(BigDecimal.valueOf(2)),
                () -> "the bare row is content, so Coverage counts with no filled cell anywhere; got " + counted);
    }

    /** The control: with no row instantiated the shell is not counted, so it is the ROW that moved it. */
    @Test
    void withNoRowInstantiatedTheSameShellIsNotCounted() {
        Optional<BigDecimal> counted = coverageTally(RuntimeLaws.doc().group("/Catalogue", 1)
                .field("/Catalogue/Reference", "CAT-1", 1, 1)
                .field("/Catalogue/Contact/Email", "buyer@example.com", 1, 1, 1));
        assertEquals(0, counted.orElseThrow().compareTo(BigDecimal.ONE),
                () -> "only Contact is filled; got " + counted);
    }

    /**
     * <b>a12-kernel-lean's {@code SPEC-2026-08-30-02} discriminator</b>, which is NOT the arm-divergence row
     * above even though the outcome coincides. That row asks what an erroneous operand does to each arm; this
     * one asks whether formally invalid content is <i>content</i> for the fill predicate at all — the same
     * shell holding only a malformed value, against the same shell holding nothing.
     *
     * <p>Measured: it fills. So on the computation arm the count's notion of content is not the admitted-value
     * cache [KF120] describes for {@code GroupFilled}, where invalid-only is FALSE.
     */
    @Test
    void aShellWhoseONLYDescendantContentIsMalformedStillFillsIt() {
        Optional<BigDecimal> malformedOnly = tally(RuntimeLaws.doc().group("/Catalogue", 1)
                .field("/Catalogue/Reference", "CAT-1", 1, 1)
                .field("/Catalogue/Contact/Email", "buyer@example.com", 1, 1, 1)
                .field("/Catalogue/Warranty/Terms/Months", "abc", 1, 1, 1, 1));
        Optional<BigDecimal> emptied = tally(RuntimeLaws.doc().group("/Catalogue", 1)
                .field("/Catalogue/Reference", "CAT-1", 1, 1)
                .field("/Catalogue/Contact/Email", "buyer@example.com", 1, 1, 1));

        assertEquals(0, malformedOnly.orElseThrow().compareTo(BigDecimal.valueOf(2)),
                () -> "invalid-only descendant content still fills the shell; got " + malformedOnly);
        assertEquals(0, emptied.orElseThrow().compareTo(BigDecimal.ONE),
                () -> "the same shell holding nothing does not — the control; got " + emptied);
    }

    private static final String POLARITY_MODEL = polarityModel();

    /**
     * Two computations at the same group over the same document: one wildcard-free, one starred. Their targets
     * differ so both can coexist; nothing else about them differs.
     */
    private static String polarityModel() {
        Computation fixedOnly = M.computation(M.field("/Catalogue/TallyFixed")).name("FixedTallyComp")
                .calc(Dsl.numberOfFilledGroups(M.group("/Catalogue/Contact"), M.group("/Catalogue/Warranty"))
                        .expression())
                .message(en("Fixed tally.")).build();
        Computation starred = M.computation(M.field("/Catalogue/TallyStar")).name("StarTallyComp")
                .calc(Dsl.numberOfFilledGroups(M.flattenGroup("/Catalogue/Coverage/Riders*").group()).expression())
                .message(en("Star tally.")).build();
        Computation starredSum = M.computation(M.field("/Catalogue/TallySum")).name("StarSumComp")
                .calc(Dsl.sum(M.flatten("/Catalogue/Coverage/Riders*/RiderAmount")).expression())
                .message(en("Star sum.")).build();
        return M.add(fixedOnly).add(starred).add(starredSum).toJson();
    }

    /** Both stored tallies are deliberately wrong, so both computations' implicit self-validation mismatches. */
    private static RuntimeLaws.Doc polarityDoc(boolean fixedOperandsFilled) {
        RuntimeLaws.Doc d = RuntimeLaws.doc().group("/Catalogue", 1)
                .field("/Catalogue/Reference", "CAT-1", 1, 1)
                .field("/Catalogue/TallyFixed", "9", 1, 1)
                .field("/Catalogue/TallyStar", "9", 1, 1)
                .group("/Catalogue/Coverage/Riders", 1, 1, 1);
        if (fixedOperandsFilled) {
            d = d.field("/Catalogue/Contact/Email", "buyer@example.com", 1, 1, 1)
                    .field("/Catalogue/Warranty/Terms/Clause", "24 months", 1, 1, 1, 1);
        }
        return d;
    }

    private static String polarityOf(String targetName, boolean fixedOperandsFilled) {
        return RuntimeLaws.messages(POLARITY_MODEL, polarityDoc(fixedOperandsFilled)).stream()
                .filter(m -> m.field() != null && m.field().contains(targetName))
                .map(m -> m.type().toString())
                .findFirst().orElse("(no message for " + targetName + ")");
    }

    /** {@code rows} rows instantiated, each cell filled or left empty, with all four tallies seeded wrong. */
    private static RuntimeLaws.Doc capacityDoc(int rows, boolean cellsFilled) {
        RuntimeLaws.Doc d = RuntimeLaws.doc().group("/Catalogue", 1)
                .field("/Catalogue/Reference", "CAT-1", 1, 1)
                .field("/Catalogue/TallyFixed", "9", 1, 1)
                .field("/Catalogue/TallyStar", "9", 1, 1)
                .field("/Catalogue/TallySum", "9", 1, 1)
                .field("/Catalogue/Contact/Email", "buyer@example.com", 1, 1, 1)
                .field("/Catalogue/Warranty/Terms/Clause", "24 months", 1, 1, 1, 1);
        for (int r = 1; r <= rows; r++) {
            d = d.group("/Catalogue/Coverage/Riders", 1, 1, r);
            if (cellsFilled) {
                d = d.field("/Catalogue/Coverage/Riders/RiderName", "R" + r, 1, 1, r, 1)
                        .field("/Catalogue/Coverage/Riders/RiderAmount", "1", 1, 1, r, 1);
            }
        }
        return d;
    }

    private static String typeOfTarget(String targetName, RuntimeLaws.Doc doc) {
        return RuntimeLaws.messages(POLARITY_MODEL, doc).stream()
                .filter(m -> m.field() != null && m.field().contains(targetName))
                .map(m -> m.type().toString())
                .findFirst().orElse("(none)");
    }

    /**
     * <b>THE CAPACITY DISCRIMINATOR — polarity reads the QUANTITY'S HEADROOM, not starredness.</b>
     *
     * <p>Both projects first measured this below capacity, where "a starred operand types OMISSION" fits every
     * row and is nonetheless false. {@code Riders} is declared max 5, so filling it exhausts the growth channel
     * a starred count depends on.
     *
     * <p>The third row is the sharpest: in ONE document over ONE starred group, the count says {@code VALUE}
     * while the sum says {@code OMISSION}. No account in terms of the operand's form can produce a split
     * between two operators reading the same group — the count cannot grow because capacity is exhausted, the
     * sum still can because a row's cell is empty. Two growth channels, tracked per quantity.
     */
    @Test
    void polarityTracksTheQuantitysOwnHeadroomAndNotTheStar() {
        Map<String, String> actual = new LinkedHashMap<>();
        RuntimeLaws.Doc atCapacityFilled = capacityDoc(5, true);
        RuntimeLaws.Doc belowCapacityFilled = capacityDoc(4, true);
        RuntimeLaws.Doc atCapacityEmpty = capacityDoc(5, false);

        actual.put("5/5 rows, cells filled — starred count", typeOfTarget("TallyStar", atCapacityFilled));
        actual.put("5/5 rows, cells filled — starred sum", typeOfTarget("TallySum", atCapacityFilled));
        actual.put("4/5 rows, cells filled — starred count", typeOfTarget("TallyStar", belowCapacityFilled));
        actual.put("4/5 rows, cells filled — starred sum", typeOfTarget("TallySum", belowCapacityFilled));
        actual.put("5/5 rows, cells EMPTY — starred count", typeOfTarget("TallyStar", atCapacityEmpty));
        actual.put("5/5 rows, cells EMPTY — starred sum", typeOfTarget("TallySum", atCapacityEmpty));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("5/5 rows, cells filled — starred count", "VALUE_ERROR");
        expected.put("5/5 rows, cells filled — starred sum", "VALUE_ERROR");
        expected.put("4/5 rows, cells filled — starred count", "OMISSION_ERROR");
        expected.put("4/5 rows, cells filled — starred sum", "OMISSION_ERROR");
        expected.put("5/5 rows, cells EMPTY — starred count", "VALUE_ERROR");
        expected.put("5/5 rows, cells EMPTY — starred sum", "OMISSION_ERROR");
        assertEquals(expected, actual, "one row of capacity, and one document splitting count from sum");
    }

    /**
     * <b>a12-kernel-lean's polarity witness, on our fixture.</b> Adding a STARRED operand flips a computation's
     * implicit self-validation message from {@code VALUE_ERROR} to {@code OMISSION_ERROR}, with nothing else in
     * the model or document changing — a modelling trap, since the two computations differ only in operand form.
     *
     * <p>Our {@code FilledCountLawsTest} locks the same shape for the FIELD count, where the reading is that a
     * count with unfilled operands remaining can still grow. A starred operand can always gain another row, so
     * it can never be "all filled" — which predicts the flip. That reading is a hypothesis; the rows are the
     * measurement.
     *
     * <p>The second row is the control that keeps this from being a constant: emptying the wildcard-free
     * computation's own operands moves ITS polarity, so it tracks its cells rather than being fixed.
     */
    @Test
    void aStarredOperandFlipsTheComputationSelfValidationPolarity() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("wildcard-free, its operands filled", polarityOf("TallyFixed", true));
        actual.put("starred, same document", polarityOf("TallyStar", true));
        actual.put("wildcard-free, its operands emptied — control", polarityOf("TallyFixed", false));
        actual.put("starred, same emptied document — control", polarityOf("TallyStar", false));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("wildcard-free, its operands filled", "VALUE_ERROR");
        expected.put("starred, same document", "OMISSION_ERROR");
        expected.put("wildcard-free, its operands emptied — control", "OMISSION_ERROR");
        expected.put("starred, same emptied document — control", "OMISSION_ERROR");
        assertEquals(expected, actual, "the flip follows the starred operand, not the operator");
    }

    private static final String MIXED_MODEL = mixedModel();

    /** {@code NumberOfFilledGroups(Contact, Coverage/Riders*)} — a FIXED operand beside a STARRED one. */
    private static String mixedModel() {
        Computation comp = M.computation(M.field(TALLY)).name("MixedTallyComp")
                .calc(Dsl.numberOfFilledGroups(M.group("/Catalogue/Contact"),
                        M.flattenGroup("/Catalogue/Coverage/Riders*").group()).expression())
                .message(en("Mixed tally."))
                .build();
        return M.add(comp).toJson();
    }

    private static Optional<BigDecimal> mixedTally(RuntimeLaws.Doc doc) {
        return RuntimeLaws.computedValue(RuntimeLaws.compute(MIXED_MODEL, doc), TALLY).map(BigDecimal::new);
    }

    private static RuntimeLaws.Doc mixed(boolean contactFilled, int rows) {
        RuntimeLaws.Doc d = RuntimeLaws.doc().group("/Catalogue", 1)
                .field("/Catalogue/Reference", "CAT-1", 1, 1);
        if (contactFilled) {
            d = d.field("/Catalogue/Contact/Email", "buyer@example.com", 1, 1, 1);
        }
        for (int r = 1; r <= rows; r++) {
            d = d.group("/Catalogue/Coverage/Riders", 1, 1, r);
        }
        return d;
    }

    /**
     * <b>The mixed form is an ADDITIVE fold, and the starred operand contributes a CARDINALITY.</b>
     *
     * <p>a12-kernel-lean's {@code SPEC-2026-08-30-04} reads the two operand forms as having separate result
     * domains, so that the operator "cannot be folded by one presence question per operand". The first half is
     * right and the second overshoots: the discriminator is a mixed list whose starred operand holds THREE
     * instantiated empty rows. A presence bit for that operand caps the total at 2 whatever the fixed operand
     * does; an additive fold with a cardinality contribution predicts 4.
     *
     * <p>The four rows are one model and one operand list, varying only the fixed operand's fill and the row
     * count, so each is {@code [Contact filled] + |Riders rows|} if the fold is additive.
     */
    @Test
    void theMixedFormAddsAFixedPresenceBitToTheStarredRowCardinality() {
        assertEquals(0, mixedTally(mixed(true, 3)).orElseThrow().compareTo(BigDecimal.valueOf(4)),
                "1 + 3: a presence bit for the starred operand cannot exceed 2 here");
        assertEquals(0, mixedTally(mixed(false, 3)).orElseThrow().compareTo(BigDecimal.valueOf(3)),
                "0 + 3: the fixed operand contributes nothing while the starred one still contributes every row");
        assertEquals(0, mixedTally(mixed(true, 0)).orElseThrow().compareTo(BigDecimal.ONE),
                "1 + 0");
        assertEquals(0, mixedTally(mixed(false, 0)).orElseThrow().compareTo(BigDecimal.ZERO),
                "0 + 0 — the control, so the rows above are not a constant");
    }

    private static final String BUNDLE_MODEL = bundleModel();

    private static String bundleModel() {
        Computation comp = M.computation(M.field(TALLY)).name("BundleTallyComp")
                .calc(Dsl.numberOfFilledGroups(M.group("/Catalogue/Contact"), M.group("/Catalogue/Bundle"))
                        .expression())
                .message(en("Bundle tally."))
                .build();
        return M.add(comp).toJson();
    }

    private static Optional<BigDecimal> bundleTally(RuntimeLaws.Doc doc) {
        return RuntimeLaws.computedValue(RuntimeLaws.compute(BUNDLE_MODEL, doc), TALLY).map(BigDecimal::new);
    }

    private static RuntimeLaws.Doc bundleBase() {
        return RuntimeLaws.doc().group("/Catalogue", 1)
                .field("/Catalogue/Reference", "CAT-1", 1, 1)
                .field("/Catalogue/Contact/Email", "buyer@example.com", 1, 1, 1);
    }

    /**
     * <b>The DISJUNCTION, in the one shape that can separate the two constituents.</b>
     *
     * <p>Neither earlier measurement established it: a shape with no repeatable descendant cannot show the row
     * constituent, and inside a repeatable descendant a cell cannot exist without a row, so each of us measured
     * one constituent live where the other could not fire. {@code Bundle} carries BOTH — a nonrepeatable
     * {@code Notes/Text} and a repeatable {@code Items} — so each can be exercised alone, against the same
     * operand, in the same model.
     *
     * <p>Three documents, and the third is what makes the first two mean anything: without it, "counts either
     * way" is indistinguishable from "counts unconditionally".
     */
    @Test
    void eitherConstituentAloneIsSufficientAndNeitherIsNecessary() {
        Optional<BigDecimal> cellOnly = bundleTally(bundleBase()
                .field("/Catalogue/Bundle/Notes/Text", "gift wrap", 1, 1, 1, 1));
        Optional<BigDecimal> rowOnly = bundleTally(bundleBase()
                .group("/Catalogue/Bundle/Items", 1, 1, 1));
        Optional<BigDecimal> neither = bundleTally(bundleBase());

        assertEquals(0, cellOnly.orElseThrow().compareTo(BigDecimal.valueOf(2)),
                () -> "a nonrepeatable descendant cell, with NO row instantiated; got " + cellOnly);
        assertEquals(0, rowOnly.orElseThrow().compareTo(BigDecimal.valueOf(2)),
                () -> "an instantiated empty row, with NO cell filled anywhere; got " + rowOnly);
        assertEquals(0, neither.orElseThrow().compareTo(BigDecimal.ONE),
                () -> "NEITHER constituent — the control that makes the pair above meaningful; got " + neither);
    }
}
