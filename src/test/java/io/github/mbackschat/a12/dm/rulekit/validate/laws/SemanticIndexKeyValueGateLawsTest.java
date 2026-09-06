package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §11 — <b>the semantic-index key literal is gated by the index field's declared kind, and
 * that gate belongs to the CONDITION</b>, not to the message parameter. [KF205] records the key's classes and
 * [KF1] the decimal-format by-product; neither states which construct owns the gate.
 *
 * <p><b>The parameterless row is the whole argument.</b> A refusal that survives deleting every message
 * parameter cannot be the parameter's. Without it the matrix reads as a parameter verdict, which is how it
 * first read on the reporting side.
 *
 * <p><b>The index field's declaration is the only variable.</b> The two models are the same draft built twice,
 * differing in which direct child is designated the index field; the same condition text is refused under the
 * NUMBER designation and admitted under the STRING one, and a numeric key is admitted under the NUMBER one so
 * the gate is visibly armed rather than absent.
 *
 * <p>{@code MVK_INDEX_VALUE_INVALID} and {@code MVK_INDEX_FOR_ERROR_TEXT_INVALID} are therefore two gates at
 * two layers, and only the second is about the parameter: a consumer reporting the parameter class here sends
 * an author to rewrite a message that is already correct.
 *
 * <p>Reported by a12-kernel-lean as {@code SPEC-2026-09-05-09}; measured here with their own key spelling.
 *
 * <p>Own domain (a warehouse bin sheet).
 */
class SemanticIndexKeyValueGateLawsTest {

    private static final String GROUP = "/Bin";
    private static final String ERROR_REL = "../Ref";
    private static final String KEY_VALUE_INVALID = "MVK_INDEX_VALUE_INVALID";

    private static String model(String indexField) {
        return DocumentModelDraft.named("warehouse-bin").locale("en_US")
                .rootGroup("Bin", b -> b
                        .stringField("Ref", s -> { })
                        .group("Slots", g -> g.repeatable(20).indexField(indexField)
                                .numberField("SlotNo", n -> n.fractionDigits(0))
                                .stringField("SlotCode", s -> { })
                                .numberField("Qty", n -> n.fractionDigits(0))))
                .build().toJson();
    }

    private static final String NUMBER_INDEX = model("SlotNo");
    private static final String STRING_INDEX = model("SlotCode");

    private static String verdict(String json, String condition) {
        ValidationResult r = KernelLaws.checkModel(json, GROUP, ERROR_REL, "FieldFilled(Ref) And " + condition);
        return r.ok() ? KernelLaws.ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")"))
                .collect(Collectors.joining(","));
    }

    @Test
    void theKeyLiteralIsGatedByTheIndexFieldsKindWithNoMessageParameterInSight() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("non-numeric key, NUMBER index field", verdict(NUMBER_INDEX, "[Slots/Qty For \"k1\"] > 0"));
        actual.put("numeric key, NUMBER index field", verdict(NUMBER_INDEX, "[Slots/Qty For \"2\"] > 0"));
        actual.put("the same non-numeric key, STRING index field",
                verdict(STRING_INDEX, "[Slots/Qty For \"k1\"] > 0"));
        actual.put("numeric key, STRING index field", verdict(STRING_INDEX, "[Slots/Qty For \"2\"] > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("non-numeric key, NUMBER index field", KEY_VALUE_INVALID);
        expected.put("numeric key, NUMBER index field", KernelLaws.ADMITTED);
        expected.put("the same non-numeric key, STRING index field", KernelLaws.ADMITTED);
        expected.put("numeric key, STRING index field", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "the gate is the condition's, and it reads the index field's declaration");
    }
}
