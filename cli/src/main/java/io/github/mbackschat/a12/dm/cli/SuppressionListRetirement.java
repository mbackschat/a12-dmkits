package io.github.mbackschat.a12.dm.cli;

import java.util.Optional;

import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;

/**
 * The corrective for a computation's {@code errorCodesToSuppress} list, which kernel 31.3.1 replaced with a
 * free-text preamble (A12K-4372, KERNEL-FINDINGS KF298). The generic unknown-key refusal lists
 * {@code additionalParserInformation} among the allowed keys but cannot say that the list became a
 * {@code @SuppressWarning(...)} text, so a caller still using the earlier key is told the replacement form.
 */
final class SuppressionListRetirement {

    private static final String KEY = "errorCodesToSuppress";
    private static final String FIX = "the suppression list is now A12's computation preamble: pass "
            + "`additionalParserInformation` as `@SuppressWarning(<code>, …)` (`dmtool schema computation add`)";

    private SuppressionListRetirement() {}

    /** The corrective for {@code key} on a computation spec, or empty for any other key or target. */
    static Optional<String> fix(String target, String key) {
        return "computation".equals(target) && KEY.equals(key) ? Optional.of(FIX) : Optional.empty();
    }

    /** Replace the generic unknown-key suggestion with the preamble form where it applies. */
    static Diagnostic correct(String target, Diagnostic diagnostic) {
        if (diagnostic.code().filter(RkCode.UNKNOWN_ARG.code()::equals).isEmpty()) {
            return diagnostic;
        }
        return fix(target, diagnostic.where().arg().orElse(""))
                .map(fix -> new Diagnostic(diagnostic.severity(), diagnostic.source(), diagnostic.code(),
                        diagnostic.summary(), diagnostic.where(), Optional.of(fix), diagnostic.explain()))
                .orElse(diagnostic);
    }
}
