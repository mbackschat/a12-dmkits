package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr;

/**
 * KERNEL-FINDINGS — aggregate/count slot laws.
 *
 * <p>The entity-list duplicate gate ({@code CheckEntityListenUtils.checkParamsReferencedOnlyOnce}) has two
 * arms: {@code checkDuplicatesInDirectRefs} rejects a repeated DIRECT (non-wildcard) reference
 * ({@code MVK_DUPLICATE_PARAM1}) but — outside HModel — <b>continues past {@code ref.hasWildCards()}</b>, so a
 * plain or filtered repetition list may occupy several authored slots; {@code checkDuplicatesInIndirectRefs}
 * still rejects a group/descendant overlap ({@code MVK_DUPLICATE_PARAM2}). This holds for the whole entity-list
 * family routed through {@code getRefList} — {@code Sum}, {@code MaxValue}, {@code MinValue},
 * {@code FirstFilledValue}, {@code NumberOfFilledFields}, {@code NumberOfDifferentValues} (SPEC-2026-07-22-07).
 *
 * <p>{@code NumberOfValueInFields} is the separate value-list operator that forbids listing a field more than
 * once ({@code MVK_DUPLICATE_PARAM1} directly, {@code MVK_DUPLICATE_PARAM2} via a group) — it does not share the
 * entity-list wildcard-skip.
 *
 * <p><b>Typed-surface boundary.</b> The repeated-wildcard <em>acceptance</em> is authored through the typed DSL
 * (a {@link FieldListRef} operand). The indirect group/descendant overlap is NOT expressible through the typed
 * aggregate surface — {@code FieldOperand} permits only {@code FieldRef}/{@code FieldListRef}, no group operand —
 * so it is locked here as a raw kernel-string law only, not a typed authoring control.
 */
class AggregateSlotLawsTest {

    private static final BoundModel ORDER = Fixtures.order();

    private static String cond(Condition c) {
        return new DslRenderer().render(c);
    }

    /** Guard the aggregate with {@code FieldFilled(Quantity) And …} so the probe rule references its own error
     *  field ({@code MVK_ERROR_FIELD_NOT_REFERENCED} would otherwise mask the duplicate-admission being tested). */
    private static String guarded(NumberExpr aggregate) {
        return cond(Dsl.fieldFilled(ORDER.field("/Order/Quantity")).and(aggregate.gt(0)));
    }

    // ── NumberOfDifferentValues' operand DOMAIN — the over-rejection audit, 2026-07-25 ───────────────────
    //
    // rulekit refused every temporal operand here ("dates ride the date family"), which was wrong twice over:
    // the kernel's own code name lists DATE as allowed, and unlike Max/Min there is no date-family alternative
    // for a count of distinct values — so the form was unauthorable, not merely routed elsewhere. These locks
    // pin the real domain so the pre-check can never again be broader than its measured basis.

    /** DATE, TIME and DATETIME pairs are all admitted. */
    @Test
    void countDifferentValuesAcceptsDateLikeOperandPairs() {
        KernelLaws.acceptsOrder("/Order", "../Quantity",
                "FieldFilled(Quantity) And NumberOfDifferentValues(OrderDate, DeliveryDate) > 0");
        KernelLaws.acceptsOrder("/Order", "../Quantity",
                "FieldFilled(Quantity) And NumberOfDifferentValues(ScheduledAt, CompletedAt) > 0");
        String times = io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.named("t").locale("en_US")
                .rootGroup("M", m -> m
                        .field("Q", io.github.mbackschat.a12.dm.modelkit.FieldKind.NUMBER)
                        .timeField("T1", t -> t.format("HH:mm:ss"))
                        .timeField("T2", t -> t.format("HH:mm:ss")))
                .build().toJson();
        KernelLaws.acceptsModel(times, "/M", "../Q",
                "FieldFilled(Q) And NumberOfDifferentValues(T1, T2) > 0");
    }

    /** Booleans/confirms stay out — the one part of the original pre-check that matched the kernel. */
    @Test
    void countDifferentValuesRejectsBooleanOperands() {
        KernelLaws.rejectsOrder("/Order", "../Quantity",
                "FieldFilled(Quantity) And NumberOfDifferentValues(ExpressShipping, TermsConfirmed) > 0",
                "MVK_ONLY_STRING_ENUM_NUMBER_CMP_DATE_ALLOWED");
    }

