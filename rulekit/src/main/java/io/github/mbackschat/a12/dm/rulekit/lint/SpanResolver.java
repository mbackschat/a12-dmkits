package io.github.mbackschat.a12.dm.rulekit.lint;

import java.util.List;
import java.util.Optional;
import java.util.Set;

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
import io.github.mbackschat.a12.dm.rulekit.validate.KernelMessageOperands;
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
 * <em>text</em> candidate has no rulekit AST here — the kernel's parser returns {@code null} for an
 * invalid condition by contract — so the text path's operand-matched selection is done at the CLI seam
 * (G30, {@code cli.interp.TextConditionCorrective}) via the kernel-free interpreter parser, calling the
 * SAME shared {@link OperatorCatalog#correctiveFix} this class does. The corrective match is
 * single-sourced there; both paths fall back to the union fix when no corrective matches.
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
        // The kernel's own message gets a VETO over this inference. Intersecting the code's documenters with the
        // operators the condition uses is not sound on its own, because the catalog is a PARTIAL map: a rule using
        // both MaxValue and MinValue, where only MaxValue documents MVK_NOT_SORTABLE, would pin MaxValue and explain
        // "the largest of the listed values" — while the message in hand says `At 'MinValue'`.
        //
        // The veto is corroborated against `used`, the operators the AST actually contains, and that is what makes it
        // sound: a quoted span is not necessarily an operator (the kernel quotes enum S-values and enum names too), so
        // a condition comparing against the value "Max" must not veto a correct pin just because `Max` is also an
        // operator name. Requiring the named operator to be PRESENT in the condition rules that out. Deliberately
        // one-directional — naming nobody leaves the inference standing, so the operand-aware corrective keeps
        // working; a veto can only withhold a claim, never manufacture one.
        if (KernelMessageOperands.contradicts(catalog, diagnostic.summary(), operator, usedEntries(used))) {
            return diagnostic;
        }
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

    /** The catalog entries for the operators the condition uses — the veto's independent corroboration. */
    private List<OperatorEntry> usedEntries(Set<String> used) {
        return used.stream().map(catalog::byId).flatMap(Optional::stream).toList();
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
        return catalog.correctiveFix(operator.id(), code, tokens);
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
