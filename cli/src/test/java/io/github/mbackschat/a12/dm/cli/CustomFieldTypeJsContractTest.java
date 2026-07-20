package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** The strict JS custom-field-type contract: typed context in, boolean or the one documented object vocabulary out. */
class CustomFieldTypeJsContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void passesLocaleBoundsAndCanonicalValueModeToTheValidator(@TempDir Path dir) throws Exception {
        assumeTrue(nodeAvailable(), "node not on PATH");
        Path model = boundedModel(dir);
        Path js = jsDir(dir, """
                export function validate(value, context) {
                  const exact = context.locale === 'en_US'
                    && context.minLength === 15
                    && context.maxLength === 34
                    && context.isDisplayValue === false;
                  return exact
                    ? { valid: false, errorKey: 'CONTEXT_SEEN', errorMessage: 'bad $<fieldName>$' }
                    : { valid: false, errorKey: 'CONTEXT_WRONG' };
                }
                """);

        var result = eval(model, doc(dir), js);

        assertEquals(0, result.code(), result::err);
        assertTrue(fired(result.out(), "CONTEXT_SEEN"), result::out);
        JsonNode message = message(result.out(), "CONTEXT_SEEN");
        assertEquals("bad Iban", message.path("message").asText(), result::out);
        assertTrue(!message.has("text"), () -> "the public result key is message, never text: " + result.out());
    }

    @Test
    void retainsPlainBooleanReturnCompatibility(@TempDir Path dir) throws Exception {
        assumeTrue(nodeAvailable(), "node not on PATH");
        Path model = boundedModel(dir);
        Path js = jsDir(dir, "export function validate(value, context) { return false; }\n");

        var result = eval(model, doc(dir), js);

        assertEquals(0, result.code(), result::err);
        assertTrue(fired(result.out(), "customFieldTypeInvalid"), result::out);
    }

    @Test
    void rejectsFormerAliasVocabularyAndStrictCustomRefuses(@TempDir Path dir) throws Exception {
        assumeTrue(nodeAvailable(), "node not on PATH");
        Path model = boundedModel(dir);
        Path js = jsDir(dir, "export function validate() { return { ok: false, code: 'OLD_ALIAS' }; }\n");

        var lenient = eval(model, doc(dir), js);
        assertEquals(0, lenient.code(), lenient::err);
        assertTrue(unsupported(lenient.out()).contains("unknown key"), lenient::out);

        var strict = CliTestSupport.run(
                "-m", model.toString(), "model", "eval",
                "--instance", doc(dir).toString(),
                "--custom-field-types-js", js.toString(),
                "--strict-custom");
        assertEquals(2, strict.code(), strict::out);
    }

    @Test
    void rejectsWronglyTypedObjectMembers(@TempDir Path dir) throws Exception {
        assumeTrue(nodeAvailable(), "node not on PATH");
        Path model = boundedModel(dir);
        Path js = jsDir(dir, "export function validate() { return { valid: false, errorKey: 42 }; }\n");

        var result = eval(model, doc(dir), js);

        assertEquals(0, result.code(), result::err);
        assertTrue(unsupported(result.out()).contains("errorKey must be a string"), result::out);
    }

    @Test
    void modelEvalHelpDescribesTheCallableContextAndResultVocabulary() {
        var help = CliTestSupport.run("model", "eval", "--help");

        assertEquals(0, help.code(), help::err);
        String compact = help.out().replaceAll("\\s+", "");
        assertTrue(compact.contains("validate(value,context)"), help::out);
        assertTrue(compact.contains("{locale,minLength,maxLength,isDisplayValue}"), help::out);
        assertTrue(compact.contains("{valid,errorKey?,errorMessage?}"), help::out);
    }

    private static CliTestSupport.Result eval(Path model, Path doc, Path js) {
        return CliTestSupport.run(
                "-m", model.toString(), "model", "eval",
                "--instance", doc.toString(),
                "--custom-field-types-js", js.toString());
    }

    private static Path boundedModel(Path dir) throws Exception {
        Path model = CliTestSupport.paymentCustomType(dir);
        String json = Files.readString(model).replace(
                "\"name\": \"Iban\"",
                "\"name\": \"Iban\", \"minLength\": 15, \"maxLength\": 34");
        Files.writeString(model, json);
        return model;
    }

    private static Path jsDir(Path dir, String body) throws Exception {
        Files.writeString(dir.resolve("Iban.js"), body);
        return dir;
    }

    private static Path doc(Path dir) throws Exception {
        Path doc = dir.resolve("instance.json");
        Files.writeString(doc, "{ \"fields\": { \"Payment\": { \"Iban\": \"NOT-AN-IBAN\" } } }");
        return doc;
    }

    private static boolean fired(String output, String code) throws Exception {
        for (JsonNode fired : MAPPER.readTree(output).path("data").path("fired")) {
            if (code.equals(fired.asText())) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode message(String output, String code) throws Exception {
        for (JsonNode message : MAPPER.readTree(output).path("data").path("messages")) {
            if (code.equals(message.path("code").asText())) {
                return message;
            }
        }
        throw new AssertionError("no message for " + code + ": " + output);
    }

    private static String unsupported(String output) throws Exception {
        return MAPPER.readTree(output).path("data").path("unsupported").toString();
    }

    private static boolean nodeAvailable() {
        try {
            return new ProcessBuilder("node", "--version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
