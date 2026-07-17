package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * G42 — {@code model eval --condition/--field} (the injected candidate) must assemble its runtime messages
 * through the SAME per-row path as the persisted equivalent: one <b>row-indexed</b> message per firing
 * ({@code /Order[1]/Items[2]/Count}), never a single index-less collapse ({@code /Order/Items/Count}). The
 * lock is a parity: for the same model + instance + rule, the candidate's {@code (code, field)} message
 * multiset is IDENTICAL to persisting that rule and running whole-model {@code model eval} — and, where the
 * interpreter matches the kernel, identical to the kernel's own candidate eval.
 */
class EvalCandidatePersistedParityTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** {@code (code -> sorted field-pointer list)} of the messages carrying {@code code}, from a {@code data} envelope. */
    private static TreeMap<String, List<String>> fieldsOf(String out, String code) throws Exception {
        JsonNode data = M.readTree(out).get("data");
        List<String> fields = new java.util.ArrayList<>();
        for (JsonNode m : data.get("messages")) {
            if (m.get("code").asText().equals(code)) {
                fields.add(m.get("field").asText());
            }
        }
        java.util.Collections.sort(fields);
        TreeMap<String, List<String>> byCode = new TreeMap<>();
        byCode.put(code, fields);
        return byCode;
    }

    private static Path instance(Path dir, String json) throws Exception {
        Path f = dir.resolve("inst-" + Integer.toHexString(json.hashCode()) + ".json");
        Files.writeString(f, json);
        return f;
    }

    /** Persist a rule via {@code rule add} into a fresh model file and return it. */
    private static Path persist(Path dir, Path model, String field, String condition, String code) throws Exception {
        Path spec = dir.resolve(code + "-spec.json");
        Files.writeString(spec, M.createObjectNode()
                .put("field", field).put("condition", condition).put("code", code)
                .set("messages", M.createArrayNode().add(M.createObjectNode().put("locale", "en_US").put("text", code)))
                .toPrettyString());
        Path out = dir.resolve(code + "-persisted.dm.json");
        var add = CliTestSupport.run("-m", model.toString(), "rule", "add", spec.toString(), "-o", out.toString());
        assertEquals("applied", M.readTree(add.out()).path("outcome").asText(), () -> "rule add applied: " + add.out());
        return out;
    }

    @Test
    void perRowCandidateMatchesPersistedAndKernel(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        // three Items rows; only row 2 (Count 80) violates "> 50"
        Path inst = instance(dir, """
                { "fields": { "Order": { "Items": [
                    { "Sku": "A", "Count": "10" },
                    { "Sku": "B", "Count": "80" },
                    { "Sku": "C", "Count": "20" } ] } } }""");
        String cond = "FieldFilled(Count) And [Count] > 50";

        var candidate = CliTestSupport.run("-m", model.toString(), "model", "eval", "--instance", inst.toString(),
                "--condition", cond, "--field", "/Order/Items/Count", "--code", "COUNT_CAP");
        var kernelCandidate = CliTestSupport.runKernel("-m", model.toString(), "model", "eval", "--instance", inst.toString(),
                "--condition", cond, "--field", "/Order/Items/Count", "--code", "COUNT_CAP");
        Path persisted = persist(dir, model, "/Order/Items/Count", cond, "COUNT_CAP");
        var persistedEval = CliTestSupport.run("-m", persisted.toString(), "model", "eval", "--instance", inst.toString());

        // the correct row-indexed attachment: one message, on row 2
        assertEquals(List.of("/Order[1]/Items[2]/Count"), fieldsOf(persistedEval.out(), "COUNT_CAP").get("COUNT_CAP"),
                () -> "persisted fires row 2 only: " + persistedEval.out());
        // candidate assembles identically to persisted …
        assertEquals(fieldsOf(persistedEval.out(), "COUNT_CAP"), fieldsOf(candidate.out(), "COUNT_CAP"),
                () -> "candidate (code,field) multiset == persisted: " + candidate.out());
        // … and identically to the kernel's own candidate eval
        assertEquals(fieldsOf(kernelCandidate.out(), "COUNT_CAP"), fieldsOf(candidate.out(), "COUNT_CAP"),
                () -> "candidate (code,field) multiset == kernel candidate: " + candidate.out());
    }

    @Test
    void starAggregateCandidateMatchesPersisted(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path inst = instance(dir, """
                { "fields": { "Order": { "Items": [
                    { "Sku": "A", "Count": "1" },
                    { "Sku": "B", "Count": "2" },
                    { "Sku": "C", "Count": "3" } ] } } }""");
        String cond = "Sum(/Order/Items*/Count) > -1";

        var candidate = CliTestSupport.run("-m", model.toString(), "model", "eval", "--instance", inst.toString(),
                "--condition", cond, "--field", "/Order/Items/Count", "--code", "AGG_ABS");
        Path persisted = persist(dir, model, "/Order/Items/Count", cond, "AGG_ABS");
        var persistedEval = CliTestSupport.run("-m", persisted.toString(), "model", "eval", "--instance", inst.toString());

        // whatever the interpreter's per-row assembly yields for the persisted rule, the candidate yields the
        // SAME (code,field) multiset — no index-less collapse (robust to a future iteration-scope refinement).
        List<String> candFields = fieldsOf(candidate.out(), "AGG_ABS").get("AGG_ABS");
        assertFalse(candFields.contains("/Order/Items/Count"),
                () -> "candidate must not report an index-less field: " + candidate.out());
        assertEquals(fieldsOf(persistedEval.out(), "AGG_ABS"), fieldsOf(candidate.out(), "AGG_ABS"),
                () -> "candidate (code,field) multiset == persisted: " + candidate.out());
    }
}
