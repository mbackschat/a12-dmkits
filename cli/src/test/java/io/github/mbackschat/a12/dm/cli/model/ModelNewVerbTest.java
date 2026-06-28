package io.github.mbackschat.a12.dm.cli.model;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code model new} — create a new, empty, kernel-valid document model from scratch (the CLI's one
 * model-originating verb; every other verb edits an existing {@code -m} model). It prints the model JSON to
 * stdout, or {@code -o} writes it to a file. What it emits is kernel-checked, so the very next `-m` edit binds.
 */
class ModelNewVerbTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void createsAKernelValidModelToStdout(@TempDir Path dir) throws Exception {
        CliTestSupport.Result res = CliTestSupport.run("model", "new", "--id", "orders", "--locale", "en_US", "--root", "Order");
        assertEquals(0, res.code(), res::err);
        // the output IS the model JSON — round-trip it through `model check`
        Path model = dir.resolve("orders.dm.json");
        Files.writeString(model, res.out());
        CliTestSupport.Result validated = CliTestSupport.run("-m", model.toString(), "model", "check");
        assertEquals(0, validated.code(), validated::err);
        assertTrue(M.readTree(validated.out()).path("valid").asBoolean(),
                () -> "a freshly-created model is kernel-valid: " + validated.out());
    }

    @Test
    void writesToFileWithDashO(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("subs.dm.json");
        CliTestSupport.Result res = CliTestSupport.run(
                "model", "new", "--id", "subs", "--locale", "en_US", "-o", model.toString());
        assertEquals(0, res.code(), res::err);
        assertTrue(Files.exists(model), "the model file is written");
        // -o emits a machine-confirmable WRITE envelope on stdout (the model JSON went to the file) — the one
        // mutating path that previously had NO success signal an agent could read.
        var env = M.readTree(res.out());
        assertEquals("applied", env.path("outcome").asText(), () -> "model new -o confirms with an applied envelope: " + res.out());
        assertTrue(env.path("written").asBoolean(), () -> "written:true: " + res.out());
        assertEquals(model.toString(), env.path("output").asText(), () -> "the envelope names the output path: " + res.out());
        // and the agent can immediately edit it — `model describe` binds it
        CliTestSupport.Result describe = CliTestSupport.run("-m", model.toString(), "model", "describe");
        assertEquals(0, describe.code(), describe::err);
        assertEquals("read", M.readTree(describe.out()).path("outcome").asText());
    }

    @Test
    void aGermanLocaleModelStillAcceptsEnglishDslKeywords(@TempDir Path dir) throws Exception {
        // The probe (01-de) found a German-prompted session got MVK_UNEXPECTED_TOKEN on `FieldFilled` because
        // the new model derived its condition KEYWORD language from the de_DE DISPLAY locale. The two are
        // independent (the kernel's own de_DE models author English-keyword rules); a fresh de_DE model must
        // accept the English DSL the renderer + skill emit.
        Path model = dir.resolve("person.dm.json");
        CliTestSupport.Result created = CliTestSupport.run(
                "model", "new", "--id", "person", "--locale", "de_DE", "--root", "Person", "-o", model.toString());
        assertEquals(0, created.code(), created::err);
        assertEquals(0, CliTestSupport.run("-m", model.toString(),
                "field", "add", "--group", "/Person", "--name", "Age", "--kind", "NUMBER").code());

        CliTestSupport.Result checked = CliTestSupport.run("-m", model.toString(), "rule", "check",
                "--field", "/Person/Age", "--condition", "FieldFilled(/Person/Age) And [/Person/Age] < 18", "--code", "U18");
        assertEquals(0, checked.code(), checked::err);
        assertTrue(M.readTree(checked.out()).path("valid").asBoolean(),
                () -> "English DSL keywords must validate on a de_DE model (locale ≠ keyword language): " + checked.out());
    }

    @Test
    void aGermanLocaleModelAlsoAcceptsEnglishKeywordComputationsAndPreconditions(@TempDir Path dir) throws Exception {
        // The condition-language fix is at the MODEL level (conditionLanguage in modelConfig), and the kernel
        // parses ALL DSL against it — rule conditions, computation OPERATIONS, and PRECONDITIONS alike. So the
        // sibling of finding 01-de must hold too: a de_DE model accepts an English-keyword computation whose
        // alternative carries an English precondition. (Locks the generalization, not just the reported rule case.)
        Path model = dir.resolve("p.dm.json");
        assertEquals(0, CliTestSupport.run("model", "new", "--id", "p", "--locale", "de_DE",
                "--root", "Person", "-o", model.toString()).code());
        assertEquals(0, CliTestSupport.run("-m", model.toString(),
                "field", "add", "--group", "/Person", "--name", "Age", "--kind", "NUMBER").code());
        assertEquals(0, CliTestSupport.run("-m", model.toString(),
                "field", "add", "--group", "/Person", "--name", "Rabatt", "--kind", "NUMBER").code());

        Path spec = dir.resolve("comp.json");
        Files.writeString(spec, """
                { "computedField": "/Person/Rabatt", "name": "RabattComp",
                  "alternatives": [ {"precondition": "FieldFilled(/Person/Age) And [/Person/Age] >= 65", "operation": "10"} ],
                  "messages": [ {"locale": "de_DE", "text": "Rabatt berechnet."} ] }""");
        CliTestSupport.Result res = CliTestSupport.run("-m", model.toString(), "computation", "add", spec.toString());
        assertEquals(0, res.code(),
                () -> "an English-keyword computation + precondition must validate on a de_DE model: " + res.out() + res.err());
    }

    @Test
    void anInvalidLocaleIsReportedNotSilentlyEmitted() {
        CliTestSupport.Result res = CliTestSupport.run("model", "new", "--id", "x", "--locale", "not_a_locale");
        assertTrue(res.code() != 0, () -> "an unbuildable model is reported, not emitted: " + res.out() + res.err());
    }

    @Test
    void theHelpTeachesTheBasenameEqualsIdConvention() {
        // The SME flagged a model whose file basename (Person_DM) didn't match its id (person). The convention
        // (A12 Tools requires basename == header id, suffix NOT stripped) must be DISCOVERABLE from the verb
        // itself, not only the skill — so --help states it (report finding #122; CLI self-describing surface).
        CliTestSupport.Result help = CliTestSupport.run("model", "new", "--help");
        String text = help.out() + help.err();
        assertTrue(text.contains("basename") && text.contains("id"),
                () -> "`model new --help` must teach the file-basename = id convention: " + text);
    }
}
