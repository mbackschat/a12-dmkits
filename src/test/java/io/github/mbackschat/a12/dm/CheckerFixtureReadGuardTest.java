package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * A portable-checker test reaches {@code check} only through {@code checkRead}, which refuses a source it did not
 * admit, unless the call is one of the few whose subject IS an unadmitted or pre-empted model.
 *
 * <p><b>Why this exists, measured 2026-09-26.</b> A source the checker cannot admit reports no finding, so a row
 * expecting none passes on a fixture that never loaded. A mutant survived that way in
 * {@code ModelTextRepertoireTest}, and moving every call onto the admitting helper then found the same defect in
 * {@code ValueInFieldsConstantKindTest}, whose empty-string row had never loaded since it was written. Nothing a
 * test runs can see this: the rows are green either way.
 *
 * <p><b>What it scans is spelling, not type</b>: a call to {@code check(} not preceded by an identifier character, a
 * dot or {@code fun}. So {@code WorkspaceMountTest}'s own guarded {@code check} and a local helper both read as
 * calls, and the allowlist names them. An allowed call is keyed by its FILE and its CALL TEXT rather than a line,
 * so an edit elsewhere in the file does not stale it.
 *
 * <p><b>The JS arm can receive a different fixture than the JVM arm, too.</b> Kotlin/JS compiles a string literal
 * holding a LONE surrogate escape to {@code ?} (measured 2026-09-26 in the compiled test bundle), so a row about a
 * lone surrogate judges a plain character there: in {@code ModelTextRepertoireTest} two rows failed on JS only, and
 * in {@code KeyPatternTest} two rows passed on JS without ever testing their subject. A lone surrogate built at run
 * time with {@code Char(0xD83D)} survives, so any Kotlin source of a module that compiles to JS must build it that
 * way.
 */
class CheckerFixtureReadGuardTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    private static final String TREE = "consistency/src/commonTest/";

    private static final Pattern RAW_CALL = Pattern.compile("(?<![A-Za-z0-9_.])(?<!fun )check\\(");

    private static final Pattern MULTIPLATFORM_SOURCE = Pattern.compile("[^/]+/src/(common|js)[A-Za-z]*/.+\\.kt");

    /** A high half not followed by a low one, or a low half not preceded by a high one. */
    private static final Pattern LONE_SURROGATE = Pattern.compile(
            "\\\\u[dD][89abAB][0-9a-fA-F]{2}(?!\\\\u[dD][c-fC-F])"
                    + "|(?<!\\\\u[dD][89abAB][0-9a-fA-F]{2})\\\\u[dD][c-fC-F][0-9a-fA-F]{2}");

    /** Each call whose subject is a model the checker does not admit, or whose analysis A12 pre-empts. */
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "BasicSentenceTest.kt", Set.of("check(ModelSource(source))"),
            "CheckReadSupport.kt", Set.of("check(source)"),
            "ConditionBindingTest.kt", Set.of("check(model(\"FieldFilled(Amount)\", language = \"fr_FR\"))",
                    "check(model(\"FieldFilled(Amount)\", unsupported))", "check(source)"),
            "ConsistencyCheckTest.kt", Set.of("check(ModelSource(\"{ not json\"))"),
            "OutcomeAllChecksPassedTest.kt", Set.of("check(ModelSource(UNSUPPORTED_LANGUAGE))"),
            "ParityCorpusTest.kt", Set.of("check(sources[0])"),
            "ReportJsonTest.kt", Set.of("check(ModelSource(json))"));

    /** A file whose calls all reach a local {@code check} that guards its own fixtures. */
    private static final Set<String> OWN_GUARDED_CHECK = Set.of("WorkspaceMountTest.kt");

    @Test
    void everyCheckerTestReachesCheckThroughTheAdmittingHelper() {
        List<String> raw = new ArrayList<>();
        for (Path source : CHECKOUT.tracked(TREE)) {
            String relative = CHECKOUT.relative(source);
            if (relative.endsWith(".kt")) {
                raw.addAll(rawCallsIn(relative, read(source)));
            }
        }
        assertEquals(List.of(), raw,
                "a checker test calls `check` directly, so a fixture that never loads passes its empty rows. "
                        + "Call `checkRead`, or add the call here when its subject is an unadmitted model.");
    }

    /** A Kotlin source compiled to JS never spells a lone surrogate as a literal escape. */
    @Test
    void noMultiplatformSourceSpellsALoneSurrogate() {
        List<String> found = new ArrayList<>();
        for (Path source : multiplatformSources()) {
            found.addAll(loneSurrogatesIn(CHECKOUT.relative(source), read(source)));
        }
        assertEquals(List.of(), found, "a lone surrogate written as a literal reaches the JS arm as `?`; "
                + "build it at run time with Char(0x…) instead");
    }

    /** The surrogate detector on text written here: a lone half is found, a pair and a built one are not. */
    @Test
    void theSurrogateDetectorSeesALoneHalfAndNotAPair() {
        assertEquals(List.of("m/src/commonTest/X.kt:1 \\uD83D", "m/src/commonTest/X.kt:2 \\uDE00"),
                loneSurrogatesIn("m/src/commonTest/X.kt", "val a = \"see \\uD83D\"\nval b = \"\\uDE00\"\n"));
        assertEquals(List.of(), loneSurrogatesIn("m/src/commonTest/X.kt",
                "val a = \"\\uD83D\\uDE00\"\nval b = Char(0xD83D)\n"));
    }

    /** …and the surrogate scan reaches every JS-compiled module, so an empty result means clean. */
    @Test
    void theSurrogateScanReachesTheMultiplatformModules() {
        Set<String> modules = new java.util.TreeSet<>();
        multiplatformSources().forEach(p -> modules.add(CHECKOUT.relative(p).split("/")[0]));
        assertTrue(modules.containsAll(Set.of("consistency", "interpreter", "predicates")),
                "the surrogate scan reached only " + modules);
    }

    /** The detector on text written here, so this file stays clean while testing it. */
    @Test
    void theDetectorSeesARawCallAndNothingElse() {
        assertEquals(List.of("consistency/src/commonTest/kotlin/X.kt:1 check(ModelSource(json)).findings"),
                rawCallsIn(TREE + "kotlin/X.kt", "val a = check(ModelSource(json)).findings\n"));
        assertEquals(List.of(), rawCallsIn(TREE + "kotlin/X.kt",
                "val a = checkRead(ModelSource(json))\nfun check(s: ModelSource) = 1\nw.check(entry)\n"));
        assertEquals(List.of(), rawCallsIn(TREE + "kotlin/ReportJsonTest.kt", "private fun r(json: String) = check(ModelSource(json))\n"));
    }

    /** …and the scan reaches the tree, so an empty result means clean rather than a lost directory. */
    @Test
    void theScanReachesTheCheckerTests() {
        long files = CHECKOUT.tracked(TREE).stream().filter(p -> p.toString().endsWith(".kt")).count();
        assertTrue(files >= 100, "the scan reached only " + files + " checker test files");
    }

    /** Each raw call in {@code text} that no entry allows, as {@code path:line <call through the line's end>}. */
    private static List<String> rawCallsIn(String relative, String text) {
        String file = relative.substring(relative.lastIndexOf('/') + 1);
        if (OWN_GUARDED_CHECK.contains(file)) {
            return List.of();
        }
        List<String> found = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher m = RAW_CALL.matcher(lines[i]);
            while (m.find()) {
                String call = lines[i].substring(m.start()).trim();
                boolean allowed = ALLOWED.getOrDefault(file, Set.of()).stream().anyMatch(call::startsWith);
                if (!allowed) {
                    found.add(relative + ":" + (i + 1) + " " + call);
                }
            }
        }
        return found;
    }

    /** Every tracked Kotlin source in a common or JS source set, the ones Kotlin/JS compiles. */
    private static List<Path> multiplatformSources() {
        return CHECKOUT.tracked().stream()
                .filter(p -> MULTIPLATFORM_SOURCE.matcher(CHECKOUT.relative(p)).matches())
                .toList();
    }

    /** Each lone surrogate escape in {@code text}, as {@code path:line <escape>}. */
    private static List<String> loneSurrogatesIn(String relative, String text) {
        List<String> found = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher m = LONE_SURROGATE.matcher(lines[i]);
            while (m.find()) {
                found.add(relative + ":" + (i + 1) + " " + m.group());
            }
        }
        return found;
    }

    private static String read(Path source) {
        try {
            return Files.readString(source);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
