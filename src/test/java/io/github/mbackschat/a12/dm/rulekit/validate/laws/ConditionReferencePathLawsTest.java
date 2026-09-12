package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

import org.junit.jupiter.api.Test;

/**
 * Where a relative path in a CONDITION is resolved from, and what A12 reports when it does not resolve —
 * the basis for the portable checker's {@code resolveEntity} (PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN, B0).
 *
 * <p><b>The asymmetry is the point.</b> A condition's path climbs from the rule's GROUP while the rule's
 * {@code errorEntityRelPath} climbs from the rule ELEMENT, so the same field is {@code ../Code} in one and
 * {@code ../../Code} in the other. Both spellings look right, they differ by one level, and a resolver
 * written once for both gets exactly one of them wrong on every model. One model carries every row here, so
 * the two readings are separated within a single fixture rather than across two.
 */
class ConditionReferencePathLawsTest {

    /** A field one level above the rule's group, so a one-step climb has somewhere to land. */
    private static final String ORDERS = DocumentModelDraft.named("orders").locale("en_US")
            .rootGroup("Order", o -> o
                    .field("Code", FieldKind.STRING)
                    .group("Lines", g -> g
                            .field("Amount", FieldKind.NUMBER)
                            .field("Sku", FieldKind.STRING)))
            .build().toJson();

    /** The rule group and error target are fixed across every row; only the added reference moves. */
    private static void admits(String reference) {
        KernelLaws.acceptsModel(ORDERS, "/Order/Lines", "../Amount",
                "FieldFilled(Amount) And FieldFilled(" + reference + ")");
    }

    private static void rejects(String reference, String code) {
        KernelLaws.rejectsModel(ORDERS, "/Order/Lines", "../Amount",
                "FieldFilled(Amount) And FieldFilled(" + reference + ")", code);
    }

    /**
     * The discriminating pair. {@code ../Code} in the condition reaches the root group's field, so the climb
     * starts at {@code /Order/Lines}; the error target's own {@code ../Amount} reaches a sibling of the
     * rule, so that climb starts at {@code /Order/Lines/ProbeRule}. Both rows are in every call above.
     */
    @Test
    void aConditionPathClimbsFromTheRuleGroup() {
        admits("../Code");
        rejects("../Lines/Code", "MVK_INVALID_ENTITY");
    }

    /**
     * The floor is the ROOT GROUP, not the model root, and A12 gives it its own code rather than reporting
     * a missing element — so a checker that reported {@code MVK_INVALID_ENTITY} here would name the wrong
     * repair (declare the field, rather than remove a step).
     */
    @Test
    void climbingOutOfTheRootGroupIsItsOwnRejection() {
        rejects("../../Code", "MVK_TOO_MANY_PARENT_GROUPS");
    }

    /**
     * The other side of that boundary, in the same model: from a rule in the ROOT group a single step is
     * already too many. Without this row, "the floor is the root group" and "two steps are too many" fit the
     * row above equally.
     */
    @Test
    void oneStepIsAlreadyTooManyFromARootGroupRule() {
        KernelLaws.rejectsModel(ORDERS, "/Order", "../Code",
                "FieldFilled(Code) And FieldFilled(../Code)", "MVK_TOO_MANY_PARENT_GROUPS");
    }

    @Test
    void anAbsolutePathIsResolvedFromTheModelRoot() {
        admits("/Order/Code");
        admits("../Lines/Sku");
        KernelLaws.acceptsModel(ORDERS, "/Order", "../Code",
                "FieldFilled(Code) And FieldFilled(Lines/Sku)");
    }

    /** The control: an ordinary unresolvable name draws the entity code, so the rows above are specific. */
    @Test
    void aNameNothingDeclaresDrawsTheEntityCode() {
        rejects("Nope", "MVK_INVALID_ENTITY");
    }
}
