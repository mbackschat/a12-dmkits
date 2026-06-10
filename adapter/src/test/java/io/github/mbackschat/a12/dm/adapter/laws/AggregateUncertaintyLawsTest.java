package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.mgmtp.a12.kernel.md.rt.api.IMessage.MessageType;

/**
 * Behavioral law — <b>aggregate uncertainty marking</b> (RT-SEMANTICS-LEDGER: {@code Combiner} +
 * {@code NumberCombiner} + {@code NumberOfDifferentValuesCombiner}; KERNEL-FINDINGS §9 supplement).
 * The aggregate combiners track whether their result <em>could still change</em> if more fields were
 * filled, and that uncertainty types the fired message:
 *
 * <ul>
 *   <li>{@code NumberOfDifferentValues} counts <b>distinct filled values only</b> — an empty operand
 *       is not a value (it does not bump the count), but it marks the count as able to grow, typing a
 *       fired comparison {@code OMISSION_ERROR};</li>
 *   <li>a starred aggregate over a repeatable group is fillable-typed even when <em>every
 *       instantiated row</em> is filled — the un-instantiated remainder of the declared repeatability
 *       is an omitted, fillable tail ({@code Combiner.setOmittedTail}); the probe corrected the
 *       naive source-read here (VALUE_ERROR was expected for the filled instance);</li>
 *   <li>only <b>exhausting the repetition</b> (all declared rows instantiated and filled) makes the
 *       aggregate certain → {@code VALUE_ERROR};</li>
 *   <li>the <b>date</b> aggregates ({@code DateCombiner}) follow the same laws — incl. the empty
 *       operand, whose not-given flag travels inside the {@code VkDate} value (probe-corrected: the
 *       combiner itself has no per-operand tracking, yet the typing comes out the same).</li>
 * </ul>
 */
class AggregateUncertaintyLawsTest {

    private static final String ORDER = RuntimeLaws.model("models/order.dm.json");

    /** The error field must be referenced in the condition — hence the CustomerName guard idiom. */
    private static String withAggRule(String name, String code, String condition) {
        return RuntimeLaws.withRule(ORDER, "/Order", name, "../CustomerName", code,
                "FieldFilled(CustomerName) And " + condition);
    }

    /** Three present Items rows (Sku filled), Counts as given ({@code null} = present-but-empty). */
    private static RuntimeLaws.Doc rows(String c1, String c2, String c3) {
        RuntimeLaws.Doc doc = RuntimeLaws.doc().group("/Order", 1)
                .field("/Order/CustomerName", "Acme", 1, 1);
        String[] counts = {c1, c2, c3};
        String[] skus = {"A", "B", "C"};
        for (int i = 1; i <= 3; i++) {
            doc.group("/Order/Items", 1, i).field("/Order/Items/Sku", skus[i - 1], 1, i, 1);
            if (counts[i - 1] == null) {
                doc.emptyField("/Order/Items/Count", 1, i, 1);
            } else {
                doc.field("/Order/Items/Count", counts[i - 1], 1, i, 1);
            }
        }
        return doc;
    }

    @Test
    void anEmptyOperandIsNotADistinctValue() {
        String model = withAggRule("OneSize", "ONE_SIZE", "NumberOfDifferentValues(Items*/Count) == 1");

        // 5, empty, 5 → one distinct value, the empty does not count as a second
        assertTrue(RuntimeLaws.fires(model, "ONE_SIZE", rows("5", null, "5")),
                "an empty operand does not count as a distinct value: {5, ∅, 5} has 1 distinct value");
        assertFalse(RuntimeLaws.fires(model, "ONE_SIZE", rows("5", "6", "5")),
                "{5, 6, 5} has 2 distinct values → == 1 does not fire");
    }

    @Test
    void aStarredAggregateIsFillableEvenWithEveryInstantiatedRowFilled() {
        // The probe corrected the source-read expectation: VALUE_ERROR was expected for the fully
        // filled instance, but the kernel types it OMISSION_ERROR — the un-instantiated remainder of
        // the repeatable group counts as an omitted, fillable tail (Combiner.setOmittedTail).
        String model = withAggRule("OneSize", "ONE_SIZE", "NumberOfDifferentValues(Items*/Count) == 1");

        Optional<MessageType> uncertain = RuntimeLaws.typeOf(model, "ONE_SIZE", rows("5", null, "5"));
        assertEquals(Optional.of(MessageType.OMISSION_ERROR), uncertain,
                "with an empty operand the count could still grow → OMISSION_ERROR");

        Optional<MessageType> stillUncertain = RuntimeLaws.typeOf(model, "ONE_SIZE", rows("5", "5", "5"));
        assertEquals(Optional.of(MessageType.OMISSION_ERROR), stillUncertain,
                "even fully filled rows leave the un-instantiated tail fillable → OMISSION_ERROR");
    }

