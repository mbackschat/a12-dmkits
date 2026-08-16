package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * <b>The two halves of the performance pair address each other, so their addresses must hold.</b>
 * {@code INTERPRETER-PERFORMANCE.md} (method) and {@code INTERPRETER-PERFORMANCE-RESULTS.md} (dated
 * evidence) were split on 2026-08-15. Neither half carries anchors, so the <i>only</i> working cross-file
 * address between them is the method doc's <b>optimization-catalog id</b> — {@code ### 11.}, cited from the
 * results log as {@code catalog #11} — and the results log's own ordering contract, "one dated checkpoint
 * entry per measurement session, <b>newest first</b>", which is how a reader decides where to stop.
 *
 * <p>Both were broken by the split and neither breakage was visible: {@code ### 10.} was assigned twice
 * (the sole {@code catalog #10} citation therefore had two candidate targets), and three checkpoints sat
 * <i>below</i> the historical appendix, ~450 lines past where a reader honouring the stated contract stops.
 * {@link DocsEvidencePlacementGuardTest} could not see either — it guards which <i>file</i> a checkpoint
 * lives in, not whether the addresses inside those files resolve.
 *
 * <p>The ids are <b>dense</b> and never reused, the same contract KERNEL-FINDINGS' {@code KF<n>} anchors
 * carry: an entry is appended, never renumbered, so a citation written today still resolves tomorrow. That
 * is what makes a duplicate a defect rather than a style choice — and it is why a repair appends a fresh id
 * instead of reshuffling, which would silently repoint every existing citation.
 *
 * <p>Rides {@code docGuardTest}, whose input set already declares the whole {@code docs/*.md} tree.
 */
class PerformancePairAddressingGuardTest {

    private static final Path METHOD = Path.of("../docs/INTERPRETER-PERFORMANCE.md");

    private static final Path RESULTS = Path.of("../docs/INTERPRETER-PERFORMANCE-RESULTS.md");

    /** An optimization-catalog entry, whose number is the cross-file address: {@code ### 11. …}. */
    private static final Pattern CATALOG_ID = Pattern.compile("(?m)^### (\\d+)\\. +\\S");

    /** A results-log entry, whose date carries the newest-first contract. */
    private static final Pattern CHECKPOINT_DATE =
            Pattern.compile("(?m)^#{2,4} +Checkpoint +(\\d{4}-\\d{2}-\\d{2})");

    /** Sanity floor, asserted on what the RULE parses — the catalog carries ~13 entries. */
    private static final int MIN_CATALOG_ENTRIES = 10;

    /** Sanity floor, asserted on what the RULE parses — the log carries ~35 checkpoints. */
    private static final int MIN_CHECKPOINTS = 30;

    @Test
    void everyCatalogIdIsUniqueAndDense() throws IOException {
        List<Integer> ids = catalogIds(Files.readString(locate(METHOD)));

        assertTrue(ids.size() >= MIN_CATALOG_ENTRIES,
                () -> "parsed only " + ids.size() + " catalog entries from " + METHOD.getFileName() + " (floor "
                        + MIN_CATALOG_ENTRIES + ") — a changed heading shape would make this guard check nothing");

        assertTrue(gapsAndDuplicates(ids).isEmpty(), () -> "optimization-catalog ids must be unique and dense 1..N — they are "
                + "the only cross-file address the results log can cite, so a duplicate gives `catalog #n` two "
                + "targets and a gap breaks one. APPEND a fresh id; never reshuffle, which repoints every "
                + "existing citation silently:\n  " + String.join("\n  ", gapsAndDuplicates(ids)));
    }

    @Test
    void theResultsLogStaysNewestFirst() throws IOException {
        List<String> dates = checkpointDates(Files.readString(locate(RESULTS)));

        assertTrue(dates.size() >= MIN_CHECKPOINTS,
                () -> "parsed only " + dates.size() + " checkpoints from " + RESULTS.getFileName() + " (floor "
                        + MIN_CHECKPOINTS + ") — a changed heading shape would make this guard check nothing");

        assertTrue(inversions(dates).isEmpty(), () -> "the results log states its own contract as \"one dated "
                + "checkpoint entry per measurement session, newest first\", and a reader stops at the first "
                + "entry older than what they came for — so an out-of-order entry is unreachable, not merely "
                + "untidy. Move it into date order:\n  " + String.join("\n  ", inversions(dates)));
    }

    /** One message when the sorted ids are not exactly 1..N — the property that makes an id a stable address. */
    private static List<String> gapsAndDuplicates(List<Integer> ids) {
        List<Integer> sorted = new ArrayList<>(ids);
        sorted.sort(null);
        List<String> broken = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i) != i + 1) {
                broken.add("expected #" + (i + 1) + " but the sorted ids read " + sorted);
                break;
            }
        }
        return broken;
    }

    /**
     * Does-fire proof for the density RULE, not merely the parser beneath it. Until 2026-08-16 the comparison was
     * inlined in the real test and exercised by nothing, so replacing it with {@code if (false)} left the lane
     * green — while its twin, the ordering rule, was extracted and controlled.
     */
    @Test
    void theDensityRuleFiresOnADuplicateAndOnAGapButNotOnADenseRun() {
        assertTrue(gapsAndDuplicates(List.of(1, 2, 3)).isEmpty(), "1..N in order is exactly what dense means");
        assertTrue(gapsAndDuplicates(List.of(3, 1, 2)).isEmpty(), "document order is irrelevant; the SET is 1..N");
        assertTrue(!gapsAndDuplicates(List.of(1, 2, 2)).isEmpty(), "a duplicate gives `catalog #n` two targets");
        assertTrue(!gapsAndDuplicates(List.of(1, 3)).isEmpty(), "a gap breaks the address and must fire");
    }

    /** Does-fire proof for the PARSER the rule above consumes. */
    @Test
    void theCatalogArmFiresOnADuplicateAndOnAGapButNotOnADenseRun() {
        assertEquals(List.of(1, 2, 3), catalogIds("### 1. a\n\n### 2. b\n\n### 3. c\n"));
        assertEquals(List.of(1, 2, 2), catalogIds("### 1. a\n\n### 2. b\n\n### 2. c\n"));
        assertEquals(List.of(1, 3), catalogIds("### 1. a\n\n### 3. c\n"));
        assertTrue(catalogIds("## 1. not a catalog entry\n\n#### 2. nor this\n").isEmpty(),
                "only h3 headings are catalog entries — h2 sections and h4 sub-points must not be parsed as ids");
    }

    /** Does-fire proof for the ordering arm: an inversion must be named, an equal-dated run must not fire. */
    @Test
    void theOrderingArmFiresOnlyOnAnInversion() {
        assertTrue(inversions(List.of("2026-08-15", "2026-08-14", "2026-08-14", "2026-07-01")).isEmpty(),
                "a non-increasing run, ties included, is exactly what newest-first means");

        List<String> found = inversions(List.of("2026-08-15", "2026-07-08", "2026-08-13"));
        assertTrue(found.size() == 1 && found.get(0).contains("2026-08-13"),
                () -> "expected the one entry that jumps forward to fire, got " + found);
    }

    /** One message per checkpoint that is newer than the entry above it. */
    private static List<String> inversions(List<String> dates) {
        List<String> found = new ArrayList<>();
        for (int i = 1; i < dates.size(); i++) {
            if (dates.get(i).compareTo(dates.get(i - 1)) > 0) {
                found.add("entry " + (i + 1) + " is " + dates.get(i) + ", newer than the "
                        + dates.get(i - 1) + " entry above it");
            }
        }
        return found;
    }

    private static List<Integer> catalogIds(String markdown) {
        List<Integer> ids = new ArrayList<>();
        Matcher entry = CATALOG_ID.matcher(markdown);
        while (entry.find()) {
            ids.add(Integer.parseInt(entry.group(1)));
        }
        return ids;
    }

    private static List<String> checkpointDates(String markdown) {
        List<String> dates = new ArrayList<>();
        Matcher entry = CHECKPOINT_DATE.matcher(markdown);
        while (entry.find()) {
            dates.add(entry.group(1));
        }
        return dates;
    }

    private static Path locate(Path relative) {
        if (Files.exists(relative)) {
            return relative;
        }
        Path fromRoot = Path.of(relative.toString().replaceFirst("^\\.\\./", ""));
        if (Files.exists(fromRoot)) {
            return fromRoot;
        }
        throw new IllegalStateException("cannot locate " + relative + " from " + Path.of("").toAbsolutePath());
    }
}
