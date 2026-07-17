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
 *   <li><b>The canonical anchors stay pinned</b> — every {@link #REQUIRED_ANCHORS required anchor} (the
 *       14 numbered sections' GitHub heading slugs + the field-types table) exists, and every intra-doc
 *       {@code ](#…)} link resolves to a real heading — so deleting <b>or renaming</b> a canonical section
 *       heading fails the build naming the exact anchor, instead of silently orphaning the fold markers,
 *       SEMANTICS-MAP rows, cross-links, and downstream export consumers that key on it.</li>
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

    // ---------------------------------------------------------------- the anchor contract

    /**
     * The canonical anchors the ecosystem pins — the 14 numbered sections' GitHub heading slugs plus the
     * field-types table. Everything that resolves into the canonical doc keys on these: the fold markers'
     * {@code §n} targets, SEMANTICS-MAP's taxonomy rows, the doc's own {@code [§n](#…)} cross-links, and a
     * downstream semantic-export consumer's section references. Deleting <b>or renaming</b> one of these
     * headings breaks that keying silently — so the guard fails naming the exact anchor that went missing.
     */
    static final List<String> REQUIRED_ANCHORS = List.of(
            "the-field-types-at-a-glance",
            "1-truth-values-and-logical-evaluation",
            "2-non-specified-empty-values",
            "3-formal-errors-and-the-unknown-state",
            "4-the-required-property",
            "5-numbers-and-decimals",
            "6-dates-and-time",
            "7-strings-and-patterns",
            "8-enumerations",
            "9-repetition-iteration-and-cross-array-evaluation",
            "10-paths-and-references",
            "11-computations",
            "12-the-validation-model",
            "13-error-message-interpolation",
            "14-customcondition--the-escape-hatch");

    /** An intra-doc markdown link target: {@code ](#some-anchor)}. */
    private static final Pattern INTRA_DOC_LINK = Pattern.compile("\\]\\(#([a-z0-9_-]+)\\)");

    @Test
    void everyRequiredCanonicalAnchorExistsAndEveryIntraDocLinkResolves() throws IOException {
        List<String> violations = checkAnchors(Files.readString(KERNEL_SEMANTICS));
        assertTrue(violations.isEmpty(), () -> "the KERNEL-SEMANTICS anchor contract is broken:\n"
                + String.join("\n", violations));
    }

    /**
     * All violations of the anchor contract: every {@link #REQUIRED_ANCHORS required} anchor must exist as
     * a heading slug, and every intra-doc {@code ](#…)} link must resolve to a heading. Headings inside
     * fenced code blocks don't count.
     */
    static List<String> checkAnchors(String semantics) {
        List<String> violations = new ArrayList<>();

        Set<String> anchors = new TreeSet<>();
        boolean inFence = false;
        for (String line : semantics.lines().toList()) {
            if (line.startsWith("```")) {
                inFence = !inFence;
                continue;
            }
            if (!inFence && line.matches("^#{1,6} .+")) {
                anchors.add(slug(line));
            }
        }

        for (String required : REQUIRED_ANCHORS) {
            if (!anchors.contains(required)) {
                violations.add("required canonical anchor #" + required
                        + " is missing — its heading was deleted or renamed");
            }
        }

        Matcher link = INTRA_DOC_LINK.matcher(semantics);
        Set<String> reported = new TreeSet<>();
        while (link.find()) {
            String target = link.group(1);
            if (!anchors.contains(target) && reported.add(target)) {
                violations.add("intra-doc link #" + target + " resolves to no heading");
            }
        }
        return violations;
    }

    /** The GitHub slug of a markdown heading line: lowercase, punctuation stripped, spaces to hyphens. */
    static String slug(String headingLine) {
        String bare = headingLine.replaceFirst("^#{1,6} ", "").toLowerCase(java.util.Locale.ROOT);
        return bare.replaceAll("[^a-z0-9\\- _]", "").replace(' ', '-');
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
    void firesNamingTheAnchorWhenACanonicalHeadingIsDeletedOrRenamed() {
        String intact = canonicalSkeleton("## 3. Formal errors and the \"unknown\" state");
        assertEquals(List.of(), checkAnchors(intact), "the intact skeleton must pass");

        String deleted = canonicalSkeleton(null);
        assertTrue(checkAnchors(deleted).stream()
                        .anyMatch(v -> v.contains("3-formal-errors-and-the-unknown-state") && v.contains("missing")),
                () -> "a deleted §3 heading must fire naming its anchor: " + checkAnchors(deleted));

        String renamed = canonicalSkeleton("## 3. Formal problems and the \"unknown\" state");
        assertTrue(checkAnchors(renamed).stream()
                        .anyMatch(v -> v.contains("3-formal-errors-and-the-unknown-state") && v.contains("missing")),
                () -> "a renamed §3 heading must fire naming the vanished anchor: " + checkAnchors(renamed));
    }

    @Test
    void firesOnAnIntraDocLinkThatResolvesToNoHeadingAndIgnoresFencedHeadings() {
        String withDeadLink = canonicalSkeleton("## 3. Formal errors and the \"unknown\" state")
                + "\nsee [§3](#3-formal-errors-and-the-unknown-state) and [bogus](#a-section-that-is-not-there)\n";
        assertTrue(checkAnchors(withDeadLink).stream()
                        .anyMatch(v -> v.contains("a-section-that-is-not-there") && v.contains("resolves to no heading")),
                () -> "the dead link must fire: " + checkAnchors(withDeadLink));

        String fencedOnly = canonicalSkeleton("## 3. Formal errors and the \"unknown\" state")
                + "\n```\n## 99. not a heading, just code\n```\nsee [x](#99-not-a-heading-just-code)\n";
        assertTrue(checkAnchors(fencedOnly).stream().anyMatch(v -> v.contains("99-not-a-heading-just-code")),
                "a fenced pseudo-heading must NOT mint an anchor");
    }

    /** The 14-section canonical skeleton; pass {@code null} to drop §3, or a replacement §3 heading line. */
    private static String canonicalSkeleton(String sectionThreeHeading) {
        StringBuilder sb = new StringBuilder("# A12 Kernel — validation-language semantics\n\n")
                .append("## The field types at a glance\n\n")
                .append("## 1. Truth values and logical evaluation\n\n")
                .append("## 2. Non-specified (empty) values\n\n");
        if (sectionThreeHeading != null) {
            sb.append(sectionThreeHeading).append("\n\n");
        }
        return sb.append("## 4. The \"required\" property\n\n")
                .append("## 5. Numbers and decimals\n\n")
                .append("## 6. Dates and time\n\n")
                .append("## 7. Strings and patterns\n\n")
                .append("## 8. Enumerations\n\n")
                .append("## 9. Repetition, iteration, and cross-array evaluation\n\n")
                .append("## 10. Paths and references\n\n")
                .append("## 11. Computations\n\n")
                .append("## 12. The validation model\n\n")
                .append("## 13. Error-message interpolation\n\n")
                .append("## 14. CustomCondition — the escape hatch\n\n")
                .toString();
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
