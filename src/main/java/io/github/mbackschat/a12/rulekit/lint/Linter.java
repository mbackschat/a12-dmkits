package io.github.mbackschat.a12.rulekit.lint;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.mgmtp.a12.model.notification.Severity;

import io.github.mbackschat.a12.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.rulekit.dsl.ComparisonCondition;
import io.github.mbackschat.a12.rulekit.dsl.Condition;
import io.github.mbackschat.a12.rulekit.dsl.CustomConditionRef;
import io.github.mbackschat.a12.rulekit.dsl.FieldListPredicate;
import io.github.mbackschat.a12.rulekit.dsl.FieldListValueListCondition;
import io.github.mbackschat.a12.rulekit.dsl.FieldPresenceCondition;
import io.github.mbackschat.a12.rulekit.dsl.GroupListPredicate;
import io.github.mbackschat.a12.rulekit.dsl.GroupPresenceCondition;
import io.github.mbackschat.a12.rulekit.dsl.LogicCondition;
import io.github.mbackschat.a12.rulekit.dsl.PatternCondition;
import io.github.mbackschat.a12.rulekit.dsl.RepetitionNotUniqueCondition;
import io.github.mbackschat.a12.rulekit.dsl.Rule;
import io.github.mbackschat.a12.rulekit.dsl.ValueListCondition;
import io.github.mbackschat.a12.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.rulekit.validate.DiagnosticSource;

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
        return List.copyOf(out);
    }

    /**
     * Lint {@code rule} against {@code model} — the pure-AST lints plus the model-context ones that
     * need the rule's iteration (whether its error field lies under a repeatable group).
     */
    public List<Diagnostic> lint(BoundModel model, Rule rule) {
        String rulePath = rule.group().path() + "/" + rule.name();
        List<Diagnostic> out = new ArrayList<>(lint(rulePath, rule.condition()));
        if (model.iteratesForErrorField(rule.errorField().path())) {
            negativeUnderIteration(rule, model, rulePath, out);
        }
        return List.copyOf(out);
    }

    /**
     * An iterating rule (error field under a repeatable group) whose condition carries a negative
     * presence predicate with <em>no</em> positive presence guard anywhere — the case the kernel
     * rejects with {@code MVK_NEG_CONDITION_IN_ITERATION} (KERNEL-FINDINGS §9). Conservative by
     * design: the kernel's full positive/negative classification is context-sensitive (~800 lines,
     * kernel-delegated to F4), so this fires only on the clearly-unguarded shape.
     */
    private void negativeUnderIteration(Rule rule, BoundModel model, String rulePath, List<Diagnostic> out) {
        List<String[]> negatives = new ArrayList<>();
        boolean[] hasPositiveGuard = {false};
        collectPresence(rule.condition(), negatives, hasPositiveGuard);
        if (negatives.isEmpty() || hasPositiveGuard[0]) {
            return;
        }
        String[] negative = negatives.get(0);
        String guardGroup = model.nearestRepeatableAncestor(rule.errorField().path())
                .orElse(rule.group().path());
        out.add(new Diagnostic(Severity.WARNING, DiagnosticSource.LINT,
                Optional.of("RK_NEG_CONDITION_IN_ITERATION"),
                "this rule iterates (error field '" + rule.errorField().path() + "' is under a repeatable group)"
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
            case GroupListPredicate p -> hasPositiveGuard[0] |= POSITIVE_PRESENCE.contains(p.operatorId());
            case LogicCondition l -> {
                collectPresence(l.left(), negatives, hasPositiveGuard);
                collectPresence(l.right(), negatives, hasPositiveGuard);
            }
            case ComparisonCondition ignored -> { }
            case PatternCondition ignored -> { }
            case ValueListCondition ignored -> { }
            case FieldListValueListCondition ignored -> { }
            case RepetitionNotUniqueCondition ignored -> { }
            case CustomConditionRef ignored -> { }
        }
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
