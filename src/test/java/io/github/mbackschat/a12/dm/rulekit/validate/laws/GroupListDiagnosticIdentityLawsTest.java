package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §1/§9 — <b>which diagnostic class a group-list operand shape draws, per carrier</b>
 * (a12-kernel-lean SPEC-2026-08-11-03). The neighbouring owners lock one gate each on one carrier;
 * this one asserts the COMPLETE emitted code list across the four carriers that consume a group list —
 * {@code AllGroupsFilled}, {@code AtLeastOneGroupFilled}, {@code NotAllGroupsFilled}, and the count
 * {@code NumberOfFilledGroups} — because the claim under test is that the classes discriminate.
 *
 * <p>Two rows carry the measurement's whole weight, and both refute a generalization that a
 * <b>single-root</b> fixture cannot see:
 *
 * <ul>
 *   <li><b>The root-group classes exist</b> and are not the ancestor-overlap class wearing another name.
 *       In a one-root model every co-operand of the root is also its descendant, so the overlap gate —
 *       which runs first — is all such a model can ever show. This fixture has a <b>second root</b>
 *       ({@code /Carrier}) whose co-operand is NOT a descendant, and there the root classes appear:
 *       {@code MVK_ROOT_GROUP_REFERENCED} for the all/not-all family and
 *       {@code MVK_ROOT_GROUP_WITH_OTHER_PARAMETERS} for the count-shaped pair.</li>
 *   <li><b>The error field's locus selects the class</b> for an unstarred repeatable operand. The same
 *       {@code NumberOfFilledGroups(Parcels)} draws {@code MVK_NO_WILDCARD} when the error field sits
 *       outside the repeatable group and {@code MVK_PARAMSIZE_INVALIDGN} when it sits inside — so
 *       neither code is "the" class for that shape, and a claim stated without the locus is unfalsifiable
 *       on half its domain.</li>
 * </ul>
 *
 * <p>These gates are kernel-arbitrated (F4): the typed surface represents the shapes and lets the kernel
 * refuse. The duplicate/overlap and arity bars on the <em>entity</em> list stay in
 * {@link GroupListEntityOperandLawsTest}, the root bars in {@link GroupQuantifierLawsTest}, and the
 * count carrier's computation-side admission in {@link RootGroupCountLawsTest}.
 */
class GroupListDiagnosticIdentityLawsTest {

    /**
     * Two non-repeatable roots, so "is a root" and "is an ancestor of the co-operand" are separable;
     * plus a nested pair ({@code Origin} ▸ {@code Depot}) for overlap and a repeatable {@code Parcels}
     * carrying a field, so the error field can sit inside or outside it.
     */
    private static final String SHIPPING = DocumentModelDraft.named("shipping").locale("en_US")
            .rootGroup("Shipment", s -> s
                    .field("TrackingCode", FieldKind.STRING)
                    .group("Origin", o -> o
                            .field("Yard", FieldKind.STRING)
                            .group("Depot", d -> d.field("DepotCode", FieldKind.STRING)))
                    .group("Destination", d -> d.field("City", FieldKind.STRING))
                    .group("Parcels", p -> p.repeatable(20)
                            .field("Label", FieldKind.STRING)
                            .field("Weight", FieldKind.NUMBER)))
            .rootGroup("Carrier", c -> c.field("CarrierName", FieldKind.STRING))
            .build().toJson();

    /** A field inside the repeatable group — the error-field locus that makes the rule iterate it. */
    private static final String INSIDE = "../Parcels/Label";

    // ── the root classes are real, and the overlap gate pre-empts them on a descendant ──────────────

