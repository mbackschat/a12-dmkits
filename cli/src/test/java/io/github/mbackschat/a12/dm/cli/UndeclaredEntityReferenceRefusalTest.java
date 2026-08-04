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
 *
 * <p><b>The scope cases are the other over-rejection axis:</b> a half of the model the verb never runs must not
 * refuse it. They are paired deliberately, because the pairs are what discriminate — {@code model compute}
 * ignores a broken rule while {@code model eval} refuses on it, and {@code --no-computations} excuses a broken
 * computation on a FOCUSED route while whole-model validation still refuses on it (it evaluates the implicit
 * computation-validation rules regardless of the flag). Either half alone would be satisfied by a gate that was
 * simply too lenient or simply too broad.
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

    /**
     * Store a rule in {@code base}'s ROOT group by injecting it into the model JSON — deliberately bypassing the
     * write path's own gate, which is the only way to obtain the model a foreign producer can hand us. The rule's
     * group is read back from the group it lands in, so the injected {@code id} and the declared group can never
     * drift apart.
     */
    private static Path modelWithStoredRule(Path dir, Path base, String name, String condition, String errorRel)
            throws Exception {
        JsonNode root = M.readTree(Files.readString(base));
        String group = root.at("/content/modelRoot/rootGroups/0/id").asText();
        JsonNode rule = M.readTree("""
                { "type": "Rule", "id": "%s/%s", "name": "%s", "Rule": {
                    "errorEntityRelPath": "%s", "errorCode": "%s", "errorCondition": "%s", "severity": "ERROR",
                    "errorMessage": [ { "locale": "en_US", "text": "An injected rule." } ] } }"""
                .formatted(group, name, name, errorRel, name, condition));
        JsonNode target = root.at("/content/modelRoot/rootGroups/0/Group/elements");
        ((com.fasterxml.jackson.databind.node.ArrayNode) target).add(rule);
        Path file = dir.resolve("injected.dm.json");
        Files.writeString(file, M.writeValueAsString(root));
        return file;
    }

    /**
     * The subscription model — its clean {@code FeeCapRule} plus a computation whose operation was retargeted, in
     * the stored JSON, at a field the model does not declare. The pairing is what the scope cases need: one half
     * of the model is sound, so a refusal here can only come from the other half.
     */
    private static Path modelWithBadComputation(Path dir) throws Exception {
        Path spec = dir.resolve("comp.json");
        Files.writeString(spec, """
                { "computedField": "/Subscription/Billing/EffectiveFee",
                  "alternatives": [ { "operation": "[BaseFee]" } ],
                  "messages": [ {"locale":"en_US","text":"x"}, {"locale":"de_DE","text":"y"} ] }""");
        Path file = dir.resolve("computed.dm.json");
        var added = CliTestSupport.run("-m", CliTestSupport.modelWithRule(dir).toString(), "computation", "add",
                spec.toString(), "-o", file.toString());
        assertEquals(0, added.code(), added::err);
        Files.writeString(file, Files.readString(file).replace("[BaseFee]", "[NoSuchFee]"));
        return file;
    }

    /** A subscription instance whose {@code BaseFee} is filled and whose {@code MonthlyFee} keeps FeeCapRule quiet. */
    private static Path subscriptionDoc(Path dir) throws Exception {
        Path file = dir.resolve("sub.json");
        Files.writeString(file, """
                { "Subscription": { "Billing": { "BaseFee": "50" } } }""");
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
        Path model = modelWithStoredRule(dir, model(dir), "InjectedRule",
                "FieldNotFilled(NoSuchField)", "../CustomerName");
        assertRefused(CliTestSupport.run("-m", model.toString(), "rule", "eval", "/Order/InjectedRule",
                "--instance", doc(dir).toString()), "/Order/NoSuchField");
    }

    @Test
    void modelEvalRefusesAStoredRuleWhoseConditionNamesAnUndeclaredField(@TempDir Path dir) throws Exception {
        Path model = modelWithStoredRule(dir, model(dir), "InjectedRule",
                "FieldNotFilled(NoSuchField)", "../CustomerName");
        assertRefused(CliTestSupport.run("-m", model.toString(), "model", "eval",
                "--instance", doc(dir).toString()), "/Order/NoSuchField");
    }

    @Test
    void modelComputeRefusesAnOperationNamingAnUndeclaredField(@TempDir Path dir) throws Exception {
        // The third face: before the gate this exited 2 with a bare "computation failed: no kind declared for
        // …" line — no envelope, no code, nothing an agent could act on.
        assertRefused(CliTestSupport.run("-m", modelWithBadComputation(dir).toString(), "model", "compute",
                "--instance", subscriptionDoc(dir).toString()), "/Subscription/Billing/NoSuchFee");
    }

    // ---------------------------------------------------------------------------------------------------
    // Scope — the gate speaks for exactly what the verb evaluates, no more
    // ---------------------------------------------------------------------------------------------------

    @Test
    void aFocusedRuleEvalIsNotBlockedByAnUnrelatedRulesBadReference(@TempDir Path dir) throws Exception {
        // `rule eval <path>` evaluates ONE rule, so another rule's undeclared reference cannot change its
        // verdict and must not refuse it. This is also why the focused route asks only about the rule it was
        // given: the whole-model query forces every rule's AST and defeats the interpreter's lazy preparation.
        Path model = modelWithStoredRule(dir, model(dir), "BadRule",
                "FieldNotFilled(NoSuchField)", "../CustomerName");
        var res = CliTestSupport.run("-m", model.toString(), "rule", "eval",
                "--condition", "FieldNotFilled(Count)", "--field", "/Order/Items/Sku",
                "--instance", doc(dir).toString());
        assertEquals(0, res.code(), () -> "a focused candidate is unaffected by BadRule: " + res.out());
        assertEquals("passed", M.readTree(res.out()).path("data").path("verdict").asText(), res::out);
    }

    @Test
    void aFocusedRuleEvalIsStillBlockedByABadComputation(@TempDir Path dir) throws Exception {
        // The other half of the same scope rule: focused does not mean "the condition only". The default
        // form-engine flow applies every computation first, so a computation's undeclared reference IS reached
        // — and used to kill the run with an untyped failure.
        assertRefused(CliTestSupport.run("-m", modelWithBadComputation(dir).toString(), "rule", "eval",
                CliTestSupport.RULE_FULL_NAME, "--instance", subscriptionDoc(dir).toString()),
                "/Subscription/Billing/NoSuchFee");
    }

    @Test
    void modelEvalStillSpeaksForTheWholeModelEvenInCandidateMode(@TempDir Path dir) throws Exception {
        // `model eval --condition` appends the candidate and evaluates EVERY rule, so an unrelated stored rule's
        // bad reference is in scope here — the opposite of the focused case above, for the same reason.
        Path model = modelWithStoredRule(dir, model(dir), "BadRule",
                "FieldNotFilled(NoSuchField)", "../CustomerName");
        assertRefused(CliTestSupport.run("-m", model.toString(), "model", "eval",
                "--condition", "FieldNotFilled(Count)", "--field", "/Order/Items/Sku",
                "--instance", doc(dir).toString()), "/Order/NoSuchField");
    }

    // ---------------------------------------------------------------------------------------------------
    // Scope — a half of the model the verb does not run is out of scope, even though it is broken
    // ---------------------------------------------------------------------------------------------------
    //
    // A FOCUSED `--no-computations` call and `model compute` are the two places where the evaluated domain is
    // genuinely SMALLER than "rules + computations", and the same invariant that pulls the computations INTO a
    // focused rule eval pushes them OUT here: the gate speaks for what runs. A refusal over a half that never
    // executes would make a legal, useful call fail for a reference that cannot reach its answer. Whole-model
    // eval is deliberately NOT one of those places — see the case that keeps refusing.

    @Test
    void ruleEvalWithNoComputationsIsNotBlockedByABadComputation(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("-m", modelWithBadComputation(dir).toString(), "rule", "eval",
                CliTestSupport.RULE_FULL_NAME, "--instance", subscriptionDoc(dir).toString(),
                "--no-computations");
        assertEquals(0, res.code(), () -> "the computations never run, so they are not this call's business: "
                + res.out() + res.err());
        assertEquals("passed", M.readTree(res.out()).path("data").path("verdict").asText(), res::out);
    }

    @Test
    void aCandidateRuleEvalWithNoComputationsIsNotBlockedByABadComputation(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("-m", modelWithBadComputation(dir).toString(), "rule", "eval",
                "--condition", "FieldNotFilled(BaseFee)", "--field", "/Subscription/Billing/BaseFee",
                "--instance", subscriptionDoc(dir).toString(), "--no-computations");
        assertEquals(0, res.code(), () -> "same for the candidate route: " + res.out() + res.err());
        assertEquals("passed", M.readTree(res.out()).path("data").path("verdict").asText(), res::out);
    }

    @Test
    void modelEvalWithNoComputationsIsStillBlockedByABadComputation(@TempDir Path dir) throws Exception {
        // The exception that proves the rule, and the reason the flag is a FOCUSED-only axis. `--no-computations`
        // skips the compute-and-apply step — it does NOT stop whole-model validation from evaluating the implicit
        // computation-validation rules (`[F] != <operation>`, one per computation, IF32/IF65), so the operation is
        // reached either way. Narrowing this route was tried first and the run refuted it: the call died with
        // "document path is absent from the prepared model: /Subscription/Billing/NoSuchFee", untyped, which is
        // exactly the failure the gate exists to replace. The focused route is where the flag does narrow, and
        // the pair above/below discriminates: same model, same flag, focused passes and whole-model does not.
        assertRefused(CliTestSupport.run("-m", modelWithBadComputation(dir).toString(), "model", "eval",
                "--instance", subscriptionDoc(dir).toString(), "--no-computations"),
                "/Subscription/Billing/NoSuchFee");
    }

    @Test
    void modelComputeIsNotBlockedByABadRuleItNeverEvaluates(@TempDir Path dir) throws Exception {
        // The mirror case: `model compute` validates nothing, so a stored rule's undeclared reference cannot
        // reach its answer. The base carries a SOUND computation, so this reads a real computed value rather
        // than an empty success — a model whose only computation were broken could pass for the wrong reason.
        Path model = modelWithStoredRule(dir, CliTestSupport.modelWithRuleAndComputation(dir), "BadRule",
                "FieldNotFilled(NoSuchField)", "../Tier");
        var res = CliTestSupport.run("-m", model.toString(), "model", "compute",
                "--instance", subscriptionDoc(dir).toString());
        assertEquals(0, res.code(), () -> "no rule is evaluated here: " + res.out() + res.err());
        assertNotEquals(CODE, M.readTree(res.out()).path("diagnostics").path(0).path("code").asText(), res::out);
        assertEquals("50", M.readTree(res.out()).at("/data/computed/0/value").asText(),
                () -> "the sound computation still computes: " + res.out());
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
