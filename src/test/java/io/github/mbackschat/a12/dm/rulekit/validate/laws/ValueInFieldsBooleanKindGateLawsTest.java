package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupListRef;

/**
 * KERNEL-FINDINGS {@code KF113} — {@code NumberOfValueInFields}' Boolean constants do NOT admit the same field
 * kinds, reconciling a12-kernel-lean {@code SPEC-2026-08-24-04}'s kind-gate delta.
 *
 * <p><b>Why this is an accept/reject law and not a differential.</b> The gate decides whether a model is
 * consistent at all, so the oracle is the kernel's own {@code checkConsistency} and there is no runtime question
 * behind a refusal. The counts each admitted shape produces are measured separately, over the same kinds and both
 * kind orders, by {@code adapter.laws.BooleanValueInFieldsShapeDiffTest}.
 *
 * <p><b>The asymmetry is the finding, and it needs both directions to be visible.</b> {@code True} admits a
 * Confirm declaration beside a Boolean one; {@code False} does not. Each row therefore carries its own control:
 * the same group under the other constant, and the Boolean-only group under {@code False}. Without the controls a
 * refusal could equally be about groupness, arity, or the group's contents in general.
 *
 * <p>rulekit authors the constant through {@link Dsl#numberOfValueInFields(boolean, GroupListRef)} and delegates
 * the gate; nothing here is re-implemented client-side, so this file records the kernel's boundary rather than
 * grounding a refusal of our own.
 */
class ValueInFieldsBooleanKindGateLawsTest {

    private static final String ORDER_JSON = DocumentModelDraft.named("order").locale("en_US")
            .rootGroup("Order", g -> {
                g.stringField("Reference", f -> { });
                g.group("Mixed", m -> m
                        .field("Expedited", FieldKind.BOOLEAN)
                        .field("TermsAccepted", FieldKind.CONFIRM));
                g.group("Booleans", b -> b
                        .field("GiftWrap", FieldKind.BOOLEAN)
                        .field("Insurance", FieldKind.BOOLEAN));
            }).build().toJson();

    private static final BoundModel ORDER = BoundModel.ofJson(ORDER_JSON);

    /** Guard the count so the probe rule references its own error field ({@code MVK_ERROR_FIELD_NOT_REFERENCED}). */
    private static String guarded(Condition c) {
        return KernelLaws.render(Dsl.fieldFilled(ORDER.field("/Order/Reference")).and(c));
    }

    private static String count(boolean constant, String group) {
        return guarded(Dsl.numberOfValueInFields(constant, ORDER.flattenGroup(group)).gt(0));
    }

    /** {@code True} reaches a Confirm declaration — the permissive half of the asymmetry. */
    @Test
    void trueAdmitsAGroupMixingBooleanAndConfirmDeclarations() {
        KernelLaws.acceptsModel(ORDER_JSON, "/Order", "../Reference", count(true, "/Order/Mixed"));
    }

    /** The same constant over a Boolean-only group: the control that attributes the acceptance above to the
     *  constant rather than to that particular group. */
    @Test
    void trueAdmitsABooleanOnlyGroupToo() {
        KernelLaws.acceptsModel(ORDER_JSON, "/Order", "../Reference", count(true, "/Order/Booleans"));
    }

    /**
     * {@code False} over the SAME group is refused, and the code says why in the kernel's own words — "It is only
     * allowed to use fields of type boolean" — which is the row that makes the gate constant-specific rather than
     * a property of the operand.
     */
    @Test
    void falseRefusesAGroupWhoseExpansionCarriesAConfirmDeclaration() {
        KernelLaws.rejectsModel(ORDER_JSON, "/Order", "../Reference", count(false, "/Order/Mixed"),
                "MVK_NO_TYPEYESNO");
    }

    /** …and {@code False} over a Boolean-only group is admitted, so the refusal above is attributable to the
     *  Confirm declaration and not to `False` rejecting group operands. */
    @Test
    void falseAdmitsABooleanOnlyGroup() {
        KernelLaws.acceptsModel(ORDER_JSON, "/Order", "../Reference", count(false, "/Order/Booleans"));
    }
}
