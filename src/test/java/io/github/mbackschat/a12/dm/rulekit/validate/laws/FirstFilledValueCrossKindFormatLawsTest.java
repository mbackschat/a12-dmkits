package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.FirstFilledOperand;
import io.github.mbackschat.a12.dm.rulekit.dsl.FirstFilledValue;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS KF71 —<b>any two date-bearing kinds share one {@code FirstFilledValue} list when they
 * declare one format</b>, so a kind is never the reason such a list is refused.
 *
 * <p>{@code FirstFilledValueFamilyLawsTest} established that the temporal gate reads the declared format, on
 * DATE beside DATE_FRAGMENT and on TIME beside a time-only DATE_TIME. Both of those pairs share a format only
 * because of a narrow declaration, which made the rule look like two special cases. It is one: the model admits
 * every vocabulary format on every date-bearing kind ({@code docs/TEMPORAL-FORMAT-VOCABULARY.md}), and the
 * source counterpart maps all four kinds to one {@code DATETIME} base type told apart only by format
 * ({@code OperationResultTypeMap.getOpTypeForField}, {@code OperationResultType.matches}). So a TIME declared
 * {@code yyyy-MM-dd} shares a list with a DATE, and a DATE declared {@code HH:mm:ss} with a TIME.
 *
 * <p>rulekit's record sees kinds and never a declaration, so the most it can refuse is what no declaration
 * could admit — a date-bearing kind beside a DATE_RANGE or a non-temporal kind. It refused TIME and DATE_TIME
 * beside everything else until this file measured the pairs.
 */
class FirstFilledValueCrossKindFormatLawsTest {

    /** One group, two declared formats spread over all four date-bearing kinds, plus the refused neighbours. */
    private static final String MODEL = DocumentModelDraft.named("timesheet").locale("en_US")
            .rootGroup("Shift", s -> s
                    .dateField("ListedOn", d -> d.format("yyyy-MM-dd"))
                    .dateField("StartClock", d -> d.format("HH:mm:ss"))
                    .timeField("OpensAt", t -> t.format("HH:mm:ss"))
                    .timeField("DueOn", t -> t.format("yyyy-MM-dd"))
                    .dateTimeField("ClockedAt", d -> d.format("HH:mm:ss"))
                    .dateTimeField("PostedOn", d -> d.format("yyyy-MM-dd"))
                    .dateTimeField("ReceivedAt", d -> d.format("yyyy-MM-dd'T'HH:mm:ss"))
                    .dateFragmentField("RenewalDay", f -> f.fragmentFormat("yyyy-MM-dd"))
                    .dateRangeField("Window", r -> r.format("yyyy-MM-dd").separator("/"))
                    .field("Note", FieldKind.STRING))
            .build().toJson();

    @Test
    void everyPairOfDateBearingKindsSharesOneListAtOneDeclaredFormat() {
        Map<String, String> expected = new LinkedHashMap<>();
        // one format, two kinds — every pair the two formats can reach, in both orders where the kinds allow
        expected.put("FirstFilledValue(ListedOn, DueOn) == [ListedOn]", ADMITTED);
        expected.put("FirstFilledValue(DueOn, ListedOn) == [DueOn]", ADMITTED);
        expected.put("FirstFilledValue(ListedOn, PostedOn) == [ListedOn]", ADMITTED);
        expected.put("FirstFilledValue(PostedOn, ListedOn) == [PostedOn]", ADMITTED);
        expected.put("FirstFilledValue(DueOn, PostedOn) == [DueOn]", ADMITTED);
        expected.put("FirstFilledValue(RenewalDay, DueOn) == [RenewalDay]", ADMITTED);
        // the sixth unordered pair, without which the universal in this file's name rested on the source read
        // alone (found by a12-kernel-lean, 2026-09-22)
        expected.put("FirstFilledValue(RenewalDay, PostedOn) == [RenewalDay]", ADMITTED);
        expected.put("FirstFilledValue(PostedOn, RenewalDay) == [PostedOn]", ADMITTED);
        expected.put("FirstFilledValue(StartClock, OpensAt) == [StartClock]", ADMITTED);
        expected.put("FirstFilledValue(OpensAt, ClockedAt) == [OpensAt]", ADMITTED);
        expected.put("FirstFilledValue(ClockedAt, OpensAt) == [ClockedAt]", ADMITTED);
        // the controls: two formats, whatever the kinds — the same kind included
        expected.put("FirstFilledValue(ListedOn, OpensAt) == [ListedOn]", VARYING);
        expected.put("FirstFilledValue(ReceivedAt, ListedOn) == [ReceivedAt]", VARYING);
        expected.put("FirstFilledValue(DueOn, OpensAt) == [DueOn]", VARYING);
        // no declaration makes these share: a range at a date's own format, and a text field
        expected.put("FirstFilledValue(Window, ListedOn) == [Window]", VARYING);
        expected.put("FirstFilledValue(ListedOn, Note) == [ListedOn]", VARYING);
        // each first operand compares with itself, so no row above is the comparison refusing
        for (String field : List.of("ListedOn", "StartClock", "DueOn", "OpensAt", "ClockedAt", "PostedOn",
                "ReceivedAt", "RenewalDay", "Window")) {
            expected.put("[" + field + "] == [" + field + "]", ADMITTED);
        }

        Map<String, String> actual = new LinkedHashMap<>();
        expected.keySet().forEach(condition -> actual.put(condition, verdict(condition)));
        assertEquals(expected, actual);
    }

