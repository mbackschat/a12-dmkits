package io.github.mbackschat.a12.dm.rulekit.lint;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.mgmtp.a12.model.notification.Severity;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation;
import io.github.mbackschat.a12.dm.rulekit.dsl.CompareOp;
import io.github.mbackschat.a12.dm.rulekit.dsl.ComparisonCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.CustomConditionRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListEntityListCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListPredicate;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupScopedFieldFill;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListValueListCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldPresenceCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldValue;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupListPredicate;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupPresenceCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.LogicCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.PatternCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.CustomTypeValidity;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateValidity;
import io.github.mbackschat.a12.dm.rulekit.dsl.RepetitionNotUniqueCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.ValueListCondition;
import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.DiagnosticSource;

/**
 * The F4a <b>lint layer</b> — valid-but-suspicious findings over the typed AST (proposal §F4a;
 * study finding 7). This is the diagnostic kind {@code checkConsistency} cannot give an LLM: the
 * kernel only reports structural <em>errors</em>, so "compiles and is accepted, but probably not
 * what you meant" must come from rulekit.
 *
 * <p>Two tiers: <b>pure-AST</b> structural heuristics (run on any {@link Condition}) and
 * <b>model-context</b> heuristics (run on a {@link Rule} bound to a {@link BoundModel}, where the
 * rule's iteration is known). Each diagnostic is {@code source = LINT}, {@code severity = WARNING},
 * with an {@code RK_*} code and the offending operator in {@code where.operator}.
 */
public final class Linter {

    /** Positive presence predicates that count as guarding a negative condition under iteration. */
    private static final Set<String> POSITIVE_PRESENCE = Set.of(
            "FieldFilled", "GroupFilled",
            "AllFieldsFilled", "AtLeastOneFieldFilled",
            "AllGroupsFilled", "AtLeastOneGroupFilled");

    /** Lint {@code condition} with no rule context (the {@code where.rule} is left empty). */
    public List<Diagnostic> lint(Condition condition) {
        return lint("", condition);
    }

    /** Lint {@code condition} as the error condition of the rule at {@code rulePath}. */
    public List<Diagnostic> lint(String rulePath, Condition condition) {
        List<Diagnostic> out = new ArrayList<>();
        visit(condition, null, rulePath, out);
        unguardedNumberComparison(condition, rulePath, out);
        return List.copyOf(out);
    }

    /**
     * Lint {@code rule} against {@code model} — the pure-AST lints plus the model-context ones that
     * need the rule's iteration (whether its error field lies under a repeatable group).
     */
    public List<Diagnostic> lint(BoundModel model, Rule rule) {
        String rulePath = rule.group().path() + "/" + rule.name();
        List<Diagnostic> out = new ArrayList<>(lint(rulePath, rule.condition()));
        invertedRequiredness(rule.condition(), rule.errorField().path(), rulePath, out);
        if (model.iteratesForErrorField(rule.errorField().path())) {
            negativeUnderIteration(rule.condition(), rule.errorField().path(), rule.group().path(),
                    model, rulePath, out);
        }
        return List.copyOf(out);
    }

    /**
     * Lint a candidate condition for a rule whose error field is {@code errorFieldPath}, without a
     * built {@link Rule} — the entry the CLI's {@code add} uses on a condition ingested from text
     * (F10), so the model-context lints (inverted requiredness, negative-under-iteration) can fire on
     * the BA path. The rule's group is taken as the error field's parent (the {@code add} default).
     */
    public List<Diagnostic> lint(BoundModel model, String errorFieldPath, Condition condition) {
        List<Diagnostic> out = new ArrayList<>(lint("", condition));
        invertedRequiredness(condition, errorFieldPath, "", out);
        if (model.iteratesForErrorField(errorFieldPath)) {
            int slash = errorFieldPath.lastIndexOf('/');
            String groupPath = slash > 0 ? errorFieldPath.substring(0, slash) : errorFieldPath;
            negativeUnderIteration(condition, errorFieldPath, groupPath, model, "", out);
        }
        return List.copyOf(out);
    }

