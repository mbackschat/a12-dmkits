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
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Doc-snippet freshness lock — "approach A" (see {@code docs/TESTING.md} "Locked doc snippets").
 *
 * <p>A {@code java} code block shown to a reader in the markdown docs is hand-authored prose with no
 * compiler behind it, so it drifts silently as the API moves — a stale snippet actively misleads. This
 * guard removes the rot for the <em>canonical</em> snippets: each locked block must be byte-identical
 * (after indent/trailing-whitespace normalization) to a tagged region of a <b>real test that compiles
 * and runs against the kernel</b>. The test region is the source of truth; the doc block is a verified
 * mirror — so "the doc shows it" and "the kernel accepts it" can't diverge.
 *
 * <p>To lock a new snippet: wrap the showable lines of a passing test in {@code // tag::<id>[]} …
 * {@code // end::<id>[]}, put {@code <!-- snippet: <id> -->} immediately above the doc's {@code ```java}
 * fence, and add a {@link Lock} row here. The mechanism mirrors the CLI side's
 * {@code CliTourFreshnessTest} (compiled+run = no-rot; locked-to-source = no-drift).
 *
 * <p><b>Verification vs. sync.</b> By default this class is the <em>verifier</em> — assert-only, run by
 * {@code :test} (locally and in CI), it never edits files. The <em>sync</em> is autonomous and on demand:
 * {@code ./gradlew syncDocSnippets} re-runs this class with {@code -Dsnippets.fix=true}, which flips it to
 * the <em>fixer</em> — it rewrites every locked doc fence from its canonical test region, no hand-copying.
 * The fixer and verifier share one extraction/normalization path, so they can't disagree. CI never runs
 * the fixer, so a forgotten sync still fails the build.
 */
class DocSnippetFreshnessTest {

    /** One locked snippet: a fenced {@code ```java} block in {@code doc} mirrors a tagged region of {@code testSource}. */
    private record Lock(String id, String doc, String testSource) {}

    private static final String STUDY_SNIPPETS =
            "src/test/java/io/github/mbackschat/a12/dm/docsnippets/StudySnippetsTest.java";

    private static final List<Lock> LOCKS = List.of(
            new Lock("transform", "docs/API-POC-STUDY.md",
                    "src/test/java/io/github/mbackschat/a12/dm/rulekit/dsl/TransformConditionTest.java"),
            new Lock("nary", "docs/API-POC-STUDY.md", STUDY_SNIPPETS),
            new Lock("create-model", "docs/API-POC-STUDY.md", STUDY_SNIPPETS),
            new Lock("edit-session", "docs/API-POC-STUDY.md", STUDY_SNIPPETS),
            new Lock("catalog", "docs/API-POC-STUDY.md", STUDY_SNIPPETS),
            new Lock("navigation", "docs/API-POC-STUDY.md", STUDY_SNIPPETS),
            new Lock("navigation-annotations", "docs/API-POC-STUDY.md", STUDY_SNIPPETS),
            new Lock("study-mirroring", "docs/API-POC-STUDY.md",
                    "src/test/java/io/github/mbackschat/a12/dm/rulekit/dsl/MirroringRuleStudyTest.java"),
            new Lock("study-mirror-combinators", "docs/API-POC-STUDY.md",
                    "src/test/java/io/github/mbackschat/a12/dm/rulekit/dsl/MirroringRuleStudyTest.java"),
            new Lock("study-mirror-reuse", "docs/API-POC-STUDY.md",
                    "src/test/java/io/github/mbackschat/a12/dm/rulekit/dsl/MirroringRuleStudyTest.java"),
            new Lock("editor-number-field", "docs/API-POC-STUDY.md",
                    "src/test/java/io/github/mbackschat/a12/dm/edit/GroupEditorTest.java"));

    /** The doc whose every shipped (✅) {@code ```java} block must be classified — locked or teaser-marked. */
    private static final String STUDY = "docs/API-POC-STUDY.md";

    /**
     * Completeness guard: no shipped snippet escapes classification. Every {@code ```java} block whose
     * heading ancestry includes a ✅ section must be immediately preceded by either {@code <!-- snippet: <id> -->}
     * (locked to a run test, above) or {@code <!-- teaser -->} (acknowledged hand-maintained — elided or
     * deliberately non-compiling, e.g. §2's "what you cannot write"). This mirrors the conformance-matrix
     * discipline (every X classified), so a newly-shipped snippet can't silently dodge the lock.
     */
    @Test
    @DisabledIfSystemProperty(named = "snippets.fix", matches = "true")
    void everyShippedDocSnippetIsLockedOrTeaserMarked() throws IOException {
        List<String> lines = Files.readAllLines(repoFile(STUDY));
        String[] headingStack = new String[7];
        List<String> unclassified = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int level = headingLevel(line);
            if (level > 0) {
                headingStack[level] = line;
                for (int deeper = level + 1; deeper < headingStack.length; deeper++) {
                    headingStack[deeper] = null;
                }
            } else if (line.startsWith("```java") && shippedUnder(headingStack)) {
                String prev = precedingNonBlank(lines, i);
                if (!prev.startsWith("<!-- snippet:") && !prev.equals("<!-- teaser -->")) {
                    unclassified.add("L" + (i + 1));
                }
            }
        }
        assertTrue(unclassified.isEmpty(), () ->
                "Unclassified ```java block(s) under a ✅ section in " + STUDY + " at " + unclassified
                + " — each shipped snippet must be preceded by `<!-- snippet: <id> -->` (lock it to a run "
                + "test) or `<!-- teaser -->` (acknowledge it stays hand-maintained: elided or non-compiling).");
    }

    /** {@code #}-count of a markdown ATX heading (1–6), or 0 if {@code line} is not a heading. */
    private static int headingLevel(String line) {
        int h = 0;
        while (h < line.length() && line.charAt(h) == '#') {
            h++;
        }
        return (h >= 1 && h <= 6 && h < line.length() && line.charAt(h) == ' ') ? h : 0;
    }

    private static boolean shippedUnder(String[] headingStack) {
        for (String heading : headingStack) {
            if (heading != null && heading.contains("✅")) {
                return true;
            }
        }
        return false;
    }

    private static String precedingNonBlank(List<String> lines, int idx) {
        for (int i = idx - 1; i >= 0; i--) {
            if (!lines.get(i).isBlank()) {
                return lines.get(i).strip();
            }
        }
        return "";
    }

    @Test
    @DisabledIfSystemProperty(named = "snippets.fix", matches = "true")
    void everyLockedDocSnippetMatchesItsRunTestVerbatim() throws IOException {
        for (Lock l : LOCKS) {
            String docBlock = normalize(docFence(repoFile(l.doc()), l.id()));
            String srcBlock = normalize(taggedRegion(repoFile(l.testSource()), l.id()));
            assertEquals(srcBlock, docBlock, () ->
                    "Doc snippet '" + l.id() + "' in " + l.doc() + " has DRIFTED from its source of truth — "
                    + "the `// tag::" + l.id() + "[]` region of " + l.testSource() + " (which compiles and runs "
                    + "against the real kernel). Re-sync autonomously: `./gradlew syncDocSnippets` (rewrites the "
                    + "doc fence from the test region). The test is canonical: if the snippet itself is wrong, "
                    + "fix the test first, then sync — never hand-edit the doc to pass while the test is wrong.");
        }
    }

    /**
     * The autonomous fixer — only when {@code -Dsnippets.fix=true} (i.e. {@code ./gradlew syncDocSnippets}).
     * Rewrites every locked doc fence in place from its canonical test region; never runs in CI.
     */
    @Test
    @EnabledIfSystemProperty(named = "snippets.fix", matches = "true")
    void regenerateLockedDocSnippetsFromTheirRunTests() throws IOException {
        for (Lock l : LOCKS) {
            String region = normalize(taggedRegion(repoFile(l.testSource()), l.id()));
            rewriteDocFence(repoFile(l.doc()), l.id(), region);
        }
    }

    /** Replace the body of the first {@code ```java} fence after {@code <!-- snippet: id -->} with {@code body}. */
    private static void rewriteDocFence(Path doc, String id, String body) throws IOException {
        String text = Files.readString(doc);
        String marker = "<!-- snippet: " + id + " -->";
        int at = text.indexOf(marker);
        assertTrue(at >= 0, () -> "no marker `" + marker + "` in " + doc);
        Matcher m = Pattern.compile("```java\\R(.*?)\\R```", Pattern.DOTALL).matcher(text);
        assertTrue(m.find(at + marker.length()), () -> "no ```java fence after `" + marker + "` in " + doc);
        Files.writeString(doc, text.substring(0, m.start(1)) + body + text.substring(m.end(1)));
    }

    /** The body of the first {@code ```java} fence after {@code <!-- snippet: id -->} in {@code doc}. */
    private static String docFence(Path doc, String id) throws IOException {
        String text = Files.readString(doc);
        String marker = "<!-- snippet: " + id + " -->";
        int at = text.indexOf(marker);
        assertTrue(at >= 0, () -> "no marker `" + marker + "` in " + doc);
        Matcher m = Pattern.compile("```java\\R(.*?)\\R```", Pattern.DOTALL).matcher(text);
        assertTrue(m.find(at + marker.length()), () -> "no ```java fence after `" + marker + "` in " + doc);
        return m.group(1);
    }

    /** The lines between {@code // tag::id[]} and {@code // end::id[]} (markers excluded) in {@code src}. */
    private static String taggedRegion(Path src, String id) throws IOException {
        List<String> lines = Files.readAllLines(src);
        String begin = "// tag::" + id + "[]";
        String end = "// end::" + id + "[]";
        int b = -1;
        int e = -1;
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).strip();
            if (t.equals(begin)) {
                b = i;
            } else if (t.equals(end)) {
                e = i;
                break;
            }
        }
        final int begAt = b;
        final int endAt = e;
        assertTrue(begAt >= 0 && endAt > begAt,
                () -> "no `// tag::" + id + "[]` … `// end::" + id + "[]` region in " + src);
        return String.join("\n", lines.subList(begAt + 1, endAt));
    }

    /** Drop blank edge lines, strip the common leading indent, trim trailing whitespace — so a Java method's
     *  8-space body compares equal to a doc fence's column-0 form. */
    private static String normalize(String block) {
        List<String> lines = new ArrayList<>(List.of(block.split("\n", -1)));
        while (!lines.isEmpty() && lines.get(0).isBlank()) {
            lines.remove(0);
        }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
            lines.remove(lines.size() - 1);
        }
        int indent = lines.stream().filter(s -> !s.isBlank())
                .mapToInt(DocSnippetFreshnessTest::leadingSpaces).min().orElse(0);
        return lines.stream()
                .map(s -> s.isBlank() ? "" : stripTrailing(s.substring(indent)))
                .reduce((a, b) -> a + "\n" + b).orElse("");
    }

    private static int leadingSpaces(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    private static String stripTrailing(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    /** Locate a repo-root file regardless of the module working dir (mirrors {@code CliTourFreshnessTest}). */
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
