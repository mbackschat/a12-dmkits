package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * <b>No source file grows past its hard code-line limit.</b> The limits live in {@code scripts/source-size-limits.json},
 * which the pre-commit hook's soft warning reads too, so the two cannot disagree on a number. Production and test
 * code each have their own entry, so either can be retuned alone (owner decision, 2026-10-26).
 *
 * <p><b>Why code lines, counted by locmetry.</b> Comments here carry the load-bearing why, and a physical-line limit
 * would charge them against the file's budget. Separating code from comments needs a real lexer per
 * language, which <a href="https://github.com/mbackschat/locmetry">locmetry</a> provides, and its {@code .locmetryrc}
 * policy already decides which files are source and whether each is production or test code. A source of any other
 * purpose, such as {@code other} (the third-party Gradle wrapper scripts), is not held to a limit. The version is
 * pinned in the policy because counts are a property of the counter: another version is refused, not trusted.
 *
 * <p><b>Git decides which files are held, not locmetry.</b> Locmetry selects by ignore files, including the user's
 * global excludes, so it runs with an empty {@code HOME} and its report must account for every tracked file; a
 * tracked file it neither measured nor listed as unrecognized fails by name, so no ignore rule unguards a source.
 *
 * <p><b>Files already past the hard limit sit on a shrink-only ratchet</b>, each at its size when code lines were
 * adopted (2026-10-26, 32 files). A listed file may not grow, and its entry must be deleted once it fits, so the
 * table only empties. Adding an entry is the defect this guard exists to refuse: split the file instead.
 *
 * <p>Rides {@code leakGuardTest}: its subject is every source in the checkout, which no declared input list can name.
 */
class SourceFileSizeLimitGuardTest {

    private static final Checkout CHECKOUT = Checkout.locate();
    private static final String POLICY = "scripts/source-size-limits.json";
    private static final ObjectMapper JSON = new ObjectMapper();

    enum Kind {
        PRODUCTION, TEST;

        /** The kind of a locmetry purpose; empty for a purpose the policy holds to no limit. */
        static Optional<Kind> ofPurpose(String purpose) {
            return Arrays.stream(values()).filter(kind -> kind.purpose().equals(purpose)).findFirst();
        }

        /** The locmetry purpose this kind is, which is also its key under {@code limits} in the policy. */
        String purpose() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    record Limits(int soft, int hard) {}

    /** One source file as locmetry measured it: its checkout-relative path, its purpose, and its code lines. */
    record SourceFile(String path, String purpose, int code) {}

    /** The policy file, read once: the pinned locmetry version, the limits per kind, and the legacy ceilings. */
    record Policy(String locmetryVersion, Map<Kind, Limits> limits, Map<String, Integer> legacyCeilings) {

        static Policy read(Path file) throws IOException {
            JsonNode root = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
            Map<Kind, Limits> limits = new EnumMap<>(Kind.class);
            for (Kind kind : Kind.values()) {
                JsonNode node = root.required("limits").required(kind.purpose());
                limits.put(kind, new Limits(node.required("soft").asInt(), node.required("hard").asInt()));
            }
            Map<String, Integer> legacy = new LinkedHashMap<>();
            root.required("legacyCeilings").fields().forEachRemaining(e -> legacy.put(e.getKey(), e.getValue().asInt()));
            return new Policy(root.required("locmetry").required("version").asText(), limits, legacy);
        }
    }

