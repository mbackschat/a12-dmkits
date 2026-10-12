package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Probe 01-de Finding 7 — a verb that LOADS a model whose stored content is already kernel-invalid must not
 * leak a raw {@code ModelConsistencyException} stack trace (the P1 "no raw exception escapes a verb" invariant,
 * on the model-LOAD path that P1's candidate-validation fix didn't cover). The strict {@code ofJson} load
 * throws on an invalid stored model; an execution-exception handler maps it to a structured {@code rejected}
 * envelope. Covers ≥2 load verbs (the guard is for the class, not one verb).
 */
class InvalidModelLoadDiagnosticTest {

    private static final ObjectMapper M = new ObjectMapper();

    private record Notice(Optional<String> code, String severity, String source, JsonNode where) {}
    private record DeclarationCase(String id, String name, String rawCode, int exit,
                                   Boolean valid, List<Notice> notices) {}

    private static Notice kernelNotice(String code, String name) {
        ObjectNode where = M.createObjectNode();
        if (name != null) where.put("rule", "/Form/" + name);
        return new Notice(Optional.ofNullable(code), "ERROR", "KERNEL", where);
    }

    private static Map<Notice, Long> multiset(List<Notice> notices) {
        return notices.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }

    private static List<Notice> notices(JsonNode envelope) {
        JsonNode diagnostics = envelope.path("diagnostics");
        assertTrue(diagnostics.isArray(), envelope::toString);
        List<Notice> out = new ArrayList<>();
        for (JsonNode diagnostic : diagnostics) {
            Optional<String> code = Optional.empty();
            if (diagnostic.has("code")) {
                assertTrue(diagnostic.path("code").isTextual(), diagnostic::toString);
                code = Optional.of(diagnostic.path("code").textValue());
            }
            assertTrue(diagnostic.path("severity").isTextual(), diagnostic::toString);
            assertTrue(diagnostic.path("source").isTextual(), diagnostic::toString);
            assertTrue(diagnostic.path("where").isObject(), diagnostic::toString);
            out.add(new Notice(code, diagnostic.path("severity").textValue(),
                    diagnostic.path("source").textValue(), diagnostic.path("where").deepCopy()));
        }
        return out;
    }

    private static String declarationControl() {
        var session = EditSession.read(DocumentModelDraft.named("declaration-admission")
                .locale("en_US")
                .rootGroup("Form", form -> form.stringField("Value", f -> {}))
                .build().toJson());
        var form = session.group("/Form");
        var value = form.str("Value");
        form.addRule(r -> r.name("Ordinary").code("CODE").severity(Severity.ERROR)
                .errorField(value).when(value.filled())
                .message(Dsl.en("Check the value.")).build());
        return session.toJson();
    }

    // The legal typed rule owns every other slot; only name/errorCode need byte surgery.
    private static String declarationCandidate(String control, DeclarationCase row) throws Exception {
        ObjectNode root = (ObjectNode) M.readTree(control);
        ObjectNode rule = null;
        for (JsonNode element : root.path("content").path("modelRoot").path("rootGroups")
                .path(0).path("Group").path("elements")) {
            if ("Rule".equals(element.path("type").asText())
                    && "Ordinary".equals(element.path("name").asText())) {
                assertEquals(null, rule, "the typed control must contain exactly one probe rule");
                rule = (ObjectNode) element;
            }
        }
        assertTrue(rule != null, "the typed probe rule must exist");
        rule.put("name", row.name());
        ObjectNode body = (ObjectNode) rule.path("Rule");
        assertTrue(body.has("errorCode"), "the legal writer must emit the control's code");
        if (row.rawCode() == null) body.remove("errorCode");
        else body.set("errorCode", M.readTree(row.rawCode()));
        return root.toString();
    }

