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
class GapLedgerOpenMarkerGuardTest {

    private static final Path DOCS = Path.of("../docs");

    /** The positive assertion of emptiness. Exact text on its own line, so the roll-up grep is exact. */
    private static final String MARKER = "_None open._";

    /** Sanity floor: the estate has eight `-GAPS` ledgers. A scan finding almost none is checking nothing. */
    private static final int MIN_LEDGERS = 6;

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
        String marked = "# x\n\n## 1. Open\n\n_None open._\n\nEverything shipped.\n\n## 2. Deferred\n";
        String withItems = "# x\n\n## 1. Open\n\n- **IG9 — a real open gap**\n\n## 2. Deferred\n";

        assertTrue(!audit("bare.md", bare).isEmpty(), "a drained open section with no marker must fire");
        assertTrue(audit("marked.md", marked).isEmpty(), "the exact marker must satisfy the guard");
        assertTrue(audit("items.md", withItems).isEmpty(), "a section that lists an item needs no marker");
    }

    /** One violation per open section that is neither populated nor explicitly empty. */
    private static List<String> audit(String name, String markdown) {
        List<String> violations = new ArrayList<>();
        String[] lines = markdown.split("\\R", -1);
        String section = null;
        boolean hasItem = false;
        boolean hasMarker = false;

        for (String line : lines) {
            if (line.startsWith("## ")) {
                if (section != null && !hasItem && !hasMarker) {
                    violations.add(name + " — \"" + section.substring(3) + "\"");
                }
                section = isOpenSection(line) ? line : null;
                hasItem = false;
                hasMarker = false;
                continue;
            }
            if (section == null) {
                continue;
            }
            if (line.strip().equals(MARKER) || line.startsWith(MARKER)) {
                hasMarker = true;
            } else if (line.startsWith("- **") || line.startsWith("### ") || line.startsWith("| ")) {
                hasItem = true;
            }
        }
        if (section != null && !hasItem && !hasMarker) {
            violations.add(name + " — \"" + section.substring(3) + "\"");
        }
        return violations;
    }

    /** An "open" section by heading: `## 1. Open`, `## Open`, `## 3. Open backlog`, `## 1. Open (to fix)`. */
    private static boolean isOpenSection(String heading) {
        String text = heading.substring(3).toLowerCase();
        return text.matches("(\\d+\\.\\s*)?open\\b.*");
    }

    private static List<Path> ledgers() throws IOException {
        try (Stream<Path> files = Files.list(DOCS)) {
            return files.filter(p -> p.getFileName().toString().endsWith("-GAPS.md")).sorted().toList();
        }
    }
}
