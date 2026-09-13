package io.github.mbackschat.a12.dm.core;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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
     * The kernel's A12K-30 blacklist, which decides on the pattern's SOURCE CHARACTERS and nothing else.
     *
     * <p>Every position is examined, escaped or not: the kernel applies one regular expression to the source
     * text, so {@code \*+} is refused although the pattern it spells is an ordinary escaped star followed by
     * a quantifier, and so is {@code \}+}. Consuming an escape while scanning — which this method did until
     * 2026-09-13 — reads the pattern instead of the text and quietly admits both.
     *
     * <p>The escape set is exactly {@code A G Z z a e p Q E}. Uppercase {@code \P} is NOT in it and
     * {@code \P{L}} is admitted by the kernel, measured at the declared-field locus
     * ({@code rulekit.validate.laws.DeclaredPatternGateLawsTest}). This method rejected it until the same
     * day, on a 2026-07-20 observation of {@code \P{L}+} being refused — a correct measurement with the
     * wrong mechanism attached, since that pattern ends in {@code }} followed by {@code +} and is caught by
     * the possessive alternative above with the escape playing no part.
     */
    private static boolean rejectedByKernelAdmission(String regex) {
        for (int i = 0; i < regex.length(); i++) {
            char c = regex.charAt(i);
            if (i + 1 < regex.length() && regex.charAt(i + 1) == '+'
                    && (c == '?' || c == '+' || c == '*' || c == '}')) {
                return true;
            }
            if (c == '\\' && i + 1 < regex.length() && "AGZzaepQE".indexOf(regex.charAt(i + 1)) >= 0) {
                return true;
            }
            if (c == '(' && i + 2 < regex.length() && regex.charAt(i + 1) == '?'
                    && (regex.charAt(i + 2) == '<' || regex.charAt(i + 2) == '>')) {
                return true;
            }
            if (c == '[' && !immediatelyEscaped(regex, i)) {
                for (int j = i + 1; j < regex.length() && regex.charAt(j) != ']'; j++) {
                    if (regex.charAt(j) == '[' && !immediatelyEscaped(regex, j)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean immediatelyEscaped(String regex, int index) {
        return index > 0 && regex.charAt(index - 1) == '\\';
    }
}