    /**
     * Every breach of the policy over {@code files}. Pure, so the rule is proven on fabricated counts as well as run
     * on the estate.
     */
    static List<String> violations(Policy policy, List<SourceFile> files) {
        List<String> found = new ArrayList<>();
        files.stream().sorted(Comparator.comparing(SourceFile::path)).forEach(file -> {
            Optional<Kind> kind = Kind.ofPurpose(file.purpose());
            if (kind.isEmpty()) return;
            String path = file.path();
            int count = file.code();
            int hard = policy.limits().get(kind.get()).hard();
            Integer ceiling = policy.legacyCeilings().get(path);
            if (ceiling == null && count > hard) {
                found.add(path + " has " + count + " code lines, over the hard limit of " + hard + " for "
                        + kind.get().purpose() + " code: split it by responsibility. Never add it to legacyCeilings in "
                        + POLICY + "; that table only shrinks");
            } else if (ceiling != null && count > ceiling) {
                found.add(path + " grew to " + count + " code lines past its legacy ceiling of " + ceiling
                        + ": it is already over the hard limit of " + hard + ", so split it before adding to it");
            } else if (ceiling != null && count <= hard) {
                found.add(path + " now has " + count + " code lines, within the hard limit of " + hard
                        + ": delete its entry from legacyCeilings in " + POLICY);
            } else if (ceiling != null && count < ceiling) {
                found.add(path + " shrank to " + count + " code lines: lower its legacy ceiling in " + POLICY
                        + " from " + ceiling + " to " + count + ", so the room it freed cannot be refilled");
            }
        });
        Set<String> measured = files.stream().filter(file -> Kind.ofPurpose(file.purpose()).isPresent())
                .map(SourceFile::path).collect(Collectors.toSet());
        policy.legacyCeilings().keySet().stream().filter(path -> !measured.contains(path)).sorted()
                .forEach(path -> found.add(path + " is listed in legacyCeilings but is no longer a production or test "
                        + "source: delete its entry from " + POLICY));
        return found;
    }

    /** The source files of a full locmetry report, refusing a report another locmetry version measured. */
    static List<SourceFile> sourceFiles(JsonNode report, String pinnedVersion) {
        String measuredBy = report.required("metadata").required("locmetry_version").asText();
        if (!measuredBy.equals(pinnedVersion)) {
            throw new IllegalStateException("the estate was measured by locmetry " + measuredBy + ", but " + POLICY
                    + " pins " + pinnedVersion + ": install that version, or re-pin it and re-derive legacyCeilings");
        }
        List<SourceFile> files = new ArrayList<>();
        for (JsonNode file : report.required("files")) {
            if (file.required("kind").asText().equals("source")) {
                files.add(new SourceFile(file.required("path").asText(), file.required("purpose").asText(),
                        file.required("counts").required("code").asInt()));
            }
        }
        return files;
    }

    /** The {@code tracked} paths {@code report} neither measured nor listed as unrecognized: an ignore rule hid them. */
    static List<String> unaccounted(JsonNode report, Collection<String> tracked) {
        Set<String> seen = new HashSet<>();
        report.required("files").forEach(file -> seen.add(file.required("path").asText()));
        report.required("metadata").required("coverage").required("unrecognized").forEach(path -> seen.add(path.asText()));
        return tracked.stream().filter(path -> !seen.contains(path)).sorted().toList();
    }

    @Test
    void everySourceFitsItsHardLimitOrItsShrinkOnlyCeiling() throws IOException, InterruptedException {
        Policy policy = policy();
        List<String> found = violations(policy, Estate.measured(policy).sources());
        assertEquals(List.of(), found, () -> String.join("\n", found));
    }

    @Test
    void locmetryAccountsForEveryTrackedFile() throws IOException, InterruptedException {
        List<String> hidden = Estate.measured(policy()).unaccounted();
        assertEquals(List.of(), hidden, () -> "locmetry neither measured nor listed these tracked files, so an ignore "
                + "rule (.gitignore, .ignore, .tokeignore or .git/info/exclude) hides them from this guard; stop "
                + "ignoring them:\n" + String.join("\n", hidden));
    }

    @Test
    void aTrackedFileAReportOmitsIsNamed() throws IOException {
        JsonNode report = JSON.readTree("""
                {"metadata": {"coverage": {"unrecognized": ["LICENSE"]}}, "files": [{"path": "A.kt"}]}
                """);
        assertEquals(List.of("B.kt"), unaccounted(report, List.of("A.kt", "B.kt", "LICENSE")));
    }

