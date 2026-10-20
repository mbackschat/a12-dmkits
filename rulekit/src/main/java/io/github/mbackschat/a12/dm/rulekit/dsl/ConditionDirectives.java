package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.KernelCode;

/**
 * The one directive a rule condition may open with: A12's waiver of the numeric equality scale gate, the
 * editor's "Allow Differing Decimal Places" (KERNEL-FINDINGS KF75).
 *
 * <p>The keyword is read in the MODEL's condition language. {@code @SuppressWarning} is admitted in an English
 * model and {@code @UnterdrueckeWarnung} in a German one, and each language refuses the other's spelling with
 * {@code MVK_UNEXPECTED_TOKEN} ({@code rulekit.validate.laws.ScaleWaiverLanguageLawsTest}). Writing the English keyword
 * into every model was the defect this class replaced.
 */
public final class ConditionDirectives {

    /** The one warning A12 lets a condition waive; any other name is {@code MVK_INVALID_SUPPRESSED_WARNING}. */
    public static final String SCALE_GATE = KernelCode.MVK_INVALID_COMPARE_DEC_PLACES.name();

    private ConditionDirectives() {
    }

    /** {@code condition} preceded by the scale-gate waiver, spelled in {@code language}. */
    public static String allowDifferingDecimals(ConditionLanguage language, String condition) {
        String keyword = switch (language) {
            case EN -> "SuppressWarning";
            case DE -> "UnterdrueckeWarnung";
        };
        return "@" + keyword + "(" + SCALE_GATE + ") " + condition;
    }
}
