package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The {@code batch} verb — run a sequence of verb invocations in one process so JVM startup and the
 * kernel's one-time StringTemplate warm-up are amortized across all ops (the benchmark-runner enabler).
 * Each op runs through the same {@link Cli#run} seam; per-op results are collected into one aggregate envelope.
 */
class BatchVerbTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path model(Path dir) throws IOException {
        Path f = dir.resolve("m.dm.json");
        Files.writeString(f, CliTestSupport.subscriptionJson());
        return f;
    }

    @Test
    void anApplyShapedOpIsRedirectedToApply(@TempDir Path dir) throws Exception {
        // an apply op-record ({target,op,…}) fed to batch — name it + point at the right verb (cross-type corrective)
        Path opsFile = dir.resolve("ops.json");
        Files.writeString(opsFile, """
                [ {"target":"field","op":"add","group":"/Subscription","name":"X","kind":"NUMBER"} ]""");

        var res = CliTestSupport.run("batch", opsFile.toString());
        assertEquals(2, res.code());
        JsonNode envelope = MAPPER.readTree(res.out());
        assertEquals("refused", envelope.path("outcome").asText(), res::out);
        assertFalse(envelope.path("written").asBoolean(), res::out);
        JsonNode diagnostic = envelope.path("diagnostics").get(0);
        assertEquals("RK_UNKNOWN_ARG", diagnostic.path("code").asText());
        assertTrue(diagnostic.path("fix").asText().contains("dmtool apply"),
                () -> "points at `apply` before dispatch: " + diagnostic);
    }

    @Test
    void runsMultipleOpsInOneInvocationAndCarriesPerOpResults(@TempDir Path dir) throws Exception {
        String m = model(dir).toString();
        String ops = """
                [
                  {"verb":"rule","args":["check","-m","%s","--field","/Subscription/Billing/EffectiveFee","--condition","[EffectiveFee] < [BaseFee]","--code","FEE"]},
                  {"verb":"rule","args":["check","-m","%s","--field","/Subscription/Billing/EffectiveFee","--condition","@@@ not a condition @@@","--code","X"]},
                  {"verb":"model","args":["describe","-m","%s"]}
                ]""".formatted(m, m, m);
        Path opsFile = dir.resolve("ops.json");
        Files.writeString(opsFile, ops);

        var res = CliTestSupport.run("batch", opsFile.toString());
        assertEquals(1, res.code(), "a failed child makes the aggregate unsuccessful");
        JsonNode envelope = MAPPER.readTree(res.out());
        assertFalse(envelope.path("ok").asBoolean(), res::out);
        assertTrue(envelope.path("data").path("dispatched").asBoolean(), res::out);
        assertFalse(envelope.path("data").path("allSucceeded").asBoolean(), res::out);
        JsonNode arr = batchItems(res.out());
        assertEquals(3, arr.size(), () -> "one result per op: " + res.out());

        // op0 — a valid candidate: exit 0, its JSON result embedded
        assertEquals("rule", arr.get(0).get("verb").asText());
        assertEquals(0, arr.get(0).get("exit").asInt());
        assertTrue(arr.get(0).get("result").get("valid").asBoolean(), "valid candidate");

        // op1 — an invalid candidate: non-zero exit, but the batch did NOT abort
        assertEquals(1, arr.get(1).get("exit").asInt(), "a failing op keeps its own exit code");
        assertFalse(arr.get(1).get("result").get("valid").asBoolean());

        // op2 — a different verb ran in the same process (warm cache)
        assertEquals("model", arr.get(2).get("verb").asText());
        assertEquals(0, arr.get(2).get("exit").asInt());
    }

    @Test
    void diagnosticsAreAttributableToTheirOp(@TempDir Path dir) throws Exception {
        // Two checks that BOTH fail with kernel diagnostics on the SAME synthetic rule name
        // (/Subscription/Addons/CheckRule). The diagnostics alone can't tell them apart — only the
        // self-describing wrapper (op index + echoed id + echoed args) attributes each correctly.
        String m = model(dir).toString();
        String ops = """
                [
                  {"id":"neg-in-iter","verb":"rule","args":["check","-m","%s","--field","/Subscription/Addons/MonthlyFee","--condition","FieldNotFilled(MonthlyFee)","--code","A"]},
                  {"id":"syntax","verb":"rule","args":["check","-m","%s","--field","/Subscription/Addons/MonthlyFee","--condition","@@@ junk @@@","--code","B"]}
                ]""".formatted(m, m);
        Path opsFile = dir.resolve("ops.json");
        Files.writeString(opsFile, ops);

        var res = CliTestSupport.run("batch", opsFile.toString());
        JsonNode arr = batchItems(res.out());

        // both ops failed with their own kernel diagnostics, kept distinct
        assertEquals(1, arr.get(0).get("exit").asInt());
        assertEquals(1, arr.get(1).get("exit").asInt());
        // each result is self-describing: explicit op index, echoed id, echoed args
        assertEquals(0, arr.get(0).get("op").asInt());
        assertEquals(1, arr.get(1).get("op").asInt());
        assertEquals("neg-in-iter", arr.get(0).get("id").asText());
        assertEquals("syntax", arr.get(1).get("id").asText());
        assertTrue(arr.get(0).get("args").toString().contains("FieldNotFilled"), "the args that caused op 0 are on op 0");
        assertTrue(arr.get(1).get("args").toString().contains("junk"), "the args that caused op 1 are on op 1");
        // and the diagnostics themselves differ, so the attribution is checkable end-to-end
        assertTrue(arr.get(0).get("result").get("diagnostics").toString().contains("MVK_NEG_CONDITION_IN_ITERATION"));
        assertTrue(arr.get(1).get("result").get("diagnostics").toString().contains("MVK_UNEXPECTED_TOKEN"));
    }

    @Test
    void malformedOpsFileIsABatchLevelError(@TempDir Path dir) throws Exception {
        Path opsFile = dir.resolve("ops.json");
        Files.writeString(opsFile, "{ not an array }");
        var res = CliTestSupport.run("batch", opsFile.toString());
        assertNotEquals(0, res.code(), "a malformed ops file fails at the batch level");
    }

    @Test
    void validatesThePublishedBatchFrameBeforeDispatchingAnyChild(@TempDir Path dir) throws Exception {
        Path m = model(dir);
        String before = Files.readString(m);
        Path fieldSpec = dir.resolve("field.json");
        Files.writeString(fieldSpec,
                "{\"group\":\"/Subscription/Billing\",\"name\":\"MustNotAppear\",\"kind\":\"STRING\"}");
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, "[{\"id\":\"write\",\"verb\":\"field\",\"args\":[\"add\","
                + MAPPER.writeValueAsString(fieldSpec.toString()) + "],\"typo\":true}]");

        var res = CliTestSupport.run("-m", m.toString(), "batch", ops.toString());

        assertEquals(1, res.code(), res::out);
        JsonNode envelope = MAPPER.readTree(res.out());
        assertEquals("rejected", envelope.path("outcome").asText(), res::out);
        assertEquals("RK_UNKNOWN_ARG", envelope.path("diagnostics").get(0).path("code").asText(), res::out);
        assertFalse(envelope.path("written").asBoolean(), res::out);
        assertEquals(before, Files.readString(m), "frame validation must happen before the first child is invoked");
    }

    @Test
    void aMissingRequiredKeyInALaterItemIsRejectedBeforeTheFirstWrite(@TempDir Path dir) throws Exception {
        Path m = model(dir);
        String before = Files.readString(m);
        Path fieldSpec = dir.resolve("field.json");
        Files.writeString(fieldSpec,
                "{\"group\":\"/Subscription/Billing\",\"name\":\"MustNotAppear\",\"kind\":\"STRING\"}");
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, "[{\"verb\":\"field\",\"args\":[\"add\"," +
                MAPPER.writeValueAsString(fieldSpec.toString()) + "]},{}]");

        var res = CliTestSupport.run("-m", m.toString(), "batch", ops.toString());

        assertEquals(1, res.code(), res::out);
        JsonNode envelope = MAPPER.readTree(res.out());
        assertEquals("rejected", envelope.path("outcome").asText(), res::out);
        assertEquals("RK_INVALID_VALUE", envelope.path("diagnostics").get(0).path("code").asText(), res::out);
        assertEquals(before, Files.readString(m), "the complete schema frame is checked before child zero");
    }

    @Test
    void rejectsWrongTypedFrameValuesInsteadOfCoercingOrDroppingThem(@TempDir Path dir) throws Exception {
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, "[{\"verb\":\"model\",\"args\":\"check\"}]");

        var res = CliTestSupport.run("batch", ops.toString());

        assertEquals(1, res.code(), res::out);
        JsonNode envelope = MAPPER.readTree(res.out());
        assertEquals("RK_INVALID_VALUE", envelope.path("diagnostics").get(0).path("code").asText(), res::out);
    }

    @Test
    void validOptionalIdAndOmittedArgsStillDispatch(@TempDir Path dir) throws Exception {
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, "[{\"id\":\"catalog\",\"verb\":\"manifest\"}]");

        var res = CliTestSupport.run("batch", ops.toString());

        assertEquals(0, res.code(), res::out);
        JsonNode result = batchItems(res.out()).get(0);
        assertEquals("catalog", result.path("id").asText(), res::out);
        assertEquals(0, result.path("exit").asInt(), res::out);
    }

    @Test
    void refusesToNest(@TempDir Path dir) throws Exception {
        Path opsFile = dir.resolve("ops.json");
        Files.writeString(opsFile, "[ {\"verb\":\"batch\",\"args\":[\"x\"]} ]");
        var res = CliTestSupport.run("batch", opsFile.toString());
        assertEquals(1, res.code(), "the nested child failed even though the batch dispatched it");
        JsonNode arr = batchItems(res.out());
        assertEquals(2, arr.get(0).get("exit").asInt(), "but a nested batch op is rejected");
        assertTrue(arr.get(0).get("stderr").asText().toLowerCase().contains("nest"));
    }

    // --- G18: a batch-level -m is the default for ops that omit their own (was accepted but ignored) ---

    @Test
    void batchLevelModelIsPropagatedToOpsThatOmitIt(@TempDir Path dir) throws Exception {
        Path m = model(dir);
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, "[ {\"verb\":\"model\",\"args\":[\"check\"]}, {\"verb\":\"config\",\"args\":[\"read\"]} ]");
        // set -m once on the batch; the ops carry no -m of their own and should inherit it
        var res = CliTestSupport.run("-m", m.toString(), "batch", ops.toString());
        assertEquals(0, res.code(), res::err);
        JsonNode envelope = MAPPER.readTree(res.out());
        assertTrue(envelope.path("ok").asBoolean(), res::out);
        assertTrue(envelope.path("data").path("dispatched").asBoolean(), res::out);
        assertTrue(envelope.path("data").path("allSucceeded").asBoolean(), res::out);
        JsonNode arr = batchItems(res.out());
        assertEquals(0, arr.get(0).get("exit").asInt(), () -> "model check used the batch -m: " + arr);
        assertEquals(0, arr.get(1).get("exit").asInt(), () -> "config read used the batch -m: " + arr);
    }

    @Test
    void anOpsOwnModelOverridesTheBatchLevelOne(@TempDir Path dir) throws Exception {
        Path subscription = model(dir);                   // has no /Order rules
        Path orderRuled = CliTestSupport.orderRuled(dir);  // has /Order/EligibilityCheck
        Path ops = dir.resolve("ops.json");
        // the op carries its own -m (orderRuled); the batch-level -m (subscription) must NOT override it
        Files.writeString(ops, "[ {\"verb\":\"rule\",\"args\":[\"read\",\"/Order/EligibilityCheck\",\"-m\","
                + MAPPER.writeValueAsString(orderRuled.toString()) + "]} ]");
        var res = CliTestSupport.run("-m", subscription.toString(), "batch", ops.toString());
        assertEquals(0, res.code(), res::err);
        JsonNode arr = batchItems(res.out());
        assertEquals(0, arr.get(0).get("exit").asInt(),
                () -> "the op's own -m (orderRuled) won — it read an Order rule the batch model lacks: " + arr);
    }

    @Test
    void aLaterFailureIsReportedWithoutPretendingEarlierWritesWereRolledBack(@TempDir Path dir) throws Exception {
        Path m = model(dir);
        Path fieldSpec = dir.resolve("batch-field.json");
        Files.writeString(fieldSpec, """
                {"group":"/Subscription/Billing","name":"BatchCommitted","kind":"STRING"}
                """);
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, """
                [
                  {"id":"first-write","verb":"field","args":["add",%s]},
                  {"id":"second-rejected-duplicate","verb":"field","args":["add",%s]}
                ]
                """.formatted(
                    MAPPER.writeValueAsString(fieldSpec.toString()),
                    MAPPER.writeValueAsString(fieldSpec.toString())));

        var res = CliTestSupport.run("-m", m.toString(), "batch", ops.toString());

        assertEquals(1, res.code(), res::out);
        JsonNode envelope = MAPPER.readTree(res.out());
        assertEquals("completed", envelope.path("outcome").asText(), res::out);
        assertFalse(envelope.path("ok").asBoolean(), res::out);
        assertTrue(envelope.path("data").path("dispatched").asBoolean(), res::out);
        assertFalse(envelope.path("data").path("allSucceeded").asBoolean(), res::out);
        JsonNode results = envelope.path("data").path("results");
        assertEquals(0, results.get(0).path("exit").asInt(), results::toString);
        assertTrue(results.get(0).path("result").path("written").asBoolean(), results::toString);
        assertEquals(1, results.get(1).path("exit").asInt(), results::toString);

        var read = CliTestSupport.run(
                "-m", m.toString(), "field", "read", "/Subscription/Billing/BatchCommitted");
        assertEquals(0, read.code(), "batch is explicitly non-transactional; the first write remains");
    }

    /**
     * The per-op items. `batch` now returns the standard result envelope with them under {@code data.results},
     * where it used to print a bare JSON array — which is exactly why {@code batch --text} emitted raw JSON: the
     * renderer needs an object carrying a textual {@code outcome}, and an array has neither. The ITEMS themselves
     * are unchanged, so every assertion below still reads the same fields.
     */
    private static JsonNode batchItems(String stdout) throws Exception {
        JsonNode envelope = MAPPER.readTree(stdout);
        assertEquals("completed", envelope.path("outcome").asText(),
                () -> "a fully dispatched batch is `completed` whatever the children returned: " + stdout);
        return envelope.path("data").path("results");
    }
}
