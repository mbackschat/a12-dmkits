package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
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

    @BeforeEach
    @AfterEach
    void clearStdin() {
        JsonInput.stdinSource = () -> new StringReader("");
        JsonInput.resetForInvocation();
    }

    /** Point the stdin seam at fixed text, the way the process's real stdin would arrive. */
    private static void stdin(String text) {
        JsonInput.stdinSource = () -> new StringReader(text);
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
     * The claim is cleared by {@code Cli.run}, and this drives it THROUGH {@code Cli.run} rather than calling
     * the reset itself. The distinction is the whole point: the first version of this test primed stdin with a
     * helper that also reset the claim, so it supplied the state the production code is supposed to provide and
     * a mutant deleting {@code Cli.run}'s reset survived. A lock must not hand the code under test its answer.
     */
    @Test
    void twoSuccessiveInvocationsInOneProcessMayEachClaimStdin(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("Cover_DM.json");
        Files.writeString(model, CliTestSupport.orderRuledJson());

        JsonInput.stdinSource = () -> new StringReader(RANGE_SPEC);
        var first = CliTestSupport.run("-m", model.toString(), "field", "add", "-");
        assertEquals(0, first.code(), first.err());

        // Only the SOURCE is re-primed — no reset here, so a second claim can only succeed if Cli.run cleared it.
        JsonInput.stdinSource = () -> new StringReader(RANGE_SPEC.replace("CoverWindow", "SecondWindow"));
        var second = CliTestSupport.run("-m", model.toString(), "field", "add", "-");
        assertEquals(0, second.code(), second.err());
        assertEquals("/Order/SecondWindow", M.readTree(second.out()).path("changed").path("added").asText(),
                second::out);
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
