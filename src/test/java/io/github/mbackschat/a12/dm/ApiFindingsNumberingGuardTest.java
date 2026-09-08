package io.github.mbackschat.a12.dm;

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
import org.junit.jupiter.api.io.TempDir;

/**
 * Keeps API-FINDINGS visible AF identifiers and explicit anchors aligned with existing "finding N" citations.
 *
 * <p>The findings preamble promises stable numbering; headings must stay dense and unique in document
 * order, and each heading's {@code afN} anchor must agree with its visible number. The scan floor catches
 * a changed heading format that would otherwise make the check vacuous. The root {@code docGuardTest}
 * declares the document as a runtime input so prose-only edits re-run this guard.
 */
class ApiFindingsNumberingGuardTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    private static final Pattern ITEM = Pattern.compile("^## AF(\\d+): ", Pattern.MULTILINE);
    private static final Pattern HEADING = Pattern.compile("^## .+$", Pattern.MULTILINE);
    private static final Pattern ANCHORED_ITEM =
            Pattern.compile("## AF([1-9]\\d*): \\S.* <a id=\"af([1-9]\\d*)\"></a>");

    /** The live doc carries 125 findings (2026-08-16); the floor only catches a scan that found nothing. */
    private static final int MIN_FINDINGS = 90;

    @Test
    void sectionHeadingsCarryTheFindingNumbers() {
        assertEquals(List.of(1, 2), ordinals("""
                ## AF1: First claim <a id="af1"></a>

                Body with an unrelated list:
                1. **Not a finding**

                ## AF2: Second claim <a id="af2"></a>
                """));
    }

    @Test
    void ordinalsAreDenseUniqueAndAligned() throws IOException {
        String markdown = Files.readString(CHECKOUT.requireFile("docs/API-FINDINGS.md"));
        List<Integer> ordinals = ordinals(markdown);

        assertTrue(ordinals.size() >= MIN_FINDINGS,
                () -> "found only " + ordinals.size() + " findings in docs/API-FINDINGS.md (floor " + MIN_FINDINGS
                        + ") — did the heading shape or the working dir change? The guard would check nothing.");
        assertTrue(audit(markdown).isEmpty(), () -> String.join("\n  ", audit(markdown)));
    }

    /**
     * Does-fire proof against a fabricated doc, exercising {@link #audit(String)} — the same method the real
     * check calls — for each defect class: a gap (deletion), a duplicate (careless insert), and a
     * count/max mismatch. Clean sections must not fire.
     */
    @Test
    void theGuardFiresOnAGapADuplicateAndAMisalignedMax(@TempDir Path dir) throws IOException {
        String first = "## AF1: a <a id=\"af1\"></a>\n";
        String second = "## AF2: b <a id=\"af2\"></a>\n";
        Files.writeString(dir.resolve("gap.md"), first + second + "## AF4: d <a id=\"af4\"></a>\n");
        Files.writeString(dir.resolve("dup.md"), first + second + second);
        Files.writeString(dir.resolve("clean.md"), first + second + "## AF3: c <a id=\"af3\"></a>\n");

        assertTrue(audit(Files.readString(dir.resolve("gap.md"))).stream()
                        .anyMatch(v -> v.contains("gap")),
                "a deleted finding must fire the density check");
        assertTrue(audit(Files.readString(dir.resolve("dup.md"))).stream()
                        .anyMatch(v -> v.contains("duplicate")),
                "a duplicated ordinal must fire");
        assertTrue(audit(Files.readString(dir.resolve("clean.md"))).isEmpty(),
                "dense, unique, aligned sections must NOT fire");
    }

    @Test
    void theGuardFiresOnMissingMismatchedAndMalformedAnchors() {
        for (String heading : List.of("## AF1: a", "## AF1: a <a id=\"af2\"></a>",
                "## AF1: a <a id=\"af01\"></a>", "## Unnumbered finding <a id=\"af1\"></a>")) {
            assertTrue(audit(heading).stream().anyMatch(v -> v.contains("anchor")), heading);
        }
        assertTrue(audit("## AF1: a <a id=\"af1\"></a>").isEmpty());
    }

    private static List<String> audit(String markdown) {
        List<String> violations = new ArrayList<>();
        Matcher headings = HEADING.matcher(markdown);
        while (headings.find()) {
            Matcher item = ANCHORED_ITEM.matcher(headings.group());
            if (!item.matches() || !item.group(1).equals(item.group(2))) {
                violations.add("missing or mismatched finding number/anchor: " + headings.group());
            }
        }
        List<Integer> ordinals = ordinals(markdown);
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
                        + " — section numbers must remain dense and in document order");
            }
        }
        int max = ordinals.stream().mapToInt(Integer::intValue).max().orElse(0);
        if (!ordinals.isEmpty() && max != ordinals.size()) {
            violations.add("max ordinal " + max + " != count " + ordinals.size()
                    + " — section numbers must span 1..N");
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
}
