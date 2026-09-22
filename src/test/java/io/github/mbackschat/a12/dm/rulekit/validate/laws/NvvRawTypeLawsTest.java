package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;
import org.junit.jupiter.api.Test;

/**
 * KERNEL-FINDINGS "raw-type (noValueValidation) reads" — the accept/reject tier of IG71/IF125. The kernel
 * closes EVERY value window on an nvv STRING field at the model tier — a value operation is
 * {@code MVK_INVALID_RAW_TYPE}, {@code Length} is authorable only as the whole condition
 * {@code Length(f) > constant} (or mirrored {@code constant < Length(f)}; any other shape, including
 * {@code >=} and a surrounding {@code And}, is {@code MVK_INVALID_LENGTH_OF_RAW_TYPE}) — and computations
 * may not reference an nvv field at all. Presence checks are unrestricted. The fires/does-not-fire tier
 * (the accepted shape is ELIMINATED, never fired) is {@code adapter.laws.NvvRawTypeDiffTest}.
 */
class NvvRawTypeLawsTest {

    private static final RuleValidator VALIDATOR = new RuleValidator();

    private static final String CLAIM = DocumentModelDraft.named("claim").locale("en_US")
            .rootGroup("Claim", g -> g
                    .stringField("IncidentNote", n -> n.lineBreaks().noValueValidation())
                    .stringField("Other", o -> { })
                    .numberField("Len", n -> n.fractionDigits(0))
                    .group("Attachments", a -> a.repeatable(5)
                            .stringField("Blob", b -> b.lineBreaks().noValueValidation())
                            .stringField("Name", nm -> { })))
            .build().toJson();

    // ---- the ONE accepted value-ish shape (a disguised max-length declaration, eliminated at codegen) ----

    @Test
    void wholeConditionLengthGreaterConstantIsAccepted() {
        KernelLaws.acceptsModel(CLAIM, "/Claim", "../IncidentNote", "Length(IncidentNote) > 5");
    }

    @Test
    void integerTokenWithTrailingZeroesIsAccepted() {
        // The parsed number can have a negative scale, but the raw Length contract is lexical: no decimal point.
        KernelLaws.acceptsModel(CLAIM, "/Claim", "../IncidentNote", "Length(IncidentNote) > 100");
    }

    @Test
    void mirroredConstantLessThanLengthIsAccepted() {
        KernelLaws.acceptsModel(CLAIM, "/Claim", "../IncidentNote", "-1 < Length(IncidentNote)");
    }

    @Test
    void theAcceptedConstantDomainIncludesBothSignedIntEndpoints() {
        KernelLaws.acceptsModel(CLAIM, "/Claim", "../IncidentNote", "Length(IncidentNote) > -2147483648");
        KernelLaws.acceptsModel(CLAIM, "/Claim", "../IncidentNote", "2147483647 < Length(IncidentNote)");
    }

    @Test
    void decimalPointConstantsAreRejectedInBothStrictOrientations() {
        for (String condition : List.of(
                "Length(IncidentNote) > 5.0",
                "Length(IncidentNote) > 0.5",
                "5.0 < Length(IncidentNote)",
                "0.5 < Length(IncidentNote)")) {
            rejectsWithExactCode(condition, "MVK_INTERNAL_ERROR");
        }
    }

    @Test
    void oneStepSignedIntOverflowsAreRejected() {
        rejectsWithExactCode("Length(IncidentNote) > 2147483648", "MVK_INTERNAL_ERROR");
        rejectsWithExactCode("-2147483649 < Length(IncidentNote)", "MVK_INTERNAL_ERROR");
    }

