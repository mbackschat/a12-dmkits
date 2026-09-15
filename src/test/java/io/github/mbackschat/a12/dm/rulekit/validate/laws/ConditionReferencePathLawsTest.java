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

    /**
     * Exclusivity, not mere presence: `rejectsModelInsteadOf` asserts the expected code fires AND the rival
     * one does not, which an `anyMatch` lock cannot separate when both co-fire.
     */
    private static void rejects(String reference, String code, String notThisOne) {
        KernelLaws.rejectsModelInsteadOf(ORDERS, "/Order/Lines", "../Amount",
                "FieldFilled(Amount) And FieldFilled(" + reference + ")", code, notThisOne);
    }

    /**
     * The discriminating pair. {@code ../Code} in the condition reaches the root group's field, so the climb
     * starts at {@code /Order/Lines}; the error target's own {@code ../Amount} reaches a sibling of the
     * rule, so that climb starts at {@code /Order/Lines/ProbeRule}. Both rows are in every call above.
     */
    @Test
    void aConditionPathClimbsFromTheRuleGroup() {
        admits("../Code");
        rejects("../Lines/Code", "MVK_INVALID_ENTITY", "MVK_TOO_MANY_PARENT_GROUPS");
    }

    /**
     * The floor is the ROOT GROUP, not the model root, and A12 gives it its own code rather than reporting
     * a missing element — so a checker that reported {@code MVK_INVALID_ENTITY} here would name the wrong
     * repair (declare the field, rather than remove a step).
     */
    @Test
    void climbingOutOfTheRootGroupIsItsOwnRejection() {
        rejects("../../Code", "MVK_TOO_MANY_PARENT_GROUPS", "MVK_INVALID_ENTITY");
    }

    /**
     * The other side of that boundary, in the same model: from a rule in the ROOT group a single step is
     * already too many. Without this row, "the floor is the root group" and "two steps are too many" fit the
     * row above equally.
     */
    @Test
    void oneStepIsAlreadyTooManyFromARootGroupRule() {
        KernelLaws.rejectsModelInsteadOf(ORDERS, "/Order", "../Code",
                "FieldFilled(Code) And FieldFilled(../Code)",
                "MVK_TOO_MANY_PARENT_GROUPS", "MVK_INVALID_ENTITY");
    }

    @Test
    void anAbsolutePathIsResolvedFromTheModelRoot() {
        admits("/Order/Code");
        admits("../Lines/Sku");
        KernelLaws.acceptsModel(ORDERS, "/Order", "../Code",
                "FieldFilled(Code) And FieldFilled(Lines/Sku)");
    }

    /**
     * Whitespace rides the lexer's HIDDEN channel and is insignificant between ANY two tokens
     * ([KF76](../../../../../../../../../../../docs/KERNEL-FINDINGS.md)), so every spaced spelling must behave
     * exactly like its tight twin. Each row here is paired with the tight control in the tests above.
     */
    @Test
    void spacingInsideAPathIsInsignificant() {
        admits(".. / Code");
        admits("/ Order / Code");
        admits(".. / Lines / Sku");
        rejects(".. / Nope", "MVK_INVALID_ENTITY", "MVK_TOO_MANY_PARENT_GROUPS");
    }

    /**
     * `..` may carry a TURNING-POINT LABEL naming the ancestor it lands on (`relativeUp: relUp? '..'
     * idName?`); the label is not a path segment. The second row is the control that shows A12 checks the
     * label rather than ignoring it — a label naming the rule's own group, not the ancestor, is refused.
     */
    @Test
    void aTurningPointLabelNamesTheAncestorAndIsNotASegment() {
        admits("..Order/Code");
        rejects("..Lines/Code", "MVK_INVALID_ENTITY", "MVK_TOO_MANY_PARENT_GROUPS");
    }

    /** A digit-LEADING name is one identifier, so it is looked up whole rather than as a number and a name. */
    @Test
    void aDigitLeadingNameIsOneEntityName() {
        rejects("1Amount", "MVK_INVALID_ENTITY", "MVK_UNEXPECTED_TOKEN");
    }

    /**
     * A candidate at ROOT-GROUP level draws its own code, so a checker reporting `MVK_INVALID_ENTITY` for it
     * would name the wrong repair. The second row is the control one level deeper.
     */
    @Test
    void aRootGroupLevelCandidateHasItsOwnRejection() {
        rejects("/Nope", "MVK_INVALID_ROOT_GROUP", "MVK_INVALID_ENTITY");
        rejects("/Order/Nope", "MVK_INVALID_ENTITY", "MVK_INVALID_ROOT_GROUP");
    }

    /** The control: an ordinary unresolvable name draws the entity code, so the rows above are specific. */
    @Test
    void aNameNothingDeclaresDrawsTheEntityCode() {
        rejects("Nope", "MVK_INVALID_ENTITY", "MVK_TOO_MANY_PARENT_GROUPS");
    }

    /**
     * The same climb on the GROUP carrier — run rather than inherited, because every row above resolves a
     * FIELD and a rule that holds for one operand kind is a claim about the other until it is measured
     * there (CLAUDE.md, the scope-transfer row).
     *
     * <p>The bare own-group name is the row worth having. A rule declared IN {@code /Order/Lines} writing
     * {@code GroupFilled(Lines)} reads as naming its own group and does not: the climb starts at the
     * declaring group, so the candidate is {@code /Order/Lines/Lines}. The spelling that does name it is
     * {@code ../Lines}, one level up and back down, which is the pair a resolver has to separate.
     */
    @Test
    void theClimbIsTheSameWhenTheOperandIsAGroup() {
        admitsGroup("../Lines");
        admitsGroup("/Order/Lines");
        rejectsGroup("Lines", "MVK_INVALID_ENTITY", "MVK_TOO_MANY_PARENT_GROUPS");
        rejectsGroup("/Nope", "MVK_INVALID_ROOT_GROUP", "MVK_INVALID_ENTITY");
    }

    private static void admitsGroup(String reference) {
        KernelLaws.acceptsModel(ORDERS, "/Order/Lines", "../Amount",
                "FieldFilled(Amount) And GroupFilled(" + reference + ")");
    }

    private static void rejectsGroup(String reference, String code, String notThisOne) {
        KernelLaws.rejectsModelInsteadOf(ORDERS, "/Order/Lines", "../Amount",
                "FieldFilled(Amount) And GroupFilled(" + reference + ")", code, notThisOne);
    }
}
