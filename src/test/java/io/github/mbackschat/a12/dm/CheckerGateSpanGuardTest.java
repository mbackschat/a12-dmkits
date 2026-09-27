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
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Every finding a family builds where it states a POSITIONED code carries a span, or its file says why not.
 *
 * <p><b>The defect this exists to stop, measured 2026-09-25.</b> {@code Check.kt} orders a positioned class
 * against the rest of its text only when the finding carries its span; one without stands beside every later
 * placed class and is withheld behind any bad name wherever it sits. When the
 * operand gates were first given their spans, 48 of the 56 families stating a positioned code published none,
 * because a family's own tests build its findings directly and never meet the report assembly that places
 * them. Nothing went red; the kernel rows that caught it were written for a different question.
 *
 * <p><b>What is read.</b> The positioned codes are {@code Check.kt}'s own set, the files stating one are
 * {@link CheckerReportedCodes}'s, and in each file every {@code Finding(…)} construction must pass a sixth
 * argument or a named {@code span} — unless its code is a literal that is not positioned, which a family
 * stating both kinds writes for the other kind. The span's VALUE is not judged here: which production A12
 * raises a gate from is the laws tier's to measure, and each family's KDoc names it.
 *
 * <p><b>What it does not see.</b> A finding built in another file than the one stating its code, and a
 * {@code Finding(…)} in a file stating a positioned code that is itself about something else — each such file
 * is named below with its reason.
 */
class CheckerGateSpanGuardTest {

    private static final Path SOURCES =
            Path.of("consistency/src/commonMain/kotlin/io/github/mbackschat/a12/dm/consistency");

    private static final Pattern CODE = Pattern.compile("\"(MVK_[A-Z0-9_]+)\"");

    /** A construction, not the declaration: the data class itself is `data class Finding(`. */
    private static final Pattern CONSTRUCTION = Pattern.compile("(?<![\\w.])Finding\\(");

    /** Files stating a positioned code whose findings need no span, each with why. */
    private static final Map<String, String> OUTSIDE_THE_TEXTS_ORDER = Map.of(
            "SuppressedWarnings.kt",
            "the suppression preamble's classes, which A12 raises ahead of the texts and report assembly never "
                    + "places among them (withoutTheTextsBehindARefusedPreamble)");

    /** R3a states syntax codes in the verdict and constructs their findings in the carrier adapter. */
    private static final Map<String, String> FINDING_ADAPTERS = Map.of(
            "syntax/ConditionShape.kt", "ConditionSyntax.kt");

    @Test
    void everyFindingStatingAPositionedCodeCarriesItsSpan() {
        Set<String> positioned = positionedCodes();
        Map<String, Set<String>> reported = CheckerReportedCodes.bySource(positioned::contains);
        Set<String> files = new TreeSet<>();
        reported.values().forEach(sources -> sources.forEach(s -> files.add(SOURCES.relativize(Path.of(s)).toString())));
        assertTrue(files.size() >= 40, "the scan found only " + files.size() + " files stating a positioned code");
        FINDING_ADAPTERS.forEach((verdict, adapter) -> {
            assertTrue(files.contains(verdict), "the finding adapter's code source disappeared: " + verdict);
            files.add(adapter);
        });

        Map<String, String> constants = codeConstants();
        Map<String, List<Integer>> spanless = new TreeMap<>();
        for (String file : files) {
            if (OUTSIDE_THE_TEXTS_ORDER.containsKey(file)) {
                continue;
            }
            String text = withoutComments(read(SOURCES.resolve(file)));
            List<Integer> lines = spanlessConstructions(text, positioned, constants);
            if (!lines.isEmpty()) {
                spanless.put(file, lines);
            }
        }
        assertEquals(Map.of(), spanless, "these files state a positioned code and build a Finding with no span "
                + "(file → line), so report assembly cannot place it and it stands beside the class A12 names; "
                + "pass the span of the production A12 raises it from (GateSpans.kt), or name the file here");
    }

    /**
     * The positive control: a five-argument construction is caught whether its code is a positioned literal, a
     * constant holding one, or a variable, and six arguments, a named span, or a code that is not positioned —
     * written out or held in a constant — are not.
     */
    @Test
    void theArgumentCountSeesASpanlessConstruction() {
        String source = """
                val a = Finding(category, severity, "MVK_X", explain, at)
                val b = Finding(category, severity, "MVK_X", explain, at, call.spanIn(member))
                val c = Finding(category, severity, code = "MVK_X", explain = e(f, g), at = at, span = s)
                val d = Finding(category, severity, code, explain, at)
                val e = Finding(category, severity, "NO_BASE_YEAR", explain, at)
                val f = Finding(category, severity, CK_Y, explain, at)
                val g = Finding(category, severity, MVK_X_CONSTANT, explain, at)
                data class Finding(val category: String)
                """;
        Map<String, String> constants = Map.of("CK_Y", "CK_Y", "MVK_X_CONSTANT", "MVK_X");
        assertEquals(List.of(1, 4, 7), spanlessConstructions(source, Set.of("MVK_X"), constants));
    }

