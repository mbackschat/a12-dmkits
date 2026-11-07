package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.Arrays;
import java.util.List;

import io.github.mbackschat.a12.dm.core.KernelCode;

/**
 * A12's condition preamble, {@code @SuppressWarning(a,b)}: the directive a rule condition may open with, and the
 * text a computation stores as its {@code additionalParserInformation}. The rule-side use is the waiver of the
 * numeric equality scale gate, the editor's "Allow Differing Decimal Places" (KERNEL-FINDINGS KF75).
 *
 * <p>The keyword is read in the MODEL's condition language. {@code @SuppressWarning} is admitted in an English
 * model and {@code @UnterdrueckeWarnung} in a German one, and each language refuses the other's spelling with
 * {@code MVK_UNEXPECTED_TOKEN} ({@code rulekit.validate.laws.ScaleWaiverLanguageLawsTest},
 * {@code ComputationParserInformationLawsTest}). Writing the English keyword into every model was the defect this
 * class replaced.
 */
public final class ConditionDirectives {

    /** The static scale gate a condition or a computation may waive. */
    public static final String SCALE_GATE = KernelCode.MVK_INVALID_COMPARE_DEC_PLACES.name();

    private ConditionDirectives() {
    }

    /** {@code condition} preceded by the scale-gate waiver, spelled in {@code language}. */
    public static String allowDifferingDecimals(ConditionLanguage language, String condition) {
        return suppressionPreamble(language, List.of(SCALE_GATE)) + " " + condition;
    }

    /**
     * The preamble suppressing {@code codes}, spelled in {@code language} as A12's own reader spells the legacy list
     * it folds ({@code @SuppressWarning(a,b)}, no padding).
     */
    public static String suppressionPreamble(ConditionLanguage language, List<String> codes) {
        String keyword = switch (language) {
            case EN -> "SuppressWarning";
            case DE -> "UnterdrueckeWarnung";
        };
        return "@" + keyword + "(" + String.join(",", codes) + ")";
    }

    /**
     * The codes a preamble A12 admitted names, in order. Only for kernel-admitted text: it reads the names between
     * the parentheses and judges nothing.
     */
    public static List<String> suppressedWarnings(String preamble) {
        int open = preamble.indexOf('(');
        int close = preamble.lastIndexOf(')');
        if (open < 0 || close < open) {
            return List.of();
        }
        return Arrays.stream(preamble.substring(open + 1, close).split(",")).map(String::strip)
                .filter(code -> !code.isEmpty()).toList();
    }
}