    @Test
    void exhaustingTheRepetitionMakesTheAggregateCertain() {
        // The boundary that proves the tail logic: with ALL 50 repetitions (the group's declared
        // repeatability) instantiated and filled, no fillable tail remains → VALUE_ERROR.
        String model = withAggRule("TotalLow", "TOTAL_LOW", "Sum(Items*/Count) < 100");

        RuntimeLaws.Doc full = RuntimeLaws.doc().group("/Order", 1)
                .field("/Order/CustomerName", "Acme", 1, 1);
        for (int i = 1; i <= 50; i++) {
            full.group("/Order/Items", 1, i).field("/Order/Items/Count", "1", 1, i, 1);
        }
        assertEquals(Optional.of(MessageType.VALUE_ERROR), RuntimeLaws.typeOf(model, "TOTAL_LOW", full),
                "all 50 repetitions filled → Sum(50×1)=50 < 100 fires with no fillable tail → VALUE_ERROR");
    }

    @Test
    void anEmptyOperandMakesTheSumFillable() {
        String model = withAggRule("TotalLow", "TOTAL_LOW", "Sum(Items*/Count) < 100");

        Optional<MessageType> uncertain = RuntimeLaws.typeOf(model, "TOTAL_LOW", rows("5", null, "7"));
        assertEquals(Optional.of(MessageType.OMISSION_ERROR), uncertain,
                "a sum with an empty operand can still grow → OMISSION_ERROR");
    }

    // ---- the DATE combiner (DateCombiner) — same tail law, but NO per-operand empty tracking ----

    /** {@code count} Items rows, each with a Sku and a PromiseDate ({@code null} = present-but-empty). */
    private static RuntimeLaws.Doc dateRows(String... promiseDates) {
        RuntimeLaws.Doc doc = RuntimeLaws.doc().group("/Order", 1)
                .field("/Order/CustomerName", "Acme", 1, 1)
                .field("/Order/DeliveryDate", "2026-07-01", 1, 1);
        for (int i = 1; i <= promiseDates.length; i++) {
            doc.group("/Order/Items", 1, i).field("/Order/Items/Sku", "S" + i, 1, i, 1);
            if (promiseDates[i - 1] == null) {
                doc.emptyField("/Order/Items/PromiseDate", 1, i, 1);
            } else {
                doc.field("/Order/Items/PromiseDate", promiseDates[i - 1], 1, i, 1);
            }
        }
        return doc;
    }

    @Test
    void theLatestDateWins() {
        String model = withAggRule("LatePromise", "LATE_PROMISE",
                "MaxValue(Items*/PromiseDate) > [DeliveryDate]");

        assertTrue(RuntimeLaws.fires(model, "LATE_PROMISE",
                dateRows("2026-06-15", "2026-07-20", "2026-06-20")),
                "the latest promise (2026-07-20) is after the delivery date → fires");
        assertFalse(RuntimeLaws.fires(model, "LATE_PROMISE",
                dateRows("2026-06-15", "2026-06-20", "2026-06-25")),
                "every promise is before the delivery date → does not fire");
    }

    @Test
    void aDateAggregateIsFillableUntilTheRepetitionIsExhausted() {
        String model = withAggRule("LatePromise", "LATE_PROMISE",
                "MaxValue(Items*/PromiseDate) > [DeliveryDate]");

        Optional<MessageType> tail = RuntimeLaws.typeOf(model, "LATE_PROMISE",
                dateRows("2026-07-20", "2026-07-21", "2026-07-22"));
        assertEquals(Optional.of(MessageType.OMISSION_ERROR), tail,
                "3 of 50 declared rows instantiated → the omitted tail keeps the max fillable");
    }

    @Test
    void anEmptyDateOperandAlsoMakesTheAggregateFillable() {
        // Probe-corrected: the naive source-read expected VALUE_ERROR (DateCombiner itself tracks
        // only the filter/omitted-tail bits, unlike NumberCombiner's per-operand tracking) — but the
        // kernel types this OMISSION_ERROR: the not-given flag travels inside the VkDate value
        // through max/min, so dates behave like numbers after all.
        String model = withAggRule("LatePromise", "LATE_PROMISE",
                "MaxValue(Items*/PromiseDate) > [DeliveryDate]");

        String[] dates = new String[50];
        java.util.Arrays.fill(dates, "2026-07-20");
        dates[24] = null;                                      // one present-but-empty operand
        assertEquals(Optional.of(MessageType.OMISSION_ERROR),
                RuntimeLaws.typeOf(model, "LATE_PROMISE", dateRows(dates)),
                "an empty date operand keeps the max fillable → OMISSION_ERROR (all 50 rows present)");
    }

    @Test
    void exhaustingTheRepetitionMakesTheDateAggregateCertainToo() {
        String model = withAggRule("LatePromise", "LATE_PROMISE",
                "MaxValue(Items*/PromiseDate) > [DeliveryDate]");

        String[] dates = new String[50];
        java.util.Arrays.fill(dates, "2026-07-20");
        assertEquals(Optional.of(MessageType.VALUE_ERROR),
                RuntimeLaws.typeOf(model, "LATE_PROMISE", dateRows(dates)),
                "all 50 rows instantiated and filled → no fillable tail → VALUE_ERROR");
    }
}
