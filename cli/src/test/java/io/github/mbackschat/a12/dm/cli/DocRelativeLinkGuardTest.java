package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>A relative link in a doc must point at a file that exists.</b> {@link DocAnchorResolveGuardTest} checks
 * the <i>fragment</i> half of a link — that {@code #ifNN} resolves inside its target doc — and says nothing
 * about the <i>path</i> half, so a link to a file that was never committed or has since been deleted reads as
 * a working citation forever.
 *
 * <p><b>Why this is the cheapest guard in the estate to justify.</b> Its oracle is {@link Files#exists} — for a
 * target this repository contains. For one that leaves the repository it is not an oracle at all, and that
 * distinction is load-bearing: see {@link #UNVERIFIABLE_OUTSIDE_THE_REPO}. Measured 2026-08-16 <i>through this
 * class's own rule</i>: <b>2432</b> relative link targets across <b>66</b> docs, of which <b>7</b> were dead when it
 * landed — a 0.3 % rate, all seven from one cause.
 *
 * <p><i>Corrected 2026-08-16:</i> this paragraph read <b>1513</b>, a figure taken with the narrower extractor used
 * while scoping the repair rather than with the rule that shipped, and it contradicted {@link #MIN_TARGETS}'s own
 * comment twelve lines below. Neither the guard nor any register could see it: a derived figure in a javadoc is a
 * source file, which is the gap {@code docs/CLAUDE.recompute.md} explicitly does not reach. Re-derive before citing.
 *
 * <p>That cause is worth naming, because it is the concrete cost of a rule this repo already adopted. The seven
 * pointed at performance run records under {@code perf-results/} that no longer exist, because they were written
 * before the tree was committed, back when the gate wrote its observations under {@code build/} — the exact loss
 * that "NEVER store data that must survive under {@code build/}" was adopted on 2026-08-11 to stop. The docs
 * outlived the evidence they cite. The repair keeps the run id (a record recovered later is still identifiable)
 * in the plain code-span form the same doc already uses elsewhere, and drops the link that cannot resolve.
 *
 * <p>Scope is {@code docs/*.md} for {@link #everyRelativeLinkTargetExists()}, plus every other tracked
 * {@code .md} in the repository for {@link #everyRelativeLinkOutsideDocsAlsoResolves()}. {@code docs/archived/}
 * is a frozen record and out of scope, as it is for the anchor guard.
 *
 * <p><i>Widened 2026-09-20, and the trigger is worth recording because it is this rule applied to itself.</i>
 * This paragraph read "markdown elsewhere in the repo is not covered — extending there means extending the
 * task's declared inputs, which is a separate change", and that separate change never came. A session then
 * added <b>27 module and sub-project READMEs</b> carrying relative links, hand-checked them once, and landed
 * them: 747 relative targets lived outside {@code docs/} with no enforcement locus at all, which is exactly
 * the "advertise nothing you cannot point at" defect one layer out — a documentation surface whose own
 * citations nothing re-checks. Measured through this class's rule at the widening: <b>zero</b> dead targets
 * outside {@code docs/} once the mirror sources below are excluded, so the hand check had held and the gap was
 * purely the absence of a repeat.
 */
class DocRelativeLinkGuardTest {

    private static final Path DOCS = Path.of("../docs");

    /** A markdown link target, fragment stripped: {@code [label](../perf-results/x.jsonl#frag)}. */
    private static final Pattern LINK = Pattern.compile("\\]\\(([^)\\s#]+)(?:#[^)\\s]*)?\\)");

    /** Sanity floor, asserted on what the RULE parses — the docs tree carries 2432 relative targets (2026-08-16). */
    private static final int MIN_TARGETS = 2000;

    /** Sanity floor on the SCAN SET: a skip filter must not shrink the corpus past the target floor's headroom. */
    private static final int MIN_DOCS = 50;

    /**
     * <b>Targets that leave the repository, enumerated rather than skipped.</b> {@link Files#exists} answers for
     * something this repository contains; for a sibling-repo path it silently measures the developer's directory
     * layout. That shipped: this guard was green on the author's checkout (where {@code ../a12-kernel-bootstrap}
     * happens to exist) and <b>red in every {@code git worktree}</b> — including the ones this project's own
     * review workflow creates. Worse, the first worktree run returned {@code FROM-CACHE} green, because an
     * out-of-repo path cannot be a declared task input, so the cache key cannot see it.
     *
     * <p>Such a target is therefore excluded from the existence check and <b>named here</b>. A new one fails by
     * name rather than joining a silent skip.
     */
    private static final Set<String> UNVERIFIABLE_OUTSIDE_THE_REPO = Set.of(
            // KERNEL-31-UPGRADE-PROPOSAL names the sibling kit that publishes the kernel to mavenLocal.
            "../../a12-kernel-bootstrap");
    // PORTABLE-CONSISTENCY-PROPOSAL's two consult-only `../../a12-kernel/...` citations were listed here until
    // 2026-09-19, when that proposal was archived. They are not re-spelled one level deeper: `docs/archived/` is
    // outside this guard's scan set by the scope rule above, so those targets are no longer parsed at all, and a
    // literal nothing reads would be a pin on a file this guard cannot see.

    @Test
    void everyRelativeLinkTargetExists() throws IOException {
        Path docs = locate();
        List<Path> scanned = markdown(docs);
        assertTrue(scanned.size() >= MIN_DOCS,
                () -> "scanned only " + scanned.size() + " docs (floor " + MIN_DOCS + ") — a skip filter or a "
                        + "moved tree shrinks the scan set, and the target floor alone cannot see it");

        List<String> targets = new ArrayList<>();
        List<String> dead = new ArrayList<>();
        Set<String> outside = new LinkedHashSet<>();
        for (Path doc : scanned) {
            audit(docs, doc.getFileName().toString(), Files.readString(doc), targets, dead, outside);
        }

        assertEquals(UNVERIFIABLE_OUTSIDE_THE_REPO, outside,
                "the set of link targets that leave the repository changed. `Files.exists` cannot be an oracle "
                + "for those — it measures whichever sibling checkouts this machine happens to have");
        assertTrue(targets.size() >= MIN_TARGETS,
                () -> "parsed only " + targets.size() + " relative link targets from " + docs + " (floor "
                        + MIN_TARGETS + ") — wrong working dir or an emptied tree, and the guard checks nothing");
        assertTrue(dead.isEmpty(), () -> "links whose TARGET FILE does not exist — the fragment guard cannot see "
                + "this half, so a dead path reads as a working citation forever. Point it at a live file, or, "
                + "if the artifact is genuinely gone, keep its identifier as a plain code span and drop the "
                + "link:\n  " + String.join("\n  ", dead));
    }

    /**
     * <b>Markdown that is a MIRROR SOURCE, excluded by name with its reason.</b> These two files are assembled
     * into a public release tree, and their links resolve <em>there</em>: {@code assemble-release-repo.sh} places
     * {@code LICENSE}, {@code NOTICE}, {@code examples/} and {@code codex/} beside them in the mirror, none of
     * which sits beside them here. {@link Files#exists} against this checkout would therefore fail honest
     * content — and a guard that fails honest content gets disabled, which is worse than no guard.
     *
     * <p>Their real oracle is the assembly: the mirror checkers verify the tree a consumer receives, where the
     * targets exist. Named rather than pattern-skipped, so a third mirror source fails here by name instead of
     * joining a silent exemption.
     */
    private static final Set<String> MIRROR_SOURCES_CHECKED_BY_THE_ASSEMBLER = Set.of(
            "plugin/README.md",
            "interpreter/public-release/README.md");

    /** Trees with no tracked markdown of ours; walking them is slow and their contents are not ours to fix. */
    private static final Set<String> NOT_OURS = Set.of("build", "node_modules", ".git", ".gradle",
            ".gradle-isolated", ".kotlin", ".uv-cache", ".uv-tools");

    /**
     * <b>The other markdown in the repository — module READMEs, sub-project READMEs, the root README, the
     * instruction files.</b> Same rule, same oracle, different denominator; see the widening note in the class
     * javadoc for why this is a second test rather than a wider first one (the two have different exclusion
     * sets and different floors, and collapsing them would hide which half a failure came from).
     */
    @Test
    void everyRelativeLinkOutsideDocsAlsoResolves() throws IOException {
        Path root = locate().toAbsolutePath().normalize().getParent();
        List<Path> scanned = new ArrayList<>();
        Set<String> mirrorSourcesSeen = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String relative = root.relativize(file).toString();
                if (!relative.endsWith(".md") || relative.startsWith("docs" + java.io.File.separator)) {
                    continue;
                }
                if (NOT_OURS.stream().anyMatch(skip -> relative.contains(skip + java.io.File.separator))) {
                    continue;
                }
                if (MIRROR_SOURCES_CHECKED_BY_THE_ASSEMBLER.contains(relative.replace(java.io.File.separator, "/"))) {
                    mirrorSourcesSeen.add(relative.replace(java.io.File.separator, "/"));
                    continue;
                }
                scanned.add(file);
            }
        }

        assertEquals(MIRROR_SOURCES_CHECKED_BY_THE_ASSEMBLER, mirrorSourcesSeen,
                "the set of mirror-source READMEs changed. Their links resolve in the ASSEMBLED tree, not here, "
                + "so each needs a named exemption and the assembler as its oracle — never a silent skip");
        assertTrue(scanned.size() >= MIN_MARKDOWN_OUTSIDE_DOCS,
                () -> "scanned only " + scanned.size() + " markdown files outside docs/ (floor "
                        + MIN_MARKDOWN_OUTSIDE_DOCS + ") — a skip filter or a moved tree shrinks the scan set, "
                        + "and a green result then proves nothing");

        List<String> targets = new ArrayList<>();
        List<String> dead = new ArrayList<>();
        Set<String> outside = new LinkedHashSet<>();
        for (Path file : scanned) {
            audit(root, file.getParent(), root.relativize(file).toString(), Files.readString(file),
                    targets, dead, outside);
        }

        assertTrue(targets.size() >= MIN_TARGETS_OUTSIDE_DOCS,
                () -> "parsed only " + targets.size() + " relative targets outside docs/ (floor "
                        + MIN_TARGETS_OUTSIDE_DOCS + ") — the extractor or the scan broke");
        assertTrue(outside.isEmpty(), () -> "a link outside docs/ leaves the repository, where `Files.exists` "
                + "measures whichever sibling checkouts this machine happens to have rather than anything "
                + "durable. Point it at a committed file, or name it as unverifiable:\n  " + outside);
        assertTrue(dead.isEmpty(), () -> "links whose TARGET FILE does not exist. A module README's citations "
                + "are read far more often than docs/, and nothing else checks them:\n  "
                + String.join("\n  ", dead));
    }

    /** Sanity floor on the non-docs scan set — 60 tracked markdown files outside docs/ (2026-09-20). */
    private static final int MIN_MARKDOWN_OUTSIDE_DOCS = 45;

    /** Sanity floor on what the RULE parses there — 747 relative targets by a crude count (2026-09-20). */
    private static final int MIN_TARGETS_OUTSIDE_DOCS = 500;

    /**
     * Does-fire proof against a fabricated tree, through the method the real check calls: a dead relative path
     * fires; a live one, an external URL, a bare fragment, and a path inside a code span must all stay silent.
     */
    @Test
    void theGuardFiresOnlyOnADeadRelativePath(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("peer.md"), "x");
        String doc = """
                [live](peer.md) [live-frag](peer.md#anchor) [self](#anchor)
                [dead](../gone/missing.jsonl) [url](https://example.com/x.md) [away](../../escapes-the-repo)
                Prose about the syntax: `[x](../also-missing.md)` is code, not a link.
                """;

        List<String> targets = new ArrayList<>();
        List<String> dead = new ArrayList<>();
        Set<String> outside = new LinkedHashSet<>();
        audit(dir, "t.md", doc, targets, dead, outside);

        assertTrue(dead.size() == 1 && dead.get(0).contains("missing.jsonl"),
                () -> "expected exactly the dead relative path to fire, got " + dead);
        assertEquals(Set.of("../../escapes-the-repo"), outside,
                "a target that leaves the tree must be CLASSIFIED, not resolved against the developer's disk");
        assertTrue(targets.size() == 4,
                () -> "expected the four relative targets to be counted toward the floor "
                        + "(a bare fragment and an external URL are neither checked nor counted), got " + targets);
    }

    /**
     * Collect every relative target, those that do not exist, and those that leave the repository.
     *
     * <p>Containment is judged against {@code base}'s parent, which is the repository root only because
     * {@code base} is {@code docs/}. A README in a nested module needs the root passed explicitly — see the
     * overload — or every one of its {@code ../..} citations reads as escaping a tree it is still inside.
     */
    private static void audit(Path base, String name, String markdown, List<String> targets, List<String> dead,
            Set<String> outside) {
        audit(base.toAbsolutePath().normalize().getParent(), base, name, markdown, targets, dead, outside);
    }

    /** As above, with the containment root stated rather than derived from the scanned file's own depth. */
    private static void audit(Path root, Path base, String name, String markdown, List<String> targets,
            List<String> dead, Set<String> outside) {
        Matcher link = LINK.matcher(withoutCode(markdown));
        while (link.find()) {
            String target = link.group(1);
            if (target.startsWith("http://") || target.startsWith("https://") || target.startsWith("mailto:")) {
                continue;
            }
            targets.add(target);
            // normalize() AFTER toAbsolutePath(): a relative base like `../docs` keeps its `..` segment
            // otherwise, and every containment test then silently answers false.
            Path resolved = base.resolve(target).toAbsolutePath().normalize();
            if (!resolved.startsWith(root)) {
                outside.add(target);
                continue;
            }
            if (!Files.exists(resolved)) {
                dead.add(name + " → " + target);
            }
        }
    }

    /** Blank out fenced blocks and inline code spans, so prose *about* a link is not read as one. */
    private static String withoutCode(String markdown) {
        StringBuilder out = new StringBuilder(markdown.length());
        boolean fenced = false;
        for (String line : markdown.split("\n", -1)) {
            if (line.strip().startsWith("```")) {
                fenced = !fenced;
                out.append('\n');
                continue;
            }
            out.append(fenced ? "" : line.replaceAll("`+[^`]*`+", " ")).append('\n');
        }
        return out.toString();
    }

    private static List<Path> markdown(Path docs) throws IOException {
        try (Stream<Path> files = Files.list(docs)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".md")).sorted().toList();
        }
    }

    private static Path locate() {
        return Files.isDirectory(DOCS) ? DOCS : Path.of("docs");
    }
}