    /**
     * An {@code errorCondition} is the <em>error scenario</em> (true ⇒ the data is invalid), so a
     * requiredness rule must fire on <em>absence</em>. A bare {@code FieldFilled(E)} on the rule's
     * own error field {@code E} fires on <em>presence</em> — flagging valid-looking data, almost
     * always an inversion ("did you mean {@code FieldNotFilled}?"). Conservative: only the bare
     * (sole-predicate) form — a guarded {@code FieldFilled(E) And <ctx>} is a legitimate
     * "{@code E} must be empty in this context" rule, so it is left alone.
     */
    private void invertedRequiredness(Condition condition, String errorFieldPath, String rulePath,
            List<Diagnostic> out) {
        if (condition instanceof FieldPresenceCondition f
                && f.filled()
                && f.field().path().equals(errorFieldPath)) {
            out.add(new Diagnostic(Severity.WARNING, DiagnosticSource.LINT,
                    Optional.of("RK_INVERTED_REQUIREDNESS"),
                    "the rule errors when '" + f.field().path() + "' IS filled — an errorCondition fires on"
                            + " the invalid case, so a requiredness rule should fire on absence",
                    new Diagnostic.Where(rulePath, Optional.of("FieldFilled")),
                    Optional.of("FieldNotFilled(" + f.field().path() + ")"),
                    Optional.of("an errorCondition is the error scenario (true ⇒ invalid); FieldFilled is true"
                            + " when the field HAS a value, so this flags present values, not missing ones —"
                            + " did you mean FieldNotFilled?")));
        }
    }

    /**
     * An iterating rule (error field under a repeatable group) whose condition carries a negative
     * presence predicate with <em>no</em> positive presence guard anywhere — the case the kernel
     * rejects with {@code MVK_NEG_CONDITION_IN_ITERATION} (KERNEL-FINDINGS §9). Conservative by
     * design: the kernel's full positive/negative classification is context-sensitive (~800 lines,
     * kernel-delegated to F4), so this fires only on the clearly-unguarded shape.
     */
    private void negativeUnderIteration(Condition condition, String errorFieldPath, String ruleGroupPath,
            BoundModel model, String rulePath, List<Diagnostic> out) {
        List<String[]> negatives = new ArrayList<>();
        boolean[] hasPositiveGuard = {false};
        collectPresence(condition, negatives, hasPositiveGuard);
        if (negatives.isEmpty() || hasPositiveGuard[0]) {
            return;
        }
        String[] negative = negatives.get(0);
        String guardGroup = model.nearestRepeatableAncestor(errorFieldPath)
                .orElse(ruleGroupPath);
        out.add(new Diagnostic(Severity.WARNING, DiagnosticSource.LINT,
                Optional.of("RK_NEG_CONDITION_IN_ITERATION"),
                "this rule iterates (error field '" + errorFieldPath + "' is under a repeatable group)"
                        + " and uses the negative condition " + negative[0] + "(" + negative[1] + ") with no"
                        + " positive presence guard — the kernel rejects unguarded negatives in iteration",
                new Diagnostic.Where(rulePath, Optional.of(negative[0])),
                Optional.of("guard it, e.g. GroupFilled(" + guardGroup + ") And <the existing condition>"),
                Optional.of("a negative condition under iteration must be guarded by a positive presence check so"
                        + " the kernel can bound the iteration (MVK_NEG_CONDITION_IN_ITERATION; KERNEL-FINDINGS §9)")));
    }

