package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A12 Tools requires a model's <b>file basename to equal its {@code header.id}</b>, and {@code model new --id}
 * says so in its own help — but nothing made the pair readable, so a plain file copy broke the invariant with
 * no way to notice short of opening the raw JSON. {@code model info} now emits {@code fileName} beside
 * {@code id}, which is the locus for that advertised guarantee.
 *
 * <p><b>Why a readable FACT and not a diagnostic.</b> A warning was built first, on the
 * {@code RK_MODEL_VERSION_SKEW} template, and removed once the demo gate measured what it costs: six demos
 * drifted, and the filenames doing it were {@code subject.dm.json}, {@code struct.dm.json},
 * {@code edit-add.json} — every one a {@code cp}-made working copy. Copy-to-a-scratch-name, edit, check is the
 * NORMAL workflow, so the warning fired on the common case rather than the exceptional mistake, and a
 * diagnostic that always fires teaches its reader to skip diagnostics. The kernel does not enforce this
 * convention, so neither do we; we make it checkable and let the caller decide.
 */
class ModelIdBasenamePolicyTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** The order-ruled fixture written to {@code basename}, with its {@code header.id} set to {@code id}. */
    private static Path model(Path dir, String basename, String id) throws Exception {
        ObjectNode model = (ObjectNode) M.readTree(CliTestSupport.orderRuledJson());
        ((ObjectNode) model.path("header")).put("id", id);
        Path file = dir.resolve(basename);
        Files.writeString(file, M.writeValueAsString(model));
        return file;
    }

    private static JsonNode info(Path model) throws Exception {
        var res = CliTestSupport.run("-m", model.toString(), "model", "info");
        assertEquals(0, res.code(), res.err());
        return M.readTree(res.out()).path("data");
    }

    @Test
    void modelInfoAnswersTheInvariantWithoutReadingRawJson(@TempDir Path dir) throws Exception {
        JsonNode data = info(model(dir, "Renamed_DM.json", "Cover_DM"));
        assertEquals("Cover_DM", data.path("id").asText(), data::toString);
        assertEquals("Renamed_DM.json", data.path("fileName").asText(), data::toString);
        assertFalse(data.path("fileNameMatchesId").asBoolean(true), data::toString);
    }

    @Test
    void aMatchingPairIsMarkedAsAgreeing(@TempDir Path dir) throws Exception {
        JsonNode data = info(model(dir, "Cover_DM.json", "Cover_DM"));
        assertEquals("Cover_DM.json", data.path("fileName").asText(), data::toString);
        assertTrue(data.path("fileNameMatchesId").asBoolean(false), data::toString);
    }

    /**
     * The tolerance, and why it is not a bug: A12 Tools' handling of a DOUBLE extension is not measured by this
     * project, and the `<name>.dm.json` convention is used throughout our own docs and fixtures. Reporting it as
     * a disagreement would assert a rule we cannot demonstrate, against the most common filename shape there is.
     */
    @Test
    void theDotDmDotJsonConventionCountsAsAgreeing(@TempDir Path dir) throws Exception {
        assertTrue(info(model(dir, "cover.dm.json", "cover")).path("fileNameMatchesId").asBoolean(false));
    }

    /** …while a genuinely different name disagrees under every stripping rule, so it is reported as false. */
    @Test
    void aGenuinelyDifferentNameIsMarkedAsDisagreeing(@TempDir Path dir) throws Exception {
        assertFalse(info(model(dir, "cover-ruled.dm.json", "cover")).path("fileNameMatchesId").asBoolean(true));
    }

    /**
     * The deliberate NON-GOAL, locked so it cannot drift back in: no verb emits a diagnostic for the mismatch.
     * See the class note for the measurement that settled it.
     */
    @Test
    void noVerbWarnsAboutTheMismatch(@TempDir Path dir) throws Exception {
        Path file = model(dir, "Renamed_DM.json", "Cover_DM");
        for (String[] argv : new String[][] {
                {"model", "check"},
                {"field", "add", "--group", "/Order", "--name", "Note", "--kind", "STRING", "--dry-run"}}) {
            String[] full = new String[argv.length + 2];
            full[0] = "-m";
            full[1] = file.toString();
            System.arraycopy(argv, 0, full, 2, argv.length);
            var res = CliTestSupport.run(full);
            assertEquals(0, res.code(), res.err());
            assertFalse(res.out().contains("BASENAME"), res::out);
        }
    }
}