    @Test
    void strictRawLengthConstantFailuresGetAFocusedCorrective() {
        for (String condition : List.of(
                "Length(IncidentNote) > 5.0",
                "2147483648 < Length(IncidentNote)",
                // Parenthesised: A12 admits the parentheses and still reaches the constant gate, so the
                // corrective must reach it too — measured 2026-09-13, and the regex shape reader missed it.
                "(Length(IncidentNote) > 5.0)")) {
            ValidationResult result = KernelLaws.checkModel(CLAIM, "/Claim", "../IncidentNote", condition);
            String fix = result.diagnostics().stream()
                    .filter(d -> d.code().filter("MVK_INTERNAL_ERROR"::equals).isPresent())
                    .flatMap(d -> d.fix().stream())
                    .findFirst()
                    .orElse("");
            assertTrue(fix.contains("decimal-point-free signed-32-bit integer constant"),
                    () -> "raw Length needs its focused integer-domain corrective: " + result.diagnostics());
        }
    }

    // ---- presence checks are unrestricted ----

    @Test
    void presenceChecksOverNvvAreAccepted() {
        KernelLaws.acceptsModel(CLAIM, "/Claim", "../IncidentNote", "FieldNotFilled(IncidentNote)");
        KernelLaws.acceptsModel(CLAIM, "/Claim", "../Other", "AllFieldsFilled(IncidentNote, Other)");
    }

    // ---- every other value window is closed ----

    @Test
    void aValueComparisonOnNvvIsRejected() {
        KernelLaws.rejectsModel(CLAIM, "/Claim", "../IncidentNote", "[IncidentNote] == \"x\"",
                "MVK_INVALID_RAW_TYPE");
    }

    @Test
    void lengthInsideALargerConditionIsRejected() {
        KernelLaws.rejectsModel(CLAIM, "/Claim", "../IncidentNote",
                "Length(IncidentNote) > 5 And FieldFilled(Other)", "MVK_INVALID_LENGTH_OF_RAW_TYPE");
    }

    @Test
    void lengthWithANonStrictComparatorIsRejected() {
        // the allowed-shape switch is GT / mirrored LT ONLY — `>=` is not a meta-length declaration
        KernelLaws.rejectsModel(CLAIM, "/Claim", "../IncidentNote", "Length(IncidentNote) >= 5",
                "MVK_INVALID_LENGTH_OF_RAW_TYPE");
    }


    @Test
    void shapeValidationPreemptsTheConstantDomain() {
        rejectsWithExactCode("Length(IncidentNote) >= 5.0", "MVK_INVALID_LENGTH_OF_RAW_TYPE");
        rejectsWithExactCode("Length(IncidentNote) > 5.0 And FieldFilled(Other)",
                "MVK_INVALID_LENGTH_OF_RAW_TYPE");
    }

    @Test
    void ordinaryStringLengthDoesNotUseTheRawConstantRestriction() {
        KernelLaws.acceptsModel(CLAIM, "/Claim", "../Other", "Length(Other) >= 5.0");
    }

    @Test
    void aValueListOpOnNvvIsRejected() {
        KernelLaws.rejectsModel(CLAIM, "/Claim/Attachments", "../Blob",
                "FieldValuesNotUnique(/Claim/Attachments*/Blob)", "MVK_INVALID_RAW_TYPE");
    }

    // ---- computations may not reference an nvv field at all ----

    @Test
    void aComputationCopyOfNvvIsRejected() {
        rejectsComputation("[IncidentNote]", null, "MVK_INVALID_RAW_TYPE");
    }

    @Test
    void aComputationLengthOfNvvIsRejected() {
        rejectsComputation("Length(IncidentNote)", null, "MVK_INVALID_LENGTH_OF_RAW_TYPE");
    }

    @Test
    void aComputationPreconditionOverNvvLengthIsRejected() {
        // even the rule-side allowed comparison shape is NOT allowed as a computation precondition
        rejectsComputation("1", "Length(IncidentNote) > 5", "MVK_INVALID_LENGTH_OF_RAW_TYPE");
    }

