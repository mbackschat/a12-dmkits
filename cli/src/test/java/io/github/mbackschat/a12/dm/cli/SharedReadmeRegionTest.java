package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * The shared <b>"dmtool story"</b> block — <i>Why dmtool</i> + the natural-language agent session + the
 * skill/plugin explanation — is authored ONCE (canonically in the top-level {@code README.md}) and MIRRORED
 * verbatim into {@code plugin/README.md}, which ships to the public release mirror. This guard fails the build
 * if the two regions drift, so the single-source discipline can't silently rot (the same posture as the demo
 * + snippet freshness guards). It also keeps the block <b>link-light</b>: the mirror has no {@code docs/} tree,
 * so a {@code docs/} link inside the shared block would 404 there — doc pointers stay in the top-level-only
 * sections outside the markers. To change the story: edit the canonical block in {@code README.md}, then
 * re-copy the region between the markers into {@code plugin/README.md}.
 */
class SharedReadmeRegionTest {

    private static final String BEGIN = "<!-- shared:dmtool-story";        // prefix; the marker line may carry a note
    private static final String END = "<!-- /shared:dmtool-story -->";
    private static final Path TOP = Path.of("../README.md");
    private static final Path PLUGIN = Path.of("../plugin/README.md");

    @Test
    void pluginReadmeMirrorsTheCanonicalDmtoolStory() throws IOException {
        assertEquals(region(TOP), region(PLUGIN),
                "plugin/README.md's shared:dmtool-story block has drifted from the canonical one in README.md. "
                        + "It is single-sourced in the top-level README — re-copy the region between the markers.");
    }

    @Test
    void theSharedBlockStaysLinkLight() throws IOException {
        assertTrue(!region(TOP).contains("docs/"),
                "the shared:dmtool-story block must not link into docs/ — the public mirror has no docs/ tree, "
                        + "so keep doc pointers in the top-level-only sections, outside the markers.");
    }

    @Test
    void topLevelReadmeDescribesTheStandaloneCliAsShipped() throws IOException {
        var readme = Files.readString(TOP);

        assertTrue(readme.contains("**The shipped standalone CLI distributable: EUPL-1.2**"));
        assertFalse(readme.contains("future standalone CLI distributable"));
    }

    /** The text strictly between the BEGIN marker line and the END marker, trimmed. */
    private static String region(Path md) throws IOException {
        String text = Files.readString(md);
        int b = text.indexOf(BEGIN);
        assertTrue(b >= 0, () -> md + " is missing the '" + BEGIN + " …' marker");
        int contentStart = text.indexOf('\n', b) + 1;
        int e = text.indexOf(END, contentStart);
        assertTrue(e >= 0, () -> md + " is missing the '" + END + "' marker");
        return text.substring(contentStart, e).strip();
    }
}
