package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.ModelDrafts;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS KF182 — a computation that reads its own computed field <b>through a projection</b> draws
 * its own kernel class, {@code MVK_ERROR_SEMANTIC_INDEX_OR_CATEGORY_FOR_ERRORFIELD}, and not the plain
 * {@code MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD} (the lean project's {@code SPEC-2026-08-10-03}; every
 * verdict here was reproduced against the real kernel rather than accepted as reported).
 *
 * <p>The MVK ledger classified that code as <em>erased</em> — "the computed-field slot is a plain field
 * handle, so a semantic-index or category decoration on it is unrepresentable". That reasoning is about the
 * <b>slot</b> and the code is not about the slot: it fires on the <em>read</em> of the computed field inside
 * an operation or precondition, which is ordinary authorable DSL. Both halves of the code's name are
 * measured here, because a fix that covered only the category half would leave the same misattribution live
 * on the semantic-index route.
 *
 * <p><b>What makes the rows discriminating.</b> Every rejected row sits on one model beside an accepted
 * control that differs only in <em>which field</em> is projected, so the projection is not itself the
 * rejection; and beside a plain self-read of the same field, so the two classes are separated on identical
 * topology. The mixed rows then settle precedence: a projected read pre-empts a plain one in either operand
 * order <em>and across slots</em> — so {@code Computation.build()} folds the reading mode over the whole
 * computation before it reports one ({@code dsl.ComputationAuthoringTest}).
 *
 * <p>Explicitly outside these rows: mixed category/direct lists longer than two, incompatible categories,
 * stars, {@code Having}, repeatable-computation scheduling, and any runtime claim. The consistency oracle
 * establishes what the kernel refuses, never how it evaluates.
 */
class ComputedTargetProjectedReadLawsTest {

    private static final RuleValidator VALIDATOR = new RuleValidator();
    private static final String PROJECTED = "MVK_ERROR_SEMANTIC_INDEX_OR_CATEGORY_FOR_ERRORFIELD";
    private static final String PLAIN = "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD";
    private static final String SCALE_GATE = "MVK_INVALID_COMPARE_DEC_PLACES";

    private static final String ROUTING = ModelDrafts.routing().toJson();

    private static final String SELECTED = "/Routing/SelectedTier";
    private static final String REQUESTED = "/Routing/RequestedTier";
    private static final String FALLBACK = "/Routing/FallbackTier";
    private static final String DISTANCE = "/Routing/Legs/Distance";

    /**
     * {@code checkConsistency} over one computation, authored as DSL text through the draft — the route the
     * CLI uses, and the only one that can carry a shape the typed builder pre-checks away.
     */
    private static List<String> codes(String group, String relTarget, String operation, String precondition) {
        return codes(group, relTarget, operation, precondition, null);
    }

    /**
     * The complete emitted code list. Asserting the list alone would not see a kernel notification that
     * carries no {@code MVK_*} token at all (a real class — the date-format messages are one), so the size is
     * asserted against the codes: every row below is exactly as long as it looks.
     */
    private static List<String> codes(String group, String relTarget, String operation,
            String commonPrecondition, String tierPrecondition) {
        DraftComputation comp = new DraftComputation(group, "TierComp", relTarget, commonPrecondition,
                List.of(new DraftComputation.Alternative(tierPrecondition, operation)), List.of());
        ValidationResult result = VALIDATOR.check(ROUTING, comp);
        List<String> codes = result.diagnostics().stream().flatMap(d -> d.code().stream()).toList();
        assertEquals(result.diagnostics().size(), codes.size(),
                () -> "a diagnostic without an MVK code would be invisible to this row: " + result.diagnostics());
        return codes;
    }

    private static List<String> tier(String operation) {
        return codes("/Routing", "../SelectedTier", operation, null);
    }

    private static String category(String fieldPath) {
        return fieldPath + " -> ServiceTier";
    }

    // ── the enum-category half ────────────────────────────────────────────────────────────────────────

    @Test
    void anOrdinaryCategoryReadOfTheComputedFieldReportsTheProjectedClassAlone() {
        assertEquals(List.of(PROJECTED), tier("[" + category(SELECTED) + "]"));
    }

    @Test
    void eitherTargetPositionInATwoCategoryFirstFilledValueReportsIt() {
        assertEquals(List.of(PROJECTED),
                tier("FirstFilledValue(" + category(SELECTED) + ", " + category(REQUESTED) + ")"));
        assertEquals(List.of(PROJECTED),
                tier("FirstFilledValue(" + category(REQUESTED) + ", " + category(SELECTED) + ")"));
    }

    /** The two accepted controls: the same projections of a DIFFERENT field are ordinary computation sources. */
    @Test
    void projectingAnyOtherFieldOfTheSameCategoryIsAccepted() {
        assertEquals(List.of(), tier("[" + category(REQUESTED) + "]"));
        assertEquals(List.of(),
                tier("FirstFilledValue(" + category(REQUESTED) + ", " + category(FALLBACK) + ")"));
    }

