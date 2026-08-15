package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
 * <p><b>Why this is the cheapest guard in the estate to justify.</b> Its oracle is {@link Files#exists}: there
 * is no interpretation, no slug rule, no window, nothing to tune. Measured when it landed: <b>1513</b> relative
 * link targets across {@code docs/*.md}, <b>7</b> dead — a 0.5 % rate, all seven from one cause.
 *
 * <p>That cause is worth naming, because it is the concrete cost of a rule this repo already adopted. The seven
 * pointed at performance run records under {@code perf-results/} that no longer exist, because they were written
 * before the tree was committed, back when the gate wrote its observations under {@code build/} — the exact loss
 * that "NEVER store data that must survive under {@code build/}" was adopted on 2026-08-11 to stop. The docs
 * outlived the evidence they cite. The repair keeps the run id (a record recovered later is still identifiable)
 * in the plain code-span form the same doc already uses elsewhere, and drops the link that cannot resolve.
 *
 * <p>Scope is {@code docs/*.md}, the tree {@code docGuardTest} declares as its inputs; {@code docs/archived/} is
 * a frozen record and out of scope, as it is for the anchor guard. Markdown elsewhere in the repo is not covered
 * — extending there means extending the task's declared inputs, which is a separate change.
 */
class DocRelativeLinkGuardTest {

    private static final Path DOCS = Path.of("../docs");

    /** A markdown link target, fragment stripped: {@code [label](../perf-results/x.jsonl#frag)}. */
    private static final Pattern LINK = Pattern.compile("\\]\\(([^)\\s#]+)(?:#[^)\\s]*)?\\)");

    /** Sanity floor, asserted on what the RULE parses — the docs tree carries ~1500 relative targets. */
    private static final int MIN_TARGETS = 1000;

    @Test
    void everyRelativeLinkTargetExists() throws IOException {
        Path docs = locate();
        List<String> targets = new ArrayList<>();
        List<String> dead = new ArrayList<>();
        for (Path doc : markdown(docs)) {
            audit(docs, doc.getFileName().toString(), Files.readString(doc), targets, dead);
        }

        assertTrue(targets.size() >= MIN_TARGETS,
                () -> "parsed only " + targets.size() + " relative link targets from " + docs + " (floor "
                        + MIN_TARGETS + ") — wrong working dir or an emptied tree, and the guard checks nothing");
        assertTrue(dead.isEmpty(), () -> "links whose TARGET FILE does not exist — the fragment guard cannot see "
                + "this half, so a dead path reads as a working citation forever. Point it at a live file, or, "
                + "if the artifact is genuinely gone, keep its identifier as a plain code span and drop the "
                + "link:\n  " + String.join("\n  ", dead));
    }

    /**
     * Does-fire proof against a fabricated tree, through the method the real check calls: a dead relative path
     * fires; a live one, an external URL, a bare fragment, and a path inside a code span must all stay silent.
     */
    @Test
    void theGuardFiresOnlyOnADeadRelativePath(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("peer.md"), "x");
        String doc = """
                [live](peer.md) [live-frag](peer.md#anchor) [self](#anchor)
                [dead](../gone/missing.jsonl) [url](https://example.com/x.md)
                Prose about the syntax: `[x](../also-missing.md)` is code, not a link.
                """;

        List<String> targets = new ArrayList<>();
        List<String> dead = new ArrayList<>();
        audit(dir, "t.md", doc, targets, dead);

        assertTrue(dead.size() == 1 && dead.get(0).contains("missing.jsonl"),
                () -> "expected exactly the dead relative path to fire, got " + dead);
        assertTrue(targets.size() == 3,
                () -> "expected the three resolvable relative targets to be counted toward the floor "
                        + "(a bare fragment and an external URL are neither checked nor counted), got " + targets);
    }

    /** Collect every relative target, and the subset that does not exist on disk. */
    private static void audit(Path base, String name, String markdown, List<String> targets, List<String> dead) {
        Matcher link = LINK.matcher(withoutCode(markdown));
        while (link.find()) {
            String target = link.group(1);
            if (target.startsWith("http://") || target.startsWith("https://") || target.startsWith("mailto:")) {
                continue;
            }
            targets.add(target);
            if (!Files.exists(base.resolve(target).normalize())) {
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
