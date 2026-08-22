package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code batch --observations} and {@code -} are structurally incompatible, and the refusal has to say why
 * rather than name a path the caller never wrote (G60).
 *
 * <p>The observation artifact's whole value is that it commits to the <b>exact authored bytes</b> of each
 * candidate: capture re-reads the child's spec from disk and hashes it. A stream cannot serve that — it is
 * consumed, unnamed, and unre-readable. Before this, both halves fabricated {@code <cwd>/-} instead: the
 * computation-spec read failed with a corrective pointing at a nonexistent file, and the ops path went into the
 * overwrite-protection set, so a real file named {@code -} was falsely protected while the actual ops source
 * (stdin) was not protected at all.
 */
class BatchObservationStdinTest {

    private static final ObjectMapper M = new ObjectMapper();

    private final java.util.function.Supplier<java.io.Reader> original = JsonInput.stdinSource;

    @BeforeEach
    @AfterEach
    void restoreStdin() {
        JsonInput.stdinSource = original;
        JsonInput.resetForInvocation();
    }

    private static Path model(Path dir) throws Exception {
        Path file = dir.resolve("m.json");
        Files.writeString(file, Files.readString(
                Path.of("src/test/resources/models/subscription-computed.dm.json")));
        return file;
    }

    private static JsonNode firstDiagnostic(String out) throws Exception {
        return M.readTree(out).path("diagnostics").path(0);
    }

    @Test
    void opsFromStdinAreRefusedForAnObservedBatch(@TempDir Path dir) throws Exception {
        Path model = model(dir);
        JsonInput.stdinSource = () -> new StringReader("""
                [{"id":"x","verb":"rule","args":["check","--condition","FieldFilled(/Subscription/Reference)",
                  "--group","/Subscription","--field","../Reference","--code","E1","-m","%s"]}]"""
                .formatted(model.toString().replace("\\", "\\\\")));
        JsonInput.resetForInvocation();

        var res = CliTestSupport.run("batch", "-", "--observations", dir.resolve("obs.json").toString());
        assertTrue(res.code() != 0, res::out);
        JsonNode d = firstDiagnostic(res.out());
        assertTrue(d.path("summary").asText().contains("observation"), res::out);
        assertTrue(d.path("summary").asText().contains("stdin") || d.path("fix").asText().contains("stdin"),
                () -> "the refusal must name stdin as the cause, not a fabricated path: " + res.out());
        assertFalse((d.path("summary").asText() + d.path("fix").asText()).contains("/-"),
                () -> "no fabricated <cwd>/- may appear: " + res.out());
    }

    @Test
    void aStdinComputationSpecIsRefusedWithTheRealReason(@TempDir Path dir) throws Exception {
        Path model = model(dir);
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, """
                [{"id":"x","verb":"computation","args":["add","-","--dry-run","-m","%s"]}]"""
                .formatted(model.toString().replace("\\", "\\\\")));

        var res = CliTestSupport.run("batch", ops.toString(),
                "--observations", dir.resolve("obs.json").toString());
        assertTrue(res.code() != 0, res::out);
        JsonNode d = firstDiagnostic(res.out());
        assertFalse((d.path("summary").asText() + d.path("fix").asText()).contains("/-"),
                () -> "no fabricated <cwd>/- may appear: " + res.out());
        assertTrue(d.path("fix").asText().contains("file"),
                () -> "the corrective must say the artifact needs a file: " + res.out());
    }

    /** The control: from files, an observed batch still completes. */
    @Test
    void anObservedBatchFromFilesStillWorks(@TempDir Path dir) throws Exception {
        Path model = model(dir);
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, """
                [{"id":"x","verb":"rule","args":["check","--condition","FieldFilled(/Subscription/Reference)",
                  "--group","/Subscription","--field","../Reference","--code","E1","-m","%s"]}]"""
                .formatted(model.toString().replace("\\", "\\\\")));

        var res = CliTestSupport.run("batch", ops.toString(),
                "--observations", dir.resolve("obs.json").toString());
        assertEquals(0, res.code(), res::err);
        assertEquals("completed", M.readTree(res.out()).path("outcome").asText(), res::out);
    }
}
