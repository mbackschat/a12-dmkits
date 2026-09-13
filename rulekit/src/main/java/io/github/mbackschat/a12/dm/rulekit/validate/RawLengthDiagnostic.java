package io.github.mbackschat.a12.dm.rulekit.validate;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.mbackschat.a12.dm.core.ModelIndex;
import io.github.mbackschat.a12.dm.core.ModelResolver;

/** Contextual corrective for the kernel's generic raw-{@code Length} integer-conversion failure. */
final class RawLengthDiagnostic {

    private static final String FIELD = "([A-Za-z_][A-Za-z0-9_./]*)";
    private static final String CONSTANT = "([^\\s]+)";
    private static final Pattern LENGTH_LEFT = Pattern.compile(
            "^\\s*Length\\(\\s*" + FIELD + "\\s*\\)\\s*>\\s*" + CONSTANT + "\\s*$");
    private static final Pattern LENGTH_RIGHT = Pattern.compile(
            "^\\s*" + CONSTANT + "\\s*<\\s*Length\\(\\s*" + FIELD + "\\s*\\)\\s*$");
    private static final Pattern INTEGER = Pattern.compile("[+-]?\\d+");
    private static final BigInteger MIN = BigInteger.valueOf(Integer.MIN_VALUE);
    private static final BigInteger MAX = BigInteger.valueOf(Integer.MAX_VALUE);
    private static final String FIX = "use a decimal-point-free signed-32-bit integer constant "
            + "(-2147483648 through 2147483647) in the strict whole-condition raw-Length form; "
            + "5.0 is not accepted even though it is numerically integral";
    private static final String EXPLAIN = "For a noValueValidation string, the kernel converts the strict "
            + "Length bound with Integer.parseInt while extracting the field's generated maximum length.";

    private RawLengthDiagnostic() {
    }

    static ValidationResult enrich(ValidationResult result, String modelJson, DraftRule rule, ModelResolver resolver) {
        Optional<RawLength> rawLength = parse(rule.errorCondition());
        if (rawLength.isEmpty() || validSignedInt(rawLength.get().constant())) {
            return result;
        }
        ModelIndex index;
        try {
            index = ModelIndex.of(modelJson, resolver);
            String path = ModelIndex.resolvePath(rule.groupPath(), rawLength.get().field());
            if (!index.stringNoValueValidation(path)) {
                return result;
            }
        } catch (RuntimeException notClassifiable) {
            return result;
        }
        List<Diagnostic> diagnostics = result.diagnostics().stream()
                .map(RawLengthDiagnostic::enrichInternalError)
                .toList();
        if (diagnostics.equals(result.diagnostics())) {
            return result;
        }
        return result.consistencyGate()
                .map(mode -> ValidationResult.of(diagnostics, mode))
                .orElseGet(() -> ValidationResult.beforeTheGate(diagnostics));
    }

    private static Diagnostic enrichInternalError(Diagnostic diagnostic) {
        if (diagnostic.code().filter("MVK_INTERNAL_ERROR"::equals).isEmpty()) {
            return diagnostic;
        }
        return diagnostic.withFix(Optional.of(FIX), Optional.of(EXPLAIN));
    }

    private static Optional<RawLength> parse(String condition) {
        if (condition == null) {
            return Optional.empty();
        }
        condition = withoutRedundantParentheses(condition);
        Matcher left = LENGTH_LEFT.matcher(condition);
        if (left.matches()) {
            return Optional.of(new RawLength(left.group(1), left.group(2)));
        }
        Matcher right = LENGTH_RIGHT.matcher(condition);
        if (right.matches()) {
            return Optional.of(new RawLength(right.group(2), right.group(1)));
        }
        return Optional.empty();
    }

    /**
     * The condition with each fully-enclosing parenthesis pair removed.
     *
     * <p>A12's shape gate treats redundant parentheses as transparent — {@code (Length(f) > 5)} is ADMITTED,
     * measured 2026-09-13 by {@link laws.NvvRawTypeLawsTest} — so a parenthesised condition reaches the
     * constant gate like any other and must get the same focused corrective. Only a pair that spans the
     * whole text: {@code (a) > (b)} opens and closes with parentheses that are not each other's match.
     */
    private static String withoutRedundantParentheses(String condition) {
        String trimmed = condition.trim();
        while (trimmed.startsWith("(") && trimmed.endsWith(")") && enclosingPair(trimmed)) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    /** Whether the leading {@code (} of [text] is closed by its trailing {@code )}. */
    private static boolean enclosingPair(String text) {
        int depth = 0;
        for (int at = 0; at < text.length(); at++) {
            char character = text.charAt(at);
            if (character == '(') {
                depth++;
            } else if (character == ')' && --depth == 0) {
                return at == text.length() - 1;
            }
        }
        return false;
    }

    private static boolean validSignedInt(String lexical) {
        if (!INTEGER.matcher(lexical).matches()) {
            return false;
        }
        BigInteger value = new BigInteger(lexical);
        return value.compareTo(MIN) >= 0 && value.compareTo(MAX) <= 0;
    }

    private record RawLength(String field, String constant) { }
}