    /** A date beside a non-date — the homogeneity law rulekit now reports by its real code. */
    @Test
    void countDifferentValuesRejectsADateMixedWithANonDate() {
        KernelLaws.rejectsOrder("/Order", "../Quantity",
                "FieldFilled(Quantity) And NumberOfDifferentValues(OrderDate, Quantity) > 0",
                "MVK_DATE_AND_NONDATE");
    }

    /**
     * Two date-like operands are NOT enough — their FORMATS must be compatible, so a DATE beside a DATETIME is
     * refused. Deliberately left F4-arbitrated (the MVK ledger classifies it kernel-delegated): format
     * compatibility is model data, and pre-checking it would risk refusing a legal pair.
     */
    @Test
    void countDifferentValuesRejectsIncompatibleDateFormats() {
        KernelLaws.rejectsOrder("/Order", "../Quantity",
                "FieldFilled(Quantity) And NumberOfDifferentValues(OrderDate, ScheduledAt) > 0",
                "MVK_DATEFORMATS_NOT_COMPATIBLE");
    }

    @Test
    void numberOfValueInFieldsRejectsDuplicateFields() {
        KernelLaws.rejectsOrder("/Order", "../Quantity",
                "FieldFilled(Quantity) And NumberOfValueInFields(0 In Quantity, Quantity) > 0",
                "MVK_DUPLICATE_PARAM1");
    }

    /** The kernel admits a repeated wildcard operand across the entity-list aggregate family (it is expanded
     *  and consumed again at each slot). Authored via the typed DSL, so the DSL's output is proven kernel-legal. */
    @Test
    void entityListAggregatesAcceptRepeatedWildcardOperands() {
        FieldListRef count = ORDER.flatten("/Order/Items*/Count");
        KernelLaws.acceptsOrder("/Order", "../Quantity", guarded(Dsl.sum(count, count)));
        KernelLaws.acceptsOrder("/Order", "../Quantity", guarded(Dsl.maxValue(count, count)));
        KernelLaws.acceptsOrder("/Order", "../Quantity", guarded(Dsl.minValue(count, count)));
        KernelLaws.acceptsOrder("/Order", "../Quantity", guarded(Dsl.firstFilledNumber(count, count)));
        KernelLaws.acceptsOrder("/Order", "../Quantity", guarded(Dsl.numberOfFilledFields(count, count)));
        KernelLaws.acceptsOrder("/Order", "../Quantity", guarded(Dsl.numberOfDifferentValues(count, count)));
    }

    /** A repeated filtered wildcard, and a plain plus a filtered reference to the same star, are also legal. */
    @Test
    void repeatedFilteredWildcardIsAccepted() {
        FieldListRef count = ORDER.flatten("/Order/Items*/Count");
        FieldListRef filtered = ORDER.flatten("/Order/Items*/Count").having(ORDER.num("/Order/Items/Count").gt(0));
        KernelLaws.acceptsOrder("/Order", "../Quantity", guarded(Dsl.sum(filtered, filtered)));
        KernelLaws.acceptsOrder("/Order", "../Quantity", guarded(Dsl.sum(count, filtered)));
    }

    /** A repeated DIRECT field is still rejected (MVK_DUPLICATE_PARAM1) — a raw string, because the typed DSL
     *  refuses to construct it (locked by {@code AggregateDuplicateWildcardTest}). */
    @Test
    void entityListAggregatesRejectRepeatedDirectFields() {
        KernelLaws.rejectsOrder("/Order", "../Quantity",
                "FieldFilled(Quantity) And Sum(Quantity, Quantity) > 0", "MVK_DUPLICATE_PARAM1");
    }

    /** An indirect group/descendant overlap is rejected — a group operand whose expansion covers a listed
     *  descendant field. Raw string only: the typed aggregate surface cannot carry a group operand. */
    @Test
    void entityListAggregatesRejectIndirectGroupFieldOverlap() {
        KernelLaws.rejectsOrder("/Order", "../Quantity",
                "FieldFilled(Quantity) And Sum(/Order/Items*, /Order/Items*/Count) > 0", "MVK_DUPLICATE_PARAM2");
    }
}
