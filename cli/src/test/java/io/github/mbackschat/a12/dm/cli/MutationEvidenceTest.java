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
 * A write states its provenance, end to end through the binary (RESULT-CONTRACT-PROPOSAL §2).
 *
 * <p>{@code verification} answers "who arbitrated this invocation". The edit verbs put the model through the real
 * consistency oracle <em>before</em> writing — that is what makes a bad edit fail instead of corrupting the file —
 * and yet the mutation families published nothing, so a write was the one route that gated and then said nothing
 * about it. {@link ResultAlgebraTest} pins the algebra at the factory; this pins that the real command paths
 * actually reach it, which a factory-level test cannot show.
 *
 * <p>The three cases are chosen to discriminate, not to cover:
 *
 * <ul>
 *   <li>a <b>gated write</b> must carry the receipt — and still no {@code valid}, because provenance is not a
 *       verdict;</li>
 *   <li>a <b>preview</b> must carry it too. {@code written:false} and "nothing was checked" are different facts,
 *       and an agent deciding whether to re-run for real needs the second;</li>
 *   <li>an <b>ungated write</b> must carry nothing. Without this the whole feature would be satisfied by stamping
 *       the ambient mode everywhere, which is the exact defect the receipt type exists to prevent — and
 *       {@code model normalize} is the honest instance, a pure reformat that never consults the oracle.</li>
 * </ul>
 */
class MutationEvidenceTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void aGatedWriteNamesTheGateThatClearedIt(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);

        CliTestSupport.Result res = CliTestSupport.run("-m", model.toString(),
                "field", "add", "--group", "/Order", "--name", "PromoCode", "--kind", "STRING");

        assertEquals(0, res.code(), res::err);
        JsonNode j = M.readTree(res.out());
        assertEquals("applied", j.path("outcome").asText(), res::out);
        assertEquals("KERNEL_CONFIRMED", j.path("verification").asText(),
                () -> "the edit gate ran before the write, so the write can name it: " + res.out());
        assertFalse(j.has("valid"),
                () -> "a successful write is provenance, not a standing verdict about the model: " + res.out());
    }

    @Test
    void aPreviewNamesItToo(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);

        CliTestSupport.Result res = CliTestSupport.run("-m", model.toString(),
                "field", "add", "--group", "/Order", "--name", "PromoCode", "--kind", "STRING", "--dry-run");

        assertEquals(0, res.code(), res::err);
        JsonNode j = M.readTree(res.out());
        assertEquals("preview", j.path("outcome").asText(), res::out);
        assertFalse(j.path("written").asBoolean());
        assertEquals("KERNEL_CONFIRMED", j.path("verification").asText(),
                () -> "--dry-run writes nothing but checks everything; the envelope must say so: " + res.out());
    }

    @Test
    void anUngatedWriteNamesNothing(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);

        CliTestSupport.Result res = CliTestSupport.run("-m", model.toString(), "model", "normalize");

        assertEquals(0, res.code(), res::err);
        JsonNode j = M.readTree(res.out());
        assertEquals("applied", j.path("outcome").asText(), res::out);
        assertFalse(j.has("verification"),
                () -> "normalize re-serializes the tree and never consults the oracle, so it may claim nothing: "
                        + res.out());
    }

    /**
     * <b>The artifact writers</b> — the {@code -o} branches of the verbs that emit something other than an edited
     * model. They were classified ungated and three of them were not, which is the reason this block exists.
     *
     * <p>Each of the three runs a real consistency gate one or two frames away, inside a helper that returns only
     * text: {@code profile synthesize} assembles through {@code BoundModel.addAll}, {@code model export-jsonschema}
     * reads every condition back through the kernel {@code ConditionReadBack} backend, and
     * {@code model import-jsonschema} binds through {@code BoundModel} whenever Tier-A+ rule synthesis has work to
     * do. A gate that hands back nothing forces its consumer to guess, and each guess came out {@code none()} —
     * silence rather than a false claim, which is exactly why no assertion failed and only a reading of the call
     * chains found it.
     *
     * <p>They are covered here rather than in {@code VerbDemonstrationTest} because the demonstration corpus is
     * read-only by construction: these verbs are demonstrated without {@code -o}, printing their artifact to
     * stdout with no envelope at all, so the branch that publishes provenance is the one branch no demo runs.
     * <b>That gap is the finding.</b> A totality guard that reaches only the demonstrated branches proves totality
     * over the demonstrations, not over the surface.
     */
    @Test
    void anArtifactWriterThatGatedSaysSo(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);

        Path profile = dir.resolve("profile.json");
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "profile", "extract",
                "-o", profile.toString()).code());
        assertGateNamed("profile synthesize", CliTestSupport.run("profile", "synthesize",
                "--input", profile.toString(), "--seed", "7", "-o", dir.resolve("syn.json").toString()));

        assertGateNamed("model export-jsonschema", CliTestSupport.run("-m", model.toString(),
                "model", "export-jsonschema", "-o", dir.resolve("schema.json").toString()));

        // exclusiveMinimum is a Tier-A+ need, so the importer synthesizes a rule and binds through the oracle.
        Path synthesizing = dir.resolve("bounded.schema.json");
        Files.writeString(synthesizing, """
                {"$schema": "https://json-schema.org/draft/2020-12/schema", "title": "Ord", "type": "object",
                 "properties": {"Qty": {"type": "integer", "exclusiveMinimum": 0}}}""");
        assertGateNamed("model import-jsonschema (synthesizing)", CliTestSupport.run("model", "import-jsonschema",
                "--schema", synthesizing.toString(), "-o", dir.resolve("imported.json").toString()));
    }

    /**
     * The other direction, and the half that keeps the block above from being satisfiable by stamping everything.
     *
     * <p>A purely structural import binds nothing — no rule needs, no {@code BoundModel} — so it must stay silent
     * even though the *same verb* speaks in the case above. That is the contract working as specified: the receipt
     * is per invocation, never per verb. {@code model expand} assembles through the interpreter's kernel-free
     * workspace and {@code model normalize} re-serializes a parsed tree; neither consults the oracle at all.
     */
    @Test
    void anArtifactWriterThatDidNotGateStaysSilent(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);

        Path structural = dir.resolve("plain.schema.json");
        Files.writeString(structural, """
                {"$schema": "https://json-schema.org/draft/2020-12/schema", "title": "Plain", "type": "object",
                 "properties": {"Name": {"type": "string"}}}""");
        assertNoGateNamed("model import-jsonschema (structural only)", CliTestSupport.run("model",
                "import-jsonschema", "--schema", structural.toString(),
                "-o", dir.resolve("plain.dm.json").toString()));

        assertNoGateNamed("model expand", CliTestSupport.run("-m", model.toString(), "model", "expand",
                "-o", dir.resolve("expanded.json").toString()));
        assertNoGateNamed("model normalize", CliTestSupport.run("-m", model.toString(), "model", "normalize",
                "-o", dir.resolve("normalized.json").toString()));
    }

    private static void assertGateNamed(String label, CliTestSupport.Result res) throws Exception {
        assertEquals(0, res.code(), () -> label + " failed: " + res.err() + res.out());
        JsonNode env = M.readTree(res.out());
        assertEquals("applied", env.path("outcome").asText(), res::out);
        assertTrue(env.hasNonNull("verification"),
                () -> label + " ran a consistency gate on the way to its artifact and published nothing. The gate "
                        + "is one frame away inside a helper that returns only text — find the object that gated "
                        + "and hand its receipt back: " + res.out());
    }

    private static void assertNoGateNamed(String label, CliTestSupport.Result res) throws Exception {
        assertEquals(0, res.code(), () -> label + " failed: " + res.err() + res.out());
        JsonNode env = M.readTree(res.out());
        assertFalse(env.has("verification"),
                () -> label + " consulted no oracle, so it may claim none: " + res.out());
    }

    /**
     * {@code apply}'s shape in one assertion pair: the receipt belongs to the transaction, not to its steps. A
     * staged step is ungated <em>by design</em> — the verb's whole invariant is that validation happens once, at
     * the end — so the step must stay silent while the aggregate speaks.
     */
    @Test
    void aCommittedTransactionCarriesTheTerminalGateAndItsStepsDoNot(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, """
                [ {"target":"field","op":"add","group":"/Order","name":"Discount","kind":"NUMBER"},
                  {"target":"group","op":"add","parent":"/Order","name":"Notes"} ]""");

        CliTestSupport.Result res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());

        assertEquals(0, res.code(), res::err);
        JsonNode j = M.readTree(res.out());
        assertTrue(j.path("data").path("committed").asBoolean(), res::out);
        assertEquals("KERNEL_CONFIRMED", j.path("verification").asText(),
                () -> "the one terminal gate cleared what was written: " + res.out());
        JsonNode step = j.path("data").path("results").get(0);
        assertEquals("staged", step.path("outcome").asText(), res::out);
        assertFalse(step.has("verification"),
                () -> "a staged step runs no gate of its own; the aggregate owns the receipt: " + res.out());
    }
}
