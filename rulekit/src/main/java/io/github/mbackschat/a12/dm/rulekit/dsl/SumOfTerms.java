package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.Objects;
import java.util.Optional;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@code SumOfTerms(Group*, term)} — kernel 31.1.1's term sum: the number {@code term} evaluated once per row of
 * the starred iteration group, optionally narrowed by a {@code Having} filter, and added up. A {@link Calculation}
 * of {@link ValueKind#NUMBER}.
 *
 * <p>The term is read per row, so its field references name the iteration group's fields unstarred
 * ({@code [Claims/Amount]} under {@code Claims*}). What the kernel admits there, and what the sum evaluates to, is
 * KERNEL-FINDINGS KF670.
 *
 * <p>The record refuses nothing beyond the number term its type demands. Every structural defect the kernel
 * reports here (a missing or misplaced star, a term reading another repeatable group, a semantic index, a
 * {@code $} marker, a nested term sum) has a class that depends on the model or on the rule the sum sits in, so
 * the kernel is asked rather than guessed ({@code validate.laws.SumOfTermsTermLawsTest},
 * {@code validate.laws.SumOfTermsIterationEntityLawsTest}).
 */
public record SumOfTerms(GroupListRef iteration, Optional<Condition> filter, Calculation term) implements Calculation {

    public SumOfTerms {
        Objects.requireNonNull(iteration, "iteration");
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(term, "term");
        // The kernel refuses a term of any other kind (MVK_NO_NUMBER, validate.laws.SumOfTermsTermLawsTest); the
        // factories take a NumberExpr, so only a hand-built record reaches this.
        if (term.kind() != ValueKind.NUMBER) {
            throw new IllegalArgumentException("SumOfTerms takes a number term; " + term + " is " + term.kind());
        }
    }

    @Override
    public ValueKind kind() {
        return ValueKind.NUMBER;
    }

    public String operatorId() {
        return "SumOfTerms";
    }
}
