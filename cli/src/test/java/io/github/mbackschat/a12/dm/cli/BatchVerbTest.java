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
 * Each op runs through the same {@link Cli#run} seam; per-op results are collected into a JSON array.
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
        JsonNode r0 = batchItems(res.out()).get(0);
        assertNotEquals(0, r0.path("exit").asInt());
        assertTrue(r0.path("stderr").asText().contains("apply"), () -> "points at `apply`: " + r0);
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
        assertEquals(0, res.code(), res::err);
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
    void refusesToNest(@TempDir Path dir) throws Exception {
        Path opsFile = dir.resolve("ops.json");
        Files.writeString(opsFile, "[ {\"verb\":\"batch\",\"args\":[\"x\"]} ]");
        var res = CliTestSupport.run("batch", opsFile.toString());
        assertEquals(0, res.code(), "the batch itself runs");
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

    /**
     * The per-op items. `batch` now returns the standard result envelope with them under {@code data.results},
     * where it used to print a bare JSON array — which is exactly why {@code batch --text} emitted raw JSON: the
     * renderer needs an object carrying a textual {@code outcome}, and an array has neither. The ITEMS themselves
     * are unchanged, so every assertion below still reads the same fields.
     */
    private static JsonNode batchItems(String stdout) throws Exception {
        JsonNode envelope = MAPPER.readTree(stdout);
        assertEquals("completed", envelope.path("outcome").asText(),
                () -> "a dispatched batch is `completed` whatever the children returned: " + stdout);
        return envelope.path("data").path("results");
    }
}
