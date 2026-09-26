package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
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
 * condition parser, so this is the Kernel oracle its relative-path arithmetic answers to. Two rows carry an
 * UNCODED verdict rather than an {@code MVK_*} one, and that is the finding rather than a gap in the fixture:
 * both fail during CONVERSION, before any classified check runs, so the model never receives a consistency
 * verdict at all and a portable report must not present its own reading of such a model as A12's.
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
        // The member is strictly RELATIVE: an ABSOLUTE spelling that names the very field the legal row
        // reaches is refused during conversion, so "relPath" is a contract and not just a naming convention.
        actual.put("absolute, naming the same field", verdict("/Order/Code", "FieldFilled(Code)"));
        // An up-step after a named segment, which a condition reference refuses as a token: the error target's
        // resolver is a different one and takes `..` lexically, so it is measured here rather than carried over.
        actual.put("an up-step after a named segment", verdictByText("../Meta/../Code", "FieldFilled(Code)"));
        actual.put("an up-step after the target", verdictByText("../Code/..", "FieldFilled(Code)"));
        actual.put("naming a group", verdictByText("../Meta", "FieldFilled(Code)"));
        actual.put("naming the rule's own group", verdictByText("../../Order", "FieldFilled(Code)"));
        actual.put("a nested rule naming its own group", verdictByText("/Order/Meta", "../../Meta", "FieldFilled(Ref)"));
        actual.put("a nested rule naming the root group", verdictByText("/Order/Meta", "../..", "FieldFilled(Ref)"));
        actual.put("an up-step landing on a nested group", verdictByText("../Meta/Ref/..", "FieldFilled(Code)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("sibling field, with the parent step", KernelLaws.ADMITTED);
        // Without `../` the path names a CHILD of the rule element, which no model declares.
        expected.put("sibling field, without the parent step",
                "MVK_ERROR_FIELD_MISSING,MVK_MISSING_ERROR_FIELD");
        expected.put("field one level deeper", KernelLaws.ADMITTED);
        expected.put("up to the root, then down again", KernelLaws.ADMITTED);
        expected.put("one step PAST the root", UNCODED);
        expected.put("absolute, naming the same field", UNCODED);
        expected.put("an up-step after a named segment", KernelLaws.ADMITTED);
        // A group target is judged like a field no condition names, unless it is the rule's OWN group: then the
        // build fails, by whichever spelling reaches it, while the root is an ordinary group to a nested rule.
        expected.put("an up-step after the target", NOT_BUILDABLE);
        expected.put("naming a group", "MVK_ERROR_FIELD_NOT_REFERENCED");
        expected.put("an up-step landing on a nested group", "MVK_ERROR_FIELD_NOT_REFERENCED");
        expected.put("naming the rule's own group", NOT_BUILDABLE);
        expected.put("a nested rule naming its own group", NOT_BUILDABLE);
        expected.put("a nested rule naming the root group", "MVK_ERROR_FIELD_NOT_REFERENCED");
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

    /** The computation's target, the sibling carrier of the rule's: which groups it may name. */
    @Test
    void aComputationsTargetNamingAGroup() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a field, the control", computing("../Code"));
        actual.put("its own group", computing(".."));
        actual.put("another group", computing("../Meta"));
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a field, the control", KernelLaws.ADMITTED);
        // Unlike the rule's target, ANY group fails the build here, the declaring one or another.
        expected.put("its own group", NOT_BUILDABLE);
        expected.put("another group", NOT_BUILDABLE);
        assertEquals(expected, actual, "which groups a computation's target may name");
    }

    private static String computing(String target) {
        List<io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic> diagnostics = new RuleValidator().check(ORDER,
                new DraftComputation("/Order", "ProbeCalc", target, null,
                        List.of(new DraftComputation.Alternative(null, "\"x\"")), List.of(), null,
                        List.of(new LocalizedText("en_US", "x")))).diagnostics();
        return diagnostics.isEmpty() ? KernelLaws.ADMITTED : diagnostics.stream()
                .map(ErrorFieldRelativePathLawsTest::byText).distinct().reduce((a, b) -> a + "," + b).orElse("?");
    }

    /** As {@link #verdict}, a codeless finding by its text, since the class it stands for is the question. */
    private static String verdictByText(String errorFieldRelPath, String condition) {
        return verdictByText("/Order", errorFieldRelPath, condition);
    }

    private static String verdictByText(String group, String errorFieldRelPath, String condition) {
        ValidationResult r = KernelLaws.checkModel(ORDER, group, errorFieldRelPath, condition);
        return r.ok() ? KernelLaws.ADMITTED : r.diagnostics().stream()
                .map(ErrorFieldRelativePathLawsTest::byText).distinct().reduce((a, b) -> a + "," + b).orElse("?");
    }

    /**
     * A codeless finding by its text, except the kernel failing to BUILD the model, whose text is an internal
     * exception's (an index out of range, a failed cast) and names no law.
     */
    private static String byText(io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic d) {
        return d.code().orElseGet(() -> d.summary().contains("the kernel could not build this model")
                ? NOT_BUILDABLE : "(no code: " + d.summary() + ")");
    }

    private static final String NOT_BUILDABLE = "not buildable";

    private static String verdict(String errorFieldRelPath, String condition) {
        ValidationResult r = KernelLaws.checkModel(ORDER, "/Order", errorFieldRelPath, condition);
        return r.ok() ? KernelLaws.ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse(UNCODED)).distinct().reduce((a, b) -> a + "," + b).orElse("?");
    }
}
