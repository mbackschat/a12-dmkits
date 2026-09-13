package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * WHEN a ROOT group may be an operand of a group-list quantifier, and which class refuses it when it may not.
 *
 * <p>{@link GroupListDiagnosticIdentityLawsTest} established that the root classes are real and not the
 * overlap class renamed — the measurement a single-root fixture cannot make — on four carriers and one
 * anchoring. What an implementation needs beyond that is the shape of the permission: which carriers admit a
 * sole root, whether the root's POSITION in the list matters, whether a second root behaves like any other
 * co-operand, and whether the class survives the error field moving inside a repeatable group, as the
 * wildcard and arity classes beside it do not.
 *
 * <p>Own domain (an e-commerce storefront), and a different shape from the fixture that first measured these
 * classes: three roots rather than two, one of them repeatable, so "is a root" and "is repeatable" are
 * separable here in a way they were not there.
 */
class GroupListRootOperandLawsTest {

    private static final String REFERENCED = "MVK_ROOT_GROUP_REFERENCED";
    private static final String WITH_OTHERS = "MVK_ROOT_GROUP_WITH_OTHER_PARAMETERS";

    private static final String STOREFRONT = DocumentModelDraft.named("storefront").locale("en_US")
            .rootGroup("Storefront", s -> s
                    .field("OrderRef", FieldKind.STRING)
                    .group("Billing", b -> b
                            .field("Payer", FieldKind.STRING)
                            .group("Card", c -> c.field("Last4", FieldKind.STRING)))
                    .group("Delivery", d -> d.field("City", FieldKind.STRING))
                    .group("Lines", l -> l.repeatable(40)
                            .field("Sku", FieldKind.STRING)
                            .field("Qty", FieldKind.NUMBER)))
            .rootGroup("Warehouse", w -> w.field("Bay", FieldKind.STRING))
            // A REPEATABLE root, so the starred-root permission can be asked without changing anything else.
            .rootGroup("Batches", b -> b.repeatable(6).field("BatchCode", FieldKind.STRING))
            .build().toJson();

    /** A field inside the repeatable group — the anchoring that re-selects the classes beside these. */
    private static final String INSIDE = "../Lines/Sku";

