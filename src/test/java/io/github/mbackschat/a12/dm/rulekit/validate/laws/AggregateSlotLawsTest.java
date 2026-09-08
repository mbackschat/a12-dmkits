package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertNotNull;

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
 * once ({@code MVK_DUPLICATE_PARAM1} directly, {@code MVK_DUPLICATE_PARAM2} via a group), and it carries the
 * entity-list wildcard-skip as well: a repeated STARRED operand is admitted there too, so the skip is a
 * property of the shared check rather than of the carrier
 * ({@link DuplicateOverlapCarrierSweepLawsTest} measures it against this class's own control).
 *
 * <p><b>Typed-surface boundary.</b> The repeated-wildcard <em>acceptance</em> is authored through the typed DSL
 * (a {@link FieldListRef} operand). The indirect group/descendant overlap needs a GROUP operand, which the typed
 * aggregates gained with {@code AggregateOperand} (DG32) — it is locked typed in
 * {@code AggregateGroupOperandLawsTest}, and stays a raw-string law here so this class keeps measuring the gate
 * itself rather than the builder in front of it.
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

    // ── the Having filter's forbidden-construct set, kernel-verified ─────────────────────────────────────
    //
    // Audited 2026-07-25: rulekit pre-checks CustomCondition in a filter, but FieldListRef's javadoc listed it
    // among the KERNEL-DELEGATED constructs — an internal contradiction that left the refusal's basis unclear.
    // The kernel really does reject it, so the pre-check stands; the javadoc was the stale half.
    //
    // Getting here needed two other filter laws out of the way, both worth having locked: a filter operand must
    // be UNSTARRED (a starred one is MVK_NO_WILDCARDS_ALLOWED), and a filter must reference the iterated level at
    // all — a filter consisting only of a CustomCondition fails MVK_NO_ITERATION_FOR_WILDCARD first, which is why
    // the sibling conjunct below is load-bearing rather than decoration.

    /** {@code CustomCondition} inside a filter: "not supported for filter conditions". */
    @Test
    void aCustomConditionIsForbiddenInsideAHavingFilter() {
        KernelLaws.rejectsOrder("/Order", "../Quantity",
                "FieldFilled(Quantity) And Sum(Items*/Count Having ([Items/Sku] == \"X\""
                        + " And CustomCondition SomeCheck)) > 0",
                "MVK_DISALLOWED_CONSTRUCT_IN_FILTER_CONDITION");
    }

    /** The control: the same filter without the custom condition is accepted, so the refusal isolates it. */
    @Test
    void theSameFilterWithoutTheCustomConditionIsAccepted() {
        KernelLaws.acceptsOrder("/Order", "../Quantity",
                "FieldFilled(Quantity) And Sum(Items*/Count Having [Items/Sku] == \"X\") > 0");
    }

    /** A filter must reference the iterated level; one that references nothing is rejected before anything else. */
    @Test
    void aFilterThatReferencesNoIteratedLevelIsRejected() {
        KernelLaws.rejectsOrder("/Order", "../Quantity",
                "FieldFilled(Quantity) And Sum(Items*/Count Having CustomCondition SomeCheck) > 0",
                "MVK_NO_ITERATION_FOR_WILDCARD");
    }

    /** A STARRED operand inside the filter is rejected — the filter reads the current row, not the list. */
    @Test
    void aStarredOperandInsideAFilterIsRejected() {
        KernelLaws.rejectsOrder("/Order", "../Quantity",
                "FieldFilled(Quantity) And Sum(Items*/Count Having [Items*/Sku] == \"X\") > 0",
                "MVK_NO_WILDCARDS_ALLOWED");
    }

    // ── a number TYPEDEF reaches the numeric aggregates on BOTH sides ────────────────────────────────────
    //
    // Audited as an over-rejection candidate (2026-07-25) because ValueKind deliberately does not map
    // "TypeDefType" — a typedef must be resolved to its base discriminator by the caller, and an unresolved
    // one would surface as UNKNOWN and be refused by the NUMBER gate. Both sides pass; locked so they stay that way.
    private static String typedefJson() {
        return io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.named("td").locale("en_US")
                .typeDef("Money", t -> t.number(n -> n.fractionDigits(2)))
                .rootGroup("M", m -> m
                        .field("Q", io.github.mbackschat.a12.dm.modelkit.FieldKind.NUMBER)
                        .group("Items", i -> i.repeatable(10).typedefField("Amount", "Money")))
                .build().toJson();
    }

    @Test
    void theKernelAcceptsSumOverANumberTypedef() {
        KernelLaws.acceptsModel(typedefJson(), "/M", "../Q",
                "FieldFilled(Q) And Sum(Items*/Amount) > 0");
    }

    @Test
    void rulekitAuthorsSumOverANumberTypedef() {
        BoundModel m = BoundModel.ofJson(typedefJson());
        assertNotNull(Dsl.sum(m.flatten("/M/Items*/Amount")),
                "a number typedef must be usable in a numeric aggregate");
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
     *  descendant field. Kept as a raw string so the gate is measured without a builder in between; the typed
     *  peer is {@code AggregateGroupOperandLawsTest.aGroupScopeBesideItsOwnDescendantIsRejected}. */
    @Test
    void entityListAggregatesRejectIndirectGroupFieldOverlap() {
        KernelLaws.rejectsOrder("/Order", "../Quantity",
                "FieldFilled(Quantity) And Sum(/Order/Items*, /Order/Items*/Count) > 0", "MVK_DUPLICATE_PARAM2");
    }

    /** A guarded rule whose FILTER BODY carries the shape under test — {@code $} is legal only inside a
     *  filter ({@code MVK_INVALID_OUTER_ITERATION} outside one), so that is where this gate can see it. */
    private static String inFilter(String aggregate) {
        return "FieldFilled(Quantity) And Sum(Items*/Count Having ([Items/Sku] == \"X\" And " + aggregate
                + ")) > 0";
    }

    /**
     * The {@code $} outer-iteration correlation is part of an operand's IDENTITY: {@code $path} reads the outer
     * row and {@code path} the current one, so the pair is two operands and is admitted. Its control below is
     * the same filter, the same operator and the same path with the marker removed — the marker is the only
     * variable between them, which is what makes this an identity claim rather than an admission observation.
     *
     * <p>All three carriers are listed because they read one shared gate through two admission roots
     * ({@code ListAggregate} and {@code CountAggregate}); the typed peer is
     * {@code dsl.operators.AggregateCorrelatedOperandTest}.
     */
    @Test
    void theOuterCorrelationSplitsAnOperandsIdentityInsideAFilter() {
        KernelLaws.acceptsOrder("/Order", "../Quantity",
                inFilter("Sum($/Order/UnitPrice, /Order/UnitPrice) > 0"));
        KernelLaws.acceptsOrder("/Order", "../Quantity",
                inFilter("NumberOfFilledFields($/Order/UnitPrice, /Order/UnitPrice) == 2"));
        KernelLaws.acceptsOrder("/Order", "../Quantity",
                inFilter("NumberOfDifferentValues($/Order/ProductCode, /Order/ProductCode) == 2"));
    }

    /** The control: with the marker removed the same pairs are the duplicate the gate exists to refuse. */
    @Test
    void theUnmarkedPairInTheSameFilterIsStillADuplicate() {
        KernelLaws.rejectsOrder("/Order", "../Quantity",
                inFilter("Sum(/Order/UnitPrice, /Order/UnitPrice) > 0"), "MVK_DUPLICATE_PARAM1");
        KernelLaws.rejectsOrder("/Order", "../Quantity",
                inFilter("NumberOfFilledFields(/Order/UnitPrice, /Order/UnitPrice) == 2"),
                "MVK_DUPLICATE_PARAM1");
        KernelLaws.rejectsOrder("/Order", "../Quantity",
                inFilter("NumberOfDifferentValues(/Order/ProductCode, /Order/ProductCode) == 2"),
                "MVK_DUPLICATE_PARAM1");
    }
}