    /** The separator: the identical shapes without the projection draw the PLAIN class on the same model. */
    @Test
    void aPlainSelfReadStillReportsThePlainClass() {
        assertEquals(List.of(PLAIN), tier("[" + SELECTED + "]"));
        assertEquals(List.of(PLAIN), tier("FirstFilledValue(" + SELECTED + ", " + REQUESTED + ")"));
    }

    /** Precedence: a projected self-read pre-empts a plain one beside it, in either operand order. */
    @Test
    void aProjectedSelfReadPreEmptsAPlainOneInTheSameOperation() {
        assertEquals(List.of(PROJECTED), tier("FirstFilledValue(" + SELECTED + ", " + category(SELECTED) + ")"));
        assertEquals(List.of(PROJECTED), tier("FirstFilledValue(" + category(SELECTED) + ", " + SELECTED + ")"));
    }

    /** Both precondition slots report the same two classes, split the same way. */
    @Test
    void aPreconditionSelfReadIsClassifiedByItsProjectionToo() {
        String cleanOperation = "[" + category(REQUESTED) + "]";
        String projectedRead = "[" + category(SELECTED) + "] == \"ECONOMY\"";
        String plainRead = "[" + SELECTED + "] == \"ECONOMY\"";
        assertEquals(List.of(PROJECTED), codes("/Routing", "../SelectedTier", cleanOperation, projectedRead));
        assertEquals(List.of(PLAIN), codes("/Routing", "../SelectedTier", cleanOperation, plainRead));
        assertEquals(List.of(PROJECTED),
                codes("/Routing", "../SelectedTier", cleanOperation, null, projectedRead));
        assertEquals(List.of(PLAIN), codes("/Routing", "../SelectedTier", cleanOperation, null, plainRead));
    }

    /**
     * The pre-emption is decided across the WHOLE computation, not per slot: a plain self-read in the
     * operation reports the projected class when a projected self-read sits in either precondition. This is
     * the row that makes a slot-ordered client check observably wrong.
     */
    @Test
    void theProjectedClassPreEmptsAPlainReadInAnotherSlot() {
        String plainOperation = "FirstFilledValue(" + SELECTED + ", " + REQUESTED + ")";
        String projectedRead = "[" + category(SELECTED) + "] == \"ECONOMY\"";
        assertEquals(List.of(PROJECTED),
                codes("/Routing", "../SelectedTier", plainOperation, projectedRead));
        assertEquals(List.of(PROJECTED),
                codes("/Routing", "../SelectedTier", plainOperation, null, projectedRead));
    }

    // ── the semantic-index half, which the ledger row erased for the same wrong reason ─────────────────

    @Test
    void aSemanticIndexReadOfTheComputedFieldReportsTheSameProjectedClass() {
        assertEquals(List.of(PROJECTED),
                codes("/Routing/Legs", "../Distance", "[" + DISTANCE + " For \"L-1\"]", null));
        // nested in arithmetic, so the class does not depend on the read being the whole operation
        assertEquals(List.of(PROJECTED),
                codes("/Routing/Legs", "../Distance", "[" + DISTANCE + " For \"L-1\"] * 2", null));
    }

    /** Its accepted control: the identical indexed read into a target that is not the field being read. */
    @Test
    void thatSameIndexedReadIntoAnotherTargetIsAccepted() {
        assertEquals(List.of(),
                codes("/Routing", "../PlannedDistance", "[" + DISTANCE + " For \"L-1\"]", null));
    }

    /**
     * The half a reading of the key as "just the lookup value" gets wrong: the kernel matches the calculated
     * field against the semantic-index <b>KEY</b> too, so a computation whose operation reads another field
     * keyed by its own target is the projected class — even though the target is not the indexed field. The
     * control swaps only the key for a second non-target field of the same kind.
     */
    @Test
    void theComputedFieldUsedAsTheSemanticIndexKeyIsAlsoTheProjectedClass() {
        String legRef = "/Routing/Legs/LegRef";
        assertEquals(List.of(PROJECTED), codes("/Routing", "../PlannedLeg",
                "[" + legRef + " For /Routing/PlannedLeg]", null));
        assertEquals(List.of(PROJECTED), codes("/Routing", "../PlannedLeg",
                "FirstFilledValue(" + legRef + " For /Routing/PlannedLeg, " + legRef + " For \"L-1\")", null));
        assertEquals(List.of(), codes("/Routing", "../PlannedLeg",
                "[" + legRef + " For /Routing/PreferredLeg]", null));
    }

    /**
     * The join with KF181 (see {@code ComputedTargetScaleLawsTest}): the implicit target-scale comparison is
     * decided ahead of this class as well, so the projected class is not the first rejection unconditionally.
     * Distance is scale 0, so the {@code 1.5} factor is the only thing that changed from the row above.
     */
    @Test
    void theScaleGateIsStillDecidedAheadOfThisClass() {
        assertEquals(List.of(SCALE_GATE),
                codes("/Routing/Legs", "../Distance", "[" + DISTANCE + " For \"L-1\"] * 1.5", null));
    }
}