    @Test
    void whichCarriersAdmitASoleRootAndWhichRefuseIt() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("all | sole root", guarded("AllGroupsFilled(/Warehouse)"));
        actual.put("notAll | sole root", guarded("NotAllGroupsFilled(/Warehouse)"));
        actual.put("notCollectively | sole root", guarded("GroupsNotCollectivelyFilled(/Warehouse)"));
        actual.put("atLeastOne | sole root", guarded("AtLeastOneGroupFilled(/Warehouse)"));
        actual.put("noGroup | sole root", guarded("NoGroupFilled(/Warehouse)"));
        actual.put("count | sole root", guarded("NumberOfFilledGroups(/Warehouse) > 0"));
        actual.put("count | sole root, STARRED and repeatable", guarded("NumberOfFilledGroups(/Batches*) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("all | sole root", REFERENCED);
        expected.put("notAll | sole root", REFERENCED);
        expected.put("notCollectively | sole root", REFERENCED);
        expected.put("atLeastOne | sole root", "admitted");
        expected.put("noGroup | sole root", "admitted");
        // NOT admitted, and not the root's doing: the count carrier's own arity gate wants two fixed groups
        // or one STARRED repeatable, so a sole FIXED operand draws this whether or not it is a root. The
        // starred row below is the sole-root permission's only reachable form on this carrier.
        expected.put("count | sole root", "MVK_PARAMSIZE_INVALIDGN");
        expected.put("count | sole root, STARRED and repeatable", "admitted");
        assertEquals(expected, actual, "which carriers admit a sole root");
    }

    @Test
    void whichClassARootBesideAnotherOperandDraws() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("all | root + disjoint", guarded("AllGroupsFilled(/Warehouse, Billing)"));
        actual.put("notAll | root + disjoint", guarded("NotAllGroupsFilled(/Warehouse, Billing)"));
        actual.put("notCollectively | root + disjoint",
                guarded("GroupsNotCollectivelyFilled(/Warehouse, Billing)"));
        actual.put("atLeastOne | root + disjoint", guarded("AtLeastOneGroupFilled(/Warehouse, Billing)"));
        actual.put("noGroup | root + disjoint", guarded("NoGroupFilled(/Warehouse, Billing)"));
        actual.put("count | root + disjoint", guarded("NumberOfFilledGroups(/Warehouse, Billing) > 0"));
        // Position and multiplicity: neither should matter, and an implementation that scans only the first
        // operand or stops at one root would pass every row above and fail these.
        actual.put("all | root named SECOND", guarded("AllGroupsFilled(Billing, /Warehouse)"));
        actual.put("all | TWO disjoint roots", guarded("AllGroupsFilled(/Warehouse, /Batches*)"));
        actual.put("atLeastOne | root named SECOND", guarded("AtLeastOneGroupFilled(Billing, /Warehouse)"));
        // The overlap gate pre-empts the root classes — re-measured on this fixture rather than carried over.
        actual.put("all | root + its OWN descendant", guarded("AllGroupsFilled(/Storefront, Billing)"));
        // Is a STARRED root exempt beside another operand, as it is from the duplicate gate? The sole-root
        // permission is starred-only on the count carrier, which makes the question live rather than idle.
        actual.put("all | STARRED repeatable root + disjoint", guarded("AllGroupsFilled(/Batches*, Billing)"));
        actual.put("count | STARRED repeatable root + disjoint",
                guarded("NumberOfFilledGroups(/Batches*, Billing) > 0"));
        actual.put("all | the rule's OWN root beside a disjoint root",
                guarded("AllGroupsFilled(/Storefront, /Warehouse)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("all | root + disjoint", REFERENCED);
        expected.put("notAll | root + disjoint", REFERENCED);
        expected.put("notCollectively | root + disjoint", REFERENCED);
        expected.put("atLeastOne | root + disjoint", WITH_OTHERS);
        expected.put("noGroup | root + disjoint", WITH_OTHERS);
        expected.put("count | root + disjoint", WITH_OTHERS);
        expected.put("all | root named SECOND", REFERENCED);
        expected.put("all | TWO disjoint roots", REFERENCED);
        expected.put("atLeastOne | root named SECOND", WITH_OTHERS);
        expected.put("all | root + its OWN descendant", "MVK_DUPLICATE_PARAM2");
        expected.put("all | STARRED repeatable root + disjoint", REFERENCED);
        expected.put("count | STARRED repeatable root + disjoint", WITH_OTHERS);
        expected.put("all | the rule's OWN root beside a disjoint root", REFERENCED);
        assertEquals(expected, actual, "which class a root beside another operand draws");
    }

    /**
     * What PRE-EMPTS the root classes, so a checker reporting them does not name a class A12 never issued.
     *
     * <p>The overlap gate is known to pre-empt them. The other two gates a group list can trip on the same
     * operand list — an unresolvable name and the wildcard demand — are measured here, because the portable
     * checker reports neither and would therefore be silent about the model if either won.
     */
    @Test
    void whatPreEmptsTheRootClasses() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("all | root + an UNRESOLVABLE operand", guarded("AllGroupsFilled(/Warehouse, Nowhere)"));
        actual.put("all | root + an unstarred REPEATABLE co-operand",
                guarded("AllGroupsFilled(/Warehouse, Lines)"));
        actual.put("count | root + an unstarred REPEATABLE co-operand",
                guarded("NumberOfFilledGroups(/Warehouse, Lines) > 0"));
        actual.put("all | root + a FIELD where a group belongs",
                guarded("AllGroupsFilled(/Warehouse, Billing/Payer)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("all | root + an UNRESOLVABLE operand", "MVK_INVALID_ENTITY");
        expected.put("all | root + an unstarred REPEATABLE co-operand", "MVK_NO_WILDCARD");
        expected.put("count | root + an unstarred REPEATABLE co-operand", "MVK_NO_WILDCARD");
        expected.put("all | root + a FIELD where a group belongs", REFERENCED);
        assertEquals(expected, actual, "what pre-empts the root classes");
    }

    /**
     * The error field's LOCUS, which re-selects the wildcard and arity classes on this very carrier
     * ([KF185]) — so whether it also re-selects the root classes cannot be assumed from their being
     * measured once outside.
     */
    @Test
    void theErrorFieldsLocusAndTheRootClasses() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("all | root + disjoint, error field INSIDE the repeatable group",
                inside("AllGroupsFilled(/Warehouse, Billing)"));
        actual.put("count | root + disjoint, error field INSIDE",
                inside("NumberOfFilledGroups(/Warehouse, Billing) > 0"));
        actual.put("all | sole root, error field INSIDE", inside("AllGroupsFilled(/Warehouse)"));
        actual.put("atLeastOne | sole root, error field INSIDE", inside("AtLeastOneGroupFilled(/Warehouse)"));
        // The pair that decides whether an implementation needs the locus at all: the same operand list is
        // answered by the wildcard gate from OUTSIDE and — if this row says so — by the root class from
        // INSIDE, which makes the root violation true in both anchorings and only its REPORTING order
        // locus-dependent.
        actual.put("all | root + an unstarred REPEATABLE co-operand, error field INSIDE it",
                inside("AllGroupsFilled(/Warehouse, Lines)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("all | root + disjoint, error field INSIDE the repeatable group", REFERENCED);
        expected.put("count | root + disjoint, error field INSIDE", WITH_OTHERS);
        expected.put("all | sole root, error field INSIDE", REFERENCED);
        expected.put("atLeastOne | sole root, error field INSIDE", "admitted");
        expected.put("all | root + an unstarred REPEATABLE co-operand, error field INSIDE it", REFERENCED);
        assertEquals(expected, actual, "the error field's locus and the root classes");
    }

    /** The model-level anchoring: the error field sits outside every repeatable group. */
    private static String guarded(String condition) {
        return verdict("/Storefront", "../OrderRef", "FieldFilled(OrderRef) And " + condition);
    }

    /** …and the same condition with the error field INSIDE the repeatable group. */
    private static String inside(String condition) {
        return verdict("/Storefront", INSIDE, "FieldFilled(Lines/Sku) And " + condition);
    }

    /** {@code admitted}, or the COMPLETE emitted code list — a partial list would hide a co-firing gate. */
    private static String verdict(String group, String errorField, String condition) {
        ValidationResult r = KernelLaws.checkModel(STOREFRONT, group, errorField, condition);
        return r.ok() ? "admitted" : String.join(" + ", r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code)")).toList());
    }
}
