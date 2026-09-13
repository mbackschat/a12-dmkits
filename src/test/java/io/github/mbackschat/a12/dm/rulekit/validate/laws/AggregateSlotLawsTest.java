package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.LinkedHashMap;
import java.util.Map;

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

    // ── the Having filter's three gates, and which of them REPORTS ───────────────────────────────────────
    //
    // Audited 2026-07-25: rulekit pre-checks CustomCondition in a filter, but FieldListRef's javadoc listed it
    // among the KERNEL-DELEGATED constructs — an internal contradiction that left the refusal's basis unclear.
    // The kernel really does reject it, so the pre-check stands; the javadoc was the stale half.
    //
    // These four rows were four single-code assertions until 2026-09-14. That spelling is a CONTAINMENT check,
    // so it could neither see a co-firing gate nor say which gate wins when two are violated at once — and the
    // ordering is the part an author needs, because two of the three gates hide the third.

    /**
     * Which of the filter's three gates reports, including the bodies that violate two at once.
     *
     * <p>Every row's filter is parenthesised, so the wrapper is constant across the grid and the body is the
     * only variable; the admitted single-leaf row is the control proving the parentheses are inert.
     */
    @Test
    void whichFilterGateReports() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a leaf naming the iterated level", filtered("[Items/Sku] == \"X\""));
        actual.put("a bare CustomCondition", filtered("CustomCondition SomeCheck"));
        actual.put("the iterated level AND a CustomCondition",
                filtered("[Items/Sku] == \"X\" And CustomCondition SomeCheck"));
        actual.put("a STARRED operand", filtered("[Items*/Sku] == \"X\""));
        actual.put("an OUTER field and nothing iterated", filtered("[/Order/Quantity] > 0"));
        actual.put("a STARRED operand AND a CustomCondition",
                filtered("[Items*/Sku] == \"X\" And CustomCondition SomeCheck"));
        actual.put("an OUTER field AND a CustomCondition",
                filtered("[/Order/Quantity] > 0 And CustomCondition SomeCheck"));
        actual.put("a star on a NON-repeatable group", filtered("[ShippingAddress*/City] == \"X\""));
        actual.put("a STARRED operand whose LEAF resolves to nothing", filtered("[Items*/Nowhere] == \"X\""));
        actual.put("a STARRED operand under the outer marker", filtered("[$Items*/Sku] == \"X\""));
        actual.put("a STARRED operand on another aggregate of the family",
                KernelLaws.orderVerdict("/Order", "../Quantity",
                        "FieldFilled(Quantity) And MaxValue(Items*/Count Having ([Items*/Sku] == \"X\")) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a leaf naming the iterated level", KernelLaws.ADMITTED);
        expected.put("a bare CustomCondition", "MVK_NO_ITERATION_FOR_WILDCARD");
        expected.put("the iterated level AND a CustomCondition",
                "MVK_DISALLOWED_CONSTRUCT_IN_FILTER_CONDITION");
        expected.put("a STARRED operand", "MVK_NO_WILDCARDS_ALLOWED");
        expected.put("an OUTER field and nothing iterated", "MVK_NO_ITERATION_FOR_WILDCARD");
        // The two rows the single-code spelling could not hold: BOTH other gates pre-empt the construct gate,
        // and neither co-fires with it. So the forbidden-construct refusal is only reachable from a filter that
        // is already otherwise legal, which is why the sibling conjunct in row three is load-bearing.
        expected.put("a STARRED operand AND a CustomCondition", "MVK_NO_WILDCARDS_ALLOWED");
        expected.put("an OUTER field AND a CustomCondition", "MVK_NO_ITERATION_FOR_WILDCARD");
        // The three rows a CHECKER needs before it may report the wildcard class from this position. A star
        // on a non-repeatable group is answered by the landing-site class instead ([KF328]), which outruns
        // every slot gate; the outer marker does not change the answer; and the gate is the entity-list
        // family's rather than `Sum`'s alone, so a second aggregate carries it too.
        expected.put("a star on a NON-repeatable group", "MVK_INVALID_WILDCARD");
        // The ENTITY gate wins here, and the prediction that it would not was a transfer: the wildcard class
        // outruns it where the star lands on something NON-repeatable (KF328), and that does not carry to a
        // legal star in an illegal position. So a checker may not judge this star before the whole reference
        // resolves — which is the row that stopped `FILTER_WILDCARD` from reporting on every unresolvable leaf.
        expected.put("a STARRED operand whose LEAF resolves to nothing", "MVK_INVALID_ENTITY");
        expected.put("a STARRED operand under the outer marker", "MVK_NO_WILDCARDS_ALLOWED");
        expected.put("a STARRED operand on another aggregate of the family", "MVK_NO_WILDCARDS_ALLOWED");
        assertEquals(expected, actual, "Having filter gates");
    }

    /** The COMPLETE class list for one {@code Having} filter body, the wrapper held constant. */
    private static String filtered(String filter) {
        return KernelLaws.orderVerdict("/Order", "../Quantity",
                "FieldFilled(Quantity) And Sum(Items*/Count Having (" + filter + ")) > 0");
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
