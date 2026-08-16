package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The <b>directory-index drift guard</b> for the two hand-maintained sibling indexes outside {@code docs/} —
 * {@code scripts/README.md} (the script table) and {@code examples/README.md} (the demo table). Same failure
 * mode as the docs index, same fix: a README that lists a directory is a derived surface with no compiler or
 * renderer behind it, so a new file that never gets a row is invisible to readers and a renamed one leaves a
 * dead link. DOC-DISCIPLINE §3 named the scripts index as the one that "recurs — don't skip it"; it drifted
 * anyway (two scripts went unlisted, fixed by hand), which is what this guard replaces.
 *
 * <p>Shape copied from {@link DocsIndexGuardTest}: it reads repo files at runtime, so it lives in the
 * {@code :cli} {@code docGuardTest} task with both trees declared as that task's inputs (see
 * {@code cli/build.gradle}) — an added/renamed script or demo re-runs only this class, and cannot go
 * up-to-date / restore from the build cache into a false green locally while CI (a fresh checkout) fails. A
 * {@code :cli} test's working directory is the {@code cli} module dir, so the trees are reached at
 * {@code ../scripts} / {@code ../examples}.
 *
 * <p>Two defect classes per index, both checked by {@link #auditIndex(Path, Predicate)} so the does-fire arm
 * exercises the exact same code path against a fabricated tree:
 *
 * <ol>
 *   <li><b>Completeness</b> — every inventoried file is linked <i>at least once</i> from the index. The
 *       inventory is every regular file directly under {@code scripts/} (a script, a sourced helper, or the
 *       data files the scripts read — all of them carry a table row), and every {@code *.md} demo directly
 *       under {@code examples/}; the index itself and dotfiles (a stray {@code .DS_Store} is not an index
 *       entry) are excluded, as are the {@code models/} and {@code profiles/} subtrees, which the index
 *       describes as directories rather than per-file.</li>
 *   <li><b>Resolution</b> — every relative markdown link in the index resolves to an existing file or
 *       directory (external {@code http(s)}/{@code mailto} links and pure {@code #anchor} fragments are
 *       skipped; this covers the {@code ../docs/}, {@code ../build.sh} and {@code ../.github/} pointers
 *       too).</li>
 * </ol>
 *
 * <p>Fenced code blocks are stripped before links are extracted — an index may <i>show</i> markdown, and a
 * demonstrated link is not a listing.
 */
class DirectoryIndexGuardTest {

    /** The two indexed trees, repo-root-relative from the {@code cli} module dir (a {@code :cli} test's working dir). */
    private static final Path SCRIPTS_DIR = Path.of("../scripts");
    private static final Path EXAMPLES_DIR = Path.of("../examples");

    /** An inline markdown link {@code [label](target)} — the label may carry backticks/paths (no {@code ]}),
     *  the target runs to the closing {@code )} (neither index uses a {@code )} inside a target). */
    private static final Pattern LINK = Pattern.compile("\\[[^\\]]*\\]\\(([^)]+)\\)");

    /** A fenced code block, stripped before link extraction (see the class doc). */
    private static final Pattern FENCE = Pattern.compile("(?ms)^```.*?^```[^\\n]*$");

    /** Sanity floors: 55 scripts + 14 demos live (2026-08-16), counted by this guard's own rule — regular
     *  files, no dotfiles, no README. If a future refactor makes a scan find almost nothing
     *  (wrong working dir, a moved tree), the guard would "pass" while checking nothing — this catches it. */
    private static final int MIN_SCRIPTS = 25;
    private static final int MIN_DEMOS = 8;

    /** Inventory filters: every file under {@code scripts/}, only the {@code *.md} demos under {@code examples/}. */
    private static final Predicate<Path> ANY_FILE = p -> true;
    private static final Predicate<Path> MARKDOWN = p -> p.getFileName().toString().endsWith(".md");

    @Test
    void everyScriptIsListedAndEveryIndexLinkResolves() {
        assertIndexed(SCRIPTS_DIR, ANY_FILE, MIN_SCRIPTS, "scripts/README.md");
    }

    @Test
    void everyDemoIsListedAndEveryIndexLinkResolves() {
        assertIndexed(EXAMPLES_DIR, MARKDOWN, MIN_DEMOS, "examples/README.md");
    }

    /**
     * Does-fire proof: a fabricated tree with one present-but-unlisted file and one dead link must surface
     * <b>both</b>, while a listed file, an external link and a pure anchor must not fire — and a link that
     * only appears inside a fenced code block must count for nothing (it neither lists a file nor is
     * reported dead). This exercises {@link #auditIndex(Path, Predicate)} — the same method the real checks
     * call — so the guard's behaviour is proven, not merely asserted against the live trees.
     */
    @Test
    void theGuardFiresOnAnUnlistedFileAndADeadLink(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("listed.sh"), "#!/bin/sh\n");
        Files.writeString(dir.resolve("orphan.sh"), "#!/bin/sh\n");   // exists but is never listed
        Files.writeString(dir.resolve("README.md"), """
                # index
                | [`listed.sh`](listed.sh) | does a thing |
                | [`gone.sh`](gone.sh) | a row left behind by a rename |
                - [external](https://example.com/x)
                - [anchor](#the-table)

                ```markdown
                | [`orphan.sh`](orphan.sh) | how a row is written |
                | [`never.sh`](never.sh) | a demonstrated link, not a listing |
                ```
                """);

        Audit audit = auditIndex(dir, ANY_FILE);
        assertTrue(audit.unlisted().stream().anyMatch(s -> s.endsWith("orphan.sh")),
                () -> "expected orphan.sh reported unlisted (its only link is inside a fence), got: " + audit.unlisted());
        assertTrue(audit.unlisted().stream().noneMatch(s -> s.endsWith("listed.sh")),
                () -> "a listed file must not be reported unlisted: " + audit.unlisted());
        assertTrue(audit.deadLinks().stream().anyMatch(s -> s.contains("gone.sh")),
                () -> "expected gone.sh reported as a dead link, got: " + audit.deadLinks());
        assertTrue(audit.deadLinks().stream().noneMatch(
                        s -> s.contains("example.com") || s.contains("#") || s.contains("never.sh")),
                () -> "external, anchor and fenced links must be skipped, not flagged dead: " + audit.deadLinks());
    }

    /** The audit of one index: files present-but-unlisted, and index links that don't resolve. */
    private record Audit(List<String> unlisted, List<String> deadLinks) {}

    private static void assertIndexed(Path dir, Predicate<Path> keep, int floor, String index) {
        List<Path> files = inventory(dir, keep);
        assertTrue(files.size() >= floor,
                () -> "found only " + files.size() + " indexable files under " + dir + " (floor " + floor
                        + ") — is the working dir wrong or the tree moved? The guard would check nothing.");

        Audit audit = auditIndex(dir, keep);
        assertTrue(audit.unlisted().isEmpty(),
                () -> "files NOT listed in " + index + " (add a row for each):\n  "
                        + String.join("\n  ", audit.unlisted()));
        assertTrue(audit.deadLinks().isEmpty(),
                () -> "relative links in " + index + " that DON'T resolve to a file/dir (fix or remove):\n  "
                        + String.join("\n  ", audit.deadLinks()));
    }

    /**
     * Audit {@code <dir>/README.md}: collect every relative link outside a fenced block (recording the dead
     * ones and the set of existing linked files), then assert every inventoried file appears in that linked
     * set. Filesystem-backed so it works identically on a real tree and on a {@link TempDir} fabrication.
     */
    private static Audit auditIndex(Path dir, Predicate<Path> keep) {
        String content = FENCE.matcher(read(dir.resolve("README.md"))).replaceAll("");

        List<String> deadLinks = new ArrayList<>();
        Set<Path> linked = new HashSet<>();
        Matcher m = LINK.matcher(content);
        while (m.find()) {
            String url = m.group(1).trim();
            int hash = url.indexOf('#');
            if (hash >= 0) {
                url = url.substring(0, hash);                       // drop a #fragment
            }
            int space = url.indexOf(' ');
            if (space >= 0) {
                url = url.substring(0, space);                      // drop a "title" after the target
            }
            if (url.isEmpty() || url.startsWith("http://") || url.startsWith("https://")
                    || url.startsWith("mailto:")) {
                continue;                                           // not a relative file link
            }
            Path target = dir.resolve(url).toAbsolutePath().normalize();
            if (!Files.exists(target)) {
                deadLinks.add(url);
            } else if (Files.isRegularFile(target)) {
                linked.add(target);
            }
        }

        List<String> unlisted = new ArrayList<>();
        for (Path file : inventory(dir, keep)) {
            if (!linked.contains(file.toAbsolutePath().normalize())) {
                unlisted.add(dir.relativize(file).toString());
            }
        }
        return new Audit(unlisted, deadLinks);
    }

    /** The files the index must cover: regular files directly under {@code dir} that pass {@code keep},
     *  minus the index itself and dotfiles. Subdirectories are the index's business as directories. */
    private static List<Path> inventory(Path dir, Predicate<Path> keep) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> !p.getFileName().toString().equals("README.md"))
                    .filter(keep)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
