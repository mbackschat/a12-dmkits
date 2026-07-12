package io.github.mbackschat.a12.dm.rulekit.lint;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.github.mbackschat.a12.dm.rulekit.catalog.Corrective;
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog;
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorEntry;
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation;
import io.github.mbackschat.a12.dm.rulekit.dsl.ComparisonCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldValue;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.OperatorUsage;
import io.github.mbackschat.a12.dm.rulekit.dsl.StringLiteral;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.DiagnosticSource;
import io.github.mbackschat.a12.dm.rulekit.validate.Span;

/**
 * F4a <b>span-pointing</b> — link a KERNEL {@link Diagnostic} back to the AST node that produced it.
 * The kernel reports a code, not a location; rulekit knows the operators the rule uses
 * ({@link OperatorUsage}). Intersecting a code's catalog candidate operators (those whose
 * {@code invalidExample} documents it) with the rule's used operators identifies the culprit when
 * exactly one matches — then {@code where.operator} is pinned (it is otherwise empty for KERNEL
 * diagnostics) and the catalog-join is re-derived from that <em>exact</em> entry rather than the
 * representative first one. Ambiguous (several used candidates) or unknown codes pass through
 * unchanged; so do non-KERNEL diagnostics (lints/gotchas already carry their operator).
 *
 * <p><b>Operand-aware fix selection (CATALOG-SPEC §6, the D1 mechanism).</b> When the pinned
 * operator carries {@code correctives} and the pinned AST node is a comparison, the node's actual
 * operands are classified (field kinds off {@link FieldValue}, literal nodes by their literal-ness —
 * a {@link DateLiteral} classifies as {@code string-literal}, since it renders as a quoted literal
 * and only reaches this path when the kernel refused its text as a date) and the corrective whose
 * {@code whenOperands} multiset matches is selected — so {@code MVK_INVALID_TYPE_FOR_COMPARISON} on
 * a date-vs-ISO-literal gets the date-literal corrective, never the string-vs-string one (the D1
 * incident). No match, or no AST node — the entry-level union fix stays the fallback. This runs
 * wherever a typed {@code Condition} exists (the built-rule path, converted reads); a kernel-REJECTED
 * <em>text</em> candidate has no AST to classify — the kernel's parser returns {@code null} for an
 * invalid condition by contract — so the text path keeps the union fix.
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
        Optional<String> fix = correctiveFix(operator, diagnostic.code().get(), rendered)
                .or(() -> Optional.ofNullable(operator.invalidExample())
                        .map(OperatorEntry.InvalidExample::fix)
                        .filter(s -> s != null && !s.isBlank()))
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

    /**
     * The operand-aware corrective (CATALOG-SPEC §6): the pinned operator's corrective for {@code code}
     * whose {@code whenOperands} multiset matches the pinned comparison node's classified operands —
     * only when exactly one AST node carries the operator (the same uniqueness rule as the span).
     */
    private Optional<String> correctiveFix(OperatorEntry operator, String code, DslRenderer.Rendered rendered) {
        if (operator.correctives().isEmpty()) {
            return Optional.empty();
        }
        List<Condition> nodes = nodesOf(operator.id(), rendered);
        if (nodes.size() != 1 || !(nodes.get(0) instanceof ComparisonCondition cmp)) {
            return Optional.empty();
        }
        List<String> tokens = classify(cmp);
        if (tokens.isEmpty()) {
            return Optional.empty();
        }
        return operator.correctives().stream()
                .filter(c -> code.equals(c.code()) && matchesUnordered(c.whenOperands(), tokens))
                .map(Corrective::fix)
                .findFirst();
    }

    /** The comparison's operand-class tokens ({@code number}/{@code string}/…, {@code string-literal},
     *  {@code number-literal}) — empty when an operand shape is outside the classification vocabulary. */
    private static List<String> classify(ComparisonCondition cmp) {
        String left = operandToken(cmp.left());
        String right = operandToken(cmp.right());
        return left == null || right == null ? List.of() : List.of(left, right);
    }

    private static String operandToken(Calculation calc) {
        return switch (calc) {
            case FieldValue fv -> OperatorCatalog.kindToken(fv.field().kind());
            case StringLiteral ignored -> "string-literal";
            case NumberLiteral ignored -> "number-literal";
            // a DateLiteral renders as a quoted literal; it reaches a corrective only when the kernel
            // refused its text as a date — exactly the "quoted literal beside a date" trigger
            case DateLiteral ignored -> "string-literal";
            default -> null;
        };
    }

    private static boolean matchesUnordered(List<String> expected, List<String> actual) {
        if (expected.size() != actual.size()) {
            return false;
        }
        List<String> a = expected.stream().sorted().toList();
        List<String> b = actual.stream().sorted().toList();
        return a.equals(b);
    }

    /** Every rendered AST node carrying {@code operatorId}. */
    private static List<Condition> nodesOf(String operatorId, DslRenderer.Rendered rendered) {
        return rendered.spans().keySet().stream()
                .filter(node -> operatorId.equals(node.operatorId()))
                .toList();
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
