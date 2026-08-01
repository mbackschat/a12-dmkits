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

    /**
     * Why the operand-matched corrective was, or was not, served — the five outcomes kept <b>distinguishable</b>.
     *
     * <p>All of the failures return the diagnostics unchanged, so they are invisible in the output by design
     * ("never worse than today"). That was fine for the corrective and wrong for the <em>visibility</em>: a
     * tolerant {@code catch} turned every future parser gap into the same silence as a legitimate "the union fix
     * is the right answer", and the two cost a debugging session to tell apart. Naming the outcome makes a parser
     * gap a fact rather than an absence.
     */
    public enum Outcome {
        /** The pinned comparison classified and the union fix was narrowed to the operand-matched corrective. */
        UPGRADED,
        /** The interpreter parser could not read the candidate text — <b>our</b> limit, not the author's. */
        PARSE_FAILED,
        /** The model index could not be built (an unresolved include, an unreadable model). */
        MODEL_INDEX_FAILED,
        /** The text parsed and carried no comparison node, so there was nothing to pin. */
        NO_COMPARISON,
        /** Parsed with comparisons, but no unique candidate operator — the union fix IS the correct answer. */
        NO_UNIQUE_CANDIDATE
    }

    /** Enriched diagnostics together with the reason they were, or were not, narrowed. */
    public record Result(List<Diagnostic> diagnostics, Outcome outcome) {}

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
        return resultOf(diagnostics, conditionText, baseGroup, modelJson, resolver).diagnostics();
    }

    /**
     * The same enrichment, reporting {@linkplain Outcome why} it narrowed or did not. Callers that only publish
     * diagnostics use {@link #enrich(List, String, String, String, ModelResolver)}; this exists so a parser gap
     * is observable instead of silent.
     */
    public static Result resultOf(List<Diagnostic> diagnostics, String conditionText, String baseGroup,
            String modelJson, ModelResolver resolver) {
        // Skip the catalog load + model expansion when there is nothing to enrich (a clean `rule check` has no
        // diagnostics) — the instance enrich's guard, hoisted before the eager argument builds it can't reach.
        if (diagnostics.isEmpty() || conditionText == null || conditionText.isBlank()) {
            return new Result(diagnostics, Outcome.NO_COMPARISON);
        }
        ModelIndex index;
        try {
            index = ModelIndex.of(modelJson, resolver);
        } catch (RuntimeException indexFailure) {
            return new Result(diagnostics, Outcome.MODEL_INDEX_FAILED);
        }
        return new TextConditionCorrective().enrichWithOutcome(diagnostics, conditionText, baseGroup, index);
    }

    /** The {@link Outcome} alone, for a caller that already has the diagnostics. */
    public static Outcome outcomeOf(List<Diagnostic> diagnostics, String conditionText, String baseGroup,
            String modelJson, ModelResolver resolver) {
        return resultOf(diagnostics, conditionText, baseGroup, modelJson, resolver).outcome();
    }

    /**
     * Upgrade each corrective-eligible KERNEL diagnostic's union fix to the operand-matched corrective, when
     * the candidate {@code conditionText} (paths relative to {@code baseGroup}) tolerantly parses and its
     * pinned comparison's operands classify against {@code index}. Any failure leaves the diagnostic as-is.
     */
    public List<Diagnostic> enrich(List<Diagnostic> diagnostics, String conditionText, String baseGroup,
            ModelIndex index) {
        return enrichWithOutcome(diagnostics, conditionText, baseGroup, index).diagnostics();
    }

    /**
     * The same enrichment, reporting {@linkplain Outcome why}. A parse failure and "the union fix is correct"
     * both return the diagnostics unchanged, so only the outcome tells them apart.
     */
    public Result enrichWithOutcome(List<Diagnostic> diagnostics, String conditionText, String baseGroup,
            ModelIndex index) {
        if (conditionText == null || conditionText.isBlank() || diagnostics.isEmpty()) {
            return new Result(diagnostics, Outcome.NO_COMPARISON);
        }
        Map<String, List<Cond.Cmp>> byOperator;
        try {
            byOperator = comparisonsByOperator(conditionText, baseGroup);
        } catch (RuntimeException parseFailure) {
            return new Result(diagnostics, Outcome.PARSE_FAILED);
        }
        if (byOperator.isEmpty()) {
            return new Result(diagnostics, Outcome.NO_COMPARISON);
        }
        List<Diagnostic> upgraded = diagnostics.stream().map(d -> upgrade(d, byOperator, index)).toList();
        return new Result(upgraded,
                upgraded.equals(diagnostics) ? Outcome.NO_UNIQUE_CANDIDATE : Outcome.UPGRADED);
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
        // Establishing the owner obliges us to recompute BOTH fields. The enricher deliberately withholds
        // `explain` while ownership is unresolved, so passing the incoming one through would preserve that
        // emptiness on the very path that just resolved it (and, before the enricher was made honest, would have
        // preserved an explanation chosen while ambiguous). Same recompute `lint.SpanResolver` does on the
        // typed-AST path.
        Optional<String> explain = catalog.byId(pinned.get(0))
                .map(OperatorEntry::meaning)
                .filter(meaning -> meaning != null && !meaning.isBlank())
                .or(d::explain);
        return new Diagnostic(d.severity(), d.source(), d.code(), d.summary(), where, fix, explain);
    }

    /** Every comparison node of the (tolerantly parsed) condition, grouped by its catalog operator-id. Empty
     *  on a parse failure — the caller then keeps the union fix. */
    /**
     * The candidate's comparisons, keyed by operator id. <b>Throws</b> on a parse failure rather than answering
     * with an empty map: an empty map means "parsed, no comparison", and conflating that with "unreadable" is the
     * defect {@code TextCorrectiveOutcomeTest} pins. The single caller classifies the throw as
     * {@link Outcome#PARSE_FAILED} and still returns the diagnostics untouched.
     */
    private Map<String, List<Cond.Cmp>> comparisonsByOperator(String conditionText, String baseGroup) {
        Cond ast = ParserKt.parseCondition(conditionText, baseGroup, ParserKt.getDEFAULT_RESOLVER());
        Map<String, List<Cond.Cmp>> byOperator = new LinkedHashMap<>();
        collect(ast, byOperator);
        return byOperator;
    }

    private void collect(Cond c, Map<String, List<Cond.Cmp>> out) {
        switch (c) {
            case Cond.Cmp cmp -> {
                out.computeIfAbsent(operatorId(cmp.getOp()), k -> new ArrayList<>()).add(cmp);
                // …and into its OPERANDS: a comparison can be nested inside one, via a starred operand's filter.
                collectIn(cmp.getLeft(), out);
                collectIn(cmp.getRight(), out);
            }
            case Cond.And a -> { collect(a.getLeft(), out); collect(a.getRight(), out); }
            case Cond.Or o -> { collect(o.getLeft(), out); collect(o.getRight(), out); }
            case Cond.Not n -> collect(n.getC(), out);
            case Cond.Pattern p -> { collectIn(p.getValue(), out); collectIn(p.getPattern(), out); }
            case Cond.Tolerance t -> { collectIn(t.getLeft(), out); collectIn(t.getRight(), out); }
            case Cond.Pred pred -> pred.getArgs().forEach(arg -> collectIn(arg, out));
            case Cond.ValueListQuant q -> {
                q.getFields().forEach(field -> collectIn(field, out));
                q.getValues().forEach(value -> collectIn(value, out));
            }
            case Cond.Custom ignored -> { }   // an opaque host predicate: a name, no sub-tree
        }
    }

    /**
     * Descend into an <b>expression</b> for the comparisons it contains. A {@code Cond} is not the only place one
     * lives: a starred operand carries an optional per-row {@code Having} filter, which is a {@code Cond} hanging
     * off an {@code Expr}.
     *
     * <p>Missing this made the uniqueness test — "the operator appears exactly once, so it must be the culprit" —
     * unsound rather than merely incomplete. With a filter's comparison uncounted, a condition using {@code <} both
     * outside and inside the filter looked like it used {@code <} once, so the OUTER comparison was pinned and given
     * the operand-matched corrective plus its operator's meaning, even when the filter's comparison was what the
     * kernel refused. Counting them makes the case correctly ambiguous, and an ambiguous case keeps the hedged
     * union fix — a defect turned back into an honest "I don't know".
     */
    private void collectIn(Expr e, Map<String, List<Cond.Cmp>> out) {
        // NOTE the absence of `default`. Both switches here used to have one, and that is the mechanism behind three
        // successive misses: `Cond` and `Expr` are SEALED, so an exhaustive switch would have named every variant the
        // walk was skipping — but `default -> { }` silences exactly that check and turns each omission into a comment
        // asserting the variants "carry no nested expression". They did. `Pattern`/`Tolerance` were missed first,
        // then `Star.having`, then `Pred`/`ValueListQuant` — three rounds of the same defect, each one a comment away
        // from being impossible. Adding a variant upstream must now break this file, which is the point.
        switch (e) {
            case Expr.Star star -> {
                if (star.getHaving() != null) {
                    collect(star.getHaving(), out);
                }
            }
            case Expr.Arith arith -> { collectIn(arith.getLeft(), out); collectIn(arith.getRight(), out); }
            case Expr.Func func -> func.getArgs().forEach(arg -> collectIn(arg, out));
            case Expr.Field ignored -> { }
            case Expr.NumLit ignored -> { }
            case Expr.StrLit ignored -> { }
            case Expr.BoolLit ignored -> { }
            case Expr.Const ignored -> { }
            case Expr.SemanticIndex ignored -> { }
            case Expr.EnumCategory ignored -> { }
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