    @Test
    void rulekitAdmitsEveryPairTheKernelAdmits_typedAsItsFirstOperand() {
        BoundModel m = BoundModel.ofJson(MODEL);
        List<List<String>> pairs = List.of(List.of("ListedOn", "DueOn"), List.of("DueOn", "ListedOn"),
                List.of("ListedOn", "PostedOn"), List.of("PostedOn", "ListedOn"), List.of("DueOn", "PostedOn"),
                List.of("RenewalDay", "DueOn"), List.of("RenewalDay", "PostedOn"), List.of("PostedOn", "RenewalDay"),
                List.of("StartClock", "OpensAt"), List.of("OpensAt", "ClockedAt"),
                List.of("ClockedAt", "OpensAt"));
        for (List<String> pair : pairs) {
            FieldRef first = m.field("/Shift/" + pair.get(0));
            FirstFilledValue value = new FirstFilledValue(FirstFilledOperand.of(first),
                    List.of(FirstFilledOperand.of(m.field("/Shift/" + pair.get(1)))));
            assertEquals(first.kind(), value.kind(), pair.toString());
        }

        // …and the typed factories route each kind to its own expression over the same lists
        assertEquals(ValueKind.TIME, Dsl.firstFilledTime(m.field("/Shift/OpensAt"), m.field("/Shift/ClockedAt"))
                .expression().kind());
        assertEquals(ValueKind.DATE_TIME, Dsl.firstFilledDateTime(m.field("/Shift/PostedOn"),
                m.field("/Shift/ListedOn")).expression().kind());
        assertEquals(ValueKind.DATE, Dsl.firstFilledDate(m.field("/Shift/ListedOn"), m.field("/Shift/DueOn"))
                .expression().kind());
    }

    @Test
    void rulekitStillRefusesWhatNoDeclarationCouldAdmit() {
        BoundModel m = BoundModel.ofJson(MODEL);
        FirstFilledOperand listedOn = FirstFilledOperand.of(m.field("/Shift/ListedOn"));
        for (String other : List.of("Window", "Note")) {
            FirstFilledOperand operand = FirstFilledOperand.of(m.field("/Shift/" + other));
            assertThrows(IllegalArgumentException.class, () -> new FirstFilledValue(operand, List.of(listedOn)));
        }
    }

    private static final String ADMITTED = "admitted";
    private static final String VARYING = "MVK_VARYING_TYPES_NOT_ALLOWED";

    /** {@code admitted}, or the COMPLETE emitted code list — a partial list would hide a co-firing gate. */
    private static String verdict(String condition) {
        String errorField = condition.substring(condition.lastIndexOf('[') + 1, condition.lastIndexOf(']'));
        ValidationResult r = KernelLaws.checkModel(MODEL, "/Shift", "../" + errorField, condition);
        return r.ok() ? ADMITTED : String.join(" + ", r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")")).map(Object::toString).toList());
    }
}
