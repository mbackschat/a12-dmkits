package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code patterns <id> --arg …} without {@code --apply} is a <b>verdict</b> route, so a scaffold the kernel
 * rejects exits <b>1</b> — the declared break (RESULT-CONTRACT-PROPOSAL §7). Before the typed result algebra this
 * verb printed a raw node and always exited 0, and the change shipped with nothing asserting it: the valid path
 * was locked, the rejected path was not, so the exit code the break is *about* was untested in both directions.
 *
 * <p>Kept separate from {@link PatternsVerbTest}, which owns the catalog/list/detail/apply surface. This file owns
 * exactly the review route's verdict-and-exit contract, so a future change to it fails a test named after it.
 */
class PatternsScaffoldVerdictTest {

    private static final ObjectMapper M = new ObjectMapper();

    /**
     * The rejected direction. {@code earlier == later} scaffolds a well-formed rule the kernel refuses
     * ({@code MVK_DUPLICATE_PARAM1} — {@code AllFieldsFilled} named one field twice), so it clears the pattern's
     * own pre-checks and is arbitrated by the oracle. That distinction is the point: a pre-check refusal is exit 2
     * on stderr and would not exercise the verdict family at all.
     */
    @Test
    void aScaffoldTheKernelRejectsIsAVerdictAtExitOne(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);

        var res = CliTestSupport.run("-m", model.toString(), "patterns", "date-order",
                "--arg", "earlier=/Order/OrderDate", "--arg", "later=/Order/OrderDate");

        assertEquals(1, res.code(), () -> "a verdict of valid:false is exit 1: " + res.out() + res.err());
        JsonNode env = M.readTree(res.out());
        assertEquals("read", env.path("outcome").asText(), res::out);
        assertTrue(env.path("ok").asBoolean(),
                () -> "the invocation itself succeeded — `ok` and `valid` are different axes: " + res.out());
        assertEquals(false, env.path("valid").asBoolean(), res::out);
        assertEquals("KERNEL_CONFIRMED", env.path("verification").asText(),
                () -> "the kernel arbitrated the refusal, so the receipt is published on it: " + res.out());
        assertEquals("MVK_DUPLICATE_PARAM1", env.path("diagnostics").path(0).path("code").asText(), res::out);
        assertTrue(env.path("written").isBoolean() && !env.path("written").asBoolean(),
                () -> "a review writes nothing: " + res.out());
    }

    /**
     * The accepted direction, asserted here too so the exit code is locked as a <em>function of the verdict</em>
     * rather than as a constant. A test that only pinned the rejection would pass against a verb that always
     * exits 1.
     */
    @Test
    void andAKernelValidScaffoldStaysExitZero(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);

        var res = CliTestSupport.run("-m", model.toString(), "patterns", "date-order",
                "--arg", "earlier=/Order/OrderDate", "--arg", "later=/Order/DeliveryDate");

        assertEquals(0, res.code(), () -> "a verdict of valid:true is exit 0: " + res.out() + res.err());
        assertEquals(true, M.readTree(res.out()).path("valid").asBoolean(), res::out);
    }

    /**
     * And the verb's contract is now <b>discoverable</b>: it emits the standard envelope, so {@code schema patterns}
     * must resolve. It was classified {@code CONTRACT_EXEMPT} with the reason "emits the validation-idiom catalog
     * (and a scaffolded rule-spec) itself — run it", which stopped being true when the verb joined the envelope —
     * and {@code CliConformanceMatrixTest} only checks that an exemption's reason is non-blank, so a false reason
     * held the dead end open.
     */
    @Test
    void andItsContractIsDiscoverableRatherThanExempt() throws Exception {
        var res = CliTestSupport.run("schema", "patterns");

        assertEquals(0, res.code(), () -> "schema patterns must resolve: " + res.out() + res.err());
        JsonNode c = M.readTree(res.out());
        assertEquals("patterns", c.path("op").asText(), res::out);
        assertEquals("result", c.path("returns").asText(),
                () -> "it returns the standard envelope, which is why it is no longer exempt: " + res.out());
        assertTrue(c.path("notes").asText().contains("--apply"),
                () -> "and the notes must tell an agent the routes differ by --apply: " + res.out());
    }
}
