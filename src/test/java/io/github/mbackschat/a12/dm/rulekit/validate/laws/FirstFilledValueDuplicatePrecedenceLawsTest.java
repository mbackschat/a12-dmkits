package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §9: {@code FirstFilledValue}'s owner entity-list duplicate precedence
 * (a12-kernel-lean SPEC-2026-08-17-01). The model is authored through modelkit; the deliberately invalid
 * condition spellings stay raw only at the kernel-oracle boundary because the typed owner correctly refuses them.
 */
class FirstFilledValueDuplicatePrecedenceLawsTest {

    private static final String CLAIM = DocumentModelDraft.named("claim").locale("en_US")
            .rootGroup("Claim", claim -> claim
                    .stringField("Notice", field -> { })
                    .group("Primary", primary -> primary
                            .field("First", FieldKind.STRING)
                            .group("Detail", detail -> detail.field("Second", FieldKind.STRING)))
                    .group("Alternatives", alternatives -> alternatives.repeatable(10)
                            .field("Value", FieldKind.STRING)))
            .build().toJson();

    @Test
    void everyDuplicateAndOverlapBranchHasOneCompleteStaticVerdict() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("one fixed group", verdict("FirstFilledValue(Primary) == \"x\""));
        actual.put("repeated fixed group", verdict("FirstFilledValue(Primary, Primary) == \"x\""));
        actual.put("repeated starred group",
                verdict("FirstFilledValue(Alternatives*, Alternatives*) == \"x\""));
        actual.put("one direct field", verdict("FirstFilledValue(Primary/First) == \"x\""));
        actual.put("repeated direct field",
                verdict("FirstFilledValue(Primary/First, Primary/First) == \"x\""));
        actual.put("strict group overlap",
                verdict("FirstFilledValue(Primary, Primary/First) == \"x\""));
        actual.put("direct duplicate completes before fixed duplicate", verdict(
                "FirstFilledValue(Primary, Primary/First, Primary/First, Primary) == \"x\""));
        actual.put("fixed duplicate completes before direct duplicate", verdict(
                "FirstFilledValue(Primary, Primary, Primary/First, Primary/First) == \"x\""));
        actual.put("fixed duplicate before overlap",
                verdict("FirstFilledValue(Primary, Primary, Primary/First) == \"x\""));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("one fixed group", "admitted");
        expected.put("repeated fixed group", "MVK_DUPLICATE_PARAM1");
        expected.put("repeated starred group", "admitted");
        expected.put("one direct field", "MVK_PARAMSIZE_INVALIDN");
        expected.put("repeated direct field", "MVK_DUPLICATE_PARAM1");
        expected.put("strict group overlap", "MVK_DUPLICATE_PARAM2");
        expected.put("direct duplicate completes before fixed duplicate", "MVK_DUPLICATE_PARAM1");
        expected.put("fixed duplicate completes before direct duplicate", "MVK_DUPLICATE_PARAM1");
        expected.put("fixed duplicate before overlap", "MVK_DUPLICATE_PARAM1");
        assertEquals(expected, actual, "complete emitted code list per entity-list branch");
    }

    @Test
    void theDuplicateWitnessLocksThePrecedenceInsideTheSharedCode() {
        assertWitness("Primary/First", verdictResult(
                "FirstFilledValue(Primary, Primary/First, Primary/First, Primary) == \"x\""));
        assertWitness("Primary", verdictResult(
                "FirstFilledValue(Primary, Primary, Primary/First, Primary/First) == \"x\""));
    }

    private static void assertWitness(String expectedPath, ValidationResult result) {
        assertEquals(List.of("MVK_DUPLICATE_PARAM1"), result.diagnostics().stream()
                .map(diagnostic -> diagnostic.code().orElse("(no code)"))
                .toList());
        assertTrue(result.diagnostics().getFirst().summary()
                        .contains("'" + expectedPath + "' was defined more than once."),
                () -> "expected duplicate witness " + expectedPath + ", got " + result.diagnostics());
    }

    /** {@code admitted}, or the complete emitted code list. */
    private static String verdict(String ownerCondition) {
        ValidationResult result = verdictResult(ownerCondition);
        return result.ok() ? "admitted" : String.join(" + ", result.diagnostics().stream()
                .map(diagnostic -> diagnostic.code().orElse("(no code)"))
                .toList());
    }

    private static ValidationResult verdictResult(String ownerCondition) {
        return KernelLaws.checkModel(CLAIM, "/Claim", "../Notice",
                "FieldFilled(Notice) And " + ownerCondition);
    }
}