    /** Collect bare negative presence predicates and note whether any positive presence guard exists. */
    private void collectPresence(Condition condition, List<String[]> negatives, boolean[] hasPositiveGuard) {
        switch (condition) {
            case FieldPresenceCondition f -> {
                if (f.filled()) {
                    hasPositiveGuard[0] = true;
                } else {
                    negatives.add(new String[] {"FieldNotFilled", f.field().path()});
                }
            }
            case GroupPresenceCondition g -> {
                if (g.filled()) {
                    hasPositiveGuard[0] = true;
                } else {
                    negatives.add(new String[] {"GroupNotFilled", g.group().path()});
                }
            }
            case FieldListPredicate p -> hasPositiveGuard[0] |= POSITIVE_PRESENCE.contains(p.operatorId());
            case GroupScopedFieldFill p -> hasPositiveGuard[0] |= POSITIVE_PRESENCE.contains(p.operatorId());
            case GroupListPredicate p -> hasPositiveGuard[0] |= POSITIVE_PRESENCE.contains(p.operatorId());
            case LogicCondition l -> {
                collectPresence(l.left(), negatives, hasPositiveGuard);
                collectPresence(l.right(), negatives, hasPositiveGuard);
            }
            case ComparisonCondition ignored -> { }
            case PatternCondition ignored -> { }
            case ValueListCondition ignored -> { }
            case FieldListValueListCondition ignored -> { }
            case FieldListEntityListCondition ignored -> { }
            case RepetitionNotUniqueCondition ignored -> { }
            case CustomConditionRef ignored -> { }
            case DateValidity ignored -> { }
            case CustomTypeValidity ignored -> { }
        }
    }

    /**
     * An <em>ordering</em> comparison ({@code <} / {@code <=} / {@code >} / {@code >=}) of a
     * <b>number</b> field against a numeric constant, with no presence guard on that field, where an
     * <em>empty</em> field would actually fire. An unspecified number defaults to {@code 0} in a
     * comparison (KERNEL-SEMANTICS §2), so {@code [Amount] < 100} fires on an EMPTY Amount
     * ({@code 0 < 100}) — usually unintended. Conservative on three axes: only ordering ops (not
     * {@code ==}/{@code !=}); only field-vs-constant (field-vs-field like {@code [A] < [B]} and
     * field-vs-aggregate like {@code [Q] < Sum(...)} are left alone); and only when {@code 0} satisfies
     * the comparison (so {@code [Amount] > 100} — which an empty value can't trip — is not flagged).
     * Number is special: empty string/date/enum comparisons are simply not evaluated, so they need no
     * guard. Pure-AST (the field kind is in the AST), so it fires on every lint entry point.
     */
    private void unguardedNumberComparison(Condition condition, String rulePath, List<Diagnostic> out) {
        Set<String> guarded = new HashSet<>();
        collectGuardedFields(condition, guarded);
        collectUnguardedNumberCompares(condition, guarded, rulePath, out);
    }

    /** Field paths positively guarded somewhere in the condition (FieldFilled / AllFieldsFilled). */
    private void collectGuardedFields(Condition c, Set<String> guarded) {
        switch (c) {
            case FieldPresenceCondition f -> {
                if (f.filled()) {
                    guarded.add(f.field().path());
                }
            }
            case FieldListPredicate p -> {
                if ("AllFieldsFilled".equals(p.operatorId())) {
                    for (var operand : p.fields()) {
                        if (operand instanceof FieldRef fr) {
                            guarded.add(fr.path());
                        }
                    }
                }
            }
            case LogicCondition l -> {
                collectGuardedFields(l.left(), guarded);
                collectGuardedFields(l.right(), guarded);
            }
            default -> { }
        }
    }

    private void collectUnguardedNumberCompares(Condition c, Set<String> guarded, String rulePath,
            List<Diagnostic> out) {
        switch (c) {
            case LogicCondition l -> {
                collectUnguardedNumberCompares(l.left(), guarded, rulePath, out);
                collectUnguardedNumberCompares(l.right(), guarded, rulePath, out);
            }
            case ComparisonCondition cmp -> maybeFlagComparison(cmp, guarded, rulePath, out);
            default -> { }
        }
    }

