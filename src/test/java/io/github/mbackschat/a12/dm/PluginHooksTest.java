package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Structural guard for the agent plugins' {@code hooks.json} (both Claude Code and Codex). The
 * SessionStart hook is how the plugin delivers the {@code dmtool} binary onto PATH — if its shape is
 * wrong the host rejects the whole file and the hook never runs, silently. That shipped in v0.1.0/v0.1.1:
 * the Claude file put {@code type}/{@code command} directly on the SessionStart entry instead of inside the
 * required {@code hooks:[…]} array, so the auto-download never fired. Nothing caught it because hooks.json
 * has no compiler and no test. This locks the schema both hosts expect:
 * {@code hooks.SessionStart[] → { hooks: [ { type:"command", command:"…" } ] } }.
 */
class PluginHooksTest {

    private static final ObjectMapper M = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"plugin/hooks/hooks.json", "plugin-codex/hooks/hooks.json"})
    void sessionStartHookHasTheArrayWrappedCommandShape(String relative) throws IOException {
        JsonNode root = M.readTree(Files.readString(repoFile(relative)));
        JsonNode sessionStart = root.path("hooks").path("SessionStart");
        assertTrue(sessionStart.isArray() && !sessionStart.isEmpty(),
                () -> relative + ": hooks.SessionStart must be a non-empty array");
        for (JsonNode entry : sessionStart) {
            JsonNode inner = entry.path("hooks");
            assertTrue(inner.isArray() && !inner.isEmpty(),
                    () -> relative + ": each SessionStart entry must carry a non-empty 'hooks' array "
                            + "(the bug was type/command placed directly on the entry)");
            for (JsonNode h : inner) {
                assertTrue("command".equals(h.path("type").asText()),
                        () -> relative + ": each hook must be {\"type\":\"command\", …}");
                assertFalse(h.path("command").asText().isBlank(),
                        () -> relative + ": each hook needs a non-blank 'command'");
            }
        }
    }

    private static Path repoFile(String relative) {
        for (String base : List.of(".", "..", "../..")) {
            Path p = Path.of(base, relative);
            if (Files.exists(p)) {
                return p;
            }
        }
        throw new IllegalStateException(relative + " not found from " + Path.of("").toAbsolutePath());
    }
}
