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
                            + "(A12K-30 blacklist or the typed/full-model \\P rejection; MVK_INVALID_PATTERN): "
                            + regex);
            case null -> regex;
        };
    }

    private static boolean rejectedByKernelAdmission(String regex) {
        for (int i = 0; i < regex.length(); i++) {
            char c = regex.charAt(i);
            if (c == '\\' && i + 1 < regex.length()) {
                char escaped = regex.charAt(++i);
                if ("AGZzaepQE".indexOf(escaped) >= 0 || escaped == 'P') {
                    return true;
                }
                continue;
            }
            if (i + 1 < regex.length() && regex.charAt(i + 1) == '+'
                    && (c == '?' || c == '+' || c == '*' || c == '}')) {
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