    @Test
    void theHardLimitFiresOneLinePastItAndNotAtIt() throws IOException {
        Policy policy = withLegacy(policy(), Map.of());
        int production = policy.limits().get(Kind.PRODUCTION).hard();
        int test = policy.limits().get(Kind.TEST).hard();
        assertEquals(List.of(), violations(policy, List.of(
                new SourceFile("a/src/main/A.java", "production", production),
                new SourceFile("a/src/test/ATest.java", "test", test))));
        assertEquals(2, violations(policy, List.of(
                new SourceFile("a/src/main/A.java", "production", production + 1),
                new SourceFile("a/src/test/ATest.java", "test", test + 1))).size());
    }

    /** The two entries are independent, which equal committed values cannot show, so this test makes them differ. */
    @Test
    void eachKindIsHeldToItsOwnEntryAndAnUnlistedPurposeToNone() {
        Policy split = new Policy("0", Map.of(Kind.PRODUCTION, new Limits(10, 20), Kind.TEST, new Limits(30, 40)), Map.of());
        assertEquals(1, violations(split, List.of(new SourceFile("a/src/main/A.java", "production", 25))).size());
        assertEquals(List.of(), violations(split, List.of(new SourceFile("a/src/test/ATest.java", "test", 25))));
        assertEquals(1, violations(split, List.of(new SourceFile("a/src/test/ATest.java", "test", 41))).size());
        assertEquals(List.of(), violations(split, List.of(new SourceFile("docs/archived/x.sh", "other", 10_000))));
    }

    @Test
    void aLegacyEntryMayNeitherGrowNorOutliveItsReasonNorKeepFreedRoom() throws IOException {
        int hard = policy().limits().get(Kind.PRODUCTION).hard();
        int ceiling = hard + 800;
        Policy policy = withLegacy(policy(), Map.of("a/src/main/Big.java", ceiling));
        assertEquals(List.of(), violations(policy, List.of(big(ceiling))), "at its ceiling it passes");
        assertTrue(violations(policy, List.of(big(ceiling + 1))).get(0).contains("grew to"));
        assertTrue(violations(policy, List.of(big(ceiling - 100))).get(0).contains("lower its legacy ceiling"));
        assertTrue(violations(policy, List.of(big(hard))).get(0).contains("delete its entry"));
        assertTrue(violations(policy, List.of()).get(0).contains("no longer a production or test source"));
    }

    @Test
    void aReportYieldsItsSourcesCodeLinesAndAnotherLocmetryVersionIsRefused() throws IOException {
        JsonNode report = JSON.readTree("""
                {"metadata": {"locmetry_version": "1.2.3"}, "files": [
                  {"path": "A.kt", "kind": "source", "purpose": "production",
                   "counts": {"code": 7, "comments": 3, "blanks": 1, "lines": 11}},
                  {"path": "README.md", "kind": "documentation", "purpose": "production",
                   "counts": {"code": 0, "comments": 9, "blanks": 0, "lines": 9}}]}
                """);
        assertEquals(List.of(new SourceFile("A.kt", "production", 7)), sourceFiles(report, "1.2.3"));
        assertThrows(IllegalStateException.class, () -> sourceFiles(report, "1.2.4"));
    }

