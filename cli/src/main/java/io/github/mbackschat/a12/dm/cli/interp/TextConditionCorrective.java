package io.github.mbackschat.a12.dm.cli.interp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.mbackschat.a12.dm.core.ModelIndex;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.interpreter.ast.CmpOp;
import io.github.mbackschat.a12.dm.interpreter.ast.Cond;
import io.github.mbackschat.a12.dm.interpreter.ast.Expr;
import io.github.mbackschat.a12.dm.interpreter.parse.ParserKt;
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog;
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorEntry;
import io.github.mbackschat.a12.dm.rulekit.dsl.CompareOp;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.DiagnosticSource;

/**
 * G30 — operand-aware fix selection on the {@code rule check} / {@code rule add} TEXT path (CATALOG-SPEC §6).
 *
 * <p>The catalog {@code correctives} select the operand-matched fix wherever a typed {@code Condition} AST
 * exists (the {@link io.github.mbackschat.a12.dm.rulekit.lint.SpanResolver} path). A kernel-REJECTED
 * <em>text</em> candidate has no such AST — the kernel's parser returns {@code null} for an invalid condition
 * by contract — so historically the entry-level <em>union</em> fix (naming both remedies) was served. This
 * closes that gap at the CLI seam (the only module that depends on {@code :interpreter}): it tolerantly parses
 * the candidate's text with the kernel-free interpreter parser (which parses ill-typed text fine), classifies
 * the pinned comparison's operands against a {@link ModelIndex}, and serves the SAME operand-matched corrective
 * the typed path would — so {@code MVK_INVALID_TYPE_FOR_COMPARISON} on a date-vs-ISO-literal gets the
 * date-literal corrective, never the string-vs-string one (the D1 incident).
 *
 * <p><b>Never worse than today.</b> On any parse failure, classification failure (a field absent from the
 * model, a kind outside the token vocabulary), or ambiguity (no unique candidate operator, or more than one
 * comparison node carries it), the diagnostics are returned <em>unchanged</em> — the union fix stays. It never
 * narrows a union to a wrong corrective.
 */
public final class TextConditionCorrective {

    private final OperatorCatalog catalog;

    public TextConditionCorrective() {
        this(OperatorCatalog.load());
    }

