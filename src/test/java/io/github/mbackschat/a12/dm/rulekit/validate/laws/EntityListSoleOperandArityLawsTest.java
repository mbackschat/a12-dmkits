package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * KERNEL-FINDINGS §9 — <b>the entity-list family's arity rule is a DISJUNCTION, and the kind gate sits behind
 * it.</b> `MVK_PARAMSIZE_INVALIDN`'s own text reads "There must be more than one field or at least one group
 * defined", so one repetition-list or group selector satisfies it alone; a sole plain field does not.
 *
 * <p><b>Why the attribution matters more than either verdict.</b> A sole starred HETEROGENEOUS group is
 * refused, and an implementation reading that refusal as the arity gate mis-states both gates. It is
 * `MVK_VARYING_TYPES_NOT_ALLOWED` — the group operand reached the KIND gate, which means it had already passed
 * arity. The whole-verdict table is what shows this; a one-code assertion cannot tell the two apart.
 *
 * <p><b>The one-field group row separates an axis nobody had named.</b> "A group counts" and "a group whose
 * expansion yields several fields counts" predict the same verdict on every fixture whose groups all own the
 * same number of fields, so the pair is what tells them apart: `/Order/BillingAddress` owns exactly one String
 * field and `/Order/ShippingAddress` owns two, and both are admitted. Neither a12-kernel-lean's
 * `SPEC-2026-09-07-11` nor our own catalog line mentioned the field count, which is why it is a row here.
 *
 * <p>Reported to us as a prose correction by a12-kernel-lean's `SPEC-2026-09-07-11`; every row below is
 * re-derived here rather than carried on their report, because a peer's measurement is their report and not a
 * pin. Recorded as [KF278].
 */
class EntityListSoleOperandArityLawsTest {

    private static final String GUARD = "FieldFilled(/Order/Quantity) And ";

    /** Every operand list is compared to a string constant, so the comparison itself never moves. */
    private static String firstFilledValue(String operands) {
        return GUARD + "FirstFilledValue(" + operands + ") == \"x\"";
    }

    @Test
    void oneRepetitionListOrGroupSelectorSatisfiesTheArityRuleAloneWhileOnePlainFieldDoesNot() {
        Map<String, String> verdicts = new LinkedHashMap<>();
        verdicts.put("a sole plain field",
                KernelLaws.orderVerdict("/Order", "../Quantity", firstFilledValue("/Order/CustomerName")));
        verdicts.put("two plain fields — the arity control",
                KernelLaws.orderVerdict("/Order", "../Quantity",
                        firstFilledValue("/Order/CustomerName, /Order/ProductCode")));
        verdicts.put("a sole repetition-list selector",
                KernelLaws.orderVerdict("/Order", "../Quantity", firstFilledValue("/Order/Items*/Sku")));
        verdicts.put("a sole plain group, two same-kind fields",
                KernelLaws.orderVerdict("/Order", "../Quantity", firstFilledValue("/Order/ShippingAddress")));
        verdicts.put("a sole plain group owning exactly ONE field",
                KernelLaws.orderVerdict("/Order", "../Quantity", firstFilledValue("/Order/BillingAddress")));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a sole plain field", "MVK_PARAMSIZE_INVALIDN");
        expected.put("two plain fields — the arity control", KernelLaws.ADMITTED);
        expected.put("a sole repetition-list selector", KernelLaws.ADMITTED);
        expected.put("a sole plain group, two same-kind fields", KernelLaws.ADMITTED);
        expected.put("a sole plain group owning exactly ONE field", KernelLaws.ADMITTED);

        assertEquals(expected, verdicts,
                "the rule is a disjunction: more than one FIELD, or at least one GROUP — and a group's own "
                        + "field count is not part of it");
    }

    /**
     * The separator for the row above: a sole starred group is refused for its KIND, not its arity, so the
     * refusal cannot be read as "one group is too few". `/Order/Items` mixes String, Number and DateRange;
     * `/Order/ShippingAddress` starred would be the homogeneous twin but is non-repeatable, so the homogeneous
     * arm is carried by the unstarred row above.
     */
    @Test
    void aSoleHeterogeneousGroupReachesTheKindGateWhichProvesItPassedArity() {
        KernelLaws.rejectsOrderInsteadOf("/Order", "../Quantity", firstFilledValue("/Order/Items*"),
                "MVK_VARYING_TYPES_NOT_ALLOWED", "MVK_PARAMSIZE_INVALIDN");
    }

