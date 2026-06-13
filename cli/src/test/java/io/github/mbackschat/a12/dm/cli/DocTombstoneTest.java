package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * The renamed {@code doc} target is a <b>fail-fast tombstone, not a silent alias</b> (CLI-SPEC §5): the
 * whole-document target became {@code model} (the document model — vs {@code --doc}, the instance). A stale
 * {@code doc …} caller must be <em>revealed and corrected</em>, never quietly served — so it exits non-zero
 * with a message naming {@code model}, and {@code doc} never appears on the self-describing surface.
 */
class DocTombstoneTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void docTargetRefusesWithACorrectiveModelMessage() {
        CliTestSupport.Result res = CliTestSupport.run("doc", "validate", "-m", "x.dm.json");
        assertEquals(2, res.code(), res::out);
        assertTrue(res.err().contains("renamed to `model`"),
                () -> "the doc tombstone names its replacement: " + res.err());
    }

    @Test
    void docTargetEchoesTheCorrectedCommandEvenWithTrailingFlags() {
        CliTestSupport.Result res = CliTestSupport.run("doc", "eval", "--doc", "instance.json");
        assertEquals(2, res.code());
        assertTrue(res.err().contains("model eval"),
                () -> "the tombstone echoes the corrected `model …` command, flags and all: " + res.err());
    }

    @Test
    void docIsAbsentFromTheManifest() throws Exception {
        // the tombstone is hidden — the canonical surface is `model`; no `doc`/`doc *` verb may be listed
        JsonNode manifest = M.readTree(CliTestSupport.run("manifest").out());
        Set<String> verbs = new HashSet<>();
        manifest.path("verbs").forEach(v -> verbs.add(v.path("verb").asText()));
        assertFalse(verbs.stream().anyMatch(v -> v.equals("doc") || v.startsWith("doc ")),
                () -> "the renamed `doc` target must not appear on the self-describing surface: " + verbs);
    }
}
