package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Guards user-facing CLI text against <b>stale command syntax</b>. The {@code cli→dmtool} rename, the
 * target-first refactor, and the {@code doc→model} target rename each left old command examples embedded
 * in messages — in diagnostic templates AND the bundled <b>schema descriptions</b> + operator catalog
 * (which an agent reads via {@code dmtool schema …} / {@code dmtool operators}). Telling an agent to run a
 * command that no longer exists is a real defect, and nothing caught it.
 *
 * <p>This scans the CLI's authored text — {@code cli/src/main/java} (help + diagnostic templates), the
 * bundled {@code schema/*.json} + {@code verb-examples.json}, and the operator catalog — and fails on the
 * stale forms. The current invocation is <b>{@code dmtool -m &lt;model.json&gt; &lt;target&gt; &lt;op&gt;}</b>: the
 * tool is {@code dmtool}, the model is the {@code -m} option (never positional), every sub-op is
 * target-prefixed ({@code model describe}, {@code rule read}, …), and the old {@code doc} target is now
 * {@code model}.
 */
class NoStaleCommandSyntaxTest {

    // backtick command hints starting with a bare sub-op (then a space) = the old flat-verb form.
    private static final Pattern FLAT_VERB_HINT = Pattern.compile(
            "`(describe|validate|info|read|explain|check|deps|format|test|eval|compute|add|modify|remove|"
                    + "rename|move|extract|inline|multiselect|attachment|new|import|add-field|add-group|"
                    + "add-include|add-typedef|eval-doc|compute-doc) ");
    private static final Pattern RULEKIT_AS_COMMAND = Pattern.compile("`rulekit ");   // the tool is `dmtool`
    private static final Pattern DOC_AS_TARGET = Pattern.compile("`doc ");            // the `doc` target is now `model`
    private static final String POSITIONAL_MODEL = "positional <model";              // the model is `-m`, not positional

    // authored user-facing text: CLI source (help + diagnostics) + bundled schema/catalog resources.
    private static final List<Path> ROOTS = List.of(
            Path.of("src/main/java"),
            Path.of("src/main/resources"),               // schema/*.json, verb-examples.json
            Path.of("../src/main/resources/catalog"));   // operators.json (the core module's catalog)

    @Test
    void noStaleCommandSyntaxInUserFacingText() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path root : ROOTS) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".json"))
                        .forEach(p -> scan(p, violations));
            }
        }
        assertTrue(!violations.isEmpty() || foundFiles(), "scanned no files — check the working dir / ROOTS");
        assertTrue(violations.isEmpty(),
                () -> "stale command syntax in user-facing CLI text (fix to the dmtool target-first form):\n"
                        + String.join("\n", violations));
    }

    private static boolean foundFiles() {
        return ROOTS.stream().anyMatch(Files::isDirectory);
    }

    private static void scan(Path file, List<String> violations) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String why = null;
            if (line.contains(POSITIONAL_MODEL)) {
                why = "the model is the `-m <model.json>` option, never positional";
            } else if (RULEKIT_AS_COMMAND.matcher(line).find()) {
                why = "the shipped command is `dmtool`, not `rulekit`";
            } else if (DOC_AS_TARGET.matcher(line).find()) {
                why = "the `doc` target was renamed to `model` (e.g. `model eval`, not `doc eval`)";
            } else if (FLAT_VERB_HINT.matcher(line).find()) {
                why = "stale flat-verb hint — use the target-first form (e.g. `rule read`, `computation explain`, `model describe`)";
            }
            if (why != null) {
                violations.add(file + ":" + (i + 1) + " — " + why + "\n    " + line.trim());
            }
        }
    }
}
