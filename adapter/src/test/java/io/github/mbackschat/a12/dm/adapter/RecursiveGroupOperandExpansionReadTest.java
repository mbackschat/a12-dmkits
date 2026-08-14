package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

/**
 * A group operand covers every descendant field, not only the fields declared directly in the group — and the
 * two operator families now say so in <b>different</b> ways, which is the point of keeping both rows here.
 *
 * <p>The MIXED group+field fill quantifier still replays the expansion into explicit field operands, because no
 * fill-quantifier operand type can hold a group beside a field. The value aggregates no longer do: their operand
 * stays a {@code GroupListRef}, so the recursion is the kernel's ({@code ExpandService}) and the read-back is the
 * authored text. The replay is what CG15 measured as lossy — see {@code GroupScopeAggregateRoundTripTest} for the
 * four shapes it could not represent, and the fill arm's own remaining loss recorded in the CLI conformance
 * ledger.
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

    /**
     * The aggregate arm keeps the group, so the nested {@code Adjustment/Correction} is reached by the kernel
     * rather than named here. {@code Adjustment} is NON-repeatable, so the old explicit form happened to be
     * correct on this fixture — which is exactly why the defect survived this test: make it repeatable and the
     * replay emits it unstarred.
     */
    @Test
    void aStarredGroupAggregateKeepsTheGroupAndLetsTheKernelRecurse() {
        String condition =
                "AtLeastOneFieldFilled(Samples*/Reading) And [Total] != Sum(Samples*)";

        String rendered = read(condition, "../Total");

        assertTrue(rendered.contains("Sum(/Calibration/Samples*)"), rendered);
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
