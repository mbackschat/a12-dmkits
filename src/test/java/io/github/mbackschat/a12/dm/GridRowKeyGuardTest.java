package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * No grid may name two of its rows the same, because the second row then silently replaces the first.
 *
 * <p><b>Why this exists, measured 2026-09-13.</b> This estate states a kernel law as a grid: a map from a
 * row's prose name to its measured verdict, built row by row, compared whole. A repeated row name collapses
 * <em>both</em> maps identically, so the test still passes — while measuring fewer rows than it reads as.
 * Nothing else can catch that: the assertion compares the two survivors and agrees, the count is never
 * stated, and the lost row leaves no trace in the source beyond the duplicate name itself.
 *
 * <p>It had already happened twice. {@code StarredNonRepeatableReachLawsTest} gave two controls on two
 * different carriers one name, so the nested carrier's control was overwritten by the root-level one and a
 * grid promising "each starred row carries its unstarred control" kept that promise for one row less than it
 * claimed. And a grid written while measuring the condition-withholding order used one name for four paired
 * rows, which reported three measurements as one — caught only because the surviving value was surprising.
 *
 * <p><b>Only a fully literal key counts.</b> An interpolated name ({@code "$type, body present"}) or one
 * built by concatenation differs per row at runtime, so a repeated occurrence in the source is the normal
 * shape of a loop and not a collapse. That exclusion is what keeps this guard quiet enough to survive: of
 * nine textually repeated keys in the estate when it was written, eight were exactly that.
 */
class GridRowKeyGuardTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    /** A test method, cut at the annotation — enough to scope a grid, which never spans two of them. */
    private static final Pattern METHOD = Pattern.compile("\\n    @Test\\b");

    /**
     * A row put into a named collection under a COMPLETE string literal.
     *
     * <p>The trailing delimiter is what makes the literal complete: {@code "ends with " + token} leaves a
     * {@code +} where this needs a {@code ,}, so a concatenated name never matches.
     */
    private static final Pattern ROW = Pattern.compile(
            "(\\w+)(?:\\.put\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*,|\\[\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*\\]\\s*=)");

    /**
     * A local declared as a Map — the only receiver whose repeated key is a collapsed ROW.
     *
     * <p>Without this the scan reads {@code node.put("type", "Rule")} on two Jackson nodes as a collapsed
     * grid: every fixture builder in the estate writes the same JSON member twice, which is nine false
     * positives and none of them anything. A grid is a map, so require the declaration.
     */
    private static final Pattern MAP_LOCAL = Pattern.compile(
            "\\bMap\\s*<[^>]*>\\s+(\\w+)\\s*="
                    + "|\\b(?:val|var)\\s+(\\w+)\\s*(?::\\s*(?:Mutable)?Map<[^>]*>\\s*)?"
                    + "=\\s*(?:mutableMapOf|linkedMapOf|LinkedHashMap|HashMap|TreeMap)");

    @Test
    void noGridNamesTwoRowsTheSame() {
        Map<String, List<String>> collapsed = new LinkedHashMap<>();
        for (Path source : CHECKOUT.testSources()) {
            String text = read(source);
            String[] methods = METHOD.split(text);
            for (int index = 1; index < methods.length; index++) {
                repeatedRowsIn(methods[index]).forEach(row ->
                        collapsed.computeIfAbsent(CHECKOUT.relative(source), key -> new ArrayList<>()).add(row));
            }
        }
        assertEquals(Map.of(), collapsed,
                "a grid names two rows the same, so the second silently REPLACES the first and the test "
                        + "compares fewer rows than it reads as — both maps collapse together, so nothing "
                        + "goes red. Give each row its own name (what distinguishes it is usually the "
                        + "carrier or the position the row varies).");
    }

    /**
     * …and the scan reaches the estate's grids, so an empty result means clean rather than broken.
     *
     * <p>The floor answers "did the scan happen", not "how many grids exist", so it sits far below the live
     * count on purpose.
     */
    @Test
    void theScanReachesTheEstatesGrids() {
        long withRows = CHECKOUT.testSources().stream()
                .filter(source -> {
                    String text = read(source);
                    return MAP_LOCAL.matcher(text).find() && ROW.matcher(text).find();
                })
                .count();
        assertTrue(withRows >= 100, "the scan found grids in only " + withRows + " test sources");
    }

    /** The row names a single test method puts twice into one MAP local, under a literal name. */
    private static List<String> repeatedRowsIn(String method) {
        java.util.Set<String> grids = new java.util.HashSet<>();
        Matcher declared = MAP_LOCAL.matcher(method);
        while (declared.find()) {
            grids.add(declared.group(1) != null ? declared.group(1) : declared.group(2));
        }
        if (grids.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> seen = new LinkedHashMap<>();
        Matcher matcher = ROW.matcher(method);
        while (matcher.find()) {
            String name = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            // An interpolated name differs per row at runtime, so its repetition in the source is a loop.
            if (name.contains("$") || !grids.contains(matcher.group(1))) {
                continue;
            }
            seen.merge(matcher.group(1) + " ← \"" + name + "\"", 1, Integer::sum);
        }
        return seen.entrySet().stream().filter(row -> row.getValue() > 1).map(Map.Entry::getKey).toList();
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + path, unreadable);
        }
    }
}
