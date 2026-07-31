package io.github.mbackschat.a12.dm.cli.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

/**
 * <b>One verb, two evidence classes — and that is correct, not a bug to normalize away.</b>
 * {@code model report} assembles a {@code ModelReport}, which loads through the consistency gate; {@code model report
 * --tree} projects the structure and never gates. So they legitimately differ in what they may claim, and the whole
 * point of the result contract is that the difference is *derived from the executed path* rather than declared per
 * verb (RESULT-CONTRACT-PROPOSAL §1b).
 *
 * <p>This is the case that killed the idea of a static verb→evidence table: any such table has one row for
 * {@code model report}, and would be wrong for one of its two paths whichever way it was written. Locked here across
 * the full 2×2 — gated/ungated × valid/invalid model — because §7's acceptance row asserted this property with no
 * mechanism named, and an asserted property nobody executes is how the original seven defects shipped.
 */
class ReportEvidenceAsymmetryTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** Deserializes cleanly but fails {@code checkConsistency}: a rule operand naming a foreign root group. */
    private static Path kernelInvalid(Path dir) throws Exception {
        Path model = dir.resolve("bad.json");
        Files.writeString(model, """
                { "header": { "id": "bad", "modelType": "document", "modelVersion": "28.4.0",
                    "locales": [ { "code": "en_US" } ], "labels": [], "annotations": [], "modelReferences": [] },
                  "content": {
                    "modelInfo": { "name": "bad", "variant": "cockpit", "revision": "r", "immutable": false },
                    "modelConfig": { "decimalSeparator": ".", "timeZone": "Europe/Berlin",
                      "conditionLanguage": { "code": "en_US" }, "fieldRefByShortNameAllowed": true },
                    "modelRoot": { "rootGroups": [ { "type": "Group", "id": "/R", "name": "R", "Group": {
                      "repeatability": 1, "elements": [
                        { "type": "Field", "id": "/R/A", "name": "A", "Field": { "fieldType": { "type": "StringType" } } },
                        { "type": "Rule", "id": "/R/BAD", "name": "BAD", "Rule": {
                          "errorEntityRelPath": "../A", "errorCode": "BAD",
                          "errorCondition": "FieldFilled(/Other/A)", "severity": "ERROR",
                          "errorMessage": [ { "locale": "en_US", "text": "x" } ] } } ] } } ] } } }""");
        return model;
    }

    private static Path valid(Path dir) throws Exception {
        Path model = dir.resolve("order.json");
        Files.writeString(model, CliTestSupport.orderRuledJson());
        return model;
    }

    @Test
    void theGatedPathPublishesItsReceiptAndTheProjectionDoesNot(@TempDir Path dir) throws Exception {
        var report = CliTestSupport.run("-m", valid(dir).toString(), "model", "report");
        var tree = CliTestSupport.run("-m", valid(dir).toString(), "model", "report", "--tree", "--root", "/Order");

        assertEquals(0, report.code(), report::err);
        assertEquals("KERNEL_CONFIRMED", M.readTree(report.out()).path("verification").asText(),
                () -> "assembling a report runs checkAndOpen, so the receipt exists and must be published: "
                        + report.out());

        assertEquals(0, tree.code(), tree::err);
        assertFalse(M.readTree(tree.out()).has("verification"),
                () -> "--tree builds no report and gates nothing, so it must claim nothing — this is the honest "
                        + "asymmetry, not a gap to fill in: " + tree.out());
    }

    /**
     * The invalid-model half, which is where a static table would have been actively misleading: the gated path is
     * <em>rejected</em> and still carries its receipt (the kernel arbitrated), while the projection succeeds at exit 0
     * on the same file — correct precisely because it asserted no validity to be wrong about.
     */
    @Test
    void andOnAnInvalidModelTheyDivergeInOutcomeToo(@TempDir Path dir) throws Exception {
        Path model = kernelInvalid(dir);

        var report = CliTestSupport.run("-m", model.toString(), "model", "report");
        var tree = CliTestSupport.run("-m", model.toString(), "model", "report", "--tree", "--root", "/R");

        assertEquals(1, report.code(), () -> "the gate refused, so the verb reports it: " + report.out());
        JsonNode rejected = M.readTree(report.out());
        assertEquals("rejected", rejected.path("outcome").asText(), report::out);
        assertEquals("KERNEL_CONFIRMED", rejected.path("verification").asText(),
                () -> "a rejection the kernel made keeps the receipt — verification says WHO arbitrated, never "
                        + "that the model was valid: " + report.out());
        assertEquals(false, rejected.path("valid").asBoolean(), report::out);

        assertEquals(0, tree.code(),
                () -> "the projection makes no validity claim, so an invalid model is not its problem: " + tree.out());
        assertEquals("read", M.readTree(tree.out()).path("outcome").asText(), tree::out);
        assertFalse(M.readTree(tree.out()).has("valid"),
                () -> "and it must not claim the model is valid, which is what the pre-contract shape did: "
                        + tree.out());
    }
}
