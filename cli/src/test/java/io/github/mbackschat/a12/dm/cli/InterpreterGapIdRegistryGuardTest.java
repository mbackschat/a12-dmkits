package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/** Locks the issued IAG/IRG namespace outside the live-only gap ledgers. */
class InterpreterGapIdRegistryGuardTest {

    private static final Path REGISTRY = Path.of("../docs/INTERPRETER-GAP-ID-REGISTRY.md");
    private static final Map<String, Path> LEDGERS = Map.of(
            "IAG", Path.of("../docs/INTERPRETER-API-GAPS.md"),
            "IRG", Path.of("../docs/INTERPRETER-RELEASE-GAPS.md"));
    private static final Pattern REGISTRY_ROW = Pattern.compile(
            "^\\| (IAG\\d+|IRG\\d+) <a id=\"([^\"]+)\"></a> \\| (open|deferred|shipped) \\| ([^|]+?) \\|$");
    private static final Pattern GAP_HEADING = Pattern.compile(
            "^### (IAG\\d+|IRG\\d+) — (.+?) <a id=\"([^\"]+)\"></a>$");
    private static final Pattern ID_TOKEN = Pattern.compile("\\b(?:IAG|IRG)\\d+\\b", Pattern.CASE_INSENSITIVE);

    @Test
    void everyIssuedInterpreterGapIdIsUniqueAndEveryLiveRowMatchesIt() throws IOException {
        Map<String, String> ledgers = new LinkedHashMap<>();
        for (Map.Entry<String, Path> entry : LEDGERS.entrySet()) {
            ledgers.put(entry.getKey(), Files.readString(entry.getValue()));
        }

        List<String> violations = audit(Files.readString(REGISTRY), ledgers);

        assertTrue(violations.isEmpty(), () -> "interpreter gap-id registry violations:\n  "
                + String.join("\n  ", violations));
    }

    @Test
    void theGuardFiresOnBothDuplicateIssuanceAndReuseOfAShippedId() {
        String duplicateRegistry = """
                | id | state | canonical meaning |
                |---|---|---|
                | IAG1 <a id="iag1"></a> | shipped | original meaning |
                | IAG1 <a id="iag1"></a> | open | accidental reuse |
                """;
        assertTrue(audit(duplicateRegistry, Map.of("IAG", "")).stream()
                        .anyMatch(v -> v.contains("issued more than once")),
                "a duplicate registry row must fail");

        String validRegistry = """
                | id | state | canonical meaning |
                |---|---|---|
                | IAG1 <a id="iag1"></a> | shipped | original meaning |
                """;
        String reusedLedger = """
                ## 1. Open

                ### IAG1 — unrelated new meaning <a id="iag1"></a>
                """;
        assertTrue(!audit(validRegistry, Map.of("IAG", reusedLedger)).isEmpty(),
                "a live row must not reuse an id whose registry disposition is shipped");
    }

    @Test
    void anIdBearingMalformedRowCannotFallThroughTheParser() {
        String malformedDuplicateRegistry = """
                | id | state | canonical meaning |
                |---|---|---|
                | IAG1 <a id="iag1"></a> | shipped | original meaning |
                | IAG1 | open | accidental reuse without its anchor |
                """;
        assertTrue(!audit(malformedDuplicateRegistry, Map.of("IAG", "")).isEmpty(),
                "an ID-bearing registry row must be rejected rather than skipped when its grammar is malformed");

        String validRegistry = """
                | id | state | canonical meaning |
                |---|---|---|
                | IAG1 <a id="iag1"></a> | open | original meaning |
                """;
        String malformedDuplicateLedger = """
                ## 1. Open

                ### IAG1 — original meaning <a id="iag1"></a>
                ### IAG1 — accidental reuse without its anchor
                """;
        assertTrue(!audit(validRegistry, Map.of("IAG", malformedDuplicateLedger)).isEmpty(),
                "an ID-bearing live heading must be rejected rather than skipped when its grammar is malformed");

        String mismatchedAnchorLedger = """
                ## 1. Open

                ### IAG1 — original meaning <a id="iag99"></a>
                """;
        assertTrue(!audit(validRegistry, Map.of("IAG", mismatchedAnchorLedger)).isEmpty(),
                "a live heading's anchor must resolve to its visible ID");
    }

