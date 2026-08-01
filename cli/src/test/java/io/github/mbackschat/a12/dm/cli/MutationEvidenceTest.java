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
