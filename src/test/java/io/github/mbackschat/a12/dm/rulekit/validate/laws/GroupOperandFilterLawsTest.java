package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §4/§11 — <b>a {@code Having} filter cannot attach to a GROUP operand</b>, on the carriers where
 * a group operand is admitted at all.
 *
 * <p><b>Why this file exists.</b> a12-kernel-lean's review of our group-operand handback listed three residuals;
 * two were measured and one was left open across four rounds: <em>"the reached-{@code Having} OMISSION contrast
 * for the group form"</em> — does a filter that reaches a row change the message type the way it does for the
 * starred FIELD form? We declined to assert it was reachable without trying to author it, which was right, and
 * then did not try, which was not.
 *
 * <p><b>Authored, it turns out to be UNASKABLE rather than unmeasured.</b> A filter belongs to a field operand
 * (the flatten of {@code Lines*} then {@code Amount}, with a {@code Having} on it); attaching one to a group
 * operand is refused outright, so there is no
 * reached-{@code Having} state for the group form whose message type could be contrasted. That closes the
 * residual as a non-question, the same shape §17.5 reached for the {@code Min}/{@code Max} carrier — and it is
 * a better answer than the measurement would have been, because it holds for every fill state at once.
 *
 * <p><b>The unfiltered controls are what make it a measurement.</b> Each refused row is paired with the same
 * operand list minus its filter, which is admitted — so the refusal is charged to the filter and not to the
 * group operand, which these carriers do accept.
 */
class GroupOperandFilterLawsTest {

    private static final String GROUP = "/StockCount";

    /**
     * A repeatable group whose fields are all one comparability category, so the operand list's own kind gate
     * cannot fire and mask the filter answer. {@code Marker} is the error field, outside the repeatable group.
     */
    private static final String MODEL = DocumentModelDraft.named("stock-count").locale("en_US")
            .rootGroup("StockCount", root -> root
                    .stringField("Marker", s -> { })
                    .group("Counts", counts -> counts.repeatable(50)
                            .stringField("CounterInitials", s -> { })
                            .stringField("BinCode", s -> { })))
            .build().toJson();

    @Test
    void aHavingFilterCannotAttachToAGroupOperand() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("FieldValuesNotUnique(group) — unfiltered control", verdict(
                "FieldValuesNotUnique(Counts*)"));
        actual.put("FieldValuesNotUnique(group Having …)", verdict(
                "FieldValuesNotUnique(Counts* Having [Counts/BinCode] == \"A1\")"));
        actual.put("FirstFilledValue(group) — unfiltered control", verdict(
                "FirstFilledValue(Counts*) == \"x\""));
        actual.put("FirstFilledValue(group Having …)", verdict(
                "FirstFilledValue(Counts* Having [Counts/BinCode] == \"A1\") == \"x\""));
        // The FIELD form of the same filter, so the refusals above cannot be read as "this model cannot carry a
        // Having at all" — which is the control that turns the pair into an attribution.
        actual.put("the FIELD form of the same filter — the shape a Having belongs to", verdict(
                "FieldValuesNotUnique(Counts*/CounterInitials Having [Counts/BinCode] == \"A1\")"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("FieldValuesNotUnique(group) — unfiltered control", "admitted");
        expected.put("FieldValuesNotUnique(group Having …)", "MVK_NO_GROUPS_ALLOWED");
        expected.put("FirstFilledValue(group) — unfiltered control", "admitted");
        expected.put("FirstFilledValue(group Having …)", "MVK_NO_GROUPS_ALLOWED");
        expected.put("the FIELD form of the same filter — the shape a Having belongs to", "admitted");
        assertEquals(expected, actual,
                "a filter attaches to a FIELD operand, never to a group one — so the reached-Having message-type "
                        + "contrast has no group-form state to be measured in");
    }

    /** {@code admitted}, or the COMPLETE emitted code list — a partial list would hide a co-firing gate. */
    private static String verdict(String condition) {
        ValidationResult r = KernelLaws.checkModel(MODEL, GROUP, "../Marker",
                "FieldFilled(Marker) And " + condition);
        return r.ok() ? "admitted" : String.join(" + ", r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")")).map(Object::toString).toList());
    }
}