    /**
     * The ENTITY-VALUED value list does not share the entity-list family's arity rule: one field on each side
     * is admitted. Its own two gates still fire, so the admissions above are gates PASSED rather than gates
     * never reached — a repeated reference across the combined lists and a crossed base kind.
     */
    @Test
    void theEntityValuedValueListAdmitsASoleFieldOnEachSideWhileItsOwnTwoGatesStillFire() {
        Map<String, String> verdicts = new LinkedHashMap<>();
        verdicts.put("one field each side", KernelLaws.orderVerdict("/Order", "../Quantity",
                GUARD + "AtLeastOneFieldValueIncludedInValueList(/Order/CustomerName In /Order/ProductCode)"));
        verdicts.put("the same reference on both sides", KernelLaws.orderVerdict("/Order", "../Quantity",
                GUARD + "AtLeastOneFieldValueIncludedInValueList(/Order/CustomerName In /Order/CustomerName)"));
        verdicts.put("a crossed base kind", KernelLaws.orderVerdict("/Order", "../Quantity",
                GUARD + "AtLeastOneFieldValueIncludedInValueList(/Order/CustomerName In /Order/Quantity)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("one field each side", KernelLaws.ADMITTED);
        expected.put("the same reference on both sides", "MVK_DUPLICATE_PARAM1");
        expected.put("a crossed base kind", "MVK_VARYING_TYPES_NOT_ALLOWED");

        assertEquals(expected, verdicts,
                "a flat 'at least two operands' rule carried over from the entity-list family would refuse the "
                        + "legal single-slot form");
    }

    /**
     * The sweep the rows above make necessary: five of our catalog entries name this same gate, and two of them
     * stated it WITHOUT the group arm. Reuse across a carrier boundary is a claim, not an inheritance, so the
     * arm is measured on each of the two rather than widened by analogy from `FirstFilledValue`.
     *
     * <p>Both need an operand list the KIND gate admits, which the Order fixture cannot supply for `Sum` — its
     * only multi-field group mixes String, Number and DateRange — so this row authors its own two-field
     * all-numeric group.
     */
    @Test
    void theGroupArmHoldsOnTheTwoCarriersWhoseCatalogPhrasingOmittedIt() {
        String model = DocumentModelDraft.named("arity").locale("en_US")
                .rootGroup("Depot", root -> root
                        .numberField("Threshold", n -> n.fractionDigits(0))
                        .group("Bay", bay -> bay
                                .numberField("Stored", n -> n.fractionDigits(0))
                                .numberField("Reserved", n -> n.fractionDigits(0))))
                .build().toJson();
        String guard = "FieldFilled(/Depot/Threshold) And ";

        Map<String, String> verdicts = new LinkedHashMap<>();
        verdicts.put("Sum, a sole group", KernelLaws.verdictModel(model, "/Depot", "../Threshold",
                guard + "Sum(/Depot/Bay) > 0"));
        verdicts.put("Sum, a sole field — control", KernelLaws.verdictModel(model, "/Depot", "../Threshold",
                guard + "Sum(/Depot/Bay/Stored) > 0"));
        verdicts.put("FieldValuesNotUnique, a sole group",
                KernelLaws.verdictModel(model, "/Depot", "../Threshold",
                        guard + "FieldValuesNotUnique(/Depot/Bay)"));
        verdicts.put("FieldValuesNotUnique, a sole field — control",
                KernelLaws.verdictModel(model, "/Depot", "../Threshold",
                        guard + "FieldValuesNotUnique(/Depot/Bay/Stored)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Sum, a sole group", KernelLaws.ADMITTED);
        expected.put("Sum, a sole field — control", "MVK_PARAMSIZE_INVALIDN");
        expected.put("FieldValuesNotUnique, a sole group", KernelLaws.ADMITTED);
        expected.put("FieldValuesNotUnique, a sole field — control", "MVK_PARAMSIZE_INVALIDN");

        assertEquals(expected, verdicts, "the disjunction is the gate's, not one operator's");
    }
}
