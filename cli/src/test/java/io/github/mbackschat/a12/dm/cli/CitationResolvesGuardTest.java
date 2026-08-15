package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * <b>A citation INTO a doc is the one direction nothing checked.</b> Three guards look adjacent and none of them
 * covers it: {@link DocAnchorResolveGuardTest} validates markdown {@code ](#…)} fragments, so plain-prose
 * {@code CLI-SPEC §5} is invisible to it; {@code ApiFindingsNumberingGuardTest} audits only that doc's own
 * internal numbering; and {@code LockCitationResolvesTest} runs the OPPOSITE way, checking test classes named
 * inside docs. Production source cites documentation in a form nothing verifies, and it has rotted.
 *
 * <p><b>Scope was chosen by measurement, not by symmetry.</b> A flat "every citation must resolve" rule was
 * specified first and would have been a bad guard. Counting each citation form separately, before any code
 * existed, produced this:
 *
 * <pre>
 *   form                  population   decidable                       defects
 *   quoted title                 121   yes                                  17
 *   CLI-SPEC §N                  196   yes                                  13
 *   ordinal `finding N`          182   only with an alias table              7
 *   KERNEL-* §n                  742   bounded 1..14 enum                    1
 *   API-GUIDE §N                   8   yes                                   1
 *   bare #NN                     269   NO — >=7 schemes share the sigil      0
 * </pre>
 *
 * <p>Two conclusions the flat rule hid. The <b>largest</b> family, 742 {@code KERNEL-* §n} citations, yields
 * exactly one defect, because {@code §n} is a bounded 14-value enum that the retrieval manual itself calls "a
 * section-CLASS hint, not an address" — a resolution rule there is near-vacuous by construction. And the
 * <b>highest-yielding</b> family, quoted titles, was missing from the specification altogether. This class
 * therefore lands the two SECTION-LABEL rules first, with the quoted-title rule measured (47 sites, 43
 * distinct quotes) and queued as its own slice because each repair needs the live heading researched
 * rather than derived. It deliberately does not touch {@code #NN} (undecidable until the
 * sigil is prefixed) or {@code finding N} (needs an authored alias table: {@code study}/{@code proposal} are
 * legacy aliases for API-FINDINGS, {@code eval}/{@code probe}/{@code report} name nothing numbered).
 *
 * <p><b>Two oracle rules are load-bearing, each because it was got wrong.</b> Every containment test is
 * <b>case-insensitive</b>: headings in this estate shout ({@code cell STATE}, {@code TIERED}), and two
 * case-sensitive "0 occurrences" measurements were published as dangling-citation findings before a cold review
 * re-ran them with {@code -i} and found the referent alive. And a quote that is <b>truncated with an ellipsis</b>
 * is rejected rather than resolved: it cannot be matched exactly, so accepting it would silently pass anything.
 *
 * <p><b>Extraction artifacts are excluded, measured rather than assumed:</b> a "quote" containing a path
 * separator or Java string concatenation is source code, not a citation. Six of 25 raw hits were these.
 */
public class CitationResolvesGuardTest {

    private static final Path ROOT = realPath(Path.of("..").toAbsolutePath().normalize());

    private static final Set<String> SKIPPED_DIRECTORIES = Set.of("build", ".git", ".gradle", "node_modules");

    private static final Set<String> SCANNED_SUFFIXES =
            Set.of(".md", ".java", ".kt", ".gradle", ".gradle.kts", ".sh");

    /** Docs cited by a quoted heading fragment. Longest-first at match time, so a `-RESULTS` doc is never
     *  truncated to its prefix and then resolved against its sibling (that mis-flagged two correct citations). */
    private static final List<String> QUOTED_DOCS = List.of(
            "INTERPRETER-PERFORMANCE-RESULTS", "INTERPRETER-PERFORMANCE", "INTERPRETER-FINDINGS",
            "KERNEL-SEMANTICS", "KERNEL-FINDINGS", "TESTING-SPEC", "DOC-DISCIPLINE", "CLI-SPEC", "API-GUIDE");

    private static final Pattern QUOTED_CITATION = Pattern.compile(
            "(" + String.join("|", QUOTED_DOCS) + ")(?:\\.md)?[^\"\\n]{0,45}\"([^\"\\n]{6,140})\"");

    /** `<DOC> §label` for the two docs whose section labels ARE addresses. */
    private static final Pattern LABELLED_CITATION =
            Pattern.compile("(CLI-SPEC|API-GUIDE)[^§\\n]{0,30}§ ?([0-9][0-9a-z.]*[0-9a-z]|[0-9])");

    /** `KERNEL-FINDINGS/SEMANTICS §n` — not an address, but a bounded range, so out-of-range is still a defect. */
    private static final Pattern KERNEL_SECTION =
            Pattern.compile("KERNEL-(?:FINDINGS|SEMANTICS)[^§\\n]{0,30}§ ?([0-9]+)");

    /**
     * A section label, with the trailing dot OPTIONAL.
     *
     * <p>CLI-SPEC writes {@code ## 1. Purpose}; API-GUIDE writes {@code ### 3.5b Group presence}, with no dot.
     * A regex demanding the dot backtracks and captures just {@code 3}, so the live section {@code §3.5b} reads
     * as undefined and a correct citation is flagged. That false positive was produced by the first cut of this
     * guard and caught before it landed.
     */
    private static final Pattern SECTION_LABEL = Pattern.compile("(?m)^#{1,6} +([0-9][0-9a-z]*(?:\\.[0-9a-z]+)*)\\.? +\\S");

    private static final Pattern KERNEL_SEMANTICS_SECTION = Pattern.compile("(?m)^## ([0-9]+)\\. ");

    /** Assembled, so this file plants no literal citation for its own scan to report. */
    private static final String KF = "KERNEL-" + "FINDINGS";

    private static List<Path> scanned;

    @Test
    public void everyLabelledSectionCitationResolvesToALiveHeading() throws IOException {
        Map<String, Set<String>> labels = Map.of(
                "CLI-SPEC", sectionLabels("docs/CLI-SPEC.md"),
                "API-GUIDE", sectionLabels("docs/API-GUIDE.md"));

        List<String> violations = new ArrayList<>();
        for (Path file : scannedFiles()) {
            String text = read(file);
            Matcher m = LABELLED_CITATION.matcher(text);
            while (m.find()) {
                String doc = m.group(1);
                String label = m.group(2).toLowerCase();
                if (!labels.get(doc).contains(label)) {
                    violations.add(ROOT.relativize(file) + ":" + lineOf(text, m.start()) + "  " + doc + " §" + label);
                }
            }
        }

        assertTrue(violations.isEmpty(), () -> "a section citation names a heading that does not exist. A section "
                + "renumber leaves every citation to it resolving to NOTHING, and no link check can see it "
                + "because these are plain prose, not markdown fragments. Re-point to the live section (and if "
                + "you are renumbering, append rather than insert):\n  " + String.join("\n  ", violations));
    }

    /**
     * The kernel docs' {@code §n} is a section-CLASS hint rather than an address, so this only bounds the range.
     *
     * <p>Kept despite yielding one defect in 742 citations, because the check is the same mechanism as the rule
     * above and costs nothing extra. Its yield is documented in the class javadoc precisely so nobody later
     * mistakes it for a resolution guarantee and builds on it.
     */
    @Test
    public void everyKernelSectionCitationIsInRange() throws IOException {
        Set<String> live = new LinkedHashSet<>();
        Matcher m = KERNEL_SEMANTICS_SECTION.matcher(read("docs/KERNEL-SEMANTICS.md"));
        while (m.find()) {
            live.add(m.group(1));
        }
        assertTrue(live.size() >= 10, () -> "only " + live.size() + " canonical kernel sections found — the "
                + "heading shape changed and this guard would pass vacuously");

        List<String> violations = new ArrayList<>();
        for (Path file : scannedFiles()) {
            String text = read(file);
            Matcher c = KERNEL_SECTION.matcher(text);
            while (c.find()) {
                if (!live.contains(c.group(1))) {
                    violations.add(ROOT.relativize(file) + ":" + lineOf(text, c.start()) + "  §" + c.group(1));
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> "a kernel-doc section citation is outside the canonical §1-§"
                + live.size() + " range:\n  " + String.join("\n  ", violations));
    }

    /** Vacuity floor: the scan must actually reach the citation-bearing surfaces, named rather than counted. */
    @Test
    public void theScanReachesEveryCitationBearingSurface() throws IOException {
        Set<Path> members = new LinkedHashSet<>(scannedFiles());
        for (String required : List.of(
                "cli/build.gradle",
                "cli/src/main/java/io/github/mbackschat/a12/dm/cli/Cli.java",
                "docs/CATALOG-SPEC.md",
                "interpreter/src/commonMain/kotlin/io/github/mbackschat/a12/dm/interpreter/parse/Parser.kt",
                "modelkit/src/main/java/io/github/mbackschat/a12/dm/modelkit/StringFieldBuilder.java")) {
            assertTrue(members.contains(ROOT.resolve(required)),
                    () -> required + " carries a doc citation and must stay inside the scan");
        }
        int size = scannedFiles().size();
        assertTrue(size > 500, () -> "only " + size + " files scanned — the walk has lost a tree");
    }

    /** Does-fire control: without it a rule that matches nothing at all passes forever. */
    @Test
    public void theGuardFiresOnASectionThatDoesNotExist() {
        assertEquals(1, labelledViolations("see CLI-SPEC \u00a7999z for the contract"),
                "the rule must fire on a section label that no heading defines");
    }

    /** Does-not-fire control: a live label must pass, or the guard rejects correct citations. */
    @Test
    public void theGuardAcceptsALiveSectionLabel() {
        assertEquals(0, labelledViolations("see CLI-SPEC \u00a76 for the refactor gate"),
                "a citation naming a live section is correct content and must not be flagged");
    }

    /** Keyed on the DOC prefix, never a bare section token: three other docs define their own live §1a/§1b. */
    @Test
    public void theGuardIgnoresASectionTokenBelongingToAnotherDoc() {
        assertEquals(0, labelledViolations("KERNEL-DECOUPLING-PROPOSAL \u00a71a is a different document"),
                "a bare section token owned by another doc must not be resolved against CLI-SPEC");
    }

    private static int labelledViolations(String text) {
        Map<String, Set<String>> labels = Map.of(
                "CLI-SPEC", sectionLabels("docs/CLI-SPEC.md"),
                "API-GUIDE", sectionLabels("docs/API-GUIDE.md"));
        int hits = 0;
        Matcher m = LABELLED_CITATION.matcher(text);
        while (m.find()) {
            if (!labels.get(m.group(1)).contains(m.group(2).toLowerCase())) {
                hits++;
            }
        }
        return hits;
    }

    private static Set<String> sectionLabels(String doc) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = SECTION_LABEL.matcher(read(doc));
        while (m.find()) {
            String label = m.group(1).toLowerCase();
            out.add(label);
            out.add(label.replaceAll("\\.$", ""));
        }
        return out;
    }

    private static String read(String relative) {
        return read(ROOT.resolve(relative));
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int lineOf(String text, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static List<Path> scannedFiles() throws IOException {
        if (scanned == null) {
            Set<Path> canonical = new LinkedHashSet<>();
            List<Path> found = new ArrayList<>();
            Files.walkFileTree(ROOT, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return SKIPPED_DIRECTORIES.contains(dir.getFileName().toString())
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    if (SCANNED_SUFFIXES.stream().anyMatch(name::endsWith)) {
                        found.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            found.sort(Comparator.naturalOrder());
            for (Path f : found) {
                canonical.add(realPath(f));
            }
            scanned = List.copyOf(canonical);
        }
        return scanned;
    }

    private static Path realPath(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
