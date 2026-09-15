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
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListRef;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §9 — a {@code Having} filter answers to TWO independent gates, and a single-column table
 * cannot tell them apart.
 *
 * <p><b>What is measured.</b> The SCOPE gate ({@code MVK_NO_ITERATION_FOR_WILDCARD}) asks whether the condition
 * carries any reference into a level the operand's star reopens; ANY in-scope conjunct clears it. The ITERATION
 * gate ({@code MVK_INVALID_ITERATION_IN_FILTER_CONDITION}) prohibits naming a repeatable level that lies
 * outside the operand's path, and NO conjunct rescues it — the enclosing rule iteration does not either, which
 * is the row that surprises. Either column alone is consistent with a single gate whose code varies: drop the
 * conjunct column and the two codes read as a spelling difference, drop the repeatability column and the second
 * gate is invisible.
 *
 * <p><b>The carrier is irrelevant, and that is a correction.</b> A {@code CurrentRepetition} operand and a plain
 * field reference draw the same code in every position measured here, so the third code is not a property of
 * {@code CurrentRepetition}'s unmarked spelling. Our shipped catalog line said it was.
 *
 * <p><b>What is NOT established, because the discriminating model cannot be authored.</b> Every refused row
 * names a level that is at once off the operand's path and unbound by the operand's star, and every admitted
 * out-of-scope row names a level that is at once ON that path and bound by the enclosing rule iteration. The
 * cell that separates those two readings — an unstarred repeatable ancestor on the operand's path that nothing
 * binds — is unreachable: the operand alone is already {@code MVK_NO_WILDCARD} there, control included
 * ({@link #anUnstarredAncestorOnTheOperandsPathIsUnreachableUnbound}). So "outside the operand's path" and "not
 * bound by any enclosing iteration" are extensionally equal over all legal models, and neither is claimed as
 * the mechanism.
 *
 * <p>Origin: a12-kernel-lean's {@code SPEC-2026-09-05-01} reported the conjunct column on a field-reference
 * carrier. Their rows are their report; every row below is measured here, on the carrier our catalog line
 * actually describes.
 */
class HavingFilterIterationGateLawsTest {

    private static final String ADMITTED = "admitted";
    private static final String NO_ITERATION = "MVK_NO_ITERATION_FOR_WILDCARD";
    private static final String NO_WILDCARD = "MVK_NO_WILDCARD";
    private static final String INVALID_ITERATION_IN_FILTER = "MVK_INVALID_ITERATION_IN_FILTER_CONDITION";

    private static final String SHIPMENT = "/Shipment";
    private static final String CARRIER = "/Shipment/Carrier";
    private static final String PARCELS = "/Shipment/Parcels";
    private static final String BOXES = "/Shipment/Parcels/Boxes";
    private static final String CLAIMS = "/Shipment/Claims";

    /**
     * Two SIBLING repeatable groups, one of them with a repeatable child, plus a non-repeatable group — so a
     * filter over a starred operand can name a level that is repeatable and off the operand's path, repeatable
     * and an unstarred ancestor ON it, or not repeatable at all.
     */
    private static final String MODEL = DocumentModelDraft.named("shipment-filter-gates").locale("en_US")
            .rootGroup("Shipment", root -> root
                    .stringField("Reference", s -> { })
                    .group("Carrier", carrier -> carrier.stringField("Scac", s -> { }))
                    .group("Parcels", parcels -> parcels.repeatable(50)
                            .stringField("Tracking", s -> { })
                            .field("Weight", FieldKind.NUMBER)
                            .group("Boxes", boxes -> boxes.repeatable(50)
                                    .stringField("Code", s -> { })
                                    .field("Volume", FieldKind.NUMBER)))
                    .group("Claims", claims -> claims.repeatable(50)
                            .stringField("ClaimNo", s -> { })
                            .field("Amount", FieldKind.NUMBER)))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(MODEL);

    /** A reference into the level a {@code Parcels*} operand's star reopens. */
    private static Condition inScopeParcels() {
        return M.num(PARCELS + "/Weight").gt(0);
    }

    /** A reference into the level a {@code Boxes*} operand's star reopens. */
    private static Condition inScopeBoxes() {
        return M.num(BOXES + "/Volume").gt(0);
    }

    /** Rule at the NON-repeatable root, so only the operand's own star can reopen a level. */
    private static String fromRoot(Condition filter) {
        return verdict(PARCELS + "*/Tracking", SHIPMENT, "../Reference", SHIPMENT + "/Reference", filter);
    }

    /** Rule hosted at the repeatable {@code Claims} — a level the operand's path does not contain. */
    private static String fromClaims(Condition filter) {
        return verdict(PARCELS + "*/Tracking", CLAIMS, "../ClaimNo", CLAIMS + "/ClaimNo", filter);
    }

    /** Rule at the repeatable {@code Parcels}, filtering an operand that stars only {@code Boxes} below it. */
    private static String fromParcels(Condition filter) {
        return verdict(BOXES + "*/Code", PARCELS, "../Tracking", PARCELS + "/Tracking", filter);
    }

    /** Rule at the root over that same {@code Boxes*} operand, leaving {@code Parcels} bound by nothing. */
    private static String fromRootOverBoxes(Condition filter) {
        return verdict(BOXES + "*/Code", SHIPMENT, "../Reference", SHIPMENT + "/Reference", filter);
    }

    /** A {@code null} filter measures the operand unfiltered, which is how a topology is shown reachable. */
    private static String verdict(String operandPath, String group, String errorField, String errorRef,
            Condition filter) {
        FieldListRef leaves = M.flatten(operandPath);
        String condition = new DslRenderer().render(Dsl.fieldFilled(M.field(errorRef))
                .and(Dsl.numberOfFilledFields(filter == null ? leaves : leaves.having(filter)).gt(0)));
        return codes(KernelLaws.checkModel(MODEL, group, errorField, condition));
    }

    /**
     * The grid. The conjunct column is what separates the gates, and the last pair is the one that refutes
     * "the enclosing rule iteration binds it": {@code Claims} is iterated by the rule and still refused.
     */
    @Test
    void anInScopeConjunctRescuesTheScopeGateAndNotTheIterationGate() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("the level the operand's own star reopens — sole",
                fromRoot(Dsl.currentRepetition(M.group(PARCELS)).gt(0)));
        actual.put("the level the operand's own star reopens — beside an in-scope conjunct",
                fromRoot(Dsl.currentRepetition(M.group(PARCELS)).gt(0).and(inScopeParcels())));
        actual.put("a repeatable sibling off the operand's path — sole",
                fromRoot(Dsl.currentRepetition(M.group(CLAIMS)).gt(0)));
        actual.put("a repeatable sibling off the operand's path — beside an in-scope conjunct",
                fromRoot(Dsl.currentRepetition(M.group(CLAIMS)).gt(0).and(inScopeParcels())));
        actual.put("a NON-repeatable group — sole", fromRoot(Dsl.currentRepetition(M.group(CARRIER)).gt(0)));
        actual.put("a NON-repeatable group — beside an in-scope conjunct",
                fromRoot(Dsl.currentRepetition(M.group(CARRIER)).gt(0).and(inScopeParcels())));
        actual.put("that same sibling while the RULE iterates it — sole",
                fromClaims(Dsl.currentRepetition(M.group(CLAIMS)).gt(0)));
        actual.put("that same sibling while the RULE iterates it — beside an in-scope conjunct",
                fromClaims(Dsl.currentRepetition(M.group(CLAIMS)).gt(0).and(inScopeParcels())));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("the level the operand's own star reopens — sole", ADMITTED);
        expected.put("the level the operand's own star reopens — beside an in-scope conjunct", ADMITTED);
        expected.put("a repeatable sibling off the operand's path — sole", INVALID_ITERATION_IN_FILTER);
        expected.put("a repeatable sibling off the operand's path — beside an in-scope conjunct",
                INVALID_ITERATION_IN_FILTER);
        expected.put("a NON-repeatable group — sole", NO_ITERATION);
        expected.put("a NON-repeatable group — beside an in-scope conjunct", ADMITTED);
        expected.put("that same sibling while the RULE iterates it — sole", INVALID_ITERATION_IN_FILTER);
        expected.put("that same sibling while the RULE iterates it — beside an in-scope conjunct",
                INVALID_ITERATION_IN_FILTER);
        assertEquals(expected, actual,
                "the scope gate yields to any in-scope conjunct; the iteration gate yields to nothing, the "
                        + "enclosing rule iteration included");
    }

    /**
     * The refused rows above could be read as a property of {@code CurrentRepetition}'s unmarked spelling — our
     * shipped catalog line read them that way. They are not: a plain field reference draws the same code in the
     * same position, and the {@code $}-marked form draws its own third code rather than the iteration one.
     */
    @Test
    void theCodeFollowsTheReferencedLevelsPositionRatherThanTheCarrierOrTheSpelling() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("unstarred ancestor ON the operand's path, CurrentRepetition — sole",
                fromParcels(Dsl.currentRepetition(M.group(PARCELS)).gt(0)));
        actual.put("unstarred ancestor ON the operand's path, CurrentRepetition — beside a conjunct",
                fromParcels(Dsl.currentRepetition(M.group(PARCELS)).gt(0).and(inScopeBoxes())));
        actual.put("a field UNDER the reopened level, which rides both — sole",
                fromParcels(inScopeBoxes()));
        actual.put("unstarred ancestor ON the operand's path, FIELD reference — sole",
                fromParcels(M.num(PARCELS + "/Weight").gt(0)));
        actual.put("unstarred ancestor ON the operand's path, FIELD reference — beside a conjunct",
                fromParcels(M.num(PARCELS + "/Weight").gt(0).and(inScopeBoxes())));
        actual.put("repeatable level OFF the operand's path, CurrentRepetition — sole",
                fromClaims(Dsl.currentRepetition(M.group(CLAIMS)).gt(0)));
        actual.put("repeatable level OFF the operand's path, FIELD reference — sole",
                fromClaims(M.num(CLAIMS + "/Amount").gt(0)));
        actual.put("repeatable level OFF the operand's path, FIELD reference — beside a conjunct",
                fromClaims(M.num(CLAIMS + "/Amount").gt(0).and(inScopeParcels())));
        actual.put("repeatable level OFF the operand's path, the $-MARKED form — sole",
                fromRoot(Dsl.currentRepetition(M.group(CLAIMS), true).gt(0)));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("unstarred ancestor ON the operand's path, CurrentRepetition — sole", NO_ITERATION);
        expected.put("unstarred ancestor ON the operand's path, CurrentRepetition — beside a conjunct", ADMITTED);
        // A reference reaching INTO the reopened level clears the scope gate on its own, which every other
        // in-scope row measures only as a conjunct beside a failing one.
        expected.put("a field UNDER the reopened level, which rides both — sole", ADMITTED);
        expected.put("unstarred ancestor ON the operand's path, FIELD reference — sole", NO_ITERATION);
        expected.put("unstarred ancestor ON the operand's path, FIELD reference — beside a conjunct", ADMITTED);
        expected.put("repeatable level OFF the operand's path, CurrentRepetition — sole",
                INVALID_ITERATION_IN_FILTER);
        expected.put("repeatable level OFF the operand's path, FIELD reference — sole",
                INVALID_ITERATION_IN_FILTER);
        expected.put("repeatable level OFF the operand's path, FIELD reference — beside a conjunct",
                INVALID_ITERATION_IN_FILTER);
        expected.put("repeatable level OFF the operand's path, the $-MARKED form — sole", NO_WILDCARD);
        assertEquals(expected, actual,
                "both carriers agree in every position, so the code is not about the operand's spelling");
    }

    /**
     * The cell that would separate "off the operand's path" from "bound by no enclosing iteration" — and the
     * reason neither is claimed as the mechanism. It cannot be authored: an unstarred repeatable ancestor that
     * nothing binds makes the OPERAND illegal, so the filter is never reached. The unfiltered control is what
     * proves the refusal is the operand's rather than the filter's.
     */
    @Test
    void anUnstarredAncestorOnTheOperandsPathIsUnreachableUnbound() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("the operand unfiltered — the control that locates the refusal", fromRootOverBoxes(null));
        actual.put("CurrentRepetition on that ancestor — sole",
                fromRootOverBoxes(Dsl.currentRepetition(M.group(PARCELS)).gt(0)));
        actual.put("CurrentRepetition on that ancestor — beside an in-scope conjunct",
                fromRootOverBoxes(Dsl.currentRepetition(M.group(PARCELS)).gt(0).and(inScopeBoxes())));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("the operand unfiltered — the control that locates the refusal", NO_WILDCARD);
        expected.put("CurrentRepetition on that ancestor — sole", NO_WILDCARD);
        expected.put("CurrentRepetition on that ancestor — beside an in-scope conjunct", NO_WILDCARD);
        assertEquals(expected, actual,
                "the operand is refused before any filter question, so this topology settles nothing");
    }

    /**
     * The remedy, measured rather than asserted. KERNEL-FINDINGS §9 says the {@code $} marker is the only fix
     * for the iteration gate; a catalog line that tells an author "no conjunct rescues this" owes them the thing
     * that does. The marker relocates the reference to the captured outer environment, so it works exactly when
     * that environment HAS a coordinate for the named level — which is why the root-hosted row still refuses.
     */
    @Test
    void theMarkerIsTheRemedyForTheIterationGateAndOnlyWhereAnOuterCoordinateExists() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("$-marked field on the enclosing-iterated level — sole",
                fromClaims(M.num(CLAIMS + "/Amount").outer().gt(0)));
        actual.put("$-marked field on the enclosing-iterated level — beside an in-scope conjunct",
                fromClaims(M.num(CLAIMS + "/Amount").outer().gt(0).and(inScopeParcels())));
        actual.put("$-marked CurrentRepetition on the enclosing-iterated level — beside a conjunct",
                fromClaims(Dsl.currentRepetition(M.group(CLAIMS), true).gt(0).and(inScopeParcels())));
        actual.put("the same marked field with NO enclosing iteration to capture — beside a conjunct",
                fromRoot(M.num(CLAIMS + "/Amount").outer().gt(0).and(inScopeParcels())));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("$-marked field on the enclosing-iterated level — sole", NO_ITERATION);
        expected.put("$-marked field on the enclosing-iterated level — beside an in-scope conjunct", ADMITTED);
        expected.put("$-marked CurrentRepetition on the enclosing-iterated level — beside a conjunct", ADMITTED);
        expected.put("the same marked field with NO enclosing iteration to capture — beside a conjunct",
                NO_WILDCARD);
        assertEquals(expected, actual,
                "the marker clears the iteration gate but not the scope gate, and needs an outer coordinate");
    }

    /**
     * The group-PRESENCE carrier, because [KF242](../../docs/KERNEL-FINDINGS.md) and the API guide state its
     * conjunct rule on a NON-repeatable out-of-scope group and read as if it held for any out-of-scope group.
     * A repeatable sibling is the carrier that separates those, so the same claim is put to it here.
     */
    @Test
    void aGroupPresenceLeafObeysTheSameTwoGatesRatherThanOneOutOfScopeRule() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("GroupFilled on a NON-repeatable out-of-scope group — sole",
                fromRoot(Dsl.groupFilled(M.group(CARRIER))));
        actual.put("GroupFilled on a NON-repeatable out-of-scope group — beside an in-scope conjunct",
                fromRoot(Dsl.groupFilled(M.group(CARRIER)).and(inScopeParcels())));
        actual.put("GroupFilled on a repeatable SIBLING — sole", fromRoot(Dsl.groupFilled(M.group(CLAIMS))));
        actual.put("GroupFilled on a repeatable SIBLING — beside an in-scope conjunct",
                fromRoot(Dsl.groupFilled(M.group(CLAIMS)).and(inScopeParcels())));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("GroupFilled on a NON-repeatable out-of-scope group — sole", NO_ITERATION);
        expected.put("GroupFilled on a NON-repeatable out-of-scope group — beside an in-scope conjunct", ADMITTED);
        expected.put("GroupFilled on a repeatable SIBLING — sole", INVALID_ITERATION_IN_FILTER);
        expected.put("GroupFilled on a repeatable SIBLING — beside an in-scope conjunct",
                INVALID_ITERATION_IN_FILTER);
        assertEquals(expected, actual,
                "the presence leaf splits by the named group's repeatability, not by being out of scope");
    }

    /**
     * Rule DECLARED at the root but reporting INSIDE {@code Parcels}, so the two candidate readings split.
     *
     * <p>Every other carrier above declares the rule in the same group its error field sits in, which makes
     * "the DECLARING group's iteration addresses the level" and "the TARGET's coordinates address it" the
     * same sentence on all of them. They are not the same question, and the checker's readers were written
     * against the first one.
     */
    private static String fromRootReportingInsideParcels(Condition filter) {
        return verdict(BOXES + "*/Code", SHIPMENT, "../Parcels/Tracking", PARCELS + "/Tracking", filter);
    }

    /**
     * Which of the two the filter's iteration gate is keyed on — the constant every other carrier here holds.
     *
     * <p>The operand stars only {@code Boxes} and so crosses {@code Parcels} unstarred. With the rule
     * declared at the root and reporting at the root, that level is bound by nothing and the operand is
     * {@code MVK_NO_WILDCARD} before any filter question. Moving only the ERROR FIELD into {@code Parcels}
     * changes nothing about the operand and everything about the rule: A12 addresses the level by the
     * target's coordinates, the operand is admitted, and the filter's own gate is what answers.
     *
     * <p>Three readers in the portable checker asked the declaring group instead; two were corrected from
     * their own differential, and this row is what settles the third rather than leaving it under-reporting
     * on a shape no grid had built.
     */
    @Test
    void whetherTheFilterIterationGateIsKeyedOnTheDeclaringGroupOrTheTarget() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("rule declared AND reporting at the root — the control", fromRootOverBoxes(inScopeBoxes()));
        actual.put("the same rule reporting INSIDE Parcels, in-scope filter",
                fromRootReportingInsideParcels(inScopeBoxes()));
        actual.put("the same, with a filter leaf off the operand's path",
                fromRootReportingInsideParcels(Dsl.groupFilled(M.group(CLAIMS))));
        actual.put("the same, unfiltered — the topology control",
                fromRootReportingInsideParcels(null));

        Map<String, String> expected = new LinkedHashMap<>();
        // Parcels is bound by nothing here, so the operand never reaches its filter.
        expected.put("rule declared AND reporting at the root — the control", NO_WILDCARD);
        // ...and the error field alone rebinds it: the target's coordinates address Parcels.
        expected.put("the same rule reporting INSIDE Parcels, in-scope filter", ADMITTED);
        // The filter's OWN gate answers once the operand is admitted, which is what proves the row above is
        // the operand being reached rather than the whole rule being admitted for some other reason.
        expected.put("the same, with a filter leaf off the operand's path", INVALID_ITERATION_IN_FILTER);
        expected.put("the same, unfiltered — the topology control", ADMITTED);
        assertEquals(expected, actual, "whether the filter's iteration gate is keyed on the declaring group");
    }

    private static String codes(ValidationResult r) {
        return r.ok() ? ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")")).collect(Collectors.joining(" + "));
    }
}
