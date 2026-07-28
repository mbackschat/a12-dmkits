package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@code --custom-conditions-js <dir>} (G31) — the "escape to JS": a model's imperative {@code CustomCondition}
 * is evaluated by the project's own {@code <Name>.js} via a persistent Node worker, instead of being reported
 * {@code unsupported}. Uses the committed {@code order-ruled} fixture, whose {@code /Order/EligibilityCheck}
 * rule is {@code FieldFilled(CustomerName) And CustomCondition ExternalEligibility}. The node-requiring cases are
 * SKIPPED (not failed) where {@code node} is absent, so they're safe in any environment; the conflict + bad-dir
 * cases need no node.
 */
class CustomConditionJsVerbTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static boolean nodeAvailable() {
        try {
            return new ProcessBuilder("node", "--version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Write {@code ExternalEligibility.js} (fires — i.e. "ineligible" — when any field value is "BLOCKED"). */
    private static Path jsDir(Path dir, String body) throws Exception {
        Path js = dir.resolve("ExternalEligibility.js");
        Files.writeString(js, body);
        return dir;
    }

    private static final String FIRES_ON_BLOCKED = """
            export function check(data, relevantEntities, formallyIncorrectFields, errorField) {
              for (const id of data) {
                if (data.getValue(id) === 'BLOCKED') return true;
              }
              return false;
            }
            """;

    private static Path doc(Path dir, String customerName) throws Exception {
        Path d = dir.resolve("doc-" + customerName + ".json");
        Files.writeString(d, "{ \"Order\": { \"CustomerName\": \"" + customerName + "\" } }");
        return d;
    }

    private static boolean fired(String out, String code) throws Exception {
        for (JsonNode c : MAPPER.readTree(out).get("data").get("fired")) {
            if (c.asText().equals(code)) {
                return true;
            }
        }
        return false;
    }

    private static String unsupported(String out) throws Exception {
        JsonNode u = MAPPER.readTree(out).get("data").get("unsupported");
        return u == null ? "" : u.toString();
    }

    @Test
    void jsConditionEvaluatesTheRuleThatWouldOtherwiseBeUnsupported(@TempDir Path dir) throws Exception {
        assumeTrue(nodeAvailable(), "node not on PATH");
        Path model = CliTestSupport.orderRuled(dir);
        Path js = jsDir(dir, FIRES_ON_BLOCKED);
        var res = CliTestSupport.run("-m", model.toString(), "model", "eval",
                "--instance", doc(dir, "BLOCKED").toString(), "--custom-conditions-js", js.toString());
        assertEquals(0, res.code(), res::err);
        assertTrue(fired(res.out(), "INELIGIBLE_ORDER"),
                () -> "the JS CustomCondition must make the rule fire on a BLOCKED customer: " + res.out());
        assertFalse(unsupported(res.out()).contains("EligibilityCheck"),
                () -> "the rule must no longer be unsupported once its JS impl is supplied: " + res.out());
    }

    @Test
    void jsConditionReturningFalseDoesNotFire(@TempDir Path dir) throws Exception {
        assumeTrue(nodeAvailable(), "node not on PATH");
        Path model = CliTestSupport.orderRuled(dir);
        Path js = jsDir(dir, FIRES_ON_BLOCKED);
        var res = CliTestSupport.run("-m", model.toString(), "model", "eval",
                "--instance", doc(dir, "Acme").toString(), "--custom-conditions-js", js.toString());
        assertEquals(0, res.code(), res::err);
        assertFalse(fired(res.out(), "INELIGIBLE_ORDER"), () -> "a normal customer must not fire: " + res.out());
        assertEquals("", unsupported(res.out()), () -> "the JS impl ran, so nothing is unsupported: " + res.out());
    }

    @Test
    void aThrowingJsConditionDegradesToUnsupported(@TempDir Path dir) throws Exception {
        assumeTrue(nodeAvailable(), "node not on PATH");
        Path model = CliTestSupport.orderRuled(dir);
        Path js = jsDir(dir, "export function check() { throw new Error('boom'); }\n");
        var res = CliTestSupport.run("-m", model.toString(), "model", "eval",
                "--instance", doc(dir, "BLOCKED").toString(), "--custom-conditions-js", js.toString());
        assertEquals(0, res.code(), res::err);
        assertTrue(unsupported(res.out()).contains("EligibilityCheck"),
                () -> "a JS impl that throws must degrade to unsupported, not crash the run: " + res.out());
    }

    @Test
    void aMissingJsDirFailsCleanly(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "model", "eval",
                "--instance", doc(dir, "Acme").toString(), "--custom-conditions-js", dir.resolve("nope").toString());
        assertNotEquals(0, res.code());
        assertTrue((res.out() + res.err()).contains("custom-conditions-js"),
                () -> "a missing --custom-conditions-js dir must fail with a clear message: " + res.out() + res.err());
    }
}
