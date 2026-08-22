package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.function.Supplier;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code -} for stdin on every JSON positional (G56). Authoring a config-bearing field kind previously needed a
 * temp file per field, because {@code --kind} covers only the config-less kinds; a scripted author wrote and
 * cleaned up one file per call.
 *
 * <p>The contract has three parts and each is locked here: the file form is unchanged, {@code -} reads stdin,
 * and a SECOND {@code -} in one invocation is refused rather than handed an exhausted stream — which would
 * compare a document against nothing and call it a result.
 */
class StdinJsonInputTest {

    private static final ObjectMapper M = new ObjectMapper();
    // A config-bearing kind, which is the whole reason a spec file was mandatory: --kind covers only the
    // config-less kinds. Deliberately NOT exercising interpretationOfYear here — that is G53's slice, and
    // coupling the two would make each one's failures read as the other's.
    private static final String RANGE_SPEC = """
            {"group":"/Order","name":"CoverWindow","kind":"DATERANGE",
             "dateRange":{"format":"dd.MM","separator":"-"}}""";

    private final Supplier<Reader> originalSource = JsonInput.stdinSource;

    @BeforeEach
    @AfterEach
    void restoreStdin() {
        JsonInput.stdinSource = originalSource;
        JsonInput.resetForInvocation();
    }

    /**
     * Point the stdin seam at fixed text. The supplier hands back the SAME reader every time, because that is
     * what {@code System.in} is — a fresh reader per call would make an exhausted stream unrepresentable, and
     * an exhausted stream is the condition every refusal in this class exists to prevent.
     */
    private static void stdin(String text) {
        Reader once = new StringReader(text);
        JsonInput.stdinSource = () -> once;
        JsonInput.resetForInvocation();
    }

