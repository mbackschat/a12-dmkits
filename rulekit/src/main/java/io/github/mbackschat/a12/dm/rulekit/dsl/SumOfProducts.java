package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.EntityPaths;
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
 * operand does not carry — so {@code Milestones*}{@code /Tasks/Effort} passes here and the kernel rejects it
 * ({@code MVK_NO_WILDCARD}). Under-reporting is safe; quoting a wrong code is not, so the refusal message
 * names the code the kernel emits for that exact PAIR, per the matrix in {@code SumOfProductsLawsTest}.
 */
public record SumOfProducts(FieldOperand left, FieldOperand right) implements Calculation {

    public SumOfProducts {
        requireNumber(left);
        requireNumber(right);
        if (!(left instanceof FieldListRef leftList) || !(right instanceof FieldListRef rightList)
                || !owningGroup(left).equals(owningGroup(right))) {
            throw new IllegalArgumentException("SumOfProducts needs two repetition lists whose fields share one"
                    + " owning group; " + left.renderedPath() + " and " + right.renderedPath()
                    + " are not parallel (" + mismatchCode(left, right) + ")");
        }
        requireLowestLevelStar(leftList);
        requireLowestLevelStar(rightList);
    }

    private static void requireNumber(FieldOperand operand) {
        if (operand.kind() != ValueKind.NUMBER) {
            throw new IllegalArgumentException("SumOfProducts takes number fields; "
                    + operand.renderedPath() + " is " + operand.kind());
        }
    }

    /**
     * The kernel's code for a non-parallel pair, reproduced from the measured matrix: a pair with no repeatable
     * level anywhere <b>and one shared owning group</b> is {@code MVK_REPEATABLE_LEVEL_REQUIRED}; an operand
     * sitting below a starred group it does not itself wildcard is {@code MVK_NO_WILDCARD}; everything else is
     * {@code MVK_DIFFERENT_GROUPS}. Applying one code per operand instead got two of these three wrong.
     *
     * <p><b>The owning-group conjunct is not decoration</b> (corrected 2026-09-14): without it a pair of plain
     * fields in DIFFERENT groups quoted the repeatable-level code, where the kernel draws the owning-group one.
     * The row that had covered this arm gave both its operands the same owning group, so the two conditions
     * were one thing on the only case ever run — on this side and in
     * {@code validate.laws.SumOfProductsLawsTest} alike, which is why measuring the kernel pair is what found it.
     */
    private static String mismatchCode(FieldOperand left, FieldOperand right) {
        if (left instanceof FieldRef && right instanceof FieldRef
                && owningGroup(left).equals(owningGroup(right))) {
            return "MVK_REPEATABLE_LEVEL_REQUIRED";
        }
        if (unwildcardedBelowTheOthersStar(left, right) || unwildcardedBelowTheOthersStar(right, left)) {
            return "MVK_NO_WILDCARD";
        }
        return "MVK_DIFFERENT_GROUPS";
    }

    private static boolean unwildcardedBelowTheOthersStar(FieldOperand operand, FieldOperand other) {
        return other instanceof FieldListRef list && list.flattened().stream().anyMatch(starred ->
                EntityPaths.isStrictlyInside(operand.fieldPath(), starred.path().value())
                        && !wildcards(operand, starred.path().value()));
    }

    private static boolean wildcards(FieldOperand operand, String groupPath) {
        return operand instanceof FieldListRef list
                && list.flattened().stream().anyMatch(starred -> starred.path().value().equals(groupPath));
    }

    /** The group that directly contains the operand's field — the kernel's {@code checkAllEntitiesInSameGroup}. */
    private static String owningGroup(FieldOperand operand) {
        String path = operand.fieldPath();
        return path.substring(0, path.lastIndexOf('/'));
    }

    private static void requireLowestLevelStar(FieldListRef operand) {
        if (operand.flattened().size() != 1) {
            throw new IllegalArgumentException("SumOfProducts permits an asterisk only at its lowest"
                    + " repetition position (MVK_WILDCARD_ONLY_AT_LOWEST_LEVEL_ALLOWED)");
        }
        if (operand.filter().isPresent()) {
            throw new IllegalArgumentException("SumOfProducts needs an unrestricted asterisk at its lowest"
                    + " repetition position (MVK_WILDCARD_AT_LOWEST_LEVEL_REQUIRED)");
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