    @Test
    void aRootOperandDrawsItsOwnClassExceptWhereTheOverlapGatePreEmptsIt() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("all | root + disjoint", guarded("AllGroupsFilled(/Carrier, Destination)"));
        actual.put("all | root + own descendant", guarded("AllGroupsFilled(/Shipment, Destination)"));
        actual.put("atLeastOne | root + disjoint", guarded("AtLeastOneGroupFilled(/Carrier, Destination)"));
        actual.put("atLeastOne | root + own descendant", guarded("AtLeastOneGroupFilled(/Shipment, Destination)"));
        actual.put("notAll | root + disjoint", guarded("NotAllGroupsFilled(/Carrier, Destination)"));
        actual.put("notAll | root + own descendant", guarded("NotAllGroupsFilled(/Shipment, Destination)"));
        actual.put("count | root + disjoint", guarded("NumberOfFilledGroups(/Carrier, Destination) > 0"));
        actual.put("count | root + own descendant", guarded("NumberOfFilledGroups(/Shipment, Destination) > 0"));
        actual.put("atLeastOne | sole root", guarded("AtLeastOneGroupFilled(/Carrier)"));
        actual.put("noGroup | sole root", guarded("NoGroupFilled(/Carrier)"));
        actual.put("all | sole root", guarded("AllGroupsFilled(/Carrier)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("all | root + disjoint", "MVK_ROOT_GROUP_REFERENCED");
        expected.put("all | root + own descendant", "MVK_DUPLICATE_PARAM2");
        expected.put("atLeastOne | root + disjoint", "MVK_ROOT_GROUP_WITH_OTHER_PARAMETERS");
        expected.put("atLeastOne | root + own descendant", "MVK_DUPLICATE_PARAM2");
        expected.put("notAll | root + disjoint", "MVK_ROOT_GROUP_REFERENCED");
        expected.put("notAll | root + own descendant", "MVK_DUPLICATE_PARAM2");
        expected.put("count | root + disjoint", "MVK_ROOT_GROUP_WITH_OTHER_PARAMETERS");
        expected.put("count | root + own descendant", "MVK_DUPLICATE_PARAM2");
        expected.put("atLeastOne | sole root", "admitted");
        expected.put("noGroup | sole root", "admitted");
        expected.put("all | sole root", "MVK_ROOT_GROUP_REFERENCED");
        assertEquals(expected, actual, "a root operand's class, per carrier");
    }

    // ── exact duplicate and ancestor overlap are two classes, in every carrier ──────────────────────

    @Test
    void anExactDuplicateAndAnAncestorOverlapAreTwoClassesInEveryCarrier() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("all | exact duplicate", guarded("AllGroupsFilled(Destination, Destination)"));
        actual.put("atLeastOne | exact duplicate", guarded("AtLeastOneGroupFilled(Destination, Destination)"));
        actual.put("notAll | exact duplicate", guarded("NotAllGroupsFilled(Destination, Destination)"));
        actual.put("count | exact duplicate", guarded("NumberOfFilledGroups(Destination, Destination) > 0"));
        actual.put("all | ancestor overlap", guarded("AllGroupsFilled(Origin, Origin/Depot)"));
        actual.put("atLeastOne | ancestor overlap", guarded("AtLeastOneGroupFilled(Origin, Origin/Depot)"));
        actual.put("notAll | ancestor overlap", guarded("NotAllGroupsFilled(Origin, Origin/Depot)"));
        actual.put("count | ancestor overlap", guarded("NumberOfFilledGroups(Origin, Origin/Depot) > 0"));
        actual.put("all | length three, duplicate", guarded("AllGroupsFilled(Destination, Origin, Destination)"));
        actual.put("all | length three, overlap", guarded("AllGroupsFilled(Destination, Origin, Origin/Depot)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("all | exact duplicate", "MVK_DUPLICATE_PARAM1");
        expected.put("atLeastOne | exact duplicate", "MVK_DUPLICATE_PARAM1");
        expected.put("notAll | exact duplicate", "MVK_DUPLICATE_PARAM1");
        expected.put("count | exact duplicate", "MVK_DUPLICATE_PARAM1");
        expected.put("all | ancestor overlap", "MVK_DUPLICATE_PARAM2");
        expected.put("atLeastOne | ancestor overlap", "MVK_DUPLICATE_PARAM2");
        expected.put("notAll | ancestor overlap", "MVK_DUPLICATE_PARAM2");
        expected.put("count | ancestor overlap", "MVK_DUPLICATE_PARAM2");
        expected.put("all | length three, duplicate", "MVK_DUPLICATE_PARAM1");
        expected.put("all | length three, overlap", "MVK_DUPLICATE_PARAM2");
        assertEquals(expected, actual, "exact duplicate versus ancestor overlap");
    }

    // ── three wildcard classes, discriminating by carrier ───────────────────────────────────────────

    @Test
    void theThreeWildcardClassesDiscriminateByCarrier() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("quantifier | starred operand where stars are barred",
                guarded("AllGroupsFilled(Parcels*, Destination)"));
        actual.put("scalar GroupFilled | starred operand", guarded("GroupFilled(Parcels*)"));
        actual.put("quantifier | unstarred repeatable operand", guarded("AllGroupsFilled(Parcels, Destination)"));
        actual.put("count | unstarred repeatable operand", guarded("NumberOfFilledGroups(Parcels, Destination) > 0"));
        actual.put("count | starred beside a fixed operand",
                guarded("NumberOfFilledGroups(Parcels*, Destination) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("quantifier | starred operand where stars are barred", "MVK_NO_WILDCARDS_G_ALLOWED");
        expected.put("scalar GroupFilled | starred operand", "MVK_NO_WILDCARDS_ALLOWED");
        expected.put("quantifier | unstarred repeatable operand", "MVK_NO_WILDCARD");
        expected.put("count | unstarred repeatable operand", "MVK_NO_WILDCARD");
        expected.put("count | starred beside a fixed operand", "admitted");
        assertEquals(expected, actual, "the three wildcard classes");
    }

    // ── multiplicity is per family ──────────────────────────────────────────────────────────────────

    @Test
    void theMultiplicityClassIsPerFamily() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("all | singleton", guarded("AllGroupsFilled(Destination)"));
        actual.put("notAll | singleton", guarded("NotAllGroupsFilled(Destination)"));
        actual.put("notCollectively | singleton", guarded("GroupsNotCollectivelyFilled(Destination)"));
        actual.put("atLeastOne | singleton", guarded("AtLeastOneGroupFilled(Destination)"));
        actual.put("noGroup | singleton", guarded("NoGroupFilled(Destination)"));
        actual.put("count | singleton relative", guarded("NumberOfFilledGroups(Destination) > 0"));
        actual.put("count | singleton absolute", guarded("NumberOfFilledGroups(/Shipment/Destination) > 0"));
        actual.put("count | singleton starred repeatable", guarded("NumberOfFilledGroups(Parcels*) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("all | singleton", "MVK_PARAMSIZE_INVALID2");
        expected.put("notAll | singleton", "MVK_PARAMSIZE_INVALID2");
        expected.put("notCollectively | singleton", "MVK_PARAMSIZE_INVALID2");
        expected.put("atLeastOne | singleton", "admitted");
        expected.put("noGroup | singleton", "admitted");
        expected.put("count | singleton relative", "MVK_PARAMSIZE_INVALIDGN");
        expected.put("count | singleton absolute", "MVK_PARAMSIZE_INVALIDGN");
        expected.put("count | singleton starred repeatable", "admitted");
        assertEquals(expected, actual, "multiplicity per family");
    }

    /**
     * The measurement that stops either code being called "the" class for an unstarred repeatable operand:
     * every row below is the SAME operand shape, and only the error field moves.
     */
    @Test
    void theErrorFieldLocusSelectsTheClassForAnUnstarredRepeatableOperand() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("count sole | error field outside", guarded("NumberOfFilledGroups(Parcels) > 0"));
        actual.put("count sole | error field inside",
                verdict("/Shipment", INSIDE, "NumberOfFilledGroups(Parcels) > 0"));
        actual.put("count sole | the repeatable group is the rule group",
                verdict("/Shipment/Parcels", "../Label", "NumberOfFilledGroups(/Shipment/Parcels) > 0"));
        actual.put("count pair | error field outside", guarded("NumberOfFilledGroups(Parcels, Destination) > 0"));
        actual.put("count pair | error field inside",
                verdict("/Shipment", INSIDE, "NumberOfFilledGroups(Parcels, Destination) > 0"));
        actual.put("quantifier pair | error field outside", guarded("AllGroupsFilled(Parcels, Destination)"));
        actual.put("quantifier pair | error field inside",
                verdict("/Shipment", INSIDE, "AllGroupsFilled(Parcels, Destination)"));
        actual.put("atLeastOne sole | error field outside", guarded("AtLeastOneGroupFilled(Parcels)"));
        actual.put("atLeastOne sole | error field inside",
                verdict("/Shipment", INSIDE, "AtLeastOneGroupFilled(Parcels)"));
        actual.put("atLeastOne sole | the repeatable group is the rule group",
                verdict("/Shipment/Parcels", "../Label", "AtLeastOneGroupFilled(/Shipment/Parcels)"));
        actual.put("scalar GroupFilled | error field outside", guarded("GroupFilled(Parcels)"));
        actual.put("scalar GroupFilled | error field inside",
                verdict("/Shipment", INSIDE, "GroupFilled(Parcels)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("count sole | error field outside", "MVK_NO_WILDCARD");
        expected.put("count sole | error field inside", "MVK_PARAMSIZE_INVALIDGN");
        expected.put("count sole | the repeatable group is the rule group", "MVK_PARAMSIZE_INVALIDGN");
        expected.put("count pair | error field outside", "MVK_NO_WILDCARD");
        expected.put("count pair | error field inside", "MVK_NEG_CONDITION_IN_ITERATION");
        expected.put("quantifier pair | error field outside", "MVK_NO_WILDCARD");
        expected.put("quantifier pair | error field inside", "admitted");
        expected.put("atLeastOne sole | error field outside", "MVK_NO_WILDCARD");
        expected.put("atLeastOne sole | error field inside", "admitted");
        expected.put("atLeastOne sole | the repeatable group is the rule group", "admitted");
        expected.put("scalar GroupFilled | error field outside", "MVK_NO_WILDCARD");
        expected.put("scalar GroupFilled | error field inside", "admitted");
        assertEquals(expected, actual, "the error field's locus selects the class");
    }

    // ── the neighbouring gates stay separable ───────────────────────────────────────────────────────

    @Test
    void anUnknownPathAndTheIterationGuardStaySeparableFromEveryOperandClass() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("all | unknown path", guarded("AllGroupsFilled(Nowhere, Destination)"));
        actual.put("count | unknown path", guarded("NumberOfFilledGroups(Nowhere, Destination) > 0"));
        actual.put("noGroup on the iterated group | unguarded", verdict("/Shipment", INSIDE, "NoGroupFilled(Parcels)"));
        actual.put("noGroup on the iterated group | guarded",
                verdict("/Shipment", INSIDE, "GroupFilled(Parcels) And NoGroupFilled(Parcels)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("all | unknown path", "MVK_INVALID_ENTITY");
        expected.put("count | unknown path", "MVK_INVALID_ENTITY");
        expected.put("noGroup on the iterated group | unguarded", "MVK_NEG_CONDITION_IN_ITERATION");
        expected.put("noGroup on the iterated group | guarded", "admitted");
        assertEquals(expected, actual, "the unknown-path and iteration-guard gates");
    }

    /** The model-level anchoring: the error field sits outside every repeatable group. */
    private static String guarded(String condition) {
        return verdict("/Shipment", "../TrackingCode", "FieldFilled(TrackingCode) And " + condition);
    }

    /** {@code admitted}, or the COMPLETE emitted code list — a partial list would hide a co-firing gate. */
    private static String verdict(String group, String errorField, String condition) {
        ValidationResult r = KernelLaws.checkModel(SHIPPING, group, errorField, condition);
        return r.ok() ? "admitted" : String.join(" + ", r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code)")).toList());
    }
}
