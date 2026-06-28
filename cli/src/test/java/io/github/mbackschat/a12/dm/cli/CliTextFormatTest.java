package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R2 (CLI-SPEC §2): a global {@code --text} flag renders the result envelope
 * human-readably for the secondary direct-BA/dev path. JSON stays the default + the machine contract;
 * non-envelope output (the operators/manifest/... catalogs) passes through as JSON. Not TTY-detection —
 * an explicit flag, so an agent in a PTY never silently gets text.
 */
class CliTextFormatTest {

    @Test
    void defaultOutputIsJson(@TempDir Path dir) throws IOException {
        Path model = CliTestSupport.modelWithRule(dir);
        CliTestSupport.Result res = CliTestSupport.run("-m", model.toString(), "model", "check");
        assertEquals(0, res.code(), res::err);
        assertTrue(res.out().stripLeading().startsWith("{") && res.out().contains("\"outcome\""),
                () -> "default is JSON: " + res.out());
    }

    @Test
    void textModeRendersHumanReadableNotJson(@TempDir Path dir) throws IOException {
        Path model = CliTestSupport.modelWithRule(dir);
        CliTestSupport.Result res = CliTestSupport.run("-m", model.toString(), "model", "check", "--text");
        assertEquals(0, res.code(), res::err);
        String out = res.out();
        assertFalse(out.stripLeading().startsWith("{"), () -> "text mode is not raw JSON: " + out);
        assertFalse(out.contains("\"outcome\""), () -> "no JSON envelope keys in text mode: " + out);
        assertTrue(out.toLowerCase().contains("valid"), () -> "renders the validity: " + out);
    }

    @Test
    void textFlagPositionDoesNotMatter(@TempDir Path dir) throws IOException {
        // --text is INHERIT, so it parses before or after the verb (ergonomics parity with -m/-w)
        Path model = CliTestSupport.modelWithRule(dir);
        String before = CliTestSupport.run("--text", "-m", model.toString(), "model", "check").out();
        String after = CliTestSupport.run("-m", model.toString(), "model", "check", "--text").out();
        assertEquals(before, after, "position of --text does not change the rendering");
        assertFalse(before.stripLeading().startsWith("{"), () -> "both are text: " + before);
    }

    @Test
    void textFlagIsSelfDescribing() {
        CliTestSupport.Result res = CliTestSupport.run("--help");
        assertTrue(res.out().contains("--text"), () -> "--text is discoverable in --help: " + res.out());
    }
}
