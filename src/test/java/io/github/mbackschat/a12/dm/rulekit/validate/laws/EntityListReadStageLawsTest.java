package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.KernelCodeRefusal;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;

/**
 * KERNEL-FINDINGS [KF458] — the two stages of A12's operand read that run AHEAD of an empty group: the
 * iteration check, where a repeatable level the operand crosses without a star is {@code MVK_NO_WILDCARD} at a
 * rule that does not iterate it, and the {@code $} check, where a correlated operand outside a filter is
 * {@code MVK_INVALID_OUTER_ITERATION}. Both answer ahead of every list gate, on every entity-list carrier and on
 * {@code FirstFilledValue}.
 *
 * <p>Neither class is fixed by the operand: an iterating rule binds the level, and a filter admits the marker,
 * and then the list gates answer ({@link #theRuleMovesTheIterationClass}; for {@code $},
 * {@code FirstFilledOperandShapeLawsTest}). An operand is built before its rule, so rulekit withholds from the
 * first such operand on, as it does behind an index. Model-resolved {@code FieldRef} ancestry preserves
 * that uncertainty through direct records as well as {@link BoundModel} factories (API-FINDINGS AF175).
 */
class EntityListReadStageLawsTest {

    private static final String MODEL = DocumentModelDraft.named("warehouse-stages").locale("en_US")
            .rootGroup("Item", i -> i
                    .field("Title", FieldKind.STRING)
                    .field("Weight", FieldKind.NUMBER)
                    .group("Hollow", g -> { })
                    .group("Lapses", g -> g.repeatable(3))
                    .group("Keyed", g -> g.repeatable(5).indexField("Key").field("Key", FieldKind.STRING)
                            .field("Amount", FieldKind.NUMBER)))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(MODEL);

    private static final String NO_WILDCARD = "MVK_NO_WILDCARD";
    private static final String OUTER = "MVK_INVALID_OUTER_ITERATION";
    private static final String UNCODED = "uncoded";
    private static final String ADMITTED = KernelLaws.ADMITTED;

    /** One authored list: the kernel's verdict on its text, and rulekit's through the record and the model. */
    private record Row(String text, String kernel, Supplier<Condition> record, String recordVerdict,
            Supplier<Condition> bound, String boundVerdict) {}

    private static List<Row> rows() {
        var weight = M.field("/Item/Weight");
        var title = M.field("/Item/Title");
        var amount = M.field("/Item/Keyed/Amount");
        var lapses = M.flattenGroup("/Item/Lapses");
        var keyed = M.flattenGroup("/Item/Keyed");
        var hollow = M.flattenGroup("/Item/Hollow").outer();
        return List.of(
                // the iteration stage: a repeatable level crossed without a star
                new Row("Sum(Lapses, Weight) > 0", NO_WILDCARD,
                        () -> Dsl.sum(lapses, weight).gt(0), ADMITTED, () -> M.sum(lapses, weight).gt(0), ADMITTED),
                new Row("Sum(Keyed, Weight, Weight) > 0", NO_WILDCARD,
                        () -> Dsl.sum(keyed, weight, weight).gt(0), UNCODED,
                        () -> M.sum(keyed, weight, weight).gt(0), UNCODED),
                new Row("Sum(Keyed/Amount, Keyed/Amount) > 0", NO_WILDCARD,
                        () -> Dsl.sum(amount, amount).gt(0), UNCODED,
                        () -> M.sum(amount, amount).gt(0), UNCODED),
                new Row("NumberOfFilledFields(Title, Keyed/Amount) > 0", NO_WILDCARD,
                        () -> Dsl.numberOfFilledFields(List.of(title, amount)).gt(0), ADMITTED,
                        () -> M.numberOfFilledFields(title, amount).gt(0), ADMITTED),
                new Row("FirstFilledValue(Title, Keyed/Amount) > 0", NO_WILDCARD,
                        () -> Dsl.firstFilledNumber(title, amount).gt(0), UNCODED,
                        null, null),
                // the $ stage: a correlated operand outside a filter
                new Row("Sum($Hollow, Weight) > 0", OUTER,
                        () -> Dsl.sum(hollow, weight).gt(0), ADMITTED, () -> M.sum(hollow, weight).gt(0), ADMITTED),
                new Row("Sum($Title, Weight) > 0", OUTER,
                        () -> Dsl.sum(title.outer(), weight).gt(0), UNCODED,
                        () -> M.sum(title.outer(), weight).gt(0), UNCODED),
                new Row("NumberOfDifferentValues($Title, Weight) > 0", OUTER,
                        () -> Dsl.numberOfDifferentValues(title.outer(), weight).gt(0), UNCODED,
                        () -> M.numberOfDifferentValues(title.outer(), weight).gt(0), UNCODED),
                new Row("MaxValue(Title, $Weight) > 0", OUTER,
                        () -> Dsl.maxValue(title, weight.outer()).gt(0), UNCODED,
                        () -> M.maxValue(title, weight.outer()).gt(0), UNCODED),
                new Row("NumberOfFilledFields($Title, Weight) > 0", OUTER,
                        () -> Dsl.numberOfFilledFields(List.of(title.outer(), weight)).gt(0), ADMITTED,
                        () -> M.numberOfFilledFields(title.outer(), weight).gt(0), ADMITTED),
                new Row("FirstFilledValue($Title, Weight) > 0", OUTER,
                        () -> Dsl.firstFilledNumber(title.outer(), weight).gt(0), UNCODED, null, null));
    }