    private static List<String> audit(String registryMarkdown, Map<String, String> ledgerMarkdown) {
        List<String> violations = new ArrayList<>();
        Map<String, IssuedId> registry = parseRegistry(registryMarkdown, violations);
        Map<String, IssuedId> liveRows = new LinkedHashMap<>();

        ledgerMarkdown.forEach((family, markdown) -> parseLedger(family, markdown, liveRows, violations));

        liveRows.forEach((id, live) -> {
            IssuedId issued = registry.get(id);
            if (issued == null) {
                violations.add(id + " is live but absent from the issued-id registry");
            } else if (!issued.equals(live)) {
                violations.add(id + " live row is `" + live.state + " | " + live.meaning
                        + "`, registry says `" + issued.state + " | " + issued.meaning + "`");
            }
        });
        registry.values().stream()
                .filter(id -> !id.state.equals("shipped") && !liveRows.containsKey(id.id))
                .forEach(id -> violations.add(id.id + " is registered " + id.state
                        + " but has no live ledger row"));
        return violations;
    }

    private static Map<String, IssuedId> parseRegistry(String markdown, List<String> violations) {
        Map<String, IssuedId> result = new LinkedHashMap<>();
        String[] lines = markdown.split("\\R");
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            Matcher matcher = REGISTRY_ROW.matcher(line);
            if (!matcher.matches()) {
                Matcher token = ID_TOKEN.matcher(line);
                if (line.startsWith("|") && token.find()) {
                    violations.add("registry line " + (index + 1) + " carries "
                            + token.group().toUpperCase() + " but is not a canonical issued-id row: " + line);
                }
                continue;
            }
            String id = matcher.group(1);
            if (!matcher.group(2).equals(id.toLowerCase())) {
                violations.add(id + " registry anchor must be `" + id.toLowerCase() + "`");
            }
            IssuedId issued = new IssuedId(id, matcher.group(3), matcher.group(4).strip());
            if (result.putIfAbsent(issued.id, issued) != null) {
                violations.add(issued.id + " is issued more than once in the registry");
            }
        }
        if (result.isEmpty()) {
            violations.add("registry contains no parseable IAG/IRG rows");
        }
        return result;
    }

    private static void parseLedger(String family, String markdown, Map<String, IssuedId> liveRows,
            List<String> violations) {
        String state = null;
        String[] lines = markdown.split("\\R");
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (line.startsWith("## ")) {
                String lower = line.toLowerCase();
                state = lower.matches("^## (\\d+\\. )?open.*") ? "open"
                        : lower.matches("^## (\\d+\\. )?deferred.*") ? "deferred" : null;
                continue;
            }
            if (state == null) {
                continue;
            }
            Matcher matcher = GAP_HEADING.matcher(line);
            if (!matcher.matches()) {
                Matcher token = ID_TOKEN.matcher(line);
                if (line.startsWith("### ") && token.find()) {
                    violations.add(family + " ledger line " + (index + 1) + " carries "
                            + token.group().toUpperCase() + " but is not a canonical live gap heading: " + line);
                }
                continue;
            }
            String id = matcher.group(1);
            if (!id.startsWith(family)) {
                violations.add(id + " appears in the " + family + " ledger");
                continue;
            }
            if (!matcher.group(3).equals(id.toLowerCase())) {
                violations.add(id + " live heading anchor must be `" + id.toLowerCase() + "`");
            }
            IssuedId row = new IssuedId(id, state, matcher.group(2).strip());
            if (liveRows.putIfAbsent(id, row) != null) {
                violations.add(id + " appears more than once in live interpreter ledgers");
            }
        }
    }

    private record IssuedId(String id, String state, String meaning) {}
}
