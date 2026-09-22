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
        String condition = RuntimeLaws.render(
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

    /** {@code Contact} carries a VALID field and a malformed one, so its presence is decided despite the error. */
    private static RuntimeLaws.Doc validPlusMalformed() {
        return RuntimeLaws.doc().group("/Catalogue", 1)
                .field("/Catalogue/Reference", "CAT-1", 1, 1)
                .field("/Catalogue/Contact/Email", "buyer@example.com", 1, 1, 1)
                .field("/Catalogue/Contact/Rating", "abc", 1, 1, 1)
                .field("/Catalogue/Warranty/Terms/Clause", "24 months", 1, 1, 1, 1);
    }

    /**
     * <b>An error does NOT make the count unavailable — an UNDECIDED PRESENCE does.</b> This contradicts a
     * sentence this project published in [KF120] ("any erroneous group makes the count unavailable") and
     * confirms a12-kernel-lean's `SPEC-2026-08-31-01`, which reports the same correction against their own
     * `spec/02`. Measured here on our own fixture rather than taken from their rows.
     *
     * <p>The pair differs in exactly one thing — whether the erroneous operand also holds an admitted value:
     *
     * <ul>
     *   <li>{@code Contact} holding <b>only</b> a malformed Number → the rule is suppressed at every
     *       comparison value, because the group can be proved neither filled nor empty (the row already
     *       locked above).</li>
     *   <li>{@code Contact} holding a valid {@code Email} <b>and</b> the same malformed Number → the count
     *       fires at 2, exactly as the clean control does.</li>
     * </ul>
     *
     * <p>So a formal error blocks only the NEGATIVE answer. That restores the invariant the old wording broke:
     * the count and the scalar {@code GroupFilled} predicate cannot disagree about an erroneous group, and
     * [KF120]'s own matrix already had {@code GroupFilled} TRUE for valid-plus-invalid.
     *
     * <p>The {@code ruleModel(1)} row is the control that keeps this from being a constant: it must NOT fire,
     * or "the rule fires" would carry no information about the number.
     */
    @Test
    void anAdmittedValueBesideTheErrorKeepsTheCountAvailable() {
        assertTrue(RuntimeLaws.fires(ruleModel(2), "TALLY", validPlusMalformed()),
                "an operand with admitted content beside a formal error still counts");
        assertFalse(RuntimeLaws.fires(ruleModel(1), "TALLY", validPlusMalformed()),
                "and it counts 2, not 1 — the error does not drop the operand");
        assertFalse(RuntimeLaws.fires(ruleModel(2), "TALLY", malformedDirectChild()),
                "while the invalid-ONLY operand stays suppressed — the one variable between the two rows");
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
        return capacityDoc(rows, cellsFilled, "9");
    }

    /** As above, with the starred count's stored seed chosen, so the computed value can sit on either side. */
    private static RuntimeLaws.Doc capacityDoc(int rows, boolean cellsFilled, String starSeed) {
        RuntimeLaws.Doc d = RuntimeLaws.doc().group("/Catalogue", 1)
                .field("/Catalogue/Reference", "CAT-1", 1, 1)
                .field("/Catalogue/TallyFixed", "9", 1, 1)
                .field("/Catalogue/TallyStar", starSeed, 1, 1)
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
     *
     * <p>The last row completes a12-kernel-lean's witness in this same document: the WILDCARD-FREE count sits
     * at {@code VALUE} beside the starred sum's {@code OMISSION}. Note it is the WEAKER of the two splits for
     * ruling out an operand-form account — a fixed/starred pair is exactly what such an account predicts — so
     * it is the starred-count-versus-starred-sum row above that does the refuting. It is carried because the
     * peer's witness rested on a single project until now.
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
        // a12-kernel-lean's witness, completed here: the wildcard-free count in the SAME document/run
        actual.put("5/5 rows, cells EMPTY — wildcard-free count", typeOfTarget("TallyFixed", atCapacityEmpty));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("5/5 rows, cells filled — starred count", "VALUE_ERROR");
        expected.put("5/5 rows, cells filled — starred sum", "VALUE_ERROR");
        expected.put("4/5 rows, cells filled — starred count", "OMISSION_ERROR");
        expected.put("4/5 rows, cells filled — starred sum", "OMISSION_ERROR");
        expected.put("5/5 rows, cells EMPTY — starred count", "VALUE_ERROR");
        expected.put("5/5 rows, cells EMPTY — starred sum", "OMISSION_ERROR");
        expected.put("5/5 rows, cells EMPTY — wildcard-free count", "VALUE_ERROR");
        assertEquals(expected, actual, "one row of capacity, and one document splitting count from sum");
    }

    /**
     * <b>Is the polarity DIRECTIONAL, or only "can this quantity move at all"?</b> Every row either project
     * held sat on one side of the seed — the computed value BELOW its stored one — so both accounts fit them
     * all and neither was tested. a12-kernel-lean raised the gap and could not reach the document; this is it.
     *
     * <p>A grow-only quantity whose computed value already EXCEEDS its seed splits them. {@code Riders} is
     * below capacity, so the count can still grow: the coarse account reads headroom and predicts
     * {@code OMISSION_ERROR}; a directional account notes that growing moves further from the seed, so filling
     * can never reconcile them, and predicts {@code VALUE_ERROR}.
     *
     * <p>The control is the same document with the seed above the computed value, where both accounts predict
     * {@code OMISSION_ERROR} — so the pair moves exactly one variable, the seed.
     */
    @Test
    void thePolarityIsDIRECTIONALAndNotMerelyWhetherTheQuantityCanStillMove() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("3 of 5 rows, computed 3 ABOVE its seed of 1",
                typeOfTarget("TallyStar", capacityDoc(3, true, "1")));
        actual.put("3 of 5 rows, computed 3 below its seed of 9 — control",
                typeOfTarget("TallyStar", capacityDoc(3, true, "9")));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("3 of 5 rows, computed 3 ABOVE its seed of 1", "VALUE_ERROR");
        expected.put("3 of 5 rows, computed 3 below its seed of 9 — control", "OMISSION_ERROR");
        assertEquals(expected, actual, "headroom alone does not decide it — the direction to the seed does");
    }

    /**
     * <b>An OMISSION can name only FILLED cells — so filtering {@code fillToFix} to empty ones shows nothing.</b>
     * a12-kernel-lean measured the channel as the whole {@code referenced} set or empty, never a proper subset,
     * across 30 message observations; this reproduces it on a shape neither of us had used for it, the
     * computation's implicit self-validation, and on our kernel oracle.
     *
     * <p>The document is the trap: four of five rows instantiated with EVERY cell filled, so the target types
     * {@code OMISSION_ERROR} on row headroom alone. There is no empty cell anywhere in an instantiated row, yet
     * the channel is non-empty — a consumer that filters it to unfilled cells renders an empty hint for a
     * message whose whole point is telling the user what to fill.
     *
     * <p>Our own interpreter is not exposed: {@code DmInterpreter} sets {@code fillToFix} to {@code referenced}
     * for an OMISSION and to empty otherwise, unconditionally, so it cannot emit a proper subset. That identity
     * is differentially locked against the kernel's {@code getRefOmissionErrorResponsiblePointers} in
     * {@code RichMessageDiffTest}; this adds the all-filled row it lacked.
     */
    @Test
    void anOmissionsFillToFixIsTheWholeReferencedSetEvenWhenEveryCellItNamesIsFilled() {
        RuntimeLaws.Msg m = RuntimeLaws.messages(POLARITY_MODEL, capacityDoc(4, true)).stream()
                .filter(x -> x.field() != null && x.field().contains("TallyStar"))
                .findFirst().orElseThrow(() -> new AssertionError("no self-validation message for TallyStar"));

        assertEquals("OMISSION_ERROR", m.type().toString(), "row headroom types it OMISSION though nothing is empty");
        assertFalse(m.omissionResponsible().isEmpty(), "the channel is populated even with no empty cell to name");
        assertEquals(m.referenced(), m.omissionResponsible(),
                "the whole referenced set, never a proper subset — so it is not a list of what is unfilled");
    }

    /**
     * <b>The {@code referenced} inventory follows the AUTHORED OPERAND, not what evaluation actually read.</b>
     * a12-kernel-lean reported the shape (`SPEC-2026-08-30-07`); this is our kernel oracle on it, with the
     * discriminator they named — a starred operand in a document holding no instantiated row.
     *
     * <p>The strong form is measurable here and settles it without argument: the set is <b>byte-identical</b>
     * with zero rows and with four filled rows. A set built from cells evaluation reached would be empty in the
     * first document and would enumerate eight pointers in the second. It does neither, because it is derived
     * from the condition's AST — which is what our own {@code DmInterpreter} does too ({@code messageReferences}
     * walks the {@code Cond} tree), so we are not exposed to the under-reporting this warns about.
     *
     * <p>Two further facts fall out of the same rows. The starred axis carries the coordinate <b>0</b> — not a
     * row index, since no row 0 exists and four instantiated rows do not change it, so it reads as the UNBOUND
     * wildcard axis; note this is the kernel pointer's own {@code repetitionIndexes()}, not a string parse of a
     * rendered path. And the extent follows each operand's own reach: the group-count operand names its group's
     * whole subtree (both row fields), while the {@code Sum} over one flattened field names only that field.
     * Every message also names its computed target.
     */
    @Test
    void theReferencedSetFollowsTheAuthoredOperandAndIsUnchangedByWhetherAnyRowExists() {
        List<String> noRows = starTallyRefs(capacityDoc(0, false));
        List<String> fourFilledRows = starTallyRefs(capacityDoc(4, true));

        assertEquals(List.of(
                "/Catalogue[1]/TallyStar",
                "/Catalogue[1]/Coverage[1]/Riders[0]/RiderName",
                "/Catalogue[1]/Coverage[1]/Riders[0]/RiderAmount"), noRows,
                "with no row instantiated the operand's row fields are still named, at the unbound axis 0");
        assertEquals(noRows, fourFilledRows,
                "four instantiated filled rows do not change it — so it is not built from what was read");

        assertEquals(List.of(
                "/Catalogue[1]/TallySum",
                "/Catalogue[1]/Coverage[1]/Riders[0]/RiderAmount"),
                refsOf("TallySum", capacityDoc(4, true)),
                "a Sum over one flattened field names that field alone, so the extent is the operand's own reach");
    }

    private static List<String> starTallyRefs(RuntimeLaws.Doc doc) {
        return refsOf("TallyStar", doc);
    }

    private static List<String> refsOf(String targetName, RuntimeLaws.Doc doc) {
        return RuntimeLaws.messages(POLARITY_MODEL, doc).stream()
                .filter(x -> x.field() != null && x.field().contains(targetName))
                .findFirst().orElseThrow(() -> new AssertionError("no message for " + targetName))
                .referenced();
    }

    /**
     * <b>The COMPUTE arm excludes an over-limit row too — the gap this project flagged as unmeasured.</b>
     * Our {@code OverRepetitionFormalGateDiffTest.numberOfFilledGroupsExcludesOverLimitRow} locks the exclusion
     * on the RULE arm only, so [KF218] said so rather than assuming the arms agree; on this same operator they
     * demonstrably diverge for a formally invalid operand ([KF120]). a12-kernel-lean measured the compute arm
     * independently (checkpoint {@code src-starred-group-count-computation}, row {@code over-limit}) after the
     * flag; this is our own kernel oracle on the same question, so the observation rests on two estates rather
     * than travelling across one.
     *
     * <p>{@code Riders} is declared max 5. Five, six and seven instantiated rows all count 5 and all sum 5, so
     * the surplus rows contribute to neither quantity.
     *
     * <p>The four-row control is what makes that mean anything: without it, "5 everywhere" is equally consistent
     * with the quantity being pinned at capacity regardless of content.
     */
    @Test
    void theCOMPUTEArmExcludesAnOverLimitRowFromBothTheCountAndTheSum() {
        Map<String, String> actual = new LinkedHashMap<>();
        for (int rows : new int[] {4, 5, 6, 7}) {
            List<RuntimeLaws.Computed> outcomes = RuntimeLaws.compute(POLARITY_MODEL, capacityDoc(rows, true));
            actual.put(rows + " rows — starred count", computed(outcomes, "/Catalogue/TallyStar"));
            actual.put(rows + " rows — starred sum", computed(outcomes, "/Catalogue/TallySum"));
        }

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("4 rows — starred count", "4");
        expected.put("4 rows — starred sum", "4");
        expected.put("5 rows — starred count", "5");
        expected.put("5 rows — starred sum", "5");
        expected.put("6 rows — starred count", "5");
        expected.put("6 rows — starred sum", "5");
        expected.put("7 rows — starred count", "5");
        expected.put("7 rows — starred sum", "5");
        assertEquals(expected, actual, "both starred quantities stop at the declared capacity on the compute arm");
    }

    /** The computed value at {@code path}, normalised so a scale difference cannot read as a count difference. */
    private static String computed(List<RuntimeLaws.Computed> outcomes, String path) {
        return RuntimeLaws.computedValue(outcomes, path)
                .map(v -> new BigDecimal(v).stripTrailingZeros().toPlainString())
                .orElse("(none)");
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

    private static final String TWO_STAR_MODEL = starListModel("TwoStarTallyComp", false);
    private static final String DUP_STAR_MODEL = starListModel("DupStarTallyComp", true);

    /** Two starred operands in one list — distinct groups, or the SAME group named twice. */
    private static String starListModel(String name, boolean duplicate) {
        var riders = M.flattenGroup("/Catalogue/Coverage/Riders*").group();
        var second = duplicate ? M.flattenGroup("/Catalogue/Coverage/Riders*").group()
                : M.flattenGroup("/Catalogue/Bundle/Items*").group();
        Computation comp = M.computation(M.field(TALLY)).name(name)
                .calc(Dsl.numberOfFilledGroups(riders, second).expression())
                .message(en("Star list tally."))
                .build();
        return M.add(comp).toJson();
    }

    private static RuntimeLaws.Doc starRows(int riderRows, int itemRows) {
        RuntimeLaws.Doc d = RuntimeLaws.doc().group("/Catalogue", 1)
                .field("/Catalogue/Reference", "CAT-1", 1, 1);
        for (int r = 1; r <= riderRows; r++) {
            d = d.group("/Catalogue/Coverage/Riders", 1, 1, r);
        }
        for (int i = 1; i <= itemRows; i++) {
            d = d.group("/Catalogue/Bundle/Items", 1, 1, i);
        }
        return d;
    }

    private static Optional<BigDecimal> starListTally(String model, RuntimeLaws.Doc doc) {
        return RuntimeLaws.computedValue(RuntimeLaws.compute(model, doc), TALLY).map(BigDecimal::new);
    }

    /**
     * <b>Two starred operands, and the DUPLICATE — the sharpest test of the fold this entry claims.</b>
     *
     * <p>[KF218] states the operator as {@code Σ contribution(operand)}. A repeated starred operand is where a
     * per-OPERAND sum and a per-GROUP one predict different numbers: three rider rows named twice are 6 under
     * the published phrasing and 3 if the carrier folds over distinct groups instead. Nothing in this project
     * exercised either shape — the 20-site sweep above found no list with two starred operands at all.
     *
     * <p>This is the RUNTIME half. The static half is ours too — all three shapes are {@code ADMITTED} by
     * {@code checkConsistency} in {@code EmptyGroupOperandLawsTest}. An earlier version of this javadoc
     * attributed the static half to a12-kernel-lean as pinned; they retracted that (the rows came from a
     * window they could not certify clean and they had declined to cite them), so no claim here rests on
     * them. Runtime acceptance would not have substituted: the kernel building an engine and computing a
     * number is not an {@code MVK_*} authoring verdict.
     *
     * <p>The zero-row row is the control: it separates a real fold from a constant.
     */
    @Test
    void aRepeatedStarredOperandIsCountedTWICEAndTwoDistinctStarredOperandsAdd() {
        // The authoring verdict on the very models these numbers come from: the kernel building an engine
        // and computing a number is runtime acceptance, which is NOT an MVK_* verdict, so the gate is run.
        assertEquals(List.of(), RuntimeLaws.consistencyMessages(TWO_STAR_MODEL),
                "two distinct starred operands are statically admitted");
        assertEquals(List.of(), RuntimeLaws.consistencyMessages(DUP_STAR_MODEL),
                "the SAME starred group named twice is statically admitted");

        // The exact spelling both numbers rest on, pinned so the operand list cannot silently dedupe.
        assertEquals("NumberOfFilledGroups(/Catalogue/Coverage/Riders*, /Catalogue/Coverage/Riders*) == 0",
                RuntimeLaws.render(Dsl.numberOfFilledGroups(
                        M.flattenGroup("/Catalogue/Coverage/Riders*").group(),
                        M.flattenGroup("/Catalogue/Coverage/Riders*").group()).eq(0)),
                "the duplicate operand survives rendering as two positions");

        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("distinct: 3 riders + 2 items", starListTally(TWO_STAR_MODEL, starRows(3, 2))
                .map(BigDecimal::toPlainString).orElse("(none)"));
        actual.put("distinct: 3 riders + 0 items", starListTally(TWO_STAR_MODEL, starRows(3, 0))
                .map(BigDecimal::toPlainString).orElse("(none)"));
        actual.put("distinct: 0 + 0 — control", starListTally(TWO_STAR_MODEL, starRows(0, 0))
                .map(BigDecimal::toPlainString).orElse("(none)"));
        actual.put("duplicate: 3 riders named twice", starListTally(DUP_STAR_MODEL, starRows(3, 0))
                .map(BigDecimal::toPlainString).orElse("(none)"));
        actual.put("duplicate: 0 rows — control", starListTally(DUP_STAR_MODEL, starRows(0, 0))
                .map(BigDecimal::toPlainString).orElse("(none)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("distinct: 3 riders + 2 items", "5");
        expected.put("distinct: 3 riders + 0 items", "3");
        expected.put("distinct: 0 + 0 — control", "0");
        expected.put("duplicate: 3 riders named twice", "6");
        expected.put("duplicate: 0 rows — control", "0");
        assertEquals(expected, actual, "the fold sums over OPERAND POSITIONS, not over distinct groups");
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
