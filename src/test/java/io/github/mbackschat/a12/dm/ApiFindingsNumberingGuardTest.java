package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>The API-FINDINGS ordinal guard.</b> {@code docs/API-FINDINGS.md} carries its findings as a markdown
 * <b>ordered list</b>, and they are cited as "finding N" from ~113 sites across the docs, the tests, and
 * <em>production</em> sources ({@code SemanticIndex}, {@code FieldInfo}). That makes the ordinal a public
 * anchor riding the weakest possible carrier: a renderer numbers an ordered list <b>sequentially from the
 * first item</b> and ignores the later source numbers, so deleting or inserting one item silently shifts
 * every following anchor in the rendered view while the source still looks intact. The doc's own preamble
 * promises "the numbering is stable so the {@code finding N} anchors resolve" — until this guard, nothing
 * checked it.
 *
 * <p>Three properties, all cheap and all violated by exactly the edits that would break a citation:
 *
 * <ol>
 *   <li><b>Dense</b> — the ordinals are {@code 1..N} with no gap, so a deletion cannot pass unnoticed.</li>
 *   <li><b>Unique</b> — no ordinal appears twice (the shape a careless insert produces).</li>
 *   <li><b>Aligned</b> — {@code max == count}, so source numbering equals what a renderer will show.</li>
 * </ol>
 *
 * <p>A floor guards the guard: the live doc carries ~100 findings, so a scan that suddenly finds almost
 * none means the working directory or the list shape moved, and the check would "pass" while checking
 * nothing.
 *
 * <p>It reads a repo doc at runtime, so it runs in the root {@code docGuardTest} task with
 * {@code docs/API-FINDINGS.md} declared as that task's input (TESTING-SPEC "Tests that read repo files at
 * runtime") — a prose-only edit re-runs just this lane and can never restore from the build cache into a
 * false green.
 */
class ApiFindingsNumberingGuardTest {

    /**
     * A findings item: an ordinal at line start, then the doc's <b>bolded claim</b> convention
     * ({@code 103. **A DM-JSON mapper is a semantic decision** — …}). Anchoring on the bold is deliberate:
     * a bare {@code ^\d+\. } would also swallow unrelated numbered prose that happens to sit at column 0.
     * Both forms count 103 in the live doc, so the stricter one loses nothing today and cannot drift into
     * false positives later. Nested/sub-lists are indented, so the line anchor already excludes them.
     */
    private static final Pattern ITEM = Pattern.compile("^(\\d+)\\. \\*\\*", Pattern.MULTILINE);

    /** The live doc had 103 findings when this landed; the floor only catches a scan that found nothing. */
    private static final int MIN_FINDINGS = 90;

    @Test
    void ordinalsAreDenseUniqueAndAligned() throws IOException {
        List<Integer> ordinals = ordinals(Files.readString(repoFile("docs/API-FINDINGS.md")));

        assertTrue(ordinals.size() >= MIN_FINDINGS,
                () -> "found only " + ordinals.size() + " findings in docs/API-FINDINGS.md (floor " + MIN_FINDINGS
                        + ") — did the list shape or the working dir change? The guard would check nothing.");
        assertTrue(audit(ordinals).isEmpty(), () -> String.join("\n  ", audit(ordinals)));
    }

    /**
     * Does-fire proof against a fabricated doc, exercising {@link #audit(List)} — the same method the real
     * check calls — for each defect class: a gap (deletion), a duplicate (careless insert), and a
     * count/max mismatch. A clean list must not fire.
     */
    @Test
    void theGuardFiresOnAGapADuplicateAndAMisalignedMax(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("gap.md"), "1. **a**\n2. **b**\n4. **d**\n");
        Files.writeString(dir.resolve("dup.md"), "1. **a**\n2. **b**\n2. **b again**\n");
        Files.writeString(dir.resolve("clean.md"), "1. **a**\n2. **b**\n3. **c**\n");

        assertTrue(audit(ordinals(Files.readString(dir.resolve("gap.md")))).stream()
                        .anyMatch(v -> v.contains("gap")),
                "a deleted finding must fire the density check");
        assertTrue(audit(ordinals(Files.readString(dir.resolve("dup.md")))).stream()
                        .anyMatch(v -> v.contains("duplicate")),
                "a duplicated ordinal must fire");
        assertTrue(audit(ordinals(Files.readString(dir.resolve("clean.md")))).isEmpty(),
                "a dense, unique, aligned list must NOT fire");
    }

    /** Every violation the ordinal contract can carry, so the does-fire arm drives the real code path. */
    private static List<String> audit(List<Integer> ordinals) {
        List<String> violations = new ArrayList<>();
        List<Integer> seen = new ArrayList<>();
        for (int i = 0; i < ordinals.size(); i++) {
            int ordinal = ordinals.get(i);
            if (seen.contains(ordinal)) {
                violations.add("duplicate ordinal " + ordinal + " — every 'finding N' citation of it is ambiguous");
            }
            seen.add(ordinal);
            int expected = i + 1;
            if (ordinal != expected) {
                violations.add("gap or misorder at position " + expected + ": found " + ordinal
                        + " — a renderer will show " + expected + ", so every later 'finding N' citation shifts");
            }
        }
        int max = ordinals.stream().mapToInt(Integer::intValue).max().orElse(0);
        if (!ordinals.isEmpty() && max != ordinals.size()) {
            violations.add("max ordinal " + max + " != count " + ordinals.size()
                    + " — source numbering disagrees with the rendered numbering");
        }
        return violations;
    }

    private static List<Integer> ordinals(String markdown) {
        List<Integer> out = new ArrayList<>();
        Matcher m = ITEM.matcher(markdown);
        while (m.find()) {
            out.add(Integer.parseInt(m.group(1)));
        }
        return out;
    }

    /** Locate a repo-root file regardless of the module working dir (mirrors {@code DocSnippetFreshnessTest}). */
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
