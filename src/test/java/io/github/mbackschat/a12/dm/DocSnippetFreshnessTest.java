package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    private static final Checkout CHECKOUT = Checkout.locate();

    private static final Pattern SNIPPET_MARKER = Pattern.compile("<!-- snippet: ([a-z0-9-]+) -->");

    /** One locked snippet: a fenced {@code ```java} block in {@code doc} mirrors a tagged region of {@code testSource}. */
    private record Lock(String id, String doc, String testSource) {}

    private static final String STUDY_SNIPPETS =
            "src/test/java/io/github/mbackschat/a12/dm/docsnippets/StudySnippetsTest.java";

    private static final String CASE_STUDY =
            "adapter/src/test/java/io/github/mbackschat/a12/dm/adapter/CaseStudyTest.java";

    private static final String NEW_SHAPES =
            "src/test/java/io/github/mbackschat/a12/dm/docsnippets/NewShapesSnippetsTest.java";

    private static final String BULK_WRITE =
            "src/test/java/io/github/mbackschat/a12/dm/rulekit/dsl/BulkWritePathTest.java";

    private static final String PROFILE_FACADE =
            "adapter/src/test/java/io/github/mbackschat/a12/dm/adapter/profile/ModelProfilesTest.java";

    private static final String PROFILE_COMPARE =
            "adapter/src/test/java/io/github/mbackschat/a12/dm/adapter/profile/ModelProfilesCompareTest.java";

    private static final List<Lock> LOCKS = List.of(
            new Lock("cs-bind", "docs/API-GUIDE.md", CASE_STUDY),
            new Lock("cs-author", "docs/API-GUIDE.md", CASE_STUDY),
            new Lock("cs-edit", "docs/API-GUIDE.md", CASE_STUDY),
            new Lock("cs-run", "docs/API-GUIDE.md", CASE_STUDY),
            new Lock("transform", "docs/API-GUIDE.md",
                    "src/test/java/io/github/mbackschat/a12/dm/rulekit/dsl/TransformConditionTest.java"),
            new Lock("nary", "docs/API-GUIDE.md", STUDY_SNIPPETS),
            new Lock("create-model", "docs/API-GUIDE.md", STUDY_SNIPPETS),
            new Lock("edit-session", "docs/API-GUIDE.md", STUDY_SNIPPETS),
            new Lock("catalog", "docs/API-GUIDE.md", STUDY_SNIPPETS),
            new Lock("navigation", "docs/API-GUIDE.md", STUDY_SNIPPETS),
            new Lock("navigation-annotations", "docs/API-GUIDE.md", STUDY_SNIPPETS),
            new Lock("study-fragments", "docs/API-GUIDE.md", STUDY_SNIPPETS),
            new Lock("computation", "docs/API-GUIDE.md", STUDY_SNIPPETS),
            new Lock("computation-group", "docs/API-GUIDE.md", NEW_SHAPES),
            new Lock("pattern-instantiate", "docs/API-GUIDE.md", STUDY_SNIPPETS),
            new Lock("study-mirroring", "docs/API-GUIDE.md",
                    "src/test/java/io/github/mbackschat/a12/dm/rulekit/dsl/MirroringRuleStudyTest.java"),
            new Lock("study-mirror-combinators", "docs/API-GUIDE.md",
                    "src/test/java/io/github/mbackschat/a12/dm/rulekit/dsl/MirroringRuleStudyTest.java"),
            new Lock("study-mirror-reuse", "docs/API-GUIDE.md",
                    "src/test/java/io/github/mbackschat/a12/dm/rulekit/dsl/MirroringRuleStudyTest.java"),
            new Lock("study-mirror-relative", "docs/API-GUIDE.md",
                    "src/test/java/io/github/mbackschat/a12/dm/rulekit/dsl/MirroringRuleStudyTest.java"),
            new Lock("editor-number-field", "docs/API-GUIDE.md",
                    "src/test/java/io/github/mbackschat/a12/dm/edit/GroupEditorTest.java"),
            new Lock("model-computations", "docs/API-GUIDE.md",
                    "src/test/java/io/github/mbackschat/a12/dm/core/ModelIndexComputationsTest.java"),
            new Lock("date-minmax", "docs/API-GUIDE.md", NEW_SHAPES),
            new Lock("temporal-cross-ordering", "docs/API-GUIDE.md", NEW_SHAPES),
            new Lock("temporal-declared-format", "docs/API-GUIDE.md", NEW_SHAPES),
            new Lock("untyped-temporal-gate", "docs/API-GUIDE.md",
                    "src/test/java/io/github/mbackschat/a12/dm/rulekit/dsl/UntypedTemporalExtractionGateTest.java"),
            new Lock("date-precision-gate", "docs/API-GUIDE.md",
                    "src/test/java/io/github/mbackschat/a12/dm/rulekit/dsl/DatePrecisionExtractionGateTest.java"),
            new Lock("date-range-equality", "docs/API-GUIDE.md", NEW_SHAPES),
            new Lock("first-filled-owner-entities", "docs/API-GUIDE.md", NEW_SHAPES),
            new Lock("date-before-1900", "docs/API-GUIDE.md",
                    "adapter/src/test/java/io/github/mbackschat/a12/dm/adapter/FieldConfigExtrasTest.java"),
            new Lock("date-range-year-interpretation", "docs/API-GUIDE.md",
                    "adapter/src/test/java/io/github/mbackschat/a12/dm/adapter/FieldConfigExtrasTest.java"),
            new Lock("field-rounding", "docs/API-GUIDE.md", NEW_SHAPES),
            new Lock("category-number-coercion", "docs/API-GUIDE.md",
                    "adapter/src/test/java/io/github/mbackschat/a12/dm/adapter/FieldValueAsNumberCategoryReadTest.java"),
            new Lock("category-value-expression", "docs/API-GUIDE.md",
                    "adapter/src/test/java/io/github/mbackschat/a12/dm/adapter/EnumCategoryValueExpressionReadTest.java"),
            new Lock("indexed-presence", "docs/API-GUIDE.md", NEW_SHAPES),
            new Lock("indexed-range", "docs/API-GUIDE.md", NEW_SHAPES),
            new Lock("indexed-coercion", "docs/API-GUIDE.md", NEW_SHAPES),
            new Lock("rule-group", "docs/API-GUIDE.md", NEW_SHAPES),
            new Lock("starred-value-list", "docs/API-GUIDE.md", NEW_SHAPES),
            new Lock("group-scope-value-list", "docs/API-GUIDE.md", NEW_SHAPES),
            new Lock("mixed-entity-list", "docs/API-GUIDE.md", NEW_SHAPES),
            new Lock("number-of-value-single-list", "docs/API-GUIDE.md", NEW_SHAPES),
            new Lock("enum-value-list-union", "docs/API-GUIDE.md", NEW_SHAPES),
            new Lock("group-count-quantifiers", "docs/API-GUIDE.md",
                    "adapter/src/test/java/io/github/mbackschat/a12/dm/adapter/WildcardGroupFillQuantifierReadTest.java"),
            new Lock("group-list-entity-operands", "docs/API-GUIDE.md",
                    "adapter/src/test/java/io/github/mbackschat/a12/dm/adapter/GroupListEntityOperandReadTest.java"),
            new Lock("computation-suppress", "docs/API-GUIDE.md", NEW_SHAPES),
            new Lock("model-diff", "docs/API-GUIDE.md",
                    "adapter/src/test/java/io/github/mbackschat/a12/dm/adapter/review/ModelDiffComputationTest.java"),
            new Lock("authored-state", "docs/API-GUIDE.md",
                    "src/test/java/io/github/mbackschat/a12/dm/modelkit/ModelDocumentAuthoredFieldRuleStateTest.java"),
            new Lock("authored-references", "docs/API-GUIDE.md",
                    "src/test/java/io/github/mbackschat/a12/dm/modelkit/ModelDocumentAuthoredReferenceStateTest.java"),
            new Lock("bulk-program-write", "docs/API-GUIDE.md", BULK_WRITE),
            new Lock("condition-language-write", "docs/API-GUIDE.md",
                    "src/test/java/io/github/mbackschat/a12/dm/rulekit/dsl/ConditionLanguageWriteTest.java"),
            new Lock("profile-extract", "docs/API-GUIDE.md", PROFILE_FACADE),
            new Lock("profile-compare", "docs/API-GUIDE.md", PROFILE_COMPARE));

    /** The guide whose every {@code ```java} block must be classified — locked or teaser-marked. */
    private static final String STUDY = "docs/API-GUIDE.md";

    /**
     * Completeness guard: no snippet escapes classification. <b>Every</b> {@code ```java} block in the guide
     * must be immediately preceded by either {@code <!-- snippet: <id> -->} (locked to a run test, above) or
     * {@code <!-- teaser -->} (acknowledged hand-maintained — elided or deliberately non-compiling, e.g. §2's
     * "what you cannot write"). Independent of heading status markers (the guide's user-facing titles carry no
     * ✅/🔮 — the project no longer tracks per-feature status; {@code API-FINDINGS.md} holds design rationale), so a new snippet can't dodge the lock.
     */
    @Test
    @DisabledIfSystemProperty(named = "snippets.fix", matches = "true")
    void everyDocSnippetIsLockedOrTeaserMarked() throws IOException {
        List<String> lines = Files.readAllLines(CHECKOUT.requireFile(STUDY));
        List<String> unclassified = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("```java")) {
                String prev = precedingNonBlank(lines, i);
                if (!isSnippetMarker(prev) && !prev.equals("<!-- teaser -->")) {
                    unclassified.add("L" + (i + 1));
                }
            }
        }
        assertTrue(unclassified.isEmpty(), () ->
                "Unclassified ```java block(s) in " + STUDY + " at " + unclassified
                + " — each must be preceded by `<!-- snippet: <id> -->` (lock it to a run test) or "
                + "`<!-- teaser -->` (acknowledge it stays hand-maintained: elided or non-compiling).");
    }

    @Test
    @DisabledIfSystemProperty(named = "snippets.fix", matches = "true")
    void everySnippetMarkerHasExactlyOneLock() throws IOException {
        Set<String> marked = new HashSet<>();
        Matcher matcher = SNIPPET_MARKER.matcher(Files.readString(CHECKOUT.requireFile(STUDY)));
        while (matcher.find()) {
            assertTrue(marked.add(matcher.group(1)),
                    () -> "duplicate snippet marker '" + matcher.group(1) + "' in " + STUDY);
        }

        List<String> registered = LOCKS.stream()
                .filter(lock -> lock.doc().equals(STUDY))
                .map(Lock::id)
                .toList();
        Set<String> locked = new HashSet<>(registered);
        assertEquals(registered.size(), locked.size(), "duplicate lock id in DocSnippetFreshnessTest");
        assertEquals(marked, locked,
                "every `<!-- snippet: id -->` marker must have exactly one Lock row, and no Lock may be stale");
    }

    @Test
    void snippetMarkerGrammarIsExact() {
        assertTrue(isSnippetMarker("<!-- snippet: exact-id -->"));
        assertTrue(!isSnippetMarker("<!-- snippet: exact-id EXTRA -->"),
                "a malformed predecessor must not classify an unlocked Java fence as a snippet");
    }

    private static String precedingNonBlank(List<String> lines, int idx) {
        for (int i = idx - 1; i >= 0; i--) {
            if (!lines.get(i).isBlank()) {
                return lines.get(i).strip();
            }
        }
        return "";
    }

    private static boolean isSnippetMarker(String line) {
        return SNIPPET_MARKER.matcher(line).matches();
    }

    @Test
    @DisabledIfSystemProperty(named = "snippets.fix", matches = "true")
    void everyLockedDocSnippetMatchesItsRunTestVerbatim() throws IOException {
        for (Lock l : LOCKS) {
            String docBlock = normalize(docFence(CHECKOUT.requireFile(l.doc()), l.id()));
            String srcBlock = normalize(taggedRegion(CHECKOUT.requireFile(l.testSource()), l.id()));
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
            String region = normalize(taggedRegion(CHECKOUT.requireFile(l.testSource()), l.id()));
            rewriteDocFence(CHECKOUT.requireFile(l.doc()), l.id(), region);
        }
    }

    /** A fenced {@code ```java} block; group 1 is the body between the fence lines. */
    private static final Pattern JAVA_FENCE = Pattern.compile("```java\\R(.*?)\\R```", Pattern.DOTALL);

    /**
     * A located snippet body: the whole document text plus the half-open range the body occupies. Both the read
     * and the in-place rewrite go through {@link #locateFence}, so the verifier and the fixer cannot disagree
     * about <em>which bytes a snippet is</em> — the same reason they already share one normalization path.
     */
    private record Fence(String text, int from, int to) {
        String body() {
            return text.substring(from, to);
        }

        String withBody(String replacement) {
            return text.substring(0, from) + replacement + text.substring(to);
        }
    }

    /** Locate the first {@code ```java} fence after {@code <!-- snippet: id -->} in {@code doc}. */
    private static Fence locateFence(Path doc, String id) throws IOException {
        String text = Files.readString(doc);
        String marker = "<!-- snippet: " + id + " -->";
        int at = text.indexOf(marker);
        assertTrue(at >= 0, () -> "no marker `" + marker + "` in " + doc);
        Matcher m = JAVA_FENCE.matcher(text);
        assertTrue(m.find(at + marker.length()), () -> "no ```java fence after `" + marker + "` in " + doc);
        return new Fence(text, m.start(1), m.end(1));
    }

    /** Replace the body of the first {@code ```java} fence after {@code <!-- snippet: id -->} with {@code body}. */
    private static void rewriteDocFence(Path doc, String id, String body) throws IOException {
        Files.writeString(doc, locateFence(doc, id).withBody(body));
    }

    /** The body of the first {@code ```java} fence after {@code <!-- snippet: id -->} in {@code doc}. */
    private static String docFence(Path doc, String id) throws IOException {
        return locateFence(doc, id).body();
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

    /** Locate a repo-root file regardless of the module working dir (mirrors {@code CliTourFreshnessTest}). */}
