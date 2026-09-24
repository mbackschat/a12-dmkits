package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;

/**
 * KERNEL-FINDINGS [KF388] — the entity-list root gate on two carriers its caller table did not reach: the three
 * value-list quantifiers, and {@code FirstFilledValue} as a computation's operation.
 *
 * <p>Each carrier gets the same five lists, so each row differs from its neighbour in one operand: a root
 * alone, a root beside another root's field, a root beside its own field, and two controls that swap the
 * root for a group inside it. The own-field row is the order question, since that field lies under the root
 * and the duplicate gate can answer before the root gate does. The controls show that neither the carrier
 * nor the cross-root reference is refused on its own.
 *
 * <p>Written as text: the typed surface refuses a root operand while building it, so it cannot author the rows
 * whose refusal is the subject.
 */
class EntityListRootGateCarrierLawsTest {

    private static final String ROOT = "MVK_ROOT_GROUP_REFERENCED";
    private static final String DUPLICATE = "MVK_DUPLICATE_PARAM2";
    private static final List<String> VALUE_LIST_QUANTIFIERS = List.of("AtLeastOneFieldValueIncludedInValueList",
            "NoFieldValueIncludedInValueList", "NotAllFieldValuesIncludedInValueList");

    private static final String MODEL = DocumentModelDraft.named("courier-desk").locale("en_US")
            .rootGroup("Parcel", p -> p
                    .field("Reference", FieldKind.STRING)
                    .field("Label", FieldKind.STRING)
                    .group("Handling", h -> h.field("Note", FieldKind.STRING)))
            .rootGroup("Depot", d -> d
                    .field("Code", FieldKind.STRING)
                    .field("Assigned", FieldKind.STRING))
            .rootGroup("Ledger", l -> l.field("Booked", FieldKind.DATE))
            .build().toJson();

    /** Operand list → expected class, the same five for every carrier. */
    private static final Map<String, String> LISTS = rows();

    private static Map<String, String> rows() {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("/Parcel", ROOT);
        rows.put("/Parcel, /Depot/Code", ROOT);
        rows.put("/Parcel, /Parcel/Label", DUPLICATE);
        rows.put("/Parcel/Handling", KernelLaws.ADMITTED);
        rows.put("/Parcel/Handling, /Depot/Code", KernelLaws.ADMITTED);
        return rows;
    }

    @Test
    void theValueListQuantifiersReferToTheRootGate() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (String quantifier : VALUE_LIST_QUANTIFIERS) {
            LISTS.forEach((list, verdict) -> {
                String row = quantifier + "(" + list + " In \"x\")";
                actual.put(row, KernelLaws.verdictModel(MODEL, "/Parcel", "../Reference",
                        "FieldFilled(Reference) And " + row));
                expected.put(row, verdict);
            });
        }
        assertEquals(expected, actual, "the root gate on every value-list quantifier");
    }

    /**
     * Where the root sits in the value-list construct's own order. A root holding only a Date draws the root
     * class, while its Date alone draws the slot-kind class, so the root gate answers before the kind gate its
     * content would fail. In the field-read spelling a root on EITHER side draws the root class, and beside
     * its own field across the {@code In} it still does: the nested-duplicate test, which answers first
     * within one list, does not span the two.
     */
    @Test
    void theRootGateAnswersAheadOfTheKindGateAndOnEitherSideOfAFieldReadList() {
        Map<String, String> byList = new LinkedHashMap<>();
        byList.put("/Ledger In \"x\"", ROOT);
        byList.put("/Ledger/Booked In \"x\"", "MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED");
        byList.put("/Parcel In /Depot/Code", ROOT);
        byList.put("/Depot/Code In /Parcel", ROOT);
        byList.put("/Ledger In /Depot/Code", ROOT);
        byList.put("/Depot/Code In /Ledger", ROOT);
        byList.put("/Parcel In /Parcel/Label", ROOT);
        byList.put("/Parcel/Label In /Parcel", ROOT);
        byList.put("/Parcel/Handling In /Depot/Code", KernelLaws.ADMITTED);
        byList.put("/Depot/Code In /Parcel/Handling", KernelLaws.ADMITTED);
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (String quantifier : VALUE_LIST_QUANTIFIERS) {
            byList.forEach((list, verdict) -> {
                String row = quantifier + "(" + list + ")";
                actual.put(row, KernelLaws.verdictModel(MODEL, "/Parcel", "../Reference",
                        "FieldFilled(Reference) And " + row));
                expected.put(row, verdict);
            });
        }
        assertEquals(expected, actual, "the root gate's place in the value-list order");
    }

    @Test
    void aComputationsFirstFilledValueRefersToTheRootGate() {
        Map<String, String> actual = new LinkedHashMap<>();
        LISTS.keySet().forEach(list -> actual.put(list, KernelLaws.computationVerdict(MODEL, new DraftComputation(
                "/Depot", "AssignedProbe", "../Assigned", null,
                List.of(new DraftComputation.Alternative(null, "FirstFilledValue(" + list + ")"))))));
        assertEquals(LISTS, actual, "the root gate on FirstFilledValue into a String target");
    }
}