    @Test
    void theKernelAsksIterationAndTheMarkerAheadOfEveryListGate() {
        Map<String, String> expected = new LinkedHashMap<>();
        Map<String, String> actual = new LinkedHashMap<>();
        for (Row row : rows()) {
            expected.put(row.text(), row.kernel());
            actual.put(row.text(), KernelLaws.verdictModel(MODEL, "/Item", "../Title", "FieldFilled(Title) And "
                    + row.text()));
        }
        assertEquals(expected, actual);
    }

    /**
     * rulekit's verdict through each surface. Where it admits, the kernel's verdict on what it RENDERS must be the
     * row's, so an admitted row stands for its text; refused rows in this matrix withhold their class.
     */
    @Test
    void rulekitWithholdsFromTheFirstOperandThatAStageMayRefuse() {
        Map<String, String> expected = new LinkedHashMap<>();
        Map<String, String> actual = new LinkedHashMap<>();
        for (Row row : rows()) {
            expected.put("record " + row.text(), row.recordVerdict());
            actual.put("record " + row.text(), verdict(row.record(), row.kernel()));
            if (row.bound() != null) {
                expected.put("bound " + row.text(), row.boundVerdict());
                actual.put("bound " + row.text(), verdict(row.bound(), row.kernel()));
            }
        }
        assertEquals(expected, actual);
    }

    /**
     * The control that makes withholding the only safe answer: the same duplicate list at a rule that iterates
     * the level reads the level as bound, and the list gate answers.
     */
    @Test
    void theRuleMovesTheIterationClass() {
        assertEquals("MVK_DUPLICATE_PARAM1", KernelLaws.verdictModel(MODEL, "/Item/Keyed", "../Amount",
                "FieldFilled(Amount) And Sum(Amount, Amount) > 0"));
    }

    /** The class rulekit refuses with, or, where it admits, that the rendered condition draws the kernel's verdict. */
    private static String verdict(Supplier<Condition> authoring, String kernel) {
        try {
            Condition condition = authoring.get();
            String rendered = KernelLaws.verdictModel(MODEL, "/Item", "../Title", M.field("/Item/Title").filled()
                    .and(condition));
            return rendered.equals(kernel) ? ADMITTED : "admitted, but its rendering draws " + rendered;
        } catch (KernelCodeRefusal refusal) {
            return refusal.code().name();
        } catch (IllegalArgumentException uncoded) {
            return UNCODED;
        }
    }
}
