package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * G4 — no-drift guard: <b>every live manifest {@code (target, op)} pair must appear in the CLI-SPEC §5
 * verb-surface table</b> ({@code manifest verbs ⊆ the §5 table}). The table is the human/agent-facing verb
 * tour; a verb landing in the tool without a row is exactly the drift that let seven verbs (model
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
        Set<String> tableVerbs = section5VerbPairs();
        Set<String> missing = new TreeSet<>();
        for (String verb : manifestVerbs()) {
            if (!tableVerbs.contains(verb)) {
                missing.add(verb);
            }
        }
        assertTrue(missing.isEmpty(),
                () -> "manifest verbs missing from the CLI-SPEC §5 verb-surface table: " + missing
                        + "\n(add the op to its target's row in docs/CLI-SPEC.md §5)");
    }

    @Test
    void explanatoryCommandReferencesCannotSatisfyTheVerbTable() {
        assertEquals(Set.of("add", "read"),
                topLevelOps("`add` *(description · `modify --id`)* · `read`"),
                "an op mentioned only in explanatory prose is not part of the authoritative list");
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

    /** The target/op pairs listed in the §5 table. Only top-level op spans are read; explanatory
     *  {@code *(...)*} prose and its command references are deliberately excluded. */
    private static Set<String> section5VerbPairs() throws IOException {
        String spec = Files.readString(clispec(), StandardCharsets.UTF_8);
        int start = spec.indexOf("## 5. Verb surface");
        assertTrue(start >= 0, "CLI-SPEC.md must have a '## 5. Verb surface' section");
        int end = spec.indexOf("\n## ", start + 1);
        String section = end < 0 ? spec.substring(start) : spec.substring(start, end);

        Set<String> verbs = new LinkedHashSet<>();
        for (String line : section.split("\n")) {
            if (!line.startsWith("|")) {
                continue;   // only markdown table rows carry the ops
            }
            List<String> cells = tableCells(line);
            if (cells.size() < 4) {
                continue;
            }
            String targetCell = cells.get(1).trim();
            String prefix = targetPrefix(targetCell);
            if (prefix == null) {
                continue;   // header/separator row
            }
            for (String op : topLevelOps(cells.get(2).trim())) {
                verbs.add(prefix + op);
            }
        }
        return verbs;
    }

    private static Set<String> topLevelOps(String opsCell) {
        Set<String> ops = new LinkedHashSet<>();
        Matcher opSpan = CODE_SPAN.matcher(withoutExplanatoryProse(opsCell));
        while (opSpan.find()) {
            for (String token : opSpan.group(1).split("·")) {
                String op = token.trim().split("\\s+")[0];
                if (!op.isBlank()) {
                    ops.add(op);
                }
            }
        }
        return ops;
    }

    private static String withoutExplanatoryProse(String opsCell) {
        StringBuilder topLevel = new StringBuilder();
        int cursor = 0;
        while (cursor < opsCell.length()) {
            int explanationStart = opsCell.indexOf("*(", cursor);
            if (explanationStart < 0) {
                topLevel.append(opsCell, cursor, opsCell.length());
                break;
            }
            topLevel.append(opsCell, cursor, explanationStart);
            int explanationEnd = opsCell.indexOf(")*", explanationStart + 2);
            assertTrue(explanationEnd >= 0,
                    () -> "unterminated explanatory prose in CLI-SPEC §5 ops cell: " + opsCell);
            cursor = explanationEnd + 2;
        }
        return topLevel.toString();
    }

    private static List<String> tableCells(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inCodeSpan = false;
        for (int i = 0; i < line.length(); i++) {
            char character = line.charAt(i);
            if (character == '`') {
                inCodeSpan = !inCodeSpan;
            }
            boolean escaped = i > 0 && line.charAt(i - 1) == '\\';
            if (character == '|' && !inCodeSpan && !escaped) {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(character);
            }
        }
        cells.add(cell.toString());
        return cells;
    }

    private static String targetPrefix(String targetCell) {
        Matcher target = CODE_SPAN.matcher(targetCell);
        if (target.find()) {
            return target.group(1) + " ";
        }
        if (targetCell.contains("root meta") || targetCell.contains("cross-cutting")) {
            return "";
        }
        return null;
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