    private void maybeFlagComparison(ComparisonCondition cmp, Set<String> guarded, String rulePath,
            List<Diagnostic> out) {
        if (!isOrdering(cmp.operator())) {
            return;
        }
        String leftField = numberFieldPath(cmp.left());
        String rightField = numberFieldPath(cmp.right());
        BigDecimal leftLit = numberLiteralValue(cmp.left());
        BigDecimal rightLit = numberLiteralValue(cmp.right());

        // Case A — number field vs numeric constant: precise. Substitute 0 for the field and flag only
        // when 0 actually fires (e.g. [X] < 100 fires; [X] > 100 doesn't).
        if (leftField != null && rightLit != null) {
            if (!guarded.contains(leftField) && holds(BigDecimal.ZERO, cmp.operator(), rightLit)) {
                flagUnguardedNumber(leftField, cmp, rulePath, out);
            }
            return;
        }
        if (rightField != null && leftLit != null) {
            if (!guarded.contains(rightField) && holds(leftLit, cmp.operator(), BigDecimal.ZERO)) {
                flagUnguardedNumber(rightField, cmp, rulePath, out);
            }
            return;
        }

        // Case B — number field vs a numeric aggregate/expression (Sum/Count/Length/… — the T4 shape).
        // The aggregate's value is unknown, so we can't prove 0 fires; but the empty-as-0 substitution is
        // a real trap, so flag advisorily. (Field-vs-field stays out of scope — both empties default to 0
        // symmetrically, so it rarely surprises; only an aggregate operand gets this treatment.)
        if (leftField != null && isComplexNumberOperand(cmp.right()) && !guarded.contains(leftField)) {
            flagUnguardedNumber(leftField, cmp, rulePath, out);
        } else if (rightField != null && isComplexNumberOperand(cmp.left()) && !guarded.contains(rightField)) {
            flagUnguardedNumber(rightField, cmp, rulePath, out);
        }
    }

    private void flagUnguardedNumber(String field, ComparisonCondition cmp, String rulePath, List<Diagnostic> out) {
        out.add(new Diagnostic(Severity.WARNING, DiagnosticSource.LINT,
                Optional.of("RK_UNGUARDED_NUMBER_COMPARISON"),
                "'" + field + "' is a number compared with an ordering operator but its presence isn't"
                        + " guarded — an empty number reads as 0, so the rule also fires on an empty '" + field + "'",
                new Diagnostic.Where(rulePath, Optional.of(cmp.operatorId())),
                Optional.of("FieldFilled(" + field + ") And <the comparison>"),
                Optional.of("an unspecified number defaults to 0 in a comparison (KERNEL-SEMANTICS §2) — unlike an"
                        + " empty string/date/enum, which is not evaluated — so guard the number with FieldFilled"
                        + " if an empty value should not trip the rule")));
    }

    /** A numeric operand that is neither a plain field nor a literal — an aggregate/function (Sum, Count, Length, …). */
    private static boolean isComplexNumberOperand(Calculation calc) {
        return calc.kind() == ValueKind.NUMBER
                && !(calc instanceof FieldValue) && !(calc instanceof NumberLiteral);
    }

    private static boolean isOrdering(CompareOp op) {
        return op == CompareOp.LT || op == CompareOp.LE || op == CompareOp.GT || op == CompareOp.GE;
    }

    /** Whether {@code l op r} holds (used to ask "would an empty field, read as 0, fire?"). */
    private static boolean holds(BigDecimal l, CompareOp op, BigDecimal r) {
        int c = l.compareTo(r);
        return switch (op) {
            case LT -> c < 0;
            case LE -> c <= 0;
            case GT -> c > 0;
            case GE -> c >= 0;
            // not ordering — guarded by isOrdering, never reached
            case EQ, NE, TOLERANCE_1, TOLERANCE_2, TOLERANCE_5, TOLERANCE_10 -> false;
        };
    }

    private static String numberFieldPath(Calculation calc) {
        return calc instanceof FieldValue fv && fv.field().kind() == ValueKind.NUMBER
                ? fv.field().path() : null;
    }

    private static BigDecimal numberLiteralValue(Calculation calc) {
        return calc instanceof NumberLiteral n ? n.value() : null;
    }

