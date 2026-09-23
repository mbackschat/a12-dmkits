package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.List;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@code Sum(…)} / {@code MaxValue(…)} / {@code MinValue(…)} — numeric aggregates over an entity
 * list, typically a flattened repetition list (KERNEL-SEMANTICS §9: {@code Sum} substitutes 0 for
 * unspecified fields; {@code MaxValue}/{@code MinValue} ignore them). Kernel slot rules (verified
 * 2026-06-04, KERNEL-FINDINGS §9): number operands only ({@code MVK_NO_NUMBER} for Sum,
 * {@code MVK_NOT_SORTABLE} for Max/Min behind a leader that is not date-like — see {@code requireNumber}), more
 * than one field or a repetition list ({@code MVK_PARAMSIZE_INVALIDN}), no repeated DIRECT field
 * ({@code MVK_DUPLICATE_PARAM1}) — an <em>identical</em> wildcarded operand may repeat, mirroring the kernel
 * skipping wildcarded refs in {@code checkDuplicatesInDirectRefs} — all pre-checked here. What the star
 * exempts is that one gate and no more: a star does not make an operand exempt from duplicate checking as
 * such, and the indirect-overlap arm ({@code MVK_DUPLICATE_PARAM2}) still refuses two operands where one
 * strictly contains the other, whichever side carries it. That side-symmetry is measured in both argument
 * orders by {@code rulekit.validate.laws.AggregateGroupOperandLawsTest} for {@code Sum} — the operator this
 * record's own {@code AggOp} covers first — and by a probe pair per operator in both orders for the rest of
 * the entity-list family (the operator catalog's {@code <Op>.group-containment} claim probes)
 * ({@link AggregateOperand}).
 *
 * <p>An operand may also be a <b>group scope</b> ({@link GroupListRef}), which the kernel expands to the
 * group's contained fields; the two gates that behave differently for it, and why they are delegated rather
 * than pre-checked, are on {@link AggregateOperand}.
 */
import io.github.mbackschat.a12.dm.core.KernelCode;
import io.github.mbackschat.a12.dm.core.Refusals;

public record ListAggregate(AggOp op, List<AggregateOperand> operands) implements Calculation {

    public enum AggOp {
        SUM("Sum"),
        MAX_VALUE("MaxValue"),
        MIN_VALUE("MinValue");

        private final String operatorId;

        AggOp(String operatorId) {
            this.operatorId = operatorId;
        }

        public String operatorId() {
            return operatorId;
        }
    }

    public ListAggregate {
        operands = List.copyOf(operands);
        // Ahead of the kind loop, because the kernel's own routine checks duplicates first: it answers
        // MVK_DUPLICATE_PARAM2 for a group scope beside a field inside it even when that field is the wrong
        // kind, and reporting the kind error there would disagree with it on input both refuse.
        EntityListRoute.requireShape(op.operatorId(), operands);
        for (AggregateOperand o : operands) {
            switch (o) {
                case FieldOperand f -> requireNumber(op, operands, f);
                // A group carries no kind of its own: the rule applies to the fields the kernel EXPANDS it
                // to, whose extent recurses into nested subgroups. Delegated rather than re-implemented —
                // see AggregateOperand. An index-selected group is the same shape (still no kind of its
                // own — the index only picks WHICH repetition, not what it expands to).
                case GroupListRef ignored -> { }
                case IndexedGroupValue ignored -> { }
            }
        }
    }

    /**
     * {@code Sum} asks every operand the one number question and cites {@code MVK_NO_NUMBER} wherever it
     * fails. {@code MaxValue}/{@code MinValue} ask it only on the route a non-date leader selects
     * ({@link EntityListRoute}): behind a date-like leader A12 walks the list as dates instead
     * ({@code MVK_DATE_AND_NONDATE}, or admits it as a date extremum), and behind a group leader the route is
     * hidden, so there the refusal stands and its class is withheld.
     */
    private static void requireNumber(AggOp op, List<AggregateOperand> operands, FieldOperand operand) {
        if (operand.kind() == ValueKind.NUMBER) {
            return;
        }
        String problem = op.operatorId() + " takes number fields; " + operand.renderedPath() + " is "
                + operand.kind();
        if (operand.kind() == ValueKind.UNKNOWN) {
            // A dangling type reference: no legal model carries it, so no class has been measured for it.
            throw new IllegalArgumentException(problem);
        }
        if (op == AggOp.SUM) {
            throw Refusals.of(KernelCode.MVK_NO_NUMBER, problem);
        }
        if (EntityListRoute.visiblyNonDateRoute(operands)) {
            throw Refusals.of(KernelCode.MVK_NOT_SORTABLE, problem);
        }
        boolean dateLed = EntityListRoute.visibleLeaderKind(operands).filter(EntityListRoute::isDateLike)
                .isPresent();
        throw new IllegalArgumentException(problem + (dateLed
                ? "; a list led by a date-like field is a date extremum, built with Dsl.latestOf/earliestOf"
                : ""));
    }

    /**
     * Whether one operand alone satisfies the param law: a repetition list or a group scope does, and so does an
     * index-selected group, which A12 admits alone under every aggregate that takes a group
     * ({@code validate.laws.SemanticIndexGroupOperandCarrierLawsTest}).
     */
    static boolean satisfiesArityAlone(AggregateOperand operand) {
        return operand instanceof FieldListRef || operand instanceof GroupListRef
                || operand instanceof IndexedGroupValue;
    }


    @Override
    public ValueKind kind() {
        return ValueKind.NUMBER;
    }

    public String operatorId() {
        return op.operatorId();
    }
}