    @Test
    void quotedCommentMarkersLeaveConstructionsVisibleAtTheirOriginalLines() {
        String source = """
                val url = "https://example.invalid"; val a = Finding(category, severity, code, explain, at)
                val begin = "/*"; val b = Finding(category, severity, code, explain, at); val end = "*/"
                // Finding(category, severity, code, explain, at)
                /* outer
                  /* nested */ Finding(category, severity, code, explain, at)
                */ val c = Finding(category, severity, code, explain, at)
                """;
        assertEquals(List.of(1, 2, 6), spanlessConstructions(withoutComments(source), Set.of("MVK_X"), Map.of()));
    }

    private static List<Integer> spanlessConstructions(
            String text, Set<String> positioned, Map<String, String> constants) {
        List<Integer> lines = new ArrayList<>();
        Matcher construction = CONSTRUCTION.matcher(text);
        while (construction.find()) {
            if (text.substring(0, construction.start()).endsWith("data class ")) {
                continue;
            }
            List<String> arguments = argumentsFrom(text, construction.end());
            boolean spanned = arguments.size() >= 6
                    || arguments.stream().anyMatch(a -> a.strip().startsWith("span ="));
            if (!spanned && !aKnownCodeOutsideTheOrder(arguments, positioned, constants)) {
                lines.add(1 + (int) text.substring(0, construction.start()).chars().filter(c -> c == '\n').count());
            }
        }
        return lines;
    }

    /**
     * Whether the code argument, third or named, is a code that is not positioned: written out as a string
     * literal, or held in a {@code const val} of the checker's.
     */
    private static boolean aKnownCodeOutsideTheOrder(
            List<String> arguments, Set<String> positioned, Map<String, String> constants) {
        String code = arguments.stream().map(String::strip).filter(a -> a.startsWith("code ="))
                .map(a -> a.substring("code =".length()).strip())
                .findFirst().orElse(arguments.size() > 2 ? arguments.get(2).strip() : "");
        Matcher literal = Pattern.compile("\"([A-Z0-9_]+)\"").matcher(code);
        String value = literal.matches() ? literal.group(1) : constants.get(code);
        return value != null && !positioned.contains(value);
    }

    /** Every {@code const val} holding a code, by name, across the checker's sources. */
    private static Map<String, String> codeConstants() {
        Pattern constant = Pattern.compile("const val ([A-Z][A-Z0-9_]*) = \"([A-Z][A-Z0-9_]*)\"");
        Map<String, String> constants = new TreeMap<>();
        try (var sources = Files.walk(SOURCES)) {
            sources.filter(p -> p.toString().endsWith(".kt")).forEach(p -> {
                Matcher m = constant.matcher(read(p));
                while (m.find()) {
                    constants.put(m.group(1), m.group(2));
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertTrue(constants.containsKey("CK_MODEL_NOT_BUILDABLE"), "the code constants no longer parse");
        return constants;
    }

    /** The top-level comma-separated arguments of the call whose `(` ends just before [from]. */
    private static List<String> argumentsFrom(String text, int from) {
        List<String> arguments = new ArrayList<>();
        int depth = 0;
        int start = from;
        boolean inString = false;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' && text.charAt(i - 1) != '\\') {
                inString = !inString;
            }
            if (inString) {
                continue;
            }
            if (c == '(' || c == '{' || c == '[') {
                depth++;
            } else if ((c == ')' || c == '}' || c == ']') && depth > 0) {
                depth--;
            } else if (c == ')') {
                arguments.add(text.substring(start, i));
                break;
            } else if (c == ',' && depth == 0) {
                arguments.add(text.substring(start, i));
                start = i + 1;
            }
        }
        return arguments.stream().filter(a -> !a.isBlank()).toList();
    }

    private static Set<String> positionedCodes() {
        String source = read(SOURCES.resolve("Check.kt"));
        int from = source.indexOf("private val POSITIONED_CONDITION_CODES = setOf(");
        assertTrue(from >= 0, "Check.kt no longer declares POSITIONED_CONDITION_CODES");
        Matcher code = CODE.matcher(source.substring(from, source.indexOf("\n)", from)));
        Set<String> codes = new TreeSet<>();
        while (code.find()) {
            codes.add(code.group(1));
        }
        assertTrue(codes.size() > 100, "POSITIONED_CONDITION_CODES no longer parses: " + codes);
        return codes;
    }

    /** Comments blanked, not removed, so a line number still points where it did. */
    private static String withoutComments(String text) {
        return SourceText.uncommented(text, true);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
