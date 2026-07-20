package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

/**
 * A group operand expands to every descendant field, not only the fields declared directly in the
 * group. This applies to both field-fill quantifiers and numeric value aggregates.
 */
class RecursiveGroupOperandExpansionReadTest {

    private static final DslRenderer RENDERER = new DslRenderer();

    @Test
    void expandsNestedFieldsInAMixedGroupAndFieldFillScope() {
        String condition = "FieldsNotCollectivelyFilled(Identity, Notes/Remark)";

        String rendered = read(condition, "../Notes/Remark");

        assertTrue(rendered.contains(
                        "FieldsNotCollectivelyFilled("
                                + "/Calibration/Identity/Label, "
                                + "/Calibration/Identity/Details/RegistrationCode, "
                                + "/Calibration/Notes/Remark)"),
                rendered);
    }

    @Test
    void expandsNestedNumberFieldsInAStarredGroupAggregate() {
        String condition =
                "AtLeastOneFieldFilled(Samples*/Reading) And [Total] != Sum(Samples*)";

        String rendered = read(condition, "../Total");

        assertTrue(rendered.contains(
                        "Sum("
                                + "/Calibration/Samples*/Reading, "
                                + "/Calibration/Samples*/Adjustment/Correction)"),
                rendered);
    }

    private static String read(String condition, String errorEntityRelPath) {
        ReadResult result = KernelAdapter.ofJson(inject(modelJson(), condition, errorEntityRelPath))
                .read("/Calibration/RecursiveExpansion");
        var converted = assertInstanceOf(
                ReadResult.Converted.class,
                result,
                () -> "expected recursive group operand conversion, got " + result);
        return RENDERER.render(converted.condition());
    }

    private static String modelJson() {
        return DocumentModelDraft.named("calibration").locale("en_US")
                .rootGroup("Calibration", calibration -> calibration
                        .numberField("Total", ignored -> { })
                        .group("Identity", identity -> identity
                                .stringField("Label", ignored -> { })
                                .group("Details", details ->
                                        details.stringField("RegistrationCode", ignored -> { })))
                        .group("Notes", notes ->
                                notes.stringField("Remark", ignored -> { }))
                        .group("Samples", samples -> samples.repeatable(100)
                                .numberField("Reading", ignored -> { })
                                .group("Adjustment", adjustment ->
                                        adjustment.numberField("Correction", ignored -> { }))))
                .build()
                .toJson();
    }

    private static String inject(String modelJson, String condition, String errorEntityRelPath) {
        String escaped = condition.replace("\\", "\\\\").replace("\"", "\\\"");
        String rule = "{ \"type\": \"Rule\", \"id\": \"/Calibration/RecursiveExpansion\","
                + " \"name\": \"RecursiveExpansion\", \"Rule\": { \"errorEntityRelPath\": \""
                + errorEntityRelPath + "\", \"errorCode\": \"CAL\", \"errorCondition\": \""
                + escaped + "\", \"severity\": \"ERROR\", \"errorMessage\": ["
                + " { \"locale\": \"en_US\", \"text\": \"calibration failed\" } ] } }";
        int elements = modelJson.indexOf('[', modelJson.indexOf("\"elements\""));
        return modelJson.substring(0, elements + 1)
                + "\n" + rule + ",\n" + modelJson.substring(elements + 1);
    }
}
