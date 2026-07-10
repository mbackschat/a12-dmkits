package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * G4 — no-drift guard: <b>every live manifest verb must appear in the CLI-SPEC §5 verb-surface table</b>
 * ({@code manifest verbs ⊆ the §5 table}). The table is the human/agent-facing verb tour; a verb landing
 * in the tool without a row is exactly the drift that let seven verbs (model
 * diff/report/normalize/import-jsonschema/export-jsonschema, rule/computation format) go untabled.
 *
 * <p>Reads {@code docs/CLI-SPEC.md} at runtime, so it lives in the {@code docGuardTest} task with that
 * file declared as its input (TESTING-SPEC "Tests that read repo files at runtime") — never on the broad
 * {@code :cli:test}, where a doc edit would go up-to-date into a false green.
 */
class CliSpecVerbTableTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern CODE_SPAN = Pattern.compile("`([^`]*)`");

    @Test
    void everyManifestVerbAppearsInTheCliSpecVerbTable() throws Exception {
        Set<String> tableOps = section5OpTokens();
        Set<String> missing = new TreeSet<>();
        for (String verb : manifestVerbs()) {
            // a verb is "<target> <op>" or a bare root verb; the §5 table lists ops per target, so the
            // subset check is on the op token (the last word), which is what a reader looks up.
            String op = verb.contains(" ") ? verb.substring(verb.lastIndexOf(' ') + 1) : verb;
            if (!tableOps.contains(op)) {
                missing.add(verb + " (op '" + op + "')");
            }
        }
        assertTrue(missing.isEmpty(),
                () -> "manifest verbs missing from the CLI-SPEC §5 verb-surface table: " + missing
                        + "\n(add the op to its target's row in docs/CLI-SPEC.md §5)");
    }

    private static Set<String> manifestVerbs() throws Exception {
        JsonNode j = MAPPER.readTree(CliTestSupport.run("manifest").out());
        Set<String> verbs = new LinkedHashSet<>();
        for (JsonNode v : j.path("verbs")) {
            verbs.add(v.path("verb").asText());
        }
        assertTrue(verbs.size() > 30, "sanity: the manifest should list every verb");
        return verbs;
    }

    /** The op tokens listed in the §5 table — the first word of each {@code ·}-separated token in every
     *  backtick code span of the table (prose in {@code *(...)*} is deliberately excluded). */
    private static Set<String> section5OpTokens() throws IOException {
        String spec = Files.readString(clispec(), StandardCharsets.UTF_8);
        int start = spec.indexOf("## 5. Verb surface");
        assertTrue(start >= 0, "CLI-SPEC.md must have a '## 5. Verb surface' section");
        int end = spec.indexOf("\n## ", start + 1);
        String section = end < 0 ? spec.substring(start) : spec.substring(start, end);

        Set<String> ops = new LinkedHashSet<>();
        for (String line : section.split("\n")) {
            if (!line.startsWith("|")) {
                continue;   // only markdown table rows carry the ops
            }
            Matcher m = CODE_SPAN.matcher(line);
            while (m.find()) {
                for (String token : m.group(1).split("·")) {
                    String firstWord = token.trim().split("\\s+")[0];
                    if (!firstWord.isBlank()) {
                        ops.add(firstWord);
                    }
                }
            }
        }
        return ops;
    }

    private static Path clispec() {
        // the test runs from cli/ (Gradle module dir); the doc is at the repo root
        Path here = Path.of("").toAbsolutePath();
        for (Path p = here; p != null; p = p.getParent()) {
            Path candidate = p.resolve("docs/CLI-SPEC.md");
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("cannot locate docs/CLI-SPEC.md from " + here);
    }
}
