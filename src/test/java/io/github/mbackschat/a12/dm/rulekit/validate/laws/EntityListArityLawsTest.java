package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * How many entities each entity-list operator's argument list needs, and the class each one answers with.
 *
 * <p>Six different arity gates live in {@code CheckEntityListenUtils}, and which an operator calls is fixed
 * per operator by a switch in its construct class. The gates are not variations on one predicate:
 * {@code checkIsMoreThanOneEntity} refuses only a LONE UNSTARRED FIELD — so a lone group passes — while
 * {@code isMoreThanOneParameter} refuses a lone entity of any kind unless it carries a wildcard. That
 * difference is what the group rows measure, and a family that read "at least two" everywhere would refuse
 * models A12 admits.
 *
 * <p>Two of the classes differ only in an underscore — {@code MVK_PARAMSIZE_INVALID2} is the group-list
 * conditions' "more than one parameter", {@code MVK_PARAMSIZE_INVALID_2} is {@code SumOfProducts}' "exactly
 * two" — so each is measured on its own carrier rather than inferred from the other.
 *
 * <p>Every refused row is written as raw condition text: the typed surface fixes each operator's arity in
 * its signature, which is why the ledger calls these codes erased. They reach a checker only as stored
 * DM-JSON.
 */
class EntityListArityLawsTest {

    private static final String GROUP = "/Order";

    private static final String ORDER_JSON = DocumentModelDraft.named("order").locale("en_US")
            .rootGroup("Order", g -> {
                g.stringField("Reference", f -> { });
                g.stringField("GiftNote", f -> { });
                g.numberField("Quantity", n -> n.fractionDigits(0));
                g.group("Packing", p -> {
                    p.stringField("Courier", f -> { });
                    p.field("Fragile", FieldKind.BOOLEAN);
                });
                g.group("Handover", h -> h.stringField("Recipient", f -> { }));
                g.group("Lines", l -> {
                    l.repeatable(50);
                    l.stringField("Sku", f -> { });
                    l.numberField("Amount", n -> n.fractionDigits(0));
                });
            }).build().toJson();

    private static String verdict(String subject) {
        return KernelLaws.verdictModel(ORDER_JSON, GROUP, "../Reference",
                "FieldFilled(Reference) And " + subject);
    }

