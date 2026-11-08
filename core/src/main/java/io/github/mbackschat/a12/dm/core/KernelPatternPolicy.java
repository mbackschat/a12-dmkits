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
            case JAVA_SYNTAX -> throw Refusals.of(KernelCode.MVK_INVALID_PATTERN,
                    "invalid Java regular-expression syntax: " + regex);
            case KERNEL_ADMISSION -> throw Refusals.of(KernelCode.MVK_INVALID_PATTERN,
                    "Java-valid pattern is rejected by A12's pattern-admission gate "
                            + "(its source-text blacklist; " + KernelCode.MVK_INVALID_PATTERN
                            + " in a condition, " + KernelCode.MVK_PATTERN_INVALID
                            + " on a declared field): " + regex);
            case null -> regex;
        };
    }

    /**
     * The kernel's A12K-30 blacklist — one live statement, in {@code :predicates}, shared with the portable
     * checker.
     *
     * <p>This method must not keep its own copy, which can be wrong in both directions: a scan that consumes
     * escapes while reading sees the pattern instead of the source text and admits {@code a\*+}, which the
     * kernel refuses; and a gate that rejects {@code \P{L}} refuses what the kernel admits — the {@code }}
     * before {@code +} in {@code \P{L}+} is what the gate matches. The kernel grid that settles both is
     * {@code rulekit.validate.laws.DeclaredPatternGateLawsTest}, which holds this policy to it row for row.
     */
    private static boolean rejectedByKernelAdmission(String regex) {
        return PatternPredicatesKt.kernelPatternBlacklist(regex) != null;
    }
}
