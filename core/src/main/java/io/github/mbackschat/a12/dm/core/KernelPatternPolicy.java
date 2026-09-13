package io.github.mbackschat.a12.dm.core;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import io.github.mbackschat.a12.dm.predicates.PatternPredicatesKt;

/**
 * The kernel 30.8.x pattern-admission policy shared by rulekit and modelkit.
 *
 * <p>Passing this policy means “accepted for kernel model generation”, not “portable to JavaScript”.
 */
public final class KernelPatternPolicy {

    /** The stage that rejects an authored pattern. */
    public enum Rejection {
        JAVA_SYNTAX,
        KERNEL_ADMISSION
    }

    private KernelPatternPolicy() {
    }

    /** Returns the rejecting stage, or empty when the pattern passes kernel admission. */
    public static Optional<Rejection> rejection(String regex) {
        Objects.requireNonNull(regex, "regex");
        try {
            Pattern.compile(regex);
        } catch (PatternSyntaxException invalidJava) {
            return Optional.of(Rejection.JAVA_SYNTAX);
        }
        return rejectedByKernelAdmission(regex) ? Optional.of(Rejection.KERNEL_ADMISSION) : Optional.empty();
    }

    /** Return {@code regex}, or fail fast with the distinct rejecting stage in the message. */
    public static String requireAccepted(String regex) {
        return switch (rejection(regex).orElse(null)) {
            case JAVA_SYNTAX -> throw new IllegalArgumentException(
                    "invalid Java regular-expression syntax (MVK_INVALID_PATTERN): " + regex);
            case KERNEL_ADMISSION -> throw new IllegalArgumentException(
                    "Java-valid pattern is rejected by the kernel 30.8.x pattern-admission gate "
                            + "(the A12K-30 source-text blacklist; MVK_INVALID_PATTERN in a condition, "
                            + "MVK_PATTERN_INVALID on a declared field): " + regex);
            case null -> regex;
        };
    }

    /**
     * The kernel's A12K-30 blacklist — one live statement, in {@code :predicates}, shared with the portable
     * checker.
     *
     * <p>This method carried its own copy until 2026-09-13 and the copy was wrong in both directions: it
     * consumed escapes while scanning, so it read the pattern instead of the source text and admitted
     * {@code a\*+}, which the kernel refuses; and it rejected {@code \P{L}}, which the kernel admits, on a
     * 2026-07-20 observation of {@code \P{L}+} whose mechanism was misread (the {@code }} before {@code +}
     * is what the gate matches). The kernel grid that settles both is
     * {@code rulekit.validate.laws.DeclaredPatternGateLawsTest}, which holds this policy to it row for row.
     */
    private static boolean rejectedByKernelAdmission(String regex) {
        return PatternPredicatesKt.kernelPatternBlacklist(regex) != null;
    }
}
