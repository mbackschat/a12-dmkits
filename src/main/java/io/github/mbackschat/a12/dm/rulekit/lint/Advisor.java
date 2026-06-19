package io.github.mbackschat.a12.dm.rulekit.lint;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.mgmtp.a12.model.notification.Severity;

import io.github.mbackschat.a12.dm.rulekit.catalog.GotchaKind;
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog;
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorEntry;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.OperatorUsage;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.DiagnosticSource;

/**
 * The F4a <b>gotcha/INFO channel</b> — the third diagnostic kind (PROJECT-DESIGN §F4a). The
 * structural-error / semantic-surprise split is fundamental: {@code checkConsistency} catches the
 * former (F4) but is <em>silent</em> on the latter, because a surprising rule is still a
 * <em>valid</em> one. So for every operator a rule uses, the advisor surfaces that operator's
 * catalog {@code gotchas} as {@code INFO} diagnostics — turning the passive catalog field into
 * proactive author-facing notes, drift-free (the catalog is N7-locked). It is rulekit's unique value
 * for an LLM: e.g. authoring {@code AddYears} surfaces the leap-day disagreement with {@code AddMonths}.
 *
 * <p>Shares the {@code Diagnostic} shape and the {@code LINT} origin with the {@link Linter} (both
 * are rulekit's AST-derived advisory layer); the {@code INFO} severity is what marks the
 * correct-but-surprising kind apart from the lint WARNINGs.
 */
public final class Advisor {

    /** The synthetic code marking a gotcha note (there is no kernel error code — the rule is valid). */
    public static final String GOTCHA_CODE = "RK_GOTCHA";

    private final OperatorCatalog catalog;

    public Advisor() {
        this(OperatorCatalog.load());
    }

    public Advisor(OperatorCatalog catalog) {
        this.catalog = catalog;
    }

    /** Gotcha notes for the operators {@code condition} uses (the {@code where.rule} is left empty). */
    public List<Diagnostic> advise(Condition condition) {
        return advise("", condition);
    }

    /** Gotcha notes for the operators used by the error condition of the rule at {@code rulePath}. */
    public List<Diagnostic> advise(String rulePath, Condition condition) {
        List<Diagnostic> out = new ArrayList<>();
        for (String operatorId : OperatorUsage.of(condition)) {
            catalog.byId(operatorId).ifPresent(entry -> {
                Optional<String> explain = Optional.ofNullable(entry.meaning())
                        .filter(s -> !s.isBlank());
                for (OperatorEntry.Gotcha gotcha : entry.gotchas()) {
                    // RENDERING gotchas are trivia rulekit already handles (language-independent
                    // symbols, And/Or parenthesization) — not author-facing, so the channel is silent.
                    if (gotcha.kind() == GotchaKind.RENDERING) {
                        continue;
                    }
                    out.add(new Diagnostic(Severity.INFO, DiagnosticSource.LINT,
                            Optional.of(GOTCHA_CODE), gotcha.text(),
                            new Diagnostic.Where(rulePath, Optional.of(operatorId)),
                            Optional.empty(), explain));
                }
            });
        }
        return List.copyOf(out);
    }
}
