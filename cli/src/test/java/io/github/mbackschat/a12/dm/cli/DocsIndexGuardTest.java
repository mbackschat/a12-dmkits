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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The <b>docs-index drift guard</b> — keeps {@code docs/README.md} (the hand-maintained index of every
 * document under {@code docs/}) from rotting against the actual {@code docs/} tree. A README index has no
 * compiler or renderer behind it, so both failure modes are silent: a new doc that never gets an index entry
 * (readers can't find it), and an index link that points at a moved/renamed/deleted file (readers hit a
 * 404). This guard fails the build on either.
 *
 * <p>It reads repo files at runtime, so it lives in the {@code :cli} {@code docGuardTest} task with the docs
 * tree declared as that task's inputs (see {@code cli/build.gradle}) — a docs edit re-runs only this class,
 * and such an edit can no longer go up-to-date / restore from the build cache into a false green locally
 * while CI (a fresh checkout) fails. The working directory of a {@code :cli} test is the {@code cli} module
 * dir, so the tree is reached at {@code ../docs} (the same {@code ../}-relative convention as
 * {@link CommandReferencesResolveTest}).
 *
 * <p>Two defect classes, both checked by {@link #auditIndex(Path)} so the does-fire arm exercises the exact
 * same code path against a fabricated tree:
 *
 * <ol>
 *   <li><b>Completeness</b> — every {@code *.md} directly under {@code docs/} and under {@code docs/archived/}
 *       (excluding the two {@code README.md} indexes themselves; non-{@code .md} artifacts like the corpus
 *       fixtures {@code .zip} are exempt) is linked <i>at least once</i> from {@code docs/README.md}.</li>
 *   <li><b>Resolution</b> — every relative markdown link in {@code docs/README.md} resolves to an existing
 *       file or directory (external {@code http(s)}/{@code mailto} links and pure {@code #anchor} fragments
 *       are skipped; this covers the {@code ../eval/}, {@code ../examples/}, {@code ../corpus/},
 *       {@code ../probe/} sibling pointers too).</li>
 * </ol>
 */
class DocsIndexGuardTest {

    /** The docs tree, repo-root-relative from the {@code cli} module dir (a {@code :cli} test's working dir). */
    private static final Path DOCS_DIR = Path.of("../docs");

    /** An inline markdown link {@code [label](target)} — the label may carry backticks/paths (no {@code ]}),
     *  the target runs to the closing {@code )} (our index uses no {@code )} inside a target). */
    private static final Pattern LINK = Pattern.compile("\\[[^\\]]*\\]\\(([^)]+)\\)");

    /** Sanity floor: the live index covers ~50 docs. If a future refactor makes the scan find almost nothing
     *  (wrong working dir, an emptied tree), the guard would "pass" while checking nothing — this catches it. */
    private static final int MIN_DOCS = 30;

    @Test
    void everyDocIsLinkedAndEveryIndexLinkResolves() {
        List<Path> docs = inventory(DOCS_DIR);
        assertTrue(docs.size() >= MIN_DOCS,
                () -> "found only " + docs.size() + " docs under " + DOCS_DIR + " (floor " + MIN_DOCS
                        + ") — is the working dir wrong or the tree emptied? The guard would check nothing.");

        Audit audit = auditIndex(DOCS_DIR);
        assertTrue(audit.unlinked().isEmpty(),
                () -> "docs/ files NOT linked from docs/README.md (add an index entry for each):\n  "
                        + String.join("\n  ", audit.unlinked()));
        assertTrue(audit.deadLinks().isEmpty(),
                () -> "relative links in docs/README.md that DON'T resolve to a file/dir (fix or remove):\n  "
                        + String.join("\n  ", audit.deadLinks()));
    }

    /**
     * Does-fire proof: a fabricated docs tree with one present-but-unlinked doc and one dead link must
     * surface <b>both</b>, while an already-linked doc, an external link, and a pure anchor must not fire.
     * This exercises {@link #auditIndex(Path)} — the same method the real check calls — so the guard's
     * behaviour is proven, not merely asserted against the live tree.
     */
    @Test
    void theGuardFiresOnAnUnlinkedDocAndADeadLink(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("archived"));
        Files.writeString(dir.resolve("LIVE.md"), "x");
        Files.writeString(dir.resolve("ORPHAN.md"), "x");              // exists but is never linked
        Files.writeString(dir.resolve("archived/OLD.md"), "x");
        Files.writeString(dir.resolve("archived/README.md"), "x");     // excluded from the inventory
        Files.writeString(dir.resolve("README.md"), """
                # index
                - [live](LIVE.md)
                - [old proposal](archived/OLD.md)
                - [gone](GHOST.md)
                - [external](https://example.com/x)
                - [anchor](#a-section)
                """);

        Audit audit = auditIndex(dir);
        assertTrue(audit.unlinked().stream().anyMatch(s -> s.endsWith("ORPHAN.md")),
                () -> "expected ORPHAN.md reported unlinked, got: " + audit.unlinked());
        assertTrue(audit.unlinked().stream().noneMatch(s -> s.endsWith("LIVE.md") || s.endsWith("OLD.md")),
                () -> "a linked doc must not be reported unlinked: " + audit.unlinked());
        assertTrue(audit.deadLinks().stream().anyMatch(s -> s.contains("GHOST.md")),
                () -> "expected GHOST.md reported as a dead link, got: " + audit.deadLinks());
        assertTrue(audit.deadLinks().stream().noneMatch(s -> s.contains("example.com") || s.contains("#")),
                () -> "external + anchor links must be skipped, not flagged dead: " + audit.deadLinks());
    }

    /** The audit of one index: docs present-but-unlinked, and index links that don't resolve. */
    private record Audit(List<String> unlinked, List<String> deadLinks) {}

    /**
     * Audit {@code <docsDir>/README.md}: collect every relative link (recording the dead ones and the set of
     * existing linked files), then assert every inventoried doc appears in that linked set. Filesystem-backed
     * so it works identically on the real tree and on a {@link TempDir} fabrication.
     */
    private static Audit auditIndex(Path docsDir) {
        String content = read(docsDir.resolve("README.md"));

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
            Path target = docsDir.resolve(url).toAbsolutePath().normalize();
            if (!Files.exists(target)) {
                deadLinks.add(url);
            } else if (Files.isRegularFile(target)) {
                linked.add(target);
            }
        }

        List<String> unlinked = new ArrayList<>();
        for (Path md : inventory(docsDir)) {
            if (!linked.contains(md.toAbsolutePath().normalize())) {
                unlinked.add(docsDir.relativize(md).toString());
            }
        }
        return new Audit(unlinked, deadLinks);
    }

    /** The doc files the index must cover: {@code *.md} directly under docsDir and under {@code archived/},
     *  minus the two {@code README.md} indexes. Non-{@code .md} artifacts (e.g. the fixtures {@code .zip})
     *  are naturally excluded. */
    private static List<Path> inventory(Path docsDir) {
        List<Path> out = new ArrayList<>();
        collectMarkdown(docsDir, out);
        Path archived = docsDir.resolve("archived");
        if (Files.isDirectory(archived)) {
            collectMarkdown(archived, out);
        }
        return out;
    }

    private static void collectMarkdown(Path dir, List<Path> out) {
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> !p.getFileName().toString().equals("README.md"))
                    .forEach(out::add);
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