    /** The operators that want EXACTLY one entity, and the two that want exactly two. */
    @Test
    void theOperatorsThatWantAFixedNumberOfEntities() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("FieldFilled over one field — the control", verdict("FieldFilled(GiftNote)"));
        actual.put("FieldFilled over two fields", verdict("FieldFilled(Reference, GiftNote)"));
        actual.put("GroupFilled over one group — the control", verdict("GroupFilled(Packing)"));
        actual.put("GroupFilled over two groups", verdict("GroupFilled(Packing, Handover)"));
        actual.put("SumOfProducts over two fields — the control",
                verdict("SumOfProducts(Lines*/Amount, Lines*/Amount) > 0"));
        actual.put("SumOfProducts over one field", verdict("SumOfProducts(Lines*/Amount) > 0"));
        actual.put("SumOfProducts over two GROUPS", verdict("SumOfProducts(Packing, Handover) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("FieldFilled over one field — the control", KernelLaws.ADMITTED);
        expected.put("FieldFilled over two fields", "MVK_PARAMSIZE_INVALID1");
        expected.put("GroupFilled over one group — the control", KernelLaws.ADMITTED);
        expected.put("GroupFilled over two groups", "MVK_PARAMSIZE_INVALID1");
        expected.put("SumOfProducts over two fields — the control", KernelLaws.ADMITTED);
        expected.put("SumOfProducts over one field", "MVK_PARAMSIZE_INVALID_2");
        // The kind gate runs BEFORE the arity one here, so a group list is answered on kind even though its
        // count is right — the row that stops a family from reading arity as the first question.
        expected.put("SumOfProducts over two GROUPS", "MVK_NO_GROUPS_ALLOWED");
        assertEquals(expected, actual, "the operators that want a fixed number of entities");
    }

    /** The gate that refuses a lone unstarred FIELD and admits a lone group, over its four classes. */
    @Test
    void theGateThatRefusesALoneUnstarredField() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("AllFieldsFilled over two fields — the control",
                verdict("AllFieldsFilled(Reference, GiftNote)"));
        actual.put("AllFieldsFilled over one field", verdict("AllFieldsFilled(GiftNote)"));
        actual.put("AllFieldsFilled over one GROUP", verdict("AllFieldsFilled(Packing)"));
        actual.put("AllFieldsFilled over one starred field", verdict("AllFieldsFilled(Lines*/Sku)"));
        actual.put("MoreThanOneFieldFilled over one field", verdict("MoreThanOneFieldFilled(GiftNote)"));
        actual.put("Sum over one field", verdict("Sum(Quantity) > 0"));
        actual.put("NumberOfFilledFields over one field", verdict("NumberOfFilledFields(GiftNote) > 0"));
        actual.put("NumberOfDifferentValues over one field",
                verdict("NumberOfDifferentValues(GiftNote) > 0"));
        actual.put("DateRangesOverlap over one field", verdict("DateRangesOverlap(GiftNote)"));
        actual.put("DateRangesOverlap over one GROUP", verdict("DateRangesOverlap(Packing)"));
        actual.put("Sum over one UNSTARRED repeatable field", verdict("Sum(Lines/Amount) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("AllFieldsFilled over two fields — the control", KernelLaws.ADMITTED);
        expected.put("AllFieldsFilled over one field", "MVK_PARAMSIZE_INVALIDN");
        expected.put("AllFieldsFilled over one GROUP", KernelLaws.ADMITTED);
        expected.put("AllFieldsFilled over one starred field", KernelLaws.ADMITTED);
        expected.put("MoreThanOneFieldFilled over one field", "MVK_PARAMSIZE_INVALIDN");
        expected.put("Sum over one field", "MVK_PARAMSIZE_INVALIDN");
        expected.put("NumberOfFilledFields over one field", "MVK_PARAMSIZE_INVALIDN");
        expected.put("NumberOfDifferentValues over one field", "MVK_PARAMSIZE_INVALIDN");
        expected.put("DateRangesOverlap over one field", "MVK_PARAMSIZE_INVALIDFN");
        expected.put("DateRangesOverlap over one GROUP", "MVK_NO_GROUPS_ALLOWED");
        // The reference reader refuses the unstarred repetition while the list is BUILT, so the operator's
        // own arity gate is never reached — the row that separates "one entity" from "one entity the reader
        // accepted".
        expected.put("Sum over one UNSTARRED repeatable field", "MVK_NO_WILDCARD");
        assertEquals(expected, actual, "the gate that refuses a lone unstarred field");
    }

    /** The other gate: a lone entity of ANY kind is refused unless it carries a wildcard. */
    @Test
    void theGateThatRefusesALoneEntityOfAnyKind() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("AllGroupsFilled over two groups — the control",
                verdict("AllGroupsFilled(Packing, Handover)"));
        actual.put("AllGroupsFilled over one group", verdict("AllGroupsFilled(Packing)"));
        actual.put("AllGroupsFilled over one starred group", verdict("AllGroupsFilled(Lines*)"));
        actual.put("NumberOfFilledGroups over one group", verdict("NumberOfFilledGroups(Packing) > 0"));
        actual.put("NumberOfFilledGroups over one starred group",
                verdict("NumberOfFilledGroups(Lines*) > 0"));
        actual.put("NumberOfFilledGroups over one FIELD", verdict("NumberOfFilledGroups(GiftNote) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("AllGroupsFilled over two groups — the control", KernelLaws.ADMITTED);
        expected.put("AllGroupsFilled over one group", "MVK_PARAMSIZE_INVALID2");
        // The star clears the ARITY gate — the wildcard is what makes a lone entity "more than one
        // parameter" — and is then refused by the wildcard gate this operator family runs right after it.
        // The `NumberOfFilledGroups` row below carries the exemption where nothing else answers.
        expected.put("AllGroupsFilled over one starred group", "MVK_NO_WILDCARDS_G_ALLOWED");
        expected.put("NumberOfFilledGroups over one group", "MVK_PARAMSIZE_INVALIDGN");
        expected.put("NumberOfFilledGroups over one starred group", KernelLaws.ADMITTED);
        expected.put("NumberOfFilledGroups over one FIELD", "MVK_NO_FIELDS_ALLOWED");
        assertEquals(expected, actual, "the gate that refuses a lone entity of any kind");
    }
}
