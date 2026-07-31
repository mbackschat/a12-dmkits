package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * G45 — {@code meta} is the one post-creation metadata write route. Target-specific reads retain their
 * projections; target-specific modifiers reject the retired keys with a direct relocation corrective.
 */
class MetadataRouteTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void applyMetaModifiesEverySupportedElementKind(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRuleAndComputation(dir);
        Path ops = dir.resolve("metadata.json");
        Files.writeString(ops, """
                [
                  {"target":"typedef","op":"add","id":"Money","kind":"NUMBER"},
                  {"target":"meta","op":"modify","ref":"/Subscription/Billing/BaseFee",
                   "label":{"en_US":"Base fee","de_DE":"Grundgebuehr"},
                   "annotation":{"owner":"billing"},"internal-description":{"en_US":"field note"}},
                  {"target":"meta","op":"modify","ref":"/Subscription/Billing",
                   "annotation":{"owner":"billing"},"external-description":{"en_US":"group hint"}},
                  {"target":"meta","op":"modify","ref":"/Subscription/Addons/FeeCapRule",
                   "annotation":{"owner":"risk"},"internal-description":{"en_US":"rule note"}},
                  {"target":"meta","op":"modify","ref":"/Subscription/Billing/EffectiveFeeComp",
                   "annotation":{"owner":"pricing"},"external-description":{"en_US":"computed hint"}},
                  {"target":"meta","op":"modify","ref":"Money",
                   "annotation":{"owner":"types"},"internal-description":{"en_US":"type note"}}
                ]
                """);

        CliTestSupport.Result result =
                CliTestSupport.run("-m", model.toString(), "apply", ops.toString());

        assertEquals(0, result.code(), result::out);
        assertTrue(M.readTree(result.out()).path("data").path("committed").asBoolean(), result::out);
        assertMeta(model, "/Subscription/Billing/BaseFee", "billing", "field note", "");
        assertMeta(model, "/Subscription/Billing", "billing", "", "group hint");
        assertMeta(model, "/Subscription/Addons/FeeCapRule", "risk", "rule note", "");
        assertMeta(model, "/Subscription/Billing/EffectiveFeeComp", "pricing", "", "computed hint");
        assertMeta(model, "Money", "types", "type note", "");
    }

    @Test
    void applyMetaSupportsEveryRemovalAndRollsBackAnEmptyOperation(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRuleAndComputation(dir);
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "meta",
                "/Subscription/Billing/BaseFee",
                "--internal-description", "en_US=internal",
                "--external-description", "en_US=external",
                "--annotation", "owner=billing").code());

        Path remove = dir.resolve("remove.json");
        Files.writeString(remove, """
                [{"target":"meta","op":"modify","ref":"/Subscription/Billing/BaseFee",
                  "remove-internal-description":["en_US"],
                  "remove-external-description":["en_US"],
                  "remove-annotation":["owner"]}]
                """);
        CliTestSupport.Result removed =
                CliTestSupport.run("-m", model.toString(), "apply", remove.toString());
        assertEquals(0, removed.code(), removed::out);
        JsonNode after = meta(model, "/Subscription/Billing/BaseFee");
        assertTrue(after.path("annotations").isEmpty(), "annotation removed");
        assertTrue(after.path("internalDescription").isEmpty(), "internal description removed");
        assertTrue(after.path("externalDescription").isEmpty(), "external description removed");

        String before = Files.readString(model);
        Path empty = dir.resolve("empty.json");
        Files.writeString(empty, """
                [
                  {"target":"group","op":"add","parent":"/Subscription","name":"WouldRollBack"},
                  {"target":"meta","op":"modify","ref":"/Subscription/Billing"}
                ]
                """);
        CliTestSupport.Result refused =
                CliTestSupport.run("-m", model.toString(), "apply", empty.toString());
        assertEquals(1, refused.code(), refused::out);
        assertFalse(M.readTree(refused.out()).path("data").path("committed").asBoolean(), refused::out);
        assertEquals(before, Files.readString(model), "an empty meta op rolls the whole sequence back");
    }

    @Test
    void applyMetaLabelRefusalIsStructuredAndAtomic(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRuleAndComputation(dir);
        String before = Files.readString(model);
        Path ops = dir.resolve("bad-label.json");
        Files.writeString(ops, """
                [
                  {"target":"group","op":"add","parent":"/Subscription","name":"WouldRollBack"},
                  {"target":"meta","op":"modify","ref":"/Subscription/Billing/EffectiveFeeComp",
                   "label":{"en_US":"not supported"}}
                ]
                """);

        CliTestSupport.Result result =
                CliTestSupport.run("-m", model.toString(), "apply", ops.toString());

        JsonNode envelope = M.readTree(result.out());
        // exit follows the kind of stop; the per-op outcome pins which kind that is
        assertEquals("refused", envelope.path("data").path("results").path(1).path("outcome").asText(), result::out);
        assertEquals(2, result.code(), result::out);
        assertFalse(envelope.path("data").path("committed").asBoolean(), result::out);
        assertEquals("RK_LABEL_NOT_SUPPORTED",
                envelope.path("data").path("results").path(1).path("diagnostics").path(0).path("code").asText(), result::out);
        assertEquals(before, Files.readString(model), "the preceding staged mutation is rolled back");
    }

    @Test
    void applyMetaRejectsWrongShapedMapBeforeDispatch(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRuleAndComputation(dir);
        Path ops = dir.resolve("wrong-shape.json");
        Files.writeString(ops, """
                [{"target":"meta","op":"modify","ref":"/Subscription/Billing/BaseFee",
                  "annotation":["owner=billing"]}]
                """);

        CliTestSupport.Result result =
                CliTestSupport.run("-m", model.toString(), "apply", ops.toString());

        assertEquals(1, result.code(), result::out);
        JsonNode diagnostic = M.readTree(result.out())
                .path("data").path("results").path(0).path("diagnostics").path(0);
        assertEquals("RK_INVALID_VALUE", diagnostic.path("code").asText(), result::out);
        assertEquals("annotation", diagnostic.path("where").path("arg").asText(), result::out);
    }

    @Test
    void retiredStandaloneAndApplyRoutesPointToMeta(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);

        Path fieldSpec = dir.resolve("field.json");
        Files.writeString(fieldSpec, """
                {"group":"/Order","name":"CustomerName","annotations":{"owner":"sales"}}
                """);
        CliTestSupport.Result field =
                CliTestSupport.run("-m", model.toString(), "field", "modify", fieldSpec.toString());
        assertMoved(field.out() + field.err(), "dmtool meta <ref> --annotation");

        CliTestSupport.Result group = CliTestSupport.run("-m", model.toString(), "group", "modify",
                "/Order/ShippingAddress", "--internal-description", "en_US=note");
        assertMoved(group.out() + group.err(), "dmtool meta <ref> --internal-description");

        CliTestSupport.Result rule = CliTestSupport.run("-m", model.toString(), "rule", "modify",
                "/Order/EligibilityCheck", "--annotation", "owner=risk");
        assertMoved(rule.out() + rule.err(), "dmtool meta <ref> --annotation");

        assertRetiredApply(dir, model, "field", """
                {"target":"field","op":"modify","group":"/Order","name":"CustomerName",
                 "annotations":{"owner":"sales"}}""", "dmtool meta <ref> --annotation");
        assertRetiredApply(dir, model, "group", """
                {"target":"group","op":"modify","groupPathInModel":"/Order/ShippingAddress",
                 "annotation":{"owner":"sales"}}""", "dmtool meta <ref> --annotation");
        assertRetiredApply(dir, model, "rule", """
                {"target":"rule","op":"modify","rulePathInModel":"/Order/EligibilityCheck",
                 "internal-description":{"en_US":"note"}}""", "dmtool meta <ref> --internal-description");
    }

    private static void assertMoved(String actual, String route) {
        assertTrue(actual.contains("moved to `" + route + "`"),
                () -> "the retired route must name its replacement; got: " + actual);
    }

    private static void assertRetiredApply(
            Path dir, Path model, String target, String operation, String route) throws Exception {
        Path ops = dir.resolve("old-" + target + "-apply.json");
        Files.writeString(ops, "[" + operation + "]");
        CliTestSupport.Result result =
                CliTestSupport.run("-m", model.toString(), "apply", ops.toString());
        JsonNode diagnostic = M.readTree(result.out())
                .path("data").path("results").path(0).path("diagnostics").path(0);
        assertEquals("RK_UNKNOWN_ARG", diagnostic.path("code").asText(), result::out);
        assertMoved(diagnostic.path("fix").asText(), route);
    }

    private static void assertMeta(
            Path model, String ref, String owner, String internal, String external) throws Exception {
        JsonNode data = meta(model, ref);
        assertEquals(owner, data.path("annotations").path("owner").asText(), ref);
        assertEquals(internal, data.path("internalDescription").path("en_US").asText(), ref);
        assertEquals(external, data.path("externalDescription").path("en_US").asText(), ref);
    }

    private static JsonNode meta(Path model, String ref) throws Exception {
        CliTestSupport.Result read = CliTestSupport.run("-m", model.toString(), "meta", ref);
        assertEquals(0, read.code(), read::err);
        return M.readTree(read.out()).path("data");
    }
}
