package io.github.mbackschat.a12.rulekit.lint;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.github.mbackschat.a12.rulekit.catalog.OperatorCatalog;
import io.github.mbackschat.a12.rulekit.catalog.OperatorEntry;
import io.github.mbackschat.a12.rulekit.dsl.Condition;
import io.github.mbackschat.a12.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.rulekit.dsl.OperatorUsage;
import io.github.mbackschat.a12.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.rulekit.validate.DiagnosticSource;
import io.github.mbackschat.a12.rulekit.validate.Span;

/**
 * F4a <b>span-pointing</b> — link a KERNEL {@link Diagnostic} back to the AST node that produced it.
 * The kernel reports a code, not a location; rulekit knows the operators the rule uses
 * ({@link OperatorUsage}). Intersecting a code's catalog candidate operators (those whose
 * {@code invalidExample} documents it) with the rule's used operators identifies the culprit when
 * exactly one matches — then {@code where.operator} is pinned (it is otherwise empty for KERNEL
 * diagnostics) and the catalog-join is re-derived from that <em>exact</em> entry rather than the
 * representative first one. Ambiguous (several used candidates) or unknown codes pass through
 * unchanged; so do non-KERNEL diagnostics (lints/gotchas already carry their operator).
 */
public final class SpanResolver {

    private final OperatorCatalog catalog;

    public SpanResolver() {
        this(OperatorCatalog.load());
    }

    public SpanResolver(OperatorCatalog catalog) {
        this.catalog = catalog;
    }

    /** The diagnostics with each KERNEL one's {@code where.operator}/{@code span} pinned where the AST disambiguates it. */
    public List<Diagnostic> resolve(List<Diagnostic> diagnostics, Condition condition) {
        return resolve(diagnostics, condition, null);
    }

    /**
     * As {@link #resolve(List, Condition)}, but renders spans relative to {@code baseGroup} so the
     * offsets match the rule's own (relative, fork #9) rendering rather than an absolute one.
     */
    public List<Diagnostic> resolve(List<Diagnostic> diagnostics, Condition condition, String baseGroup) {
        Set<String> used = OperatorUsage.of(condition);
        DslRenderer.Rendered rendered =
                new DslRenderer(ConditionLanguage.EN, baseGroup).renderWithSpans(condition);
        return diagnostics.stream().map(d -> refine(d, used, rendered)).toList();
    }

    private Diagnostic refine(Diagnostic diagnostic, Set<String> used, DslRenderer.Rendered rendered) {
        if (diagnostic.source() != DiagnosticSource.KERNEL
                || diagnostic.code().isEmpty()
                || diagnostic.where().operator().isPresent()) {
            return diagnostic;
        }
        List<OperatorEntry> pinned = catalog.operatorsForError(diagnostic.code().get()).stream()
                .filter(e -> used.contains(e.id()))
                .toList();
        if (pinned.size() != 1) {
            return diagnostic;
        }
        OperatorEntry operator = pinned.get(0);
        Optional<String> fix = Optional.ofNullable(operator.invalidExample())
                .map(OperatorEntry.InvalidExample::fix)
                .filter(s -> s != null && !s.isBlank())
                .or(diagnostic::fix);
        Optional<String> explain = Optional.ofNullable(operator.meaning())
                .filter(s -> s != null && !s.isBlank())
                .or(diagnostic::explain);
        Diagnostic.Where where = new Diagnostic.Where(diagnostic.where().rule(), Optional.of(operator.id()));
        Optional<Span> span = spanOf(operator.id(), rendered);
        if (span.isPresent()) {
            where = where.withSpan(span.get());
        }
        return new Diagnostic(diagnostic.severity(), diagnostic.source(), diagnostic.code(),
                diagnostic.summary(), where, fix, explain);
    }

    /** The span of the operator's node — only when exactly one rendered node carries that operator-id. */
    private Optional<Span> spanOf(String operatorId, DslRenderer.Rendered rendered) {
        List<Span> matches = rendered.spans().entrySet().stream()
                .filter(e -> operatorId.equals(e.getKey().operatorId()))
                .map(java.util.Map.Entry::getValue)
                .toList();
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }
}
