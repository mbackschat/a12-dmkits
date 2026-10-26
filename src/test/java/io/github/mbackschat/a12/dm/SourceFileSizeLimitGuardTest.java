package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * <b>No source file grows past its hard line limit.</b> The limits live in {@code scripts/source-size-limits.json},
 * which the pre-commit hook's soft warning reads too, so the two cannot disagree on a number: production code has
 * a soft limit of 800 and a hard limit of 1,200 physical lines, test code 600 and 1,000. Test code is held tighter
 * because this repository already rules one test file per operator family (docs/TESTING-SPEC.md, "Layout
 * conventions"), and a test file past 600 lines is almost always several families piled together.
 *
 * <p><b>Why physical lines.</b> They are what a reader scrolls and a reviewer reads. A code-only count would pass
 * a file of 3,000 lines that is mostly comment, and this repository's comments carry real reading load.
 *
 * <p><b>Files already past the hard limit sit on a shrink-only ratchet</b>, each at its size when the limit was
 * adopted (2026-10-26, 25 files). A listed file may not grow, and its entry must be deleted once it fits, so the
 * table only empties. Adding an entry is the defect this guard exists to refuse: split the file instead.
 *
 * <p>Rides {@code leakGuardTest}: its subject is every tracked source, which no declared input list can name.
 */
class SourceFileSizeLimitGuardTest {

    private static final Checkout CHECKOUT = Checkout.locate();
    private static final String POLICY = "scripts/source-size-limits.json";

    enum Kind { PRODUCTION, TEST }

    record Limits(int soft, int hard) {}

    /** The policy file, read once: which paths are test code, the limits per kind, and the legacy ceilings. */
    record Policy(Pattern testPath, Map<Kind, Limits> limits, Map<String, Integer> legacyCeilings) {

        Kind kindOf(String path) {
            return testPath.matcher(path).find() ? Kind.TEST : Kind.PRODUCTION;
        }

        static Policy read(Path file) throws IOException {
            JsonNode root = new ObjectMapper().readTree(Files.readString(file, StandardCharsets.UTF_8));
            Map<Kind, Limits> limits = new EnumMap<>(Kind.class);
            for (Kind kind : Kind.values()) {
                JsonNode node = root.required("limits").required(kind.name().toLowerCase());
                limits.put(kind, new Limits(node.required("soft").asInt(), node.required("hard").asInt()));
            }
            Map<String, Integer> legacy = new LinkedHashMap<>();
            root.required("legacyCeilings").fields().forEachRemaining(e -> legacy.put(e.getKey(), e.getValue().asInt()));
            return new Policy(Pattern.compile(root.required("testPathPattern").asText()), limits, legacy);
        }
    }

    /**
     * Every breach of the policy over {@code lines} (checkout-relative path → physical line count). Pure, so the
     * rule is proven on fabricated counts as well as run on the estate.
     */
    static List<String> violations(Policy policy, Map<String, Integer> lines) {
        List<String> found = new ArrayList<>();
        new TreeMap<>(lines).forEach((path, count) -> {
            int hard = policy.limits().get(policy.kindOf(path)).hard();
            Integer ceiling = policy.legacyCeilings().get(path);
            if (ceiling == null && count > hard) {
                found.add(path + " has " + count + " lines, over the hard limit of " + hard + " for "
                        + policy.kindOf(path).name().toLowerCase() + " code: split it by responsibility. Never "
                        + "add it to legacyCeilings in " + POLICY + "; that table only shrinks");
            } else if (ceiling != null && count > ceiling) {
                found.add(path + " grew to " + count + " lines past its legacy ceiling of " + ceiling
                        + ": it is already over the hard limit of " + hard + ", so split it before adding to it");
            } else if (ceiling != null && count <= hard) {
                found.add(path + " now has " + count + " lines, within the hard limit of " + hard
                        + ": delete its entry from legacyCeilings in " + POLICY);
            } else if (ceiling != null && count < ceiling) {
                found.add(path + " shrank to " + count + " lines: lower its legacy ceiling in " + POLICY
                        + " from " + ceiling + " to " + count + ", so the room it freed cannot be refilled");
            }
        });
        policy.legacyCeilings().keySet().stream().filter(path -> !lines.containsKey(path)).sorted()
                .forEach(path -> found.add(path + " is listed in legacyCeilings but is no longer a tracked source: "
                        + "delete its entry from " + POLICY));
        return found;
    }

    /** Physical lines: one per line terminator, plus a final line that has none. */
    static int physicalLines(String text) {
        int lines = (int) text.chars().filter(c -> c == '\n').count();
        return text.isEmpty() || text.endsWith("\n") ? lines : lines + 1;
    }

    @Test
    void everyTrackedSourceFitsItsHardLimitOrItsShrinkOnlyCeiling() throws IOException {
        List<String> found = violations(policy(), estate());
        assertEquals(List.of(), found, () -> String.join("\n", found));
    }

    @Test
    void theHardLimitFiresOneLinePastItAndNotAtIt() throws IOException {
        Policy policy = withLegacy(Map.of());
        int production = policy.limits().get(Kind.PRODUCTION).hard();
        int test = policy.limits().get(Kind.TEST).hard();
        assertEquals(List.of(), violations(policy, Map.of("a/src/main/A.java", production, "a/src/test/ATest.java", test)));
        assertEquals(2, violations(policy, Map.of("a/src/main/A.java", production + 1, "a/src/test/ATest.java", test + 1)).size());
        assertEquals(1, violations(policy, Map.of("scripts/x.test.ts", test + 1)).size(),
                "a TypeScript test is held to the test limit, not the looser production one");
    }

    @Test
    void aLegacyEntryMayNeitherGrowNorOutliveItsReasonNorKeepFreedRoom() throws IOException {
        int hard = policy().limits().get(Kind.PRODUCTION).hard();
        int ceiling = hard + 800;
        Policy policy = withLegacy(Map.of("a/src/main/Big.java", ceiling));
        assertEquals(List.of(), violations(policy, Map.of("a/src/main/Big.java", ceiling)), "at its ceiling it passes");
        assertTrue(violations(policy, Map.of("a/src/main/Big.java", ceiling + 1)).get(0).contains("grew to"));
        assertTrue(violations(policy, Map.of("a/src/main/Big.java", ceiling - 100)).get(0).contains("lower its legacy ceiling"));
        assertTrue(violations(policy, Map.of("a/src/main/Big.java", hard)).get(0).contains("delete its entry"));
        assertTrue(violations(policy, Map.of()).get(0).contains("no longer a tracked source"));
    }

    @Test
    void lineCountingCountsAnUnterminatedLastLine() {
        assertEquals(0, physicalLines(""));
        assertEquals(1, physicalLines("a\n"));
        assertEquals(2, physicalLines("a\nb"));
    }

    /**
     * The policy's test pattern names exactly the JVM sources {@link Checkout#testSources()} does, so the two
     * definitions of "test code" in this repository cannot drift apart. It also proves the walk saw the estate.
     */
    @Test
    void thePolicysTestPatternAgreesWithTheCheckoutsTestSources() throws IOException {
        Policy policy = policy();
        Set<String> checkoutTests = CHECKOUT.testSources().stream().map(CHECKOUT::relative).collect(Collectors.toSet());
        Set<String> policyTests = CHECKOUT.sources().stream().map(CHECKOUT::relative)
                .filter(path -> policy.kindOf(path) == Kind.TEST).collect(Collectors.toSet());
        assertEquals(checkoutTests, policyTests);
        Map<String, Integer> estate = estate();
        long tests = estate.keySet().stream().filter(path -> policy.kindOf(path) == Kind.TEST).count();
        assertTrue(tests >= 2000 && estate.size() - tests >= 800,
                () -> "only " + tests + " test and " + (estate.size() - tests) + " production sources were read "
                        + "(measured 2,708 and 919 on 2026-10-26), so the walk lost a tree");
    }

    private static Policy policy() throws IOException {
        return Policy.read(CHECKOUT.resolve(POLICY));
    }

    /** The real policy's pattern and limits over a fabricated legacy table, so the rule is proven on its numbers. */
    private static Policy withLegacy(Map<String, Integer> legacy) throws IOException {
        Policy real = policy();
        return new Policy(real.testPath(), real.limits(), legacy);
    }

    /** Every tracked Java, Kotlin and TypeScript source with its physical line count. */
    private static Map<String, Integer> estate() {
        List<Path> files = new ArrayList<>(CHECKOUT.sources());
        CHECKOUT.tracked().stream().filter(path -> path.getFileName().toString().endsWith(".ts")).forEach(files::add);
        Map<String, Integer> lines = new LinkedHashMap<>();
        for (Path file : files) {
            try {
                lines.put(CHECKOUT.relative(file), physicalLines(Files.readString(file, StandardCharsets.UTF_8)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return Collections.unmodifiableMap(lines);
    }
}