    /**
     * Analyze each maximal same-operator And/Or chain exactly once (at its root — where the parent
     * operator differs), then descend into the operands so nested chains of the other operator are
     * reached too.
     */
    private void visit(Condition condition, LogicCondition.Op parentOp, String rulePath, List<Diagnostic> out) {
        if (!(condition instanceof LogicCondition logic)) {
            return;
        }
        if (logic.op() != parentOp) {
            analyze(logic.op(), flatten(logic, logic.op()), rulePath, out);
        }
        visit(logic.left(), logic.op(), rulePath, out);
        visit(logic.right(), logic.op(), rulePath, out);
    }

    /** Flatten a chain of {@code op} into its operands (the leaves where the operator changes). */
    private List<Condition> flatten(Condition condition, LogicCondition.Op op) {
        if (condition instanceof LogicCondition logic && logic.op() == op) {
            List<Condition> operands = new ArrayList<>(flatten(logic.left(), op));
            operands.addAll(flatten(logic.right(), op));
            return operands;
        }
        return List.of(condition);
    }

    private void analyze(LogicCondition.Op op, List<Condition> operands, String rulePath, List<Diagnostic> out) {
        reportDuplicates(op, operands, rulePath, out);
        reportPresencePairs(op, operands, rulePath, out);
    }

    /** A structurally identical operand appearing twice in the same And/Or chain has no effect. */
    private void reportDuplicates(LogicCondition.Op op, List<Condition> operands, String rulePath, List<Diagnostic> out) {
        List<Condition> seen = new ArrayList<>();
        for (Condition operand : operands) {
            if (seen.contains(operand)) {
                out.add(lint("RK_REDUNDANT_OPERAND", op,
                        "the same operand appears more than once in this " + op.name()
                                + " — the duplicate has no effect",
                        Optional.of("remove the duplicate operand"),
                        Optional.of("a repeated And/Or operand is dead weight; And C And C ≡ C, Or C Or C ≡ C"),
                        rulePath));
                continue;
            }
            seen.add(operand);
        }
    }

    /**
     * A field appearing as both {@code FieldFilled} and {@code FieldNotFilled} in the same chain:
     * under {@code And} it is a contradiction (never fires); under {@code Or} it is a false
     * tautology (a formal-error value satisfies neither — they are not strict complements).
     */
    private void reportPresencePairs(LogicCondition.Op op, List<Condition> operands, String rulePath, List<Diagnostic> out) {
        for (int i = 0; i < operands.size(); i++) {
            if (!(operands.get(i) instanceof FieldPresenceCondition a)) {
                continue;
            }
            for (int j = i + 1; j < operands.size(); j++) {
                if (operands.get(j) instanceof FieldPresenceCondition b
                        && a.field().path().equals(b.field().path())
                        && a.filled() != b.filled()) {
                    out.add(presencePairLint(op, a.field().path(), rulePath));
                }
            }
        }
    }

    private Diagnostic presencePairLint(LogicCondition.Op op, String fieldPath, String rulePath) {
        if (op == LogicCondition.Op.AND) {
            return lint("RK_CONTRADICTORY_PRESENCE", op,
                    "'" + fieldPath + "' is required to be both filled and not filled — this And can never be true,"
                            + " so the rule never fires",
                    Optional.of("drop one side — a field cannot be both filled and not filled"),
                    Optional.of("FieldFilled(X) And FieldNotFilled(X) is unsatisfiable"),
                    rulePath);
        }
        return lint("RK_PRESENCE_NOT_COMPLEMENTARY", op,
                "'" + fieldPath + "' is tested as both filled and not filled in this Or — that is not the"
                        + " tautology it looks like",
                Optional.empty(),
                Optional.of("FieldFilled and FieldNotFilled are not strict complements: a field holding a"
                        + " formal-error value satisfies neither, so this Or is not always true"),
                rulePath);
    }

    private Diagnostic lint(String code, LogicCondition.Op op, String summary,
            Optional<String> fix, Optional<String> explain, String rulePath) {
        return new Diagnostic(Severity.WARNING, DiagnosticSource.LINT, Optional.of(code), summary,
                new Diagnostic.Where(rulePath, Optional.of(op.operatorId())), fix, explain);
    }
}