    @Test
    void aFilePathStillReadsTheFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("spec.json");
        Files.writeString(file, RANGE_SPEC);
        assertEquals(RANGE_SPEC, JsonInput.read(file.toString(), "<spec.json>"));
    }

    @Test
    void aDashReadsStdin() throws Exception {
        stdin(RANGE_SPEC);
        assertEquals(RANGE_SPEC, JsonInput.read("-", "<spec.json>"));
    }

    @Test
    void aSecondDashIsRefusedAndNamesWhatAlreadyClaimedStdin() throws Exception {
        stdin(RANGE_SPEC);
        JsonInput.read("-", "<profile.json>");
        IOException refused = assertThrows(IOException.class, () -> JsonInput.read("-", "<other.json>"));
        assertTrue(refused.getMessage().contains("<profile.json>"), refused::getMessage);
        assertTrue(refused.getMessage().contains("<other.json>"), refused::getMessage);
    }

    @Test
    void anEmptyStdinIsRefusedWithARunnableCorrective() {
        stdin("");
        IOException refused = assertThrows(IOException.class, () -> JsonInput.read("-", "<spec.json>"));
        assertTrue(refused.getMessage().contains("stdin was empty"), refused::getMessage);
        assertTrue(refused.getMessage().contains("dmtool"), refused::getMessage);
    }

    @Test
    void onlyTheExactStringDashIsTheStdinMarker(@TempDir Path dir) throws Exception {
        // Advertised in JsonInput's javadoc ("a file genuinely named `-` is reachable as `./-`"), so it needs a
        // locus. The load-bearing half is the DISCRIMINATION: any spelling other than the bare "-" must go to the
        // filesystem, never to stdin — otherwise a real file could be shadowed by the marker.
        Files.writeString(dir.resolve("-"), RANGE_SPEC);
        stdin("{\"different\":\"text\"}");
        assertEquals(RANGE_SPEC, JsonInput.read(dir.resolve("-").toString(), "<spec.json>"),
                "a path ENDING in '-' is a file, not the marker");

        // "./-" likewise attempts a file read; it resolves against the process CWD, where no such file exists.
        assertThrows(java.nio.file.NoSuchFileException.class, () -> JsonInput.read("./-", "<spec.json>"));
    }

    /**
     * The claim SURVIVES a nested {@code Cli.run}, and this drives the nesting rather than simulating it.
     *
     * <p>The first version of this test asserted the opposite — that two successive runs in one process may
     * each claim stdin — and that invariant is wrong for production: {@code batch} and the MCP invoker re-enter
     * {@code Cli.run} per child, so a reset there lets a second {@code -} through the refusal that exists to
     * stop it. stdin belongs to the PROCESS, so the reset belongs to the process shell. (It reached green by
     * priming stdin with a helper that also cleared the claim — the lock handing the code its own answer.)
     */
    @Test
    void aNestedInvocationCannotReclaimStdin(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("Cover_DM.json");
        Files.writeString(model, CliTestSupport.orderRuledJson());
        // The PARENT must claim stdin for the nesting to matter: ops arrive on stdin, and a child then asks
        // for `-` as well. (An ops FILE plus one `-` child is a single claimant and legitimately succeeds.)
        stdin("[{\"verb\":\"field\",\"args\":[\"add\",\"-\"]}]");

        var res = CliTestSupport.run("-m", model.toString(), "batch", "-");
        assertTrue(res.out().contains("stdin is already being read") || res.err().contains("stdin is already"),
                () -> "the nested claim must be refused, not served a drained stream: " + res.out() + res.err());
        assertFalse(res.out().contains("\"outcome\" : \"applied\""),
                () -> "the child must not have written anything: " + res.out());
    }

    /**
     * And the reason the refusal matters: reading stdin must NOT close it. Closing a reader over
     * {@code System.in} closes fd 0 for the whole process, which would end an {@code mcp} stdio session
     * mid-conversation. Asserted on the seam because that is where an exhaustible stream is observable.
     */
    @Test
    void readingStdinDoesNotCloseTheUnderlyingStream() throws Exception {
        var probe = new StringReader(RANGE_SPEC) {
            boolean closed;
            @Override public void close() {
                closed = true;
                super.close();
            }
        };
        JsonInput.stdinSource = () -> probe;
        JsonInput.resetForInvocation();
        JsonInput.read("-", "<spec.json>");
        assertFalse(probe.closed, "stdin must not be closed — it is the process's fd 0, not this read's");
    }

    @Test
    void aConfigBearingFieldIsAddableInOneCommandWithNoFileOnDisk(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("Cover_DM.json");
        Files.writeString(model, CliTestSupport.orderRuledJson());
        stdin(RANGE_SPEC);

        var res = CliTestSupport.run("-m", model.toString(), "field", "add", "-");
        assertEquals(0, res.code(), res.err());
        assertEquals("/Order/CoverWindow", M.readTree(res.out()).path("changed").path("added").asText(), res::out);

        var read = CliTestSupport.run("-m", model.toString(), "field", "read", "/Order/CoverWindow");
        assertEquals(0, read.code(), read.err());
        var range = M.readTree(read.out()).path("data").path("dateRange");
        assertEquals("dd.MM", range.path("format").asText(), read::out);
        assertEquals("-", range.path("separator").asText(), read::out);
    }

    @Test
    void theFileFormAndTheStdinFormProduceTheSameModel(@TempDir Path dir) throws Exception {
        Path viaFile = dir.resolve("ViaFile_DM.json");
        Path viaStdin = dir.resolve("ViaStdin_DM.json");
        Files.writeString(viaFile, CliTestSupport.orderRuledJson());
        Files.writeString(viaStdin, CliTestSupport.orderRuledJson());
        Path spec = dir.resolve("spec.json");
        Files.writeString(spec, RANGE_SPEC);

        assertEquals(0, CliTestSupport.run("-m", viaFile.toString(), "field", "add", spec.toString()).code());
        stdin(RANGE_SPEC);
        assertEquals(0, CliTestSupport.run("-m", viaStdin.toString(), "field", "add", "-").code());

        assertEquals(Files.readString(viaFile), Files.readString(viaStdin));
        assertNotEquals(CliTestSupport.orderRuledJson(), Files.readString(viaFile), "the write must have happened");
    }
}
