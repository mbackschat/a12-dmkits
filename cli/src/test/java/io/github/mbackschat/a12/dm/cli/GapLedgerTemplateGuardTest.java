package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>The `-GAPS` open-section marker guard.</b> "What remains?" is the one question an agent cannot answer
 * with a single grep, because the answer is spread over eight ledgers and most of them are empty. Before
 * this guard the empty state was written five different ways — {@code _None currently._}, {@code _None._},
 * {@code (none)}, {@code _None open._}, and twice as bare prose with no explicit statement at all — so
 * {@code grep -H '^_None open._' docs/*GAPS*.md} returned four of eight files and silently under-reported.
 *
 * <p>The contract: <b>every {@code docs/*-GAPS.md} open section either lists at least one item or carries
 * the exact marker {@code _None open._}</b>. That makes the roll-up grep exact and, just as importantly,
 * makes a drained ledger <i>positively assert</i> its emptiness instead of leaving a reader to infer it
 * from absence.
 *
 * <p>Item detection is deliberately permissive — the ledgers use three different row shapes ({@code - **IGn
 * — }, {@code ### IRG2 — }, and table rows) and a new ledger may invent a fourth. A section with any of
 * those is treated as non-empty and needs no marker; the failure mode is therefore a loud, correctable
 * "add the marker or the row", never a silent pass.
 *
 * <p>It reads repo docs at runtime, so it runs in the {@code :cli} {@code docGuardTest} task, whose input
 * set already declares the docs tree (TESTING-SPEC "Tests that read repo files at runtime") — a ledger
 * edit re-runs only this lane and cannot restore from the build cache into a false green.
 */
class GapLedgerTemplateGuardTest {

    private static final Path DOCS = Path.of("../docs");

    /** The positive assertion of emptiness. Exact text on its own line, so the roll-up grep is exact. */
    private static final String MARKER = "_None open._";

    /** Sanity floor: the estate has eight `-GAPS` ledgers. A scan finding almost none is checking nothing. */
    private static final int MIN_LEDGERS = 6;

    /**
     * <b>The shared template.</b> The eight ledgers do one job, but they had drifted apart accidentally —
     * eight H1 phrasings, three different labels for the lifecycle paragraph, and open sections that mixed
     * the marker with paragraphs of closure history. Uniform shape is what lets one grep answer "what
     * remains?" across the family, so the shape is checked rather than merely agreed:
     *
     * <ol>
     *   <li>the H1 ends with {@code — gaps};</li>
     *   <li>the preamble carries {@code > **What this is}, {@code > **Lifecycle.} and
     *       {@code > **Current state.} blockquote paragraphs;</li>
     *   <li>an open section is titled {@code Open} (a suffix after it is allowed only where a numbered
     *       section id is externally cited).</li>
     * </ol>
     *
     * <p>Section <b>numbers</b> are deliberately NOT normalised: {@code FEATURE-GAPS §4},
     * {@code DEV-API-GAPS §2/§3}, {@code INTERPRETER-GAPS §3} and {@code SCHEMAKIT-GAPS §3} are cited from
     * production sources and docs, so renumbering would break ~20 live citations. Uniformity therefore lives
     * in the titles and the preamble, not in the ordinals.
     */
    @Test
    void everyGapLedgerFollowsTheSharedTemplate() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path ledger : ledgers()) {
            String name = ledger.getFileName().toString();
            List<String> lines = Files.readAllLines(ledger);
            String h1 = lines.isEmpty() ? "" : lines.get(0);
            if (!h1.startsWith("# ") || !h1.endsWith(" — gaps")) {
                violations.add(name + " — H1 must read `# <scope> — gaps`, found: " + h1);
            }
            String body = String.join("\n", lines);
            for (String required : List.of("> **What this is", "> **Lifecycle.", "> **Current state.")) {
                if (!body.contains(required)) {
                    violations.add(name + " — preamble is missing a `" + required + "…` paragraph");
                }
            }
            if (lines.stream().noneMatch(GapLedgerTemplateGuardTest::isOpenSection)) {
                violations.add(name + " — no section titled `Open`");
            }
        }
        assertTrue(violations.isEmpty(), () -> "`-GAPS` ledgers that break the shared template (docs/CLAUDE.md "
                + "\"the -GAPS template\"):\n  " + String.join("\n  ", violations));
    }

    @Test
    void everyGapLedgerOpenSectionListsItemsOrAssertsEmptiness() throws IOException {
        List<Path> ledgers = ledgers();
        assertTrue(ledgers.size() >= MIN_LEDGERS,
                () -> "found only " + ledgers.size() + " `-GAPS` ledgers under " + DOCS + " (floor " + MIN_LEDGERS
                        + ") — wrong working dir or a renamed family? The guard would check nothing.");

        List<String> violations = new ArrayList<>();
        for (Path ledger : ledgers) {
            violations.addAll(audit(ledger.getFileName().toString(), Files.readString(ledger)));
        }
        assertTrue(violations.isEmpty(), () -> "`-GAPS` open sections that neither list an item nor assert "
                + "emptiness with the exact marker `" + MARKER + "` on its own line (add the marker, or the "
                + "item row it is missing):\n  " + String.join("\n  ", violations));
    }

    /**
     * Does-fire proof against fabricated ledgers, exercising {@link #audit(String, String)} — the same
     * method the real check calls. An empty section without the marker must fire; a section with items and
     * a section with the marker must not.
     */
    @Test
    void theGuardFiresOnADrainedSectionThatDoesNotSaySo(@TempDir Path dir) throws IOException {
        String bare = "# x\n\n## 1. Open\n\nEverything shipped.\n\n## 2. Deferred\n\n- **X1 — y**\n";
        String markerPlusProse = "# x\n\n## 1. Open\n\n_None open._\n\nEverything shipped.\n\n## 2. Deferred\n";
        String markerOnly = "# x\n\n## 1. Open\n\n_None open._\n\n---\n\n## 2. Deferred\n";
        String withItems = "# x\n\n## 1. Open\n\n- **IG9 — a real open gap**\n\n## 2. Deferred\n";

        assertTrue(!audit("bare.md", bare).isEmpty(), "a drained open section with no marker must fire");
        assertTrue(audit("markerPlusProse.md", markerPlusProse).stream().anyMatch(v -> v.contains("still carries prose")),
                "a marked section that still buries the answer under prose must fire");
        assertTrue(audit("markerOnly.md", markerOnly).isEmpty(), "the marker alone must satisfy the guard");
        assertTrue(audit("items.md", withItems).isEmpty(), "a section that lists an item needs no marker");
    }

    /** One violation per open section that is neither populated nor explicitly empty. */
    private static List<String> audit(String name, String markdown) {
        List<String> violations = new ArrayList<>();
        String[] lines = markdown.split("\\R", -1);
        String section = null;
        boolean hasItem = false;
        boolean hasMarker = false;
        List<String> prose = new ArrayList<>();

        for (String line : lines) {
            if (line.startsWith("## ")) {
                close(name, section, hasItem, hasMarker, prose, violations);
                section = isOpenSection(line) ? line : null;
                hasItem = false;
                hasMarker = false;
                prose = new ArrayList<>();
                continue;
            }
            if (section == null) {
                continue;
            }
            if (line.strip().equals(MARKER)) {
                hasMarker = true;
            } else if (line.startsWith("- **") || line.startsWith("### ") || line.startsWith("| ")) {
                hasItem = true;
            } else if (!line.isBlank() && !line.strip().equals("---")) {
                // A drained section must say ONLY that it is drained. Closure history and lifecycle policy
                // belong in the `Current state.` / `Lifecycle.` preamble, where they do not bury the answer
                // to "what remains?" under paragraphs a reader has to triage first.
                prose.add(line.strip().substring(0, Math.min(70, line.strip().length())));
            }
        }
        close(name, section, hasItem, hasMarker, prose, violations);
        return violations;
    }

    /** Verdict for one finished open section: it must list items, or say ONLY that it is drained. */
    private static void close(String name, String section, boolean hasItem, boolean hasMarker,
            List<String> prose, List<String> violations) {
        if (section == null) {
            return;
        }
        String title = section.substring(3);
        if (!hasItem && !hasMarker) {
            violations.add(name + " — \"" + title + "\" neither lists an item nor carries the marker");
        } else if (hasMarker && !prose.isEmpty()) {
            violations.add(name + " — \"" + title + "\" is marked drained but still carries prose; move it to the "
                    + "`Current state.` / `Lifecycle.` preamble: \"" + prose.get(0) + "…\"");
        }
    }

    /** An "open" section by heading: `## 1. Open`, `## Open`, `## 3. Open backlog`, `## 1. Open (to fix)`. */
    private static boolean isOpenSection(String heading) {
        if (!heading.startsWith("## ")) {
            return false;
        }
        String text = heading.substring(3).toLowerCase();
        return text.matches("(\\d+\\.\\s*)?open\\b.*");
    }

    private static List<Path> ledgers() throws IOException {
        try (Stream<Path> files = Files.list(DOCS)) {
            return files.filter(p -> p.getFileName().toString().endsWith("-GAPS.md")).sorted().toList();
        }
    }
}
