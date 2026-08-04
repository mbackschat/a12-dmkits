package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>The runtime verbs refuse a condition or operation that names an entity the model does not declare</b>,
 * instead of answering about a rule that cannot exist (G46).
 *
 * <p>The defect had three faces and one cause. A presence predicate over a missing path read the cell as empty
 * and the rule <b>FIRED</b> ({@code type: OMISSION_ERROR}, {@code referenced: []} — a message about operands it
 * never found); a starred aggregate collapsed to an empty row domain and the rule <b>PASSED</b>, the false-clean
 * shape the {@code unsupported} channel exists to prevent; and a computation operand, having no empty reading at
 * all, died as an untyped {@code computation failed:} line with no envelope and no code. The kernel's own gate
 * rejects every one of these models as {@code MVK_INVALID_ENTITY}, so the refusal is not stricter than the
 * kernel — it reaches the same verdict without one, which is what lets it work on the native binary.
 *
 * <p><b>The stored-rule cases are here because the class turned out to be wider than reported.</b> The gap was
 * written up as candidate-only, on the reasoning that every dmtool write path gates references before storing —
 * true, and irrelevant to a model that arrived from anywhere else. Injecting the same condition into a stored
 * rule reproduced the identical false FIRED, so the gate belongs at the evaluation entry, not on the candidate
 * flag.
 *
 * <p>The over-rejection controls live next to each refusal: the same verbs on the same fixture, with the
 * reference spelled correctly, must still evaluate. A gate that refused those would be worse than the leniency
 * it replaced — the breadth of reference binding (declaring-group first, then the optional model-wide short
 * name) is what {@code UndeclaredReferenceTest} pins shape by shape on the interpreter side.
 */
class UndeclaredEntityReferenceRefusalTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String CODE = "RK_UNDECLARED_ENTITY_REFERENCE";

    private static Path doc(Path dir) throws Exception {
        Path file = dir.resolve("order.json");
        // Two rows sharing a COUNT, so a uniqueness rule over a correct reference genuinely fires — otherwise
        // the starred positive control could pass for the wrong reason. The Skus stay distinct on purpose: Sku
        // is the group's index field, and duplicating it makes both rows formally invalid, which suppresses
        // every rule over them and would hide the very verdict these controls read.
        Files.writeString(file, """
                { "Order": { "CustomerName": "Ada", "Items": [ { "Sku": "A-1", "Count": "2" },
                                                               { "Sku": "A-2", "Count": "2" } ] } }""");
        return file;
    }

    /** The {@code order} fixture's model, whose {@code Order/Items*} rows carry {@code Sku} and {@code Count}. */
    private static Path model(Path dir) throws Exception {
        return CliTestSupport.order(dir);
    }

    /** Store a rule by injecting it into the model JSON — deliberately bypassing the write path's own gate. */
    private static Path modelWithStoredRule(Path dir, String name, String group, String condition,
            String errorRel) throws Exception {
        JsonNode root = M.readTree(Files.readString(model(dir)));
        JsonNode rule = M.readTree("""
                { "type": "Rule", "id": "%s/%s", "name": "%s", "Rule": {
                    "errorEntityRelPath": "%s", "errorCode": "%s", "errorCondition": "%s", "severity": "ERROR",
                    "errorMessage": [ { "locale": "en_US", "text": "An injected rule." } ] } }"""
                .formatted(group, name, name, errorRel, name, condition));
        JsonNode target = root.at("/content/modelRoot/rootGroups/0/Group/elements");
        ((com.fasterxml.jackson.databind.node.ArrayNode) target).add(rule);
        Path file = dir.resolve("order-injected.dm.json");
        Files.writeString(file, M.writeValueAsString(root));
        return file;
    }

    /** Assert a refused envelope naming {@code path}, with the corrective code and a fix on every diagnostic. */
    private static void assertRefused(CliTestSupport.Result res, String path) throws Exception {
        assertEquals(2, res.code(), () -> "an undeclared reference is refused, not answered: " + res.out());
        JsonNode env = M.readTree(res.out());
        assertEquals("refused", env.path("outcome").asText(), () -> res.out());
        assertEquals(false, env.path("ok").asBoolean(true), () -> res.out());
        JsonNode diagnostics = env.path("diagnostics");
        assertTrue(diagnostics.size() >= 1, () -> "carries a diagnostic: " + res.out());
        boolean named = false;
        for (JsonNode d : diagnostics) {
            assertEquals(CODE, d.path("code").asText(), () -> res.out());
            assertTrue(!d.path("fix").asText().isBlank(), () -> "corrective: " + res.out());
            assertTrue(!d.path("explain").asText().isBlank(), () -> "explains the code: " + res.out());
            named |= path.equals(d.path("where").path("path").asText());
        }
        assertTrue(named, () -> "names " + path + " in where.path: " + res.out());
        assertTrue(env.path("data").isMissingNode() || env.path("data").path("verdict").isMissingNode(),
                () -> "publishes no verdict for a rule that cannot exist: " + res.out());
    }

    // ---------------------------------------------------------------------------------------------------
    // The candidate routes — the two the gap reported
    // ---------------------------------------------------------------------------------------------------

    @Test
    void ruleEvalRefusesACandidateWhoseConditionNamesAnUndeclaredField(@TempDir Path dir) throws Exception {
        assertRefused(CliTestSupport.run("-m", model(dir).toString(), "rule", "eval",
                "--condition", "FieldNotFilled(NoSuchField)", "--field", "/Order/Items/Sku",
                "--instance", doc(dir).toString()), "/Order/Items/NoSuchField");
    }

    @Test
    void ruleEvalStillEvaluatesTheSameCandidateSpelledCorrectly(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("-m", model(dir).toString(), "rule", "eval",
                "--condition", "FieldNotFilled(Count)", "--field", "/Order/Items/Sku",
                "--instance", doc(dir).toString());
        assertEquals(0, res.code(), res::err);
        assertEquals("passed", M.readTree(res.out()).path("data").path("verdict").asText(),
                () -> "the declared reference evaluates as before: " + res.out());
    }

    @Test
    void modelEvalRefusesACandidateWhoseConditionNamesAnUndeclaredField(@TempDir Path dir) throws Exception {
        assertRefused(CliTestSupport.run("-m", model(dir).toString(), "model", "eval",
                "--condition", "FieldNotFilled(NoSuchField)", "--field", "/Order/Items/Sku",
                "--instance", doc(dir).toString()), "/Order/Items/NoSuchField");
    }

    @Test
    void ruleEvalRefusesAStarredCandidateReference(@TempDir Path dir) throws Exception {
        // The false-PASSED face: this used to report verdict "passed" — a clean bill of health for a rule the
        // kernel rejects outright.
        assertRefused(CliTestSupport.run("-m", model(dir).toString(), "rule", "eval",
                "--condition", "FieldValuesNotUnique(Items*/NoSuchField)", "--field", "/Order/CustomerName",
                "--instance", doc(dir).toString()), "/Order/Items/NoSuchField");
    }

    @Test
    void ruleEvalStillFiresTheSameStarredCandidateSpelledCorrectly(@TempDir Path dir) throws Exception {
        // The control that makes the refusal above meaningful: with the reference correct, the duplicate Skus
        // really do trip the rule, so the gate is not simply suppressing a rule that never fired.
        var res = CliTestSupport.run("-m", model(dir).toString(), "rule", "eval",
                "--condition", "FieldValuesNotUnique(Items*/Count)", "--field", "/Order/CustomerName",
                "--instance", doc(dir).toString());
        assertEquals(0, res.code(), res::err);
        assertEquals("fired", M.readTree(res.out()).path("data").path("verdict").asText(),
                () -> "the declared starred reference still fires on the duplicate counts: " + res.out());
    }

    // ---------------------------------------------------------------------------------------------------
    // The stored routes — the siblings the reported scope missed
    // ---------------------------------------------------------------------------------------------------

    @Test
    void ruleEvalRefusesAStoredRuleWhoseConditionNamesAnUndeclaredField(@TempDir Path dir) throws Exception {
        Path model = modelWithStoredRule(dir, "InjectedRule", "/Order",
                "FieldNotFilled(NoSuchField)", "../CustomerName");
        assertRefused(CliTestSupport.run("-m", model.toString(), "rule", "eval", "/Order/InjectedRule",
                "--instance", doc(dir).toString()), "/Order/NoSuchField");
    }

    @Test
    void modelEvalRefusesAStoredRuleWhoseConditionNamesAnUndeclaredField(@TempDir Path dir) throws Exception {
        Path model = modelWithStoredRule(dir, "InjectedRule", "/Order",
                "FieldNotFilled(NoSuchField)", "../CustomerName");
        assertRefused(CliTestSupport.run("-m", model.toString(), "model", "eval",
                "--instance", doc(dir).toString()), "/Order/NoSuchField");
    }

    @Test
    void modelComputeRefusesAnOperationNamingAnUndeclaredField(@TempDir Path dir) throws Exception {
        // The third face: before the gate this exited 2 with a bare "computation failed: no kind declared for
        // …" line — no envelope, no code, nothing an agent could act on.
        Path base = CliTestSupport.modelWithRule(dir);
        Path spec = dir.resolve("comp.json");
        Files.writeString(spec, """
                { "computedField": "/Subscription/Billing/EffectiveFee",
                  "alternatives": [ { "operation": "[BaseFee]" } ],
                  "messages": [ {"locale":"en_US","text":"x"}, {"locale":"de_DE","text":"y"} ] }""");
        Path withComp = dir.resolve("computed.dm.json");
        var added = CliTestSupport.run("-m", base.toString(), "computation", "add",
                spec.toString(), "-o", withComp.toString());
        assertEquals(0, added.code(), added::err);
        // Retarget the stored operation at a field that does not exist — the write path would have refused it.
        Files.writeString(withComp, Files.readString(withComp).replace("[BaseFee]", "[NoSuchFee]"));

        Path instance = dir.resolve("sub.json");
        Files.writeString(instance, """
                { "Subscription": { "Billing": { "BaseFee": "50" } } }""");
        assertRefused(CliTestSupport.run("-m", withComp.toString(), "model", "compute",
                "--instance", instance.toString()), "/Subscription/Billing/NoSuchFee");
    }

    // ---------------------------------------------------------------------------------------------------
    // Shape of the refusal
    // ---------------------------------------------------------------------------------------------------

    @Test
    void refusesOncePerUndeclaredReferenceSoAllOfThemAreRepairable(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("-m", model(dir).toString(), "rule", "eval",
                "--condition", "FieldNotFilled(NoSuchA) And FieldNotFilled(NoSuchB)",
                "--field", "/Order/Items/Sku", "--instance", doc(dir).toString());
        assertEquals(2, res.code(), res::err);
        JsonNode diagnostics = M.readTree(res.out()).path("diagnostics");
        assertEquals(2, diagnostics.size(), () -> "one diagnostic per bad reference: " + res.out());
        assertEquals(List.of("/Order/Items/NoSuchA", "/Order/Items/NoSuchB"),
                List.of(diagnostics.path(0).path("where").path("path").asText(),
                        diagnostics.path(1).path("where").path("path").asText()),
                () -> res.out());
    }

    @Test
    void everyOtherRuntimeVerbOnACleanModelIsUnaffected(@TempDir Path dir) throws Exception {
        // The gate runs on every runtime route, so a clean model must still pass all of them — otherwise the
        // fix would be measurable only by the failures it causes.
        Path model = model(dir);
        Path instance = doc(dir);
        for (String[] args : List.of(
                new String[]{"-m", model.toString(), "model", "eval", "--instance", instance.toString()},
                new String[]{"-m", model.toString(), "model", "compute", "--instance", instance.toString()})) {
            var res = CliTestSupport.run(args);
            assertEquals(0, res.code(), () -> String.join(" ", args) + ": " + res.err());
            assertNotEquals(CODE, M.readTree(res.out()).path("diagnostics").path(0).path("code").asText(),
                    () -> "a clean model is never refused: " + res.out());
        }
    }
}
