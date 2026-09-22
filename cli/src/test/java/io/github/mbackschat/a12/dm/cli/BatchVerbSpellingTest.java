package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A batch op may spell its verb the way the manifest does. {@code manifest} names a two-word verb as one
 * string ({@code "model check"}, {@code "computation add"}), {@code schema batch} describes {@code verb} as
 * "the verb to run", and the dispatcher used to pass that string through as a single argv token — so the
 * manifest's own spelling failed every child with {@code Unmatched argument at index 0}.
 *
 * <p><b>Why the failure was worth fixing rather than knowing.</b> It is invisible in exactly the shape
 * {@code batch} exists for: a separating matrix fails <em>uniformly</em>, controls included, which reads as a
 * route problem rather than a spelling one, and the outer envelope's {@code allExitedZero:false} plus
 * {@code outcome:completed} is what a genuine mixed-verdict run reports too. Reported twice by
 * a12-kernel-lean (2026-09-21 §1, reproduced 2026-09-22 §1), each time costing a round.
 *
 * <p>The two spellings cannot collide, because no single-token verb contains a space — which is what makes
 * accepting both a widening rather than an ambiguity.
 */
class BatchVerbSpellingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path model(Path dir) throws IOException {
        Path f = dir.resolve("m.dm.json");
        Files.writeString(f, CliTestSupport.subscriptionJson());
        return f;
    }

    private static JsonNode runBatch(Path dir, String ops) throws Exception {
        Path opsFile = dir.resolve("ops.json");
        Files.writeString(opsFile, ops);
        return MAPPER.readTree(CliTestSupport.run("batch", opsFile.toString()).out());
    }

    @Test
    void theManifestSpellingOfATwoWordVerbDispatchesExactlyAsTheSplitOneDoes(@TempDir Path dir)
            throws Exception {
        String m = model(dir).toString();
        JsonNode manifestSpelling = runBatch(dir,
                "[{\"id\":\"c\",\"verb\":\"model check\",\"args\":[\"-m\",\"" + m + "\"]}]");
        JsonNode splitSpelling = runBatch(dir,
                "[{\"id\":\"c\",\"verb\":\"model\",\"args\":[\"check\",\"-m\",\"" + m + "\"]}]");

        JsonNode manifestChild = manifestSpelling.path("data").path("results").get(0);
        JsonNode splitChild = splitSpelling.path("data").path("results").get(0);
        assertEquals(0, manifestChild.path("exit").asInt(-1),
                () -> "the manifest's own spelling must dispatch: " + manifestChild);
        assertEquals(splitChild.path("result"), manifestChild.path("result"),
                "both spellings reach the same verb, so they must produce the same child envelope");
        assertEquals("model check", manifestChild.path("verb").asText(),
                "the result echoes the caller's spelling verbatim, so a correlation still matches");
    }

    /**
     * A two-word verb still inherits the batch-level {@code -m}. The insertion POSITION is not what this
     * locks and could not be: `-m` is an INHERIT-scope root option, so it parses on either side of the
     * sub-verb (a mutant moving it back inside the verb survives this class, checked).
     */
    @Test
    void aTwoWordVerbInheritsTheBatchLevelModel(@TempDir Path dir) throws Exception {
        Path opsFile = dir.resolve("ops.json");
        Files.writeString(opsFile, "[{\"id\":\"c\",\"verb\":\"model check\",\"args\":[]}]");
        var res = CliTestSupport.run("-m", model(dir).toString(), "batch", opsFile.toString());
        JsonNode child = MAPPER.readTree(res.out()).path("data").path("results").get(0);
        assertEquals(0, child.path("exit").asInt(-1),
                () -> "the inherited -m must be placed after the two-token verb: " + child);
    }

    /**
     * The global mistake is reported globally. A verb that names no command is always wrong for every child,
     * so it is refused before the first one runs rather than repeated N times — and because dispatch is
     * non-transactional, refusing up front is also the only point at which nothing has been written yet.
     */
    @Test
    void anUnresolvableVerbRefusesTheWholeBatchBeforeAnyChildRuns(@TempDir Path dir) throws Exception {
        String m = model(dir).toString();
        Path opsFile = dir.resolve("ops.json");
        Files.writeString(opsFile, "[{\"id\":\"ok\",\"verb\":\"model check\",\"args\":[\"-m\",\"" + m + "\"]},"
                + "{\"id\":\"bad\",\"verb\":\"modle\",\"args\":[]}]");

        var res = CliTestSupport.run("batch", opsFile.toString());
        JsonNode envelope = MAPPER.readTree(res.out());
        assertEquals("refused", envelope.path("outcome").asText(), res::out);
        assertTrue(envelope.path("data").path("dispatched").isMissingNode(),
                () -> "a refusal carries no dispatch aggregate at all — nothing ran: " + res.out());
        assertFalse(envelope.path("written").asBoolean(),
                () -> "and nothing was written: " + res.out());
        JsonNode diagnostic = envelope.path("diagnostics").get(0);
        assertEquals("/1", diagnostic.path("where").path("path").asText(),
                () -> "the refusal points at the offending item: " + diagnostic);
        assertTrue(diagnostic.path("summary").asText().contains("modle"),
                () -> "the refusal quotes the verb as written: " + diagnostic);
    }

    /** Nesting stays refused whichever spelling reaches it, and stays a per-op answer. */
    @Test
    void batchStillCannotNest(@TempDir Path dir) throws Exception {
        JsonNode envelope = runBatch(dir, "[{\"verb\":\"batch\",\"args\":[\"ops.json\"]}]");
        JsonNode child = envelope.path("data").path("results").get(0);
        assertEquals(2, child.path("exit").asInt(-1), () -> "a nested batch is refused: " + child);
        assertTrue(child.path("stderr").asText().contains("cannot nest"), child::toString);
    }
}
