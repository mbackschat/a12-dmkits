package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * <b>Every code fence in our markdown closes, and nothing opens one by accident.</b>
 *
 * <p>An unpaired fence is not a local rendering glitch: it shifts the phase of every block below it, so prose
 * renders as code and code as prose to the end of the document. The doc guards inherit the same shift, because
 * {@link DocRelativeLinkGuardTest} and {@link DocAnchorResolveGuardTest} toggle a "fenced" flag on each fence
 * line to mask code before they look for links — past a stray fence they check code bodies as links and skip the
 * prose they exist to check. That shipped on 2026-09-20: an edit to {@code docs/API-GUIDE.md} left three lines of
 * a snippet and a bare fence between two paragraphs, and the guide's next {@code ```java} became content of the
 * block it opened. No guard noticed, because each one read the shifted text as well-formed.
 *
 * <p>The rule is CommonMark's own: a block opened by a run of backticks or tildes closes on a bare run of the same
 * character at least as long. Inside an open block, such a run that carries an info string is therefore content,
 * and in our docs it is never intended: it is the next snippet's opener, read as the tail of a block nobody meant
 * to open. Scope is every markdown file under the repository outside {@link #NOT_OURS}, {@code docs/archived/}
 * included, since phase is a property of a file however frozen its content.
 */
class MarkdownFenceBalanceGuardTest {

    /** A fence line: a run of three or more backticks or tildes, then the rest of the line (the info string). */
    private static final Pattern FENCE = Pattern.compile("^(`{3,}|~{3,})(.*)$");

    /** Trees with no markdown of ours, or copies of it: the same exclusions as the link guard's non-docs arm. */
    private static final Set<String> NOT_OURS = Set.of("build", "node_modules", ".git", ".gradle",
            ".gradle-isolated", ".kotlin", ".uv-cache", ".uv-tools");

    /** Sanity floor on the scan set — 196 tracked markdown files (2026-09-23). */
    private static final int MIN_FILES = 150;

    @Test
    void everyFenceInOurMarkdownPairsUp() throws IOException {
        Path root = repositoryRoot();
        List<Path> scanned = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String relative = root.relativize(file).toString();
                if (relative.endsWith(".md") && NOT_OURS.stream()
                        .noneMatch(skip -> (File.separator + relative).contains(File.separator + skip + File.separator))) {
                    scanned.add(file);
                }
            }
        }
        assertTrue(scanned.size() >= MIN_FILES, () -> "scanned only " + scanned.size() + " markdown files (floor "
                + MIN_FILES + ") — a moved tree or a widened exclusion, and a green result then proves nothing");

        List<String> unpaired = new ArrayList<>();
        for (Path file : scanned) {
            unpaired.addAll(unpairedFences(root.relativize(file).toString(), Files.readString(file)));
        }
        assertTrue(unpaired.isEmpty(), () -> "unpaired code fences — each shifts every block below it, for readers "
                + "and for the link and anchor guards alike. Close the block that was opened by accident, or delete "
                + "the stray fence:\n  " + String.join("\n  ", unpaired));
    }

    /** Does-fire proof through the method the real check calls, on the 2026-09-20 shape and its neighbours. */
    @Test
    void theScannerFiresOnAStrayFenceAndAnUnclosedBlockOnly() {
        String stray = """
                Prose that ends a paragraph.
                ValueKind classifier = model.temporalClass();
                ```

                Another paragraph, now inside a block.

                ```java
                Condition c = Dsl.fieldFilled(m.str("/Order/Reference"));
                ```
                """;
        assertEquals(List.of("t.md:7: ```java inside the block opened at line 3"), unpairedFences("t.md", stray));

        assertEquals(List.of("t.md: the block opened at line 1 never closes"),
                unpairedFences("t.md", "```java\nint x;\n"));

        String wellFormed = """
                ```java
                int x;
                ```
                ````markdown
                ```java
                nested, and content: three backticks cannot close four
                ```
                ````
                  ~~~
                  indented, in a list item
                  ~~~
                """;
        assertEquals(List.of(), unpairedFences("t.md", wellFormed));
    }

    private static List<String> unpairedFences(String name, String markdown) {
        List<String> found = new ArrayList<>();
        String opener = null;
        int openedAt = 0;
        String[] lines = markdown.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher fence = FENCE.matcher(lines[i].strip());
            if (!fence.matches()) {
                continue;
            }
            String run = fence.group(1);
            if (opener == null) {
                opener = run;
                openedAt = i + 1;
            } else if (run.charAt(0) == opener.charAt(0) && run.length() >= opener.length()) {
                if (fence.group(2).isBlank()) {
                    opener = null;
                } else {
                    found.add(name + ":" + (i + 1) + ": " + lines[i].strip() + " inside the block opened at line "
                            + openedAt);
                }
            }
        }
        if (opener != null) {
            found.add(name + ": the block opened at line " + openedAt + " never closes");
        }
        return found;
    }

    private static Path repositoryRoot() {
        Path docs = Files.isDirectory(Path.of("../docs")) ? Path.of("../docs") : Path.of("docs");
        return docs.toAbsolutePath().normalize().getParent();
    }
}
