package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.List;
import java.util.Optional;

import io.github.mbackschat.a12.dm.core.KernelCode;
import io.github.mbackschat.a12.dm.core.Refusals;
import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@code SumOfProducts(a, b)} — the kernel's pairwise sum-of-products over two parallel number field
 * lists: Σᵢ aᵢ·bᵢ (typically two wildcard repetition lists — the flatten of {@code Items*} then
 * {@code Qty} paired with {@code Items* / Price}). A {@link Calculation} of {@link ValueKind#NUMBER};
 * both operands render BARE (entity refs).
 *
 * <p>The constructor pre-checks the part of the kernel's boundary it can see from the operands alone: two
 * unfiltered single-star lists whose FIELDS share one owning group. It deliberately does <b>not</b> check that
 * the star sits on the <em>lowest repeatable</em> segment — that needs the model's repeatability, which an
 * operand does not carry — so {@code Milestones*}{@code /Tasks/Effort} passes here and the kernel rejects it,
 * with a class the rule decides (KERNEL-FINDINGS KF370). Under-reporting is safe; quoting a wrong code is not, so
 * the refusal message names the code the kernel emits for that exact PAIR, per the matrix in
 * {@code SumOfProductsLawsTest}, and names none where that code depends on the rule the pair will sit in.
 */
public record SumOfProducts(FieldOperand left, FieldOperand right) implements Calculation {

    public SumOfProducts {
        if (!(left instanceof FieldListRef leftList) || !(right instanceof FieldListRef rightList)
                || !owningGroup(left).equals(owningGroup(right))) {
            String problem = "SumOfProducts needs two repetition lists whose fields share one owning group; "
                    + left.renderedPath() + " and " + right.renderedPath() + " are not parallel";
            throw mismatchCode(left, right).<IllegalArgumentException>map(code -> Refusals.of(code, problem))
                    .orElseGet(() -> new IllegalArgumentException(problem + RULE_DEPENDENT));
        }
        requireLowestLevelStar(leftList);
        requireLowestLevelStar(rightList);
        requireNumber(left);
        requireNumber(right);
    }

    /**
     * Asked LAST, because the kernel asks it last: on a pair that also breaks a structural gate the kernel
     * reports that gate and never the number one ({@code validate.laws.SumOfProductsLawsTest}), so asking it
     * first quoted the wrong defect.
     *
     * <p>Deliberately uncoded, although a well-formed pair with a text list is {@code MVK_NO_NUMBER}. One of
     * the gates ahead of it — a repeatable ancestor left unstarred — needs the model's repeatability, which an
     * operand does not carry, so this refusal cannot know it is the kernel's answer. Citing it would quote
     * {@code MVK_NO_NUMBER} for a pair the kernel reports as a missing or misplaced wildcard.
     */
    private static void requireNumber(FieldOperand operand) {
        if (operand.kind() != ValueKind.NUMBER) {
            throw new IllegalArgumentException("SumOfProducts takes number fields; "
                    + operand.renderedPath() + " is " + operand.kind());
        }
    }

    /**
     * The kernel's code for a non-parallel pair, reproduced from the measured matrix, or empty where the code
     * depends on the rule. A pair with no repeatable level anywhere <b>and one shared owning group</b> is
     * {@code MVK_REPEATABLE_LEVEL_REQUIRED}; any other pair is {@code MVK_DIFFERENT_GROUPS}. Applying one code
     * per operand instead got two of these wrong.
     *
     * <p><b>An operand that may cross an unstarred repeatable level has no code here</b> (corrected
     * 2026-10-20). The reference reader answers {@code MVK_NO_WILDCARD} only where the rule does not bind that
     * level; where the rule binds it, by iterating it or by being declared inside it, the operator's own gates
     * answer, {@code MVK_WILDCARD_AT_LOWEST_LEVEL_REQUIRED} or {@code MVK_DIFFERENT_GROUPS}
     * (KERNEL-FINDINGS KF370, {@code adapter.laws.CheckerSumOfProductsBoundOperandAgreementTest}). An operand
     * does not know the rule it will sit in, so the pair is refused, which every one of those classes does, and
     * the code is withheld. Unknown topology counts as crossing, as in {@link FieldRef}.
     *
     * <p><b>An identical starless pair is a duplicate</b> (corrected 2026-10-21): A12's duplicate gate runs ahead
     * of the pair gates and skips only a starred operand, so {@code SumOfProducts(Quantity, Quantity)} is
     * {@code MVK_DUPLICATE_PARAM1} ({@code validate.laws.DuplicateOverlapCarrierSweepLawsTest}); this quoted the
     * repeatable-level code.
     *
     * <p><b>The owning-group conjunct is not decoration</b> (corrected 2026-09-14): without it a pair of plain
     * fields in DIFFERENT groups quoted the repeatable-level code, where the kernel draws the owning-group one.
     * The row that had covered this arm gave both its operands the same owning group, so the two conditions
     * were one thing on the only case ever run — on this side and in
     * {@code validate.laws.SumOfProductsLawsTest} alike, which is why measuring the kernel pair is what found it.
     */
    private static Optional<KernelCode> mismatchCode(FieldOperand left, FieldOperand right) {
        if (mayCrossUnstarredRepetition(left) || mayCrossUnstarredRepetition(right)) {
            return Optional.empty();
        }
        if (left instanceof FieldRef && left.equals(right)) {
            return Optional.of(KernelCode.MVK_DUPLICATE_PARAM1);
        }
        if (left instanceof FieldRef && right instanceof FieldRef
                && owningGroup(left).equals(owningGroup(right))) {
            return Optional.of(KernelCode.MVK_REPEATABLE_LEVEL_REQUIRED);
        }
        return Optional.of(KernelCode.MVK_DIFFERENT_GROUPS);
    }

    private static boolean mayCrossUnstarredRepetition(FieldOperand operand) {
        return switch (operand) {
            case FieldOperand.Cell cell -> cell.field().mayCrossUnstarredRepetition(List.of());
            case FieldListRef list -> list.field().mayCrossUnstarredRepetition(list.flattened());
        };
    }

    /**
     * Names no code on purpose: the class depends on the rule, and without a model on the topology too, so any list
     * would leave one out (two plain fields of one fixed group are {@code MVK_REPEATABLE_LEVEL_REQUIRED}).
     */
    private static final String RULE_DEPENDENT = "; which class A12 reports depends on the repetition of the operands' "
            + "levels and on whether the rule binds an unstarred one — star every repeatable level of both";

    /** The group that directly contains the operand's field — the kernel's {@code checkAllEntitiesInSameGroup}. */
    private static String owningGroup(FieldOperand operand) {
        String path = operand.fieldPath();
        return path.substring(0, path.lastIndexOf('/'));
    }

    /** A12's wildcard gate, per operand in order: the filter, read at the lowest level, before the extra stars
     *  ({@code validate.laws.SumOfProductsLawsTest}, the filtered operand that also stars two levels). */
    private static void requireLowestLevelStar(FieldListRef operand) {
        if (operand.filter().isPresent()) {
            throw Refusals.of(KernelCode.MVK_WILDCARD_AT_LOWEST_LEVEL_REQUIRED,
                    "SumOfProducts needs an unrestricted asterisk at its lowest"
                            + " repetition position");
        }
        if (operand.flattened().size() != 1) {
            throw Refusals.of(KernelCode.MVK_WILDCARD_ONLY_AT_LOWEST_LEVEL_ALLOWED,
                    "SumOfProducts permits an asterisk only at its lowest"
                            + " repetition position");
        }
    }

    @Override
    public ValueKind kind() {
        return ValueKind.NUMBER;
    }

    public String operatorId() {
        return "SumOfProducts";
    }
}
