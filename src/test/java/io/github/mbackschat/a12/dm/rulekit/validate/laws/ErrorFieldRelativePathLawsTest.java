package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;
import org.junit.jupiter.api.Test;

/**
 * A rule's {@code errorEntityRelPath} resolves from the RULE ELEMENT, not from the rule's group.
 *
 * <p>The two readings coincide for almost every path anyone writes, which is what makes this worth locking:
 * the only externally visible difference is one step, so a consumer that resolved from the group would agree
 * with A12 on every fixture in this repository except the row below. The {@code ../} form is used by every
 * existing laws test as a convention; nothing until now asserted that dropping it is a REJECTION rather than
 * an equivalent spelling.
 *
 * <p>Measured because a checker needs it: the portable consistency module resolves this member without a
 * condition parser, so this is the Kernel oracle its relative-path arithmetic answers to.
 */
class ErrorFieldRelativePathLawsTest {

    /** A rejection the kernel reports without an MVK code — it never reaches a classified check. */
    private static final String UNCODED = "rejected, uncoded";

    private static final String ORDER = DocumentModelDraft.named("order-errorfield").locale("en_US")
            .rootGroup("Order", o -> o
                    .field("Code", FieldKind.STRING)
                    .group("Meta", m -> m.field("Ref", FieldKind.STRING))
                    .group("Lines", l -> l.repeatable(9).field("Qty", FieldKind.NUMBER)))
            .build().toJson();

    @Test
    void theErrorFieldPathClimbsOutOfTheRuleElementFirst() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("sibling field, with the parent step", verdict("../Code", "FieldFilled(Code)"));
        actual.put("sibling field, without the parent step", verdict("Code", "FieldFilled(Code)"));
        actual.put("field one level deeper", verdict("../Meta/Ref", "FieldFilled(Meta/Ref)"));
        // Climbing to the root and descending again is ordinary; one step FURTHER is the discriminator, since
        // a resolver that clamped at the root would land on the same element and silently agree with the row
        // above it.
        actual.put("up to the root, then down again", verdict("../../Order/Code", "FieldFilled(Code)"));
        actual.put("one step PAST the root", verdict("../../../Order/Code", "FieldFilled(Code)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("sibling field, with the parent step", KernelLaws.ADMITTED);
        // Without `../` the path names a CHILD of the rule element, which no model declares.
        expected.put("sibling field, without the parent step",
                "MVK_ERROR_FIELD_MISSING,MVK_MISSING_ERROR_FIELD");
        expected.put("field one level deeper", KernelLaws.ADMITTED);
        expected.put("up to the root, then down again", KernelLaws.ADMITTED);
        expected.put("one step PAST the root", UNCODED);
        assertEquals(expected, actual, "the relative path is resolved from the rule element itself");
    }

    /**
     * The gate that is NOT path arithmetic, and the control that bounds it. A resolvable error-field path is
     * necessary and not sufficient: the condition must also reference the target, which is why the rows above
     * had to vary the error field and its reference together rather than the path alone. The repeatable row
     * is the control — with the reference present it is ADMITTED, so a rejection there would have come from
     * the reference gate and not from the target's repeatability.
     */
    @Test
    void aResolvablePathStillNeedsTheConditionToReferenceIt() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("path resolves but the condition does not reference it",
                verdict("../Meta/Ref", "FieldFilled(Code)"));
        actual.put("path resolves into a REPEATABLE group",
                verdict("../Lines/Qty", "FieldFilled(Lines/Qty)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("path resolves but the condition does not reference it", "MVK_ERROR_FIELD_NOT_REFERENCED");
        expected.put("path resolves into a REPEATABLE group", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "a resolvable error-field path is necessary, not sufficient");
    }

    private static String verdict(String errorFieldRelPath, String condition) {
        ValidationResult r = KernelLaws.checkModel(ORDER, "/Order", errorFieldRelPath, condition);
        return r.ok() ? KernelLaws.ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse(UNCODED)).distinct().reduce((a, b) -> a + "," + b).orElse("?");
    }
}
