package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The fold-status contract between the two kernel docs (DOC-DISCIPLINE §1): KERNEL-FINDINGS is the
 * evidence log, KERNEL-SEMANTICS the canonical reference, and every finding must state where it stands
 * in the fold — so the fold-lag that once accreted 3:1 cannot silently re-accrete. Enforced mechanically:
 *
 * <ul>
 *   <li><b>Marker presence</b> — every {@code ##}/{@code ###} finding heading in KERNEL-FINDINGS is
 *       followed (first non-blank line) by a fold-status line matching the marker grammar:
 *       {@code > Fold: folded → §n[ + §m…].} · {@code > Fold: folded → the field-types table.} ·
 *       {@code > Fold: folded → the preamble.} · {@code > Fold: not-foldable — <reason>.} ·
 *       {@code > Fold: pending — <reason>.}</li>
 *   <li><b>Folded targets resolve</b> — each {@code §n} named by a folded marker exists as a numbered
 *       {@code ## n. } section of KERNEL-SEMANTICS; the two named targets (the field-types table, the
 *       preamble) must exist as their anchor text.</li>
 *   <li><b>Reasons are stated</b> — a not-foldable/pending marker carries a non-empty reason.</li>
 *   <li><b>The §-mirror stays intact</b> — every {@code §n} tag in a KERNEL-FINDINGS heading (incl.
 *       multi-tags like {@code §9/§11}) is within the canonical taxonomy and its section exists.</li>
 * </ul>
 *
 * <p>Runs in the dedicated {@code semanticsMapGuardTest} task (TESTING-SPEC "Tests that read repo files
 * at runtime") — its input set already declares exactly these two docs, so a doc edit re-runs only this
 * lane and can never restore from the build cache into a false green.
 */
class KernelDocsFoldContractTest {

    private static final Path KERNEL_FINDINGS = Path.of("../docs/KERNEL-FINDINGS.md");
    private static final Path KERNEL_SEMANTICS = Path.of("../docs/KERNEL-SEMANTICS.md");

    /** A finding heading ({@code ##} or {@code ###}; the doc title {@code #} is not a finding). */
    private static final Pattern HEADING = Pattern.compile("^#{2,3} .+$");
    /** The marker grammar. Group 1 = the folded target list, group 2 = the reason (either family). */
    private static final Pattern MARKER = Pattern.compile(
            "^> Fold: (?:folded → (§\\d+(?: \\+ §\\d+)*|the field-types table|the preamble)\\."
                    + "|(?:not-foldable|pending) — (.+))$");
    /** A heading's §-tag(s): {@code ## §9 supplement}, {@code ### §6 supplement}, {@code ## §11/§12 supplement}. */
    private static final Pattern HEADING_TAG = Pattern.compile("^#{2,3} §(\\d+)(?:/§(\\d+))? ");
    /** A canonical numbered section heading. */
    private static final Pattern CANONICAL_SECTION = Pattern.compile("^## (\\d+)\\. ");

    private static final String FIELD_TYPES_ANCHOR = "## The field types at a glance";
    private static final String PREAMBLE_ANCHOR = "Two facts frame everything else";

    @Test
    void everyFindingCarriesAValidFoldStatusAndTheMirrorHolds() throws IOException {
        List<String> violations = check(Files.readString(KERNEL_FINDINGS), Files.readString(KERNEL_SEMANTICS));
        assertTrue(violations.isEmpty(), () -> "the KERNEL-FINDINGS ↔ KERNEL-SEMANTICS fold contract is broken:\n"
                + String.join("\n", violations));
    }

    // ---------------------------------------------------------------- the pure checker

    /** All violations of the fold contract (empty = the two docs are in step). */
    static List<String> check(String findings, String semantics) {
        List<String> violations = new ArrayList<>();

        Set<Integer> canonicalSections = new TreeSet<>();
        semantics.lines().forEach(l -> {
            Matcher m = CANONICAL_SECTION.matcher(l);
            if (m.find()) {
                canonicalSections.add(Integer.parseInt(m.group(1)));
            }
        });
        if (canonicalSections.isEmpty()) {
            violations.add("KERNEL-SEMANTICS has no numbered `## n. ` sections — the taxonomy is gone");
        }
        boolean hasFieldTypesTable = semantics.contains(FIELD_TYPES_ANCHOR);
        boolean hasPreamble = semantics.contains(PREAMBLE_ANCHOR);

        List<String> lines = findings.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            String heading = lines.get(i);
            if (!HEADING.matcher(heading).matches()) {
                continue;
            }
            String label = shortLabel(heading);

            // The §-mirror: every heading tag resolves into the canonical taxonomy.
            Matcher tag = HEADING_TAG.matcher(heading);
            if (tag.find()) {
                for (int g = 1; g <= 2; g++) {
                    if (tag.group(g) != null && !canonicalSections.contains(Integer.parseInt(tag.group(g)))) {
                        violations.add(label + ": heading tag §" + tag.group(g)
                                + " has no KERNEL-SEMANTICS `## " + tag.group(g) + ". ` section");
                    }
                }
            }

            // The marker: first non-blank line after the heading.
            int j = i + 1;
            while (j < lines.size() && lines.get(j).isBlank()) {
                j++;
            }
            String next = j < lines.size() ? lines.get(j) : "";
            Matcher marker = MARKER.matcher(next);
            if (!marker.matches()) {
                violations.add(label + ": no valid fold-status line follows the heading (found: "
                        + (next.length() > 60 ? next.substring(0, 60) + "…" : next) + ")");
                continue;
            }
            String foldedTargets = marker.group(1);
            String reason = marker.group(2);
            if (foldedTargets != null) {
                if (foldedTargets.equals("the field-types table")) {
                    if (!hasFieldTypesTable) {
                        violations.add(label + ": folded → the field-types table, but KERNEL-SEMANTICS has no `"
                                + FIELD_TYPES_ANCHOR + "` heading");
                    }
                } else if (foldedTargets.equals("the preamble")) {
                    if (!hasPreamble) {
                        violations.add(label + ": folded → the preamble, but KERNEL-SEMANTICS's preamble anchor `"
                                + PREAMBLE_ANCHOR + "` is gone");
                    }
                } else {
                    Matcher sec = Pattern.compile("§(\\d+)").matcher(foldedTargets);
                    while (sec.find()) {
                        int n = Integer.parseInt(sec.group(1));
                        if (!canonicalSections.contains(n)) {
                            violations.add(label + ": folded → §" + n
                                    + ", but KERNEL-SEMANTICS has no `## " + n + ". ` section");
                        }
                    }
                }
            } else if (reason.isBlank()) {
                violations.add(label + ": a not-foldable/pending marker needs a reason");
            }
        }
        return violations;
    }

    private static String shortLabel(String heading) {
        String bare = heading.replaceFirst("^#{2,3} ", "");
        return "`" + (bare.length() > 70 ? bare.substring(0, 70) + "…" : bare) + "`";
    }

    // ---------------------------------------------------------------- does-fire proofs

    private static final String GOOD_SEMANTICS = """
            Two facts frame everything else:

            ## The field types at a glance

            ## 1. Truth values

            ## 2. Empty values
            """;

    @Test
    void firesOnAMissingMarkerAMalformedMarkerAndAnUnresolvedTarget() {
        String findings = """
                # Kernel-behavior findings

                ## §1 supplement — no marker here

                body text

                ## some finding with a malformed marker

                > Fold: folded → somewhere else.

                ## another finding folded to a missing section

                > Fold: folded → §9.
                """;
        List<String> violations = check(findings, GOOD_SEMANTICS);
        assertTrue(violations.stream().anyMatch(v -> v.contains("no marker here") && v.contains("no valid fold-status")),
                () -> "the missing marker must fire: " + violations);
        assertTrue(violations.stream().anyMatch(v -> v.contains("malformed marker") && v.contains("no valid fold-status")),
                () -> "the malformed marker must fire: " + violations);
        assertTrue(violations.stream().anyMatch(v -> v.contains("§9") && v.contains("no `## 9. ` section")),
                () -> "the unresolved folded target must fire: " + violations);
    }

    @Test
    void firesOnABrokenSectionMirrorAndAMissingReason() {
        String findings = """
                # Kernel-behavior findings

                ## §7/§9 supplement — a tag outside the shrunken taxonomy

                > Fold: not-foldable — a stated reason.

                ## a finding without a reason

                > Fold: not-foldable —

                ## fine one

                > Fold: folded → §1 + §2.
                """;
        List<String> violations = check(findings, GOOD_SEMANTICS);
        assertTrue(violations.stream().anyMatch(v -> v.contains("§7") && v.contains("no KERNEL-SEMANTICS")),
                () -> "the broken §-mirror must fire for §7: " + violations);
        assertTrue(violations.stream().anyMatch(v -> v.contains("§9") && v.contains("no KERNEL-SEMANTICS")),
                () -> "the broken §-mirror must fire for §9: " + violations);
        assertTrue(violations.stream().anyMatch(v -> v.contains("without a reason")),
                () -> "the missing reason must fire: " + violations);
    }

    @Test
    void passesOnDocsInStep() {
        String findings = """
                # Kernel-behavior findings

                ## §1 supplement — folded fact

                > Fold: folded → §1.

                ## §1/§2 supplement — multi-target fact

                > Fold: folded → §1 + §2.

                ## a type fact

                > Fold: folded → the field-types table.

                ## a frame fact

                > Fold: folded → the preamble.

                ## a structural fact

                > Fold: not-foldable — serialization tier.

                ## an unsettled fact

                > Fold: pending — awaiting a MONTH_OPTIONAL fixture.
                """;
        assertEquals(List.of(), check(findings, GOOD_SEMANTICS));
    }
}