    /**
     * The complement of the accepted shape, as a grid — what a CHECKER has to decide.
     *
     * <p>The rows above establish the two accepted layouts and two refusals. A portable checker keying on
     * "the whole condition is `Length(f) > c` or mirrored, else report" is asserting something stronger: that
     * every OTHER layout is refused. Each row here is a layout a real author writes and the rows above do not
     * reach, so the complement is measured rather than inferred from the accepted set — and the ordinary-field
     * control carries the same surrounding shapes, which is what separates "this layout" from "this field".
     */
    @Test
    void whatTheComplementOfTheAcceptedShapeDraws() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("parenthesised whole condition", verdict("../IncidentNote", "(Length(IncidentNote) > 5)"));
        actual.put("the comparison reversed", verdict("../IncidentNote", "Length(IncidentNote) < 5"));
        actual.put("mirrored, reversed", verdict("../IncidentNote", "5 > Length(IncidentNote)"));
        actual.put("inside an Or", verdict("../IncidentNote", "Length(IncidentNote) > 5 Or FieldFilled(Other)"));
        actual.put("negated", verdict("../IncidentNote", "Not(Length(IncidentNote) > 5)"));
        actual.put("on a nested path", nestedVerdict("Length(Blob) > 5"));
        actual.put("ordinary field, inside an Or — the control",
                verdict("../Other", "Length(Other) > 5 Or FieldFilled(Other)"));
        actual.put("ordinary field, parenthesised — the control", verdict("../Other", "(Length(Other) > 5)"));

        Map<String, String> expected = new LinkedHashMap<>();
        // The row that decides an implementation: redundant parentheses are TRANSPARENT to the gate, so a
        // checker matching the accepted shape against the raw token stream would refuse a model A12 admits.
        expected.put("parenthesised whole condition", KernelLaws.ADMITTED);
        // GT with Length on the left, or LT with Length on the right. Reversing either is not a max-length
        // declaration, so both orientations of the wrong comparison are refused by the shape gate.
        expected.put("the comparison reversed", "MVK_INVALID_LENGTH_OF_RAW_TYPE");
        expected.put("mirrored, reversed", "MVK_INVALID_LENGTH_OF_RAW_TYPE");
        // `And` above, `Or` here: the gate is "the WHOLE condition", not one connective.
        expected.put("inside an Or", "MVK_INVALID_LENGTH_OF_RAW_TYPE");
        // A GRAMMAR refusal, one tier earlier, so this row belongs to no raw-type gate at all.
        expected.put("negated", "MVK_UNEXPECTED_TOKEN");
        expected.put("on a nested path", KernelLaws.ADMITTED);
        expected.put("ordinary field, inside an Or — the control", KernelLaws.ADMITTED);
        expected.put("ordinary field, parenthesised — the control", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "the complement of the accepted raw-type Length shape");
    }

    private static String verdict(String errorField, String condition) {
        return KernelLaws.verdictModel(CLAIM, "/Claim", errorField, condition);
    }

    private static String nestedVerdict(String condition) {
        return KernelLaws.verdictModel(CLAIM, "/Claim/Attachments", "../Blob", condition);
    }

    private static void rejectsComputation(String operation, String precondition, String code) {
        DraftComputation comp = new DraftComputation("/Claim", "CalcProbe", "../Len", null,
                List.of(new DraftComputation.Alternative(precondition, operation)), List.of());
        ValidationResult r = VALIDATOR.check(CLAIM, comp);
        assertFalse(r.ok(), () -> "expected rejection of computation '" + operation + "' (precond " + precondition + ")");
        assertTrue(r.diagnostics().stream().anyMatch(p -> p.code().filter(code::equals).isPresent()),
                () -> "expected " + code + ", got: " + r.diagnostics());
    }


    private static void rejectsWithExactCode(String condition, String code) {
        ValidationResult result = KernelLaws.checkModel(CLAIM, "/Claim", "../IncidentNote", condition);
        assertFalse(result.ok(), () -> "expected rejection of '" + condition + "'");
        List<String> codes = result.diagnostics().stream().flatMap(d -> d.code().stream()).toList();
        assertEquals(List.of(code), codes, () -> "unexpected diagnostics for '" + condition + "': " + result.diagnostics());
    }
}
