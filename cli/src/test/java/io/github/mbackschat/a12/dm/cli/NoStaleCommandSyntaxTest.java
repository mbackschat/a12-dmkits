package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Guards user-facing CLI text (help descriptions + diagnostic message templates) against <b>stale command
 * syntax</b>. The {@code cli→dmtool} rename and the target-first refactor each left old command examples
 * embedded in messages (e.g. "run {@code rulekit describe <model.json>}", "the positional {@code <model.json>}
 * argument", {@code read --all}) — telling an agent to run commands that no longer exist. Nothing caught it.
 *
 * <p>This scans {@code cli/src/main/java} and fails on the stale forms, so a future rename/refactor that
 * misses an embedded hint trips the build instead of shipping in the binary. The current invocation is
 * <b>{@code dmtool -m &lt;model.json&gt; &lt;target&gt; &lt;op&gt;}</b>: the tool is {@code dmtool}, the model is the
 * {@code -m} option (never positional), and every sub-op is target-prefixed ({@code model describe},
 * {@code rule read}, {@code computation explain}, …) — never a bare flat verb.
 */
class NoStaleCommandSyntaxTest {

    // sub-ops that must always be target-prefixed — a backtick command hint starting with one of these
    // bare (then a space + args) is the old flat-verb form.
    private static final Pattern FLAT_VERB_HINT = Pattern.compile(
            "`(describe|validate|info|read|explain|check|deps|format|test|eval|compute|add|modify|remove|"
                    + "rename|move|extract|inline|multiselect|attachment|new|import) ");
    private static final Pattern RULEKIT_AS_COMMAND = Pattern.compile("`rulekit ");   // the tool is `dmtool`
    private static final String POSITIONAL_MODEL = "positional <model";               // the model is `-m`, not positional

    @Test
    void noStaleCommandSyntaxInUserFacingText() throws IOException {
        Path root = Path.of("src/main/java");
        assertTrue(Files.isDirectory(root), "run from the :cli module dir; src/main/java not found");

        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                List<String> lines;
                try {
                    lines = Files.readAllLines(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    String why = null;
                    if (line.contains(POSITIONAL_MODEL)) {
                        why = "the model is the `-m <model.json>` option, never positional";
                    } else if (RULEKIT_AS_COMMAND.matcher(line).find()) {
                        why = "the shipped command is `dmtool`, not `rulekit`";
                    } else if (FLAT_VERB_HINT.matcher(line).find()) {
                        why = "stale flat-verb hint — use the target-first form (e.g. `rule read`, `computation explain`, `model describe`)";
                    }
                    if (why != null) {
                        violations.add(p + ":" + (i + 1) + " — " + why + "\n    " + line.trim());
                    }
                }
            });
        }
        assertTrue(violations.isEmpty(),
                () -> "stale command syntax in user-facing CLI text (fix to the dmtool target-first form):\n"
                        + String.join("\n", violations));
    }
}