    /**
     * Every JVM source in a test source set, the one definition {@link Checkout#testSources()} carries, is test code
     * to locmetry too, so a later split of the two limits cannot hold a test to the production entry. Locmetry's set
     * is wider on purpose: it also counts the consumer projects and the kernel-closure consumer as test code. This also
     * proves the report saw the estate, in every language a limit matters for.
     */
    @Test
    void locmetryClassesEveryTestSourceSetAsTestCode() throws IOException, InterruptedException {
        Estate estate = Estate.measured(policy());
        Set<String> locmetryTests = estate.sources().stream().filter(file -> file.purpose().equals("test"))
                .map(SourceFile::path).collect(Collectors.toSet());
        List<String> misclassed = CHECKOUT.testSources().stream().map(CHECKOUT::relative)
                .filter(path -> !locmetryTests.contains(path)).sorted().toList();
        assertEquals(List.of(), misclassed);
        long production = estate.sources().stream().filter(file -> file.purpose().equals("production")).count();
        assertTrue(locmetryTests.size() >= 2000 && production >= 800,
                () -> "only " + locmetryTests.size() + " test and " + production + " production sources were measured "
                        + "(2,781 and 977 on 2026-10-26), so the report lost a tree");
        assertTrue(estate.languages().containsAll(List.of("Groovy", "Java", "Kotlin", "Shell", "TypeScript")),
                () -> "the report measured no source in some language this estate is written in: " + estate.languages());
    }

    private static SourceFile big(int code) {
        return new SourceFile("a/src/main/Big.java", "production", code);
    }

    private static Policy policy() throws IOException {
        return Policy.read(CHECKOUT.resolve(POLICY));
    }

    /** The real policy's version and limits over a fabricated legacy table, so the rule is proven on its numbers. */
    private static Policy withLegacy(Policy real, Map<String, Integer> legacy) {
        return new Policy(real.locmetryVersion(), real.limits(), legacy);
    }

    /** The tracked sources as the pinned locmetry measures them, read once per JVM: the guard's tests share one run. */
    record Estate(List<SourceFile> sources, List<String> unaccounted, Set<String> languages) {

        private static Estate measured;

        static synchronized Estate measured(Policy policy) throws IOException, InterruptedException {
            if (measured == null) measured = measure(policy);
            return measured;
        }

        /**
         * Runs locmetry with an empty {@code HOME}, so neither the user's locmetry defaults nor their global Git
         * excludes reach it and every machine selects alike. {@code LOCMETRY} names another binary, as the doctor's
         * check honours it.
         */
        private static Estate measure(Policy policy) throws IOException, InterruptedException {
            Path home = Files.createTempDirectory("locmetry-home");
            Path errors = home.resolve("errors.txt");
            String executable = System.getenv().getOrDefault("LOCMETRY", "locmetry");
            try {
                ProcessBuilder command = CHECKOUT.command(List.of(executable, "--no-user-config", "--files",
                        "--format", "json", "--output", "-")).redirectError(errors.toFile());
                command.environment().put("HOME", home.toString());
                command.environment().put("XDG_CONFIG_HOME", home.toString());
                Process run;
                try {
                    run = command.start();
                } catch (IOException cannotStart) {
                    throw new IllegalStateException("locmetry " + policy.locmetryVersion() + " could not be started ("
                            + cannotStart.getMessage() + "), and this guard counts code lines with it: install it with "
                            + "`brew install mbackschat/tap/locmetry`, or from the release " + POLICY + " pins", cannotStart);
                }
                byte[] output = run.getInputStream().readAllBytes();
                if (run.waitFor() != 0) {
                    throw new IllegalStateException("locmetry exited " + run.exitValue() + ": " + Files.readString(errors));
                }
                JsonNode report = JSON.readTree(output);
                List<String> tracked = CHECKOUT.tracked().stream().filter(path -> !Files.isSymbolicLink(path))
                        .map(CHECKOUT::relative).filter(path -> !path.equals(".locmetryrc")).toList();
                Set<String> trackedSet = new HashSet<>(tracked);
                Set<String> languages = new TreeSet<>();
                report.required("files").forEach(file -> {
                    if (file.required("kind").asText().equals("source")) languages.add(file.required("language").asText());
                });
                return new Estate(sourceFiles(report, policy.locmetryVersion()).stream()
                        .filter(file -> trackedSet.contains(file.path())).toList(), SourceFileSizeLimitGuardTest.unaccounted(report, tracked), languages);
            } finally {
                Files.deleteIfExists(errors);
                Files.deleteIfExists(home);
            }
        }
    }
}