    public TextConditionCorrective(OperatorCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * CLI convenience — build a {@link ModelIndex} from {@code modelJson} (resolved via {@code resolver}) and
     * {@link #enrich(List, String, String, ModelIndex) enrich}. Any failure building the index (an unresolved
     * include, an unreadable model) returns {@code diagnostics} unchanged — the union fix stays, never worse
     * than today. The single entry point every text-path verb ({@code rule check}/{@code rule add}/…) calls.
     */
    public static List<Diagnostic> enrich(List<Diagnostic> diagnostics, String conditionText, String baseGroup,
            String modelJson, ModelResolver resolver) {
        // Skip the catalog load + model expansion when there is nothing to enrich (a clean `rule check` has no
        // diagnostics) — the instance enrich's guard, hoisted before the eager argument builds it can't reach.
        if (diagnostics.isEmpty() || conditionText == null || conditionText.isBlank()) {
            return diagnostics;
        }
        try {
            return new TextConditionCorrective().enrich(diagnostics, conditionText, baseGroup,
                    ModelIndex.of(modelJson, resolver));
        } catch (RuntimeException indexFailure) {
            return diagnostics;
        }
    }

    /**
     * Upgrade each corrective-eligible KERNEL diagnostic's union fix to the operand-matched corrective, when
     * the candidate {@code conditionText} (paths relative to {@code baseGroup}) tolerantly parses and its
     * pinned comparison's operands classify against {@code index}. Any failure leaves the diagnostic as-is.
     */
    public List<Diagnostic> enrich(List<Diagnostic> diagnostics, String conditionText, String baseGroup,
            ModelIndex index) {
        if (conditionText == null || conditionText.isBlank() || diagnostics.isEmpty()) {
            return diagnostics;
        }
        Map<String, List<Cond.Cmp>> byOperator = comparisonsByOperator(conditionText, baseGroup);
        if (byOperator.isEmpty()) {
            return diagnostics;
        }
        return diagnostics.stream().map(d -> upgrade(d, byOperator, index)).toList();
    }

    private Diagnostic upgrade(Diagnostic d, Map<String, List<Cond.Cmp>> byOperator, ModelIndex index) {
        if (d.source() != DiagnosticSource.KERNEL || d.code().isEmpty()) {
            return d;
        }
        String code = d.code().get();
        Set<String> candidates = catalog.operatorsForError(code).stream()
                .map(OperatorEntry::id).collect(Collectors.toSet());
        List<String> pinned = byOperator.keySet().stream().filter(candidates::contains).toList();
        if (pinned.size() != 1) {
            return d;   // no unique candidate operator in the text → keep the union
        }
        List<Cond.Cmp> nodes = byOperator.get(pinned.get(0));
        if (nodes.size() != 1) {
            return d;   // the operator appears more than once → ambiguous → keep the union
        }
        List<String> tokens = classify(nodes.get(0), index);
        if (tokens.isEmpty()) {
            return d;   // an operand shape outside the classification vocabulary → keep the union
        }
        Optional<String> fix = catalog.correctiveFix(pinned.get(0), code, tokens);
        if (fix.isEmpty()) {
            return d;   // no corrective matches these operands → keep the union
        }
        Diagnostic.Where where = d.where().operator().isPresent()
                ? d.where() : new Diagnostic.Where(d.where().rule(), Optional.of(pinned.get(0)));
        return new Diagnostic(d.severity(), d.source(), d.code(), d.summary(), where, fix, d.explain());
    }

    /** Every comparison node of the (tolerantly parsed) condition, grouped by its catalog operator-id. Empty
     *  on a parse failure — the caller then keeps the union fix. */
    private Map<String, List<Cond.Cmp>> comparisonsByOperator(String conditionText, String baseGroup) {
        Cond ast;
        try {
            ast = ParserKt.parseCondition(conditionText, baseGroup, ParserKt.getDEFAULT_RESOLVER());
        } catch (RuntimeException parseFailure) {
            return Map.of();
        }
        Map<String, List<Cond.Cmp>> byOperator = new LinkedHashMap<>();
        collect(ast, byOperator);
        return byOperator;
    }

    private void collect(Cond c, Map<String, List<Cond.Cmp>> out) {
        switch (c) {
            case Cond.Cmp cmp -> out.computeIfAbsent(operatorId(cmp.getOp()), k -> new ArrayList<>()).add(cmp);
            case Cond.And a -> { collect(a.getLeft(), out); collect(a.getRight(), out); }
            case Cond.Or o -> { collect(o.getLeft(), out); collect(o.getRight(), out); }
            case Cond.Not n -> collect(n.getC(), out);
            default -> { }   // Pred / Pattern / Tolerance / ValueListQuant / Custom carry no ordering corrective
        }
    }

    /** The comparison's operand-class tokens, or empty when either operand is outside the vocabulary. Mirrors
     *  {@link io.github.mbackschat.a12.dm.rulekit.lint.SpanResolver}'s classification, off the interpreter AST. */
    private static List<String> classify(Cond.Cmp cmp, ModelIndex index) {
        String left = operandToken(cmp.getLeft(), index);
        String right = operandToken(cmp.getRight(), index);
        return left == null || right == null ? List.of() : List.of(left, right);
    }

    private static String operandToken(Expr operand, ModelIndex index) {
        return switch (operand) {
            case Expr.Field f -> index.isField(f.getPath()) ? OperatorCatalog.kindToken(kindOf(f, index)) : null;
            case Expr.StrLit ignored -> "string-literal";
            case Expr.NumLit ignored -> "number-literal";
            default -> null;
        };
    }

    private static ValueKind kindOf(Expr.Field f, ModelIndex index) {
        try {
            return index.kindOf(f.getPath());
        } catch (RuntimeException notClassifiable) {
            return ValueKind.UNKNOWN;   // kindToken(UNKNOWN) == null → the operand doesn't classify
        }
    }

    /** The interpreter {@link CmpOp} → catalog operator-id, single-sourced through rulekit's {@link CompareOp}
     *  (both enums share the LT/LE/GT/GE/EQ/NE names). */
    private static String operatorId(CmpOp op) {
        return CompareOp.valueOf(op.name()).operatorId();
    }
}
