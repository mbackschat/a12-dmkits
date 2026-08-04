package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS §4 — {@code FieldValuesNotUnique} <b>operand admission</b> (SPEC-2026-08-04-02). Two gates the
 * canonical account did not carry, both measured here against the real kernel:
 *
 * <ol>
 * <li><b>Arity.</b> The list needs more than one operand — a single <em>direct</em> field is rejected
 *     {@code MVK_PARAMSIZE_INVALIDN} — but a single <em>starred</em> slot is admitted, because it expands to a
 *     row stream and so already is many fields. The shared param law of the count aggregates
 *     (KERNEL-FINDINGS "Param law is shared with the count aggregates").</li>
 * <li><b>One declared kind.</b> Every operand must share one declared kind; mixing draws
 *     {@code MVK_VARYING_TYPES_NOT_ALLOWED}. Differing Number <em>scales</em> stay admitted, which is what makes
 *     the canonical cross-scale equality claim (a scale-0 {@code 5} equals a scale-2 {@code 5.00}) reachable at
 *     all.</li>
 * </ol>
 *
 * <p><b>The discriminating case is the last one, and it is why this is not a shared field-list contract.</b> This
 * operator's kind gate is STRICTER than token comparability: String beside stored Enumeration is refused here
 * while {@code NumberOfDifferentValues} admits exactly that mix over the same two fields. Asserting the rejection
 * alone would be satisfied by a kernel that refused the mix everywhere; the pair is what shows the two operators
 * do not share one comparability rule.
 */
class FieldValuesNotUniqueAdmissionLawsTest {

    /** The group every probe rule lives in, and the fields it may hang its error on. */
    private static final String GROUP = "/StockCount";

    /**
     * Assert acceptance of {@code list} as the operand list, with the error hung on {@code errorField}.
     *
     * <p>The condition carries a {@code FieldFilled(errorField) And} guard because the kernel requires a rule's
     * error field to be referenced by its own condition ({@code MVK_ERROR_FIELD_NOT_REFERENCED}) — the same shape
     * the catalog's published example uses. Without it every case here fails on that unrelated gate, which is how
     * this fixture was first written and what the run corrected.
     */
    private static void admits(String errorField, String list) {
        KernelLaws.acceptsModel(stockCount(), GROUP, "../" + errorField,
                "FieldFilled(" + errorField + ") And " + list);
    }

    /** As {@link #admits}, for a list the kernel must reject with exactly {@code code}. */
    private static void refuses(String errorField, String list, String code) {
        KernelLaws.rejectsModel(stockCount(), GROUP, "../" + errorField,
                "FieldFilled(" + errorField + ") And " + list, code);
    }

    /**
     * A warehouse stock-count model: two Number fields at DIFFERENT declared scales (the cross-scale pair), two
     * String fields, one Enumeration, and a repeatable {@code Counts} group for the starred forms.
     */
    private static String stockCount() {
        return DocumentModelDraft.named("stock-count").locale("en_US")
                .rootGroup("StockCount", root -> root
                        .numberField("UnitsOnHand", n -> n.fractionDigits(0))
                        .numberField("UnitsReserved", n -> n.fractionDigits(2))
                        .numberField("UnitsDamaged", n -> n.fractionDigits(2))
                        .stringField("BinCode", s -> { })
                        .stringField("AisleCode", s -> { })
                        .enumField("Condition", e -> e.value("SEALED").value("OPENED").value("DAMAGED"))
                        .group("Counts", counts -> counts.repeatable(50)
                                .numberField("Counted", n -> n.fractionDigits(0))
                                .stringField("CounterInitials", s -> { })))
                .build().toJson();
    }

    // ── arity: more than one operand, unless one starred slot already supplies many ───────────────────

    @Test
    void aSingleDirectFieldIsRejected() {
        refuses("BinCode", "FieldValuesNotUnique(UnitsOnHand)", "MVK_PARAMSIZE_INVALIDN");
    }

    @Test
    void aSingleStarredSlotIsAdmitted() {
        admits("BinCode", "FieldValuesNotUnique(Counts*/Counted)");
    }

    @Test
    void aMixedDirectAndStarredListOfOneKindIsAdmitted() {
        admits("BinCode", "FieldValuesNotUnique(UnitsOnHand, Counts*/Counted)");
    }

    // ── one declared kind — but any scale within Number ───────────────────────────────────────────────

    @Test
    void twoNumberFieldsAtDifferentScalesAreAdmitted() {
        // The admission the canonical cross-scale equality claim rests on: without it, the scale-0 5 versus
        // scale-2 5.00 example would be unauthorable.
        admits("BinCode", "FieldValuesNotUnique(UnitsOnHand, UnitsReserved)");
    }

    @Test
    void threeNumberFieldsAreAdmitted() {
        admits("BinCode", "FieldValuesNotUnique(UnitsOnHand, UnitsReserved, UnitsDamaged)");
    }

    @Test
    void twoStringFieldsAreAdmitted() {
        admits("UnitsOnHand", "FieldValuesNotUnique(BinCode, AisleCode)");
    }

    @Test
    void aNumberBesideAStringIsRejected() {
        refuses("UnitsDamaged", "FieldValuesNotUnique(UnitsOnHand, BinCode)",
                "MVK_VARYING_TYPES_NOT_ALLOWED");
    }

    @Test
    void aStringBesideAnEnumerationIsRejected() {
        refuses("UnitsOnHand", "FieldValuesNotUnique(BinCode, Condition)",
                "MVK_VARYING_TYPES_NOT_ALLOWED");
    }

    @Test
    void anEnumerationBesideAStringIsRejected() {
        // Both orders, because a gate that inspected only the first operand's kind would pass one of them.
        refuses("UnitsOnHand", "FieldValuesNotUnique(Condition, BinCode)",
                "MVK_VARYING_TYPES_NOT_ALLOWED");
    }

    // ── the discriminator: NumberOfDifferentValues admits the very mix this operator refuses ──────────

    @Test
    void numberOfDifferentValuesAdmitsTheStringEnumerationMixThisOperatorRefuses() {
        admits("UnitsOnHand", "NumberOfDifferentValues(BinCode, Condition) < 2");
    }
}