    @Test
    void declarationAdmissionPreservesEveryNotificationAndItsLocation(@TempDir Path dir) throws Exception {
        String control = declarationControl();
        String missing = "MVK_ERROR_CODE_MISSING";
        String reserved = "MVK_RULE_NAME_INVALID";
        List<DeclarationCase> rows = List.of(
                new DeclarationCase("control", "Ordinary", "\"CODE\"", 0, true, List.of()),
                new DeclarationCase("reserved-blank", "vk_vkProbe", "\"\"", 1, false,
                        List.of(kernelNotice(missing, "vk_vkProbe"),
                                kernelNotice(reserved, "vk_vkProbe"), kernelNotice(null, null))),
                new DeclarationCase("dotted-blank", "Bad.Name", "\"\"", 1, false,
                        List.of(kernelNotice(null, "Bad.Name"),
                                kernelNotice(missing, "Bad.Name"), kernelNotice(null, null))),
                new DeclarationCase("reserved-dotted", "vk_vk.Bad", "\"CODE\"", 1, false,
                        List.of(kernelNotice(null, "vk_vk.Bad"))),
                new DeclarationCase("null-reserved", "vk_vkProbe", "null", 2, null, List.of()),
                new DeclarationCase("empty-blank", "", "\"\"", 1, false,
                        List.of(kernelNotice(null, null))),
                new DeclarationCase("underscore", "_Rule", "\"CODE\"", 1, false,
                        List.of(kernelNotice(null, "_Rule"))),
                new DeclarationCase("hyphen-code", "Ordinary", "\"A-B\"", 1, false,
                        List.of(kernelNotice(null, "Ordinary"))),
                new DeclarationCase("space-code", "Ordinary", "\"   \"", 1, false,
                        List.of(kernelNotice(missing, "Ordinary"))),
                new DeclarationCase("vt-code", "Ordinary", "\"\\u000b\"", 1, false,
                        List.of(kernelNotice(missing, "Ordinary"))),
                new DeclarationCase("nbsp-code", "Ordinary", "\"\\u00a0\"", 1, false,
                        List.of(kernelNotice(null, "Ordinary"))),
                new DeclarationCase("absent-code", "Ordinary", null, 1, false,
                        List.of(kernelNotice(missing, "Ordinary"), kernelNotice(null, null))));
        assertEquals(12, rows.size());
        for (DeclarationCase row : rows) {
            Path model = dir.resolve(row.id() + ".dm.json");
            Files.writeString(model, declarationCandidate(control, row));
            var result = CliTestSupport.run("-m", model.toString(), "model", "check");
            assertNoStackTrace(result);
            assertEquals(row.exit(), result.code(), row.id() + ": " + result.out() + result.err());
            JsonNode envelope = M.readTree(result.out());
            assertTrue(envelope.path("ok").isBoolean(), envelope::toString);
            assertEquals(row.valid() != null, envelope.path("ok").booleanValue(), row.id());
            List<Notice> expected = row.notices();
            if (row.valid() == null) {
                assertFalse(envelope.has("valid"), envelope::toString);
                expected = List.of(new Notice(Optional.of("RK_MODEL_INPUT_INVALID"), "ERROR", "PRECHECK",
                        M.createObjectNode().put("arg", model.toString())));
            } else {
                assertTrue(envelope.path("valid").isBoolean(), envelope::toString);
                assertEquals(row.valid().booleanValue(), envelope.path("valid").booleanValue(), row.id());
            }
            assertEquals(multiset(expected), multiset(notices(envelope)), row.id() + ": " + result.out());
            for (JsonNode diagnostic : envelope.path("diagnostics")) {
                assertEquals("empty-blank".equals(row.id()), diagnostic.path("summary").asText()
                        .startsWith("the kernel could not build this model:"), result::out);
            }
        }
    }

    private record StrictLoadCase(String name, List<String> args) {
        private StrictLoadCase(String name, String... args) {
            this(name, List.of(args));
        }
    }

    /** A model that DESERIALIZES but fails checkConsistency at load: a rule whose operand names a foreign root. */
    private static Path invalidModel(Path dir) throws Exception {
        Path m = dir.resolve("bad.json");
        Files.writeString(m, """
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
        return m;
    }

    private static void assertNoStackTrace(CliTestSupport.Result res) {
        String all = res.out() + "\n" + res.err();
        assertFalse(all.contains("ModelConsistencyException") || all.matches("(?s).*\\n\\tat [\\w.$]+\\(.*"),
                () -> "a raw stack trace escaped the verb:\n" + all);
    }

    @Test
    void everyStrictReaderPreservesAnInvalidStoredModelAsTheCentralStructuredRejection(@TempDir Path dir)
            throws Exception {
        Path model = invalidModel(dir);
        List<StrictLoadCase> cases = List.of(
                new StrictLoadCase("model describe", "model", "describe"),
                new StrictLoadCase("model read", "model", "read"),
                new StrictLoadCase("model report", "model", "report"),
                new StrictLoadCase("model usage", "model", "usage"),
                new StrictLoadCase("rule read", "rule", "read", "/R/BAD"),
                new StrictLoadCase("rule deps", "rule", "deps", "/R/BAD"),
                new StrictLoadCase("rule explain", "rule", "explain", "/R/BAD"),
                new StrictLoadCase("rule format", "rule", "format", "/R/BAD"),
                new StrictLoadCase("computation read", "computation", "read"),
                new StrictLoadCase("computation explain", "computation", "explain", "/R/AnyComputation"),
                new StrictLoadCase("computation format", "computation", "format", "/R/AnyComputation"),
                new StrictLoadCase("where-used", "where-used", "/R/A"),
                new StrictLoadCase("export", "export", "card"));

        for (StrictLoadCase testCase : cases) {
            List<String> args = new ArrayList<>(List.of("-m", model.toString()));
            args.addAll(testCase.args());
            CliTestSupport.Result res = CliTestSupport.run(args.toArray(String[]::new));

            assertNotEquals(0, res.code(), testCase.name() + ": an invalid-on-disk model is a refusal, not success");
            assertNoStackTrace(res);
            var env = M.readTree(res.out());
            assertEquals("rejected", env.path("outcome").asText(),
                    () -> testCase.name() + ": expected the central structured rejection: " + res.out());
            assertTrue(res.out().contains("MVK_INVALID_ROOT_GROUP"),
                    () -> testCase.name() + ": the kernel code must surface in the envelope: " + res.out());
        }
    }

    @Test
    void strictReaderPreservesAnUnresolvedIncludeAsTheCentralDomainRejection(@TempDir Path dir) throws Exception {
        String json = DocumentModelDraft.named("host").locale("en_US")
                .include("missing-library", "missing-library")
                .rootGroup("Host", host -> host.mount("Missing", "missing-library"))
                .build().toJson();
        Path model = dir.resolve("host.json");
        Files.writeString(model, json);

        CliTestSupport.Result res = CliTestSupport.run("-m", model.toString(), "model", "read");

        assertEquals(1, res.code(), "an unresolved model reference is an input rejection");
        assertNoStackTrace(res);
        var env = M.readTree(res.out());
        assertEquals("rejected", env.path("outcome").asText(), res::out);
        assertEquals("RK_UNRESOLVED_REFERENCE",
                env.path("diagnostics").path(0).path("code").asText(), res::out);
    }
}
