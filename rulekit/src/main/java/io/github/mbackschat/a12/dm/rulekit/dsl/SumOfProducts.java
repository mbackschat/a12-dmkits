package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@code SumOfProducts(a, b)} — the kernel's pairwise sum-of-products over two parallel number field
 * lists: Σᵢ aᵢ·bᵢ (typically two wildcard repetition lists — the flatten of {@code Items*} then
 * {@code Qty} paired with {@code Items* / Price}). A {@link Calculation} of {@link ValueKind#NUMBER};
 * both operands render BARE (entity refs). The kernel requires two unfiltered number lists over the same,
 * single starred group; the constructor pre-checks that measured boundary.
 */
public record SumOfProducts(FieldOperand left, FieldOperand right) implements Calculation {

    public SumOfProducts {
        requireNumber(left);
        requireNumber(right);
        FieldListRef leftList = requireList(left);
        FieldListRef rightList = requireList(right);
        requireOneStar(leftList);
        requireOneStar(rightList);
        requireUnfiltered(leftList);
        requireUnfiltered(rightList);
        if (!leftList.flattened().getFirst().path().equals(rightList.flattened().getFirst().path())) {
            throw new IllegalArgumentException("SumOfProducts operands must use the same repeatable group"
                    + " (MVK_DIFFERENT_GROUPS)");
        }
    }

    private static void requireNumber(FieldOperand operand) {
        if (operand.kind() != ValueKind.NUMBER) {
            throw new IllegalArgumentException("SumOfProducts takes number fields; "
                    + operand.renderedPath() + " is " + operand.kind());
        }
    }

    private static FieldListRef requireList(FieldOperand operand) {
        return switch (operand) {
            case FieldListRef list -> list;
            case FieldRef ignored -> throw new IllegalArgumentException(
                    "SumOfProducts needs two repetition lists (MVK_REPEATABLE_LEVEL_REQUIRED)");
        };
    }

    private static void requireOneStar(FieldListRef operand) {
        if (operand.flattened().size() != 1) {
            throw new IllegalArgumentException("SumOfProducts permits an asterisk only at its lowest"
                    + " repetition position (MVK_WILDCARD_ONLY_AT_LOWEST_LEVEL_ALLOWED)");
        }
    }

    private static void requireUnfiltered(FieldListRef operand) {
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
