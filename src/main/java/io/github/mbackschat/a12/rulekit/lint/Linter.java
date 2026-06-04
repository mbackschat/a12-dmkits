package io.github.mbackschat.a12.rulekit.lint;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.mgmtp.a12.model.notification.Severity;

import io.github.mbackschat.a12.rulekit.dsl.Condition;
import io.github.mbackschat.a12.rulekit.dsl.FieldPresenceCondition;
import io.github.mbackschat.a12.rulekit.dsl.LogicCondition;
import io.github.mbackschat.a12.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.rulekit.validate.DiagnosticSource;

/**
 * The F4a <b>lint layer</b> — valid-but-suspicious findings over the typed AST (proposal §F4a;
 * study finding 7). This is the diagnostic kind {@code checkConsistency} cannot give an LLM: the
 * kernel only reports structural <em>errors</em>, so "compiles and is accepted, but probably not
 * what you meant" must come from rulekit.
 *
 * <p>First increment — three pure-AST structural heuristics, each {@code source = LINT},
 * {@code severity = WARNING}, with an {@code RK_*} code and the offending logic operator in
 * {@code where.operator}. Model-context lints (an unguarded negative condition under a repeatable
 * group, inverted requiredness) need the rule's binding and are a later increment.
 */
public final class Linter {

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
