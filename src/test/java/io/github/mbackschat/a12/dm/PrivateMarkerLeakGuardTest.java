package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Repository-wide leak scan: no tracked file may publish a private registry, a machine-local path, an access
 * token, or a key.
 *
 * <p><strong>Denominator.</strong> {@code git ls-files} supplies the paths and the WORKING TREE supplies the
 * content, which is what a commit from here would publish. Two things are therefore out of scope by
 * construction: a file that is not tracked yet (it is scanned once it is added) and a leak already sitting in
 * history (removing that needs a rewrite, a separate decision).
 *
 * <p><strong>Why this one is the authority.</strong> Three narrower scanners already existed —
 * {@code interpreter/build.gradle}'s release secret patterns, {@code InterpreterApiDocumentationTest}'s
 * forbidden markers, and {@code release_scan_public_tree} in {@code scripts/release-mirror-common.sh}. They
 * guard the published interpreter artifacts, i.e. a tree assembled *from* this repository, so a marker family
 * only has to be complete here for the whole chain to be covered. Sharing the pattern *strings* with them was
 * deliberately rejected: those scanners run POSIX ERE and Groovy, and Java's regex is a different language —
 * a {@code \b} that silently matches nothing in ERE is exactly the defect that produced this guard's second
 * wrong version. Each engine keeps its own expression; this scan keeps the widest coverage.
 *
 * <p><strong>Controls.</strong> Every marker names the leak shapes it must catch and legitimate strings it
 * must not, both asserted in this JVM. The scan loop has its own positive control against a planted file, and
 * the run asserts a floor on how many files it actually read, so "found nothing" cannot mean "looked at
 * nothing".
 */
class PrivateMarkerLeakGuardTest {

    private record Marker(String name, Pattern pattern, List<String> mustCatch) {}

    /**
     * Note the absent company name: all three prose leaks that reached the public repository said
     * "Artifactory", so the product name alone discriminates, and this project has no reason to name it.
     */
    private static final List<Marker> MARKERS = List.of(
            // No trailing \b — `_` is a word character, so `\bartifactory\b` missed artifactory_user= and
            // ARTIFACTORY_TOKEN, the canonical Gradle/Jenkins credential spellings.
            new Marker("private registry product name",
                    Pattern.compile("(?i)\\bartifactory"),
                    List.of("routes through the private Artifactory registry", "artifactory_user=jdoe",
                            "artifactoryUrl = 'https://example'", "ARTIFACTORY_TOKEN=abc",
                            "systemProp.artifactoryPassword=s3cret")),
            new Marker("private registry URL",
                    Pattern.compile("(?i)https?://[^\\s\"'<>]*nexus"),
                    List.of("https://nexus.example-corp.com/repository/maven",
                            "url 'https://build-nexus.example-corp.net/repo'")),
            // Case-sensitive and TLD-terminated on purpose: `(?i)` plus a bare `[a-z]{2,}` tail made this
            // match Gradle's own `artifacts.component.ModuleComponentIdentifier` and `artifacts.artifacts
            // .collect`. Hostnames are lowercase, so requiring a real TLD is what separates one from a Java
            // package path. A public host that still matches is allowlisted below with its reason.
            new Marker("private registry hostname",
                    Pattern.compile("\\bartifacts\\.[a-z0-9-]+(?:\\.[a-z0-9-]+)*"
                            + "\\.(?:com|net|org|io|dev|de|eu|ch|at|co|cloud|local)\\b"),
                    List.of("https://artifacts.example-corp.com/maven-repos",
                            "artifacts.intern.example-corp.de", "artifacts.example-corp.cloud",
                            // A private host carrying an allowlisted public one as its prefix. Blanking the
                            // allowlisted text instead of filtering matches used to hide exactly this.
                            "artifacts.spring.io.build.example-corp.de/repo")),
            // The lookbehind is load-bearing: without it the prose "the workflow/home/task chain" matched.
            new Marker("machine-local path",
                    Pattern.compile("(?<![A-Za-z0-9_-])(?:/Users/|/home/)[A-Za-z0-9_-]+"
                            + "|[A-Za-z]:\\\\Users\\\\[A-Za-z0-9_-]+"),
                    List.of("/Users/jdoe/Projects/thing", "/home/jdoe/work", "file:///Users/jdoe/x",
                            "C:\\Users\\jdoe\\src")),
            // `_` is excluded on purpose: real tokens are base62 after the prefix, while npm_package_* env
            // names are not, and admitting `_` flagged npm_package_dependencies_typescript.
            new Marker("access token",
                    Pattern.compile("(?i)(?:gh[pousr]|npm)_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}"),
                    List.of("credential: ghp_0123456789abcdefghijklmnopqrst",
                            "github_pat_11ABCDEFG0123456789_abcdefghij")),
            new Marker("private key",
                    Pattern.compile("-----BEGIN [A-Z0-9 ]*PRIVATE KEY"),
                    List.of("-----BEGIN OPENSSH PRIVATE KEY-----", "-----BEGIN PRIVATE KEY-----",
                            "-----BEGIN DSA PRIVATE KEY-----", "-----BEGIN ENCRYPTED PRIVATE KEY-----",
                            "-----BEGIN PGP PRIVATE KEY BLOCK-----")));

    /** Strings this repository legitimately contains, each of which a wrong marker has already flagged. */
    private static final List<String> MUST_NOT_CATCH = List.of(
            "org.gradle.api.artifacts.component.ModuleComponentIdentifier",
            "def artifacts = configuration.incoming.artifactView {}.artifacts.artifacts.collect { artifact ->",
            "npm_package_dependencies_typescript",
            "npm_config_cache_lockfile_stale_timeout_seconds",
            "path: ~/.m2/repository/com/mgmtp",
            "Please contact a12-license@mgm-tp.com for more information.",
            "A personal exploration, not affiliated with or endorsed by mgm",
            "reported `/Users/.../person.dm.json` there",
            "`GateTierWiringTest` locks the workflow/home/task chain",
            "resolved from https://artifacts.spring.io/release");

    /**
     * Public hosts whose shape matches a marker. Each entry is a reasoned exception, not a silencer: adding
     * one is a one-line change that says which host and why.
     */
    private static final Map<String, String> PUBLIC_HOSTS = Map.of(
            "artifacts.spring.io", "Spring's public artifact repository");

    /**
     * Per-marker exemptions. A file appears here only for the marker it exists to carry, never blanket: a
     * detector that stops carrying its marker is a dead exemption, which
     * {@link #everyExemptionStillCarriesTheMarkerItClaims} turns into a failure.
     */
    private static final Map<String, Set<String>> EXEMPT = exemptions();

    private static Map<String, Set<String>> exemptions() {
        var exempt = new LinkedHashMap<String, Set<String>>();
        // The three narrower scanners named in the class doc: each must spell the product name to detect it.
        exempt.put("interpreter/build.gradle", Set.of("private registry product name"));
        exempt.put("interpreter/src/jvmTest/kotlin/io/github/mbackschat/a12/dm/interpreter/"
                + "InterpreterApiDocumentationTest.kt", Set.of("private registry product name"));
        exempt.put("interpreter/consumer-tests/browser/scripts/offline.ts",
                Set.of("private registry product name"));
        // The mirror assembler's own leak fixture is a placeholder home directory.
        exempt.put("src/releaseGuardTest/java/io/github/mbackschat/a12/dm/"
                + "InterpreterReleaseMirrorAssemblerTest.java", Set.of("machine-local path"));
        // This guard carries a fixture for every marker; that is what proves the markers still work.
        exempt.put("src/test/java/io/github/mbackschat/a12/dm/PrivateMarkerLeakGuardTest.java",
                MARKERS.stream().map(Marker::name).collect(java.util.stream.Collectors.toUnmodifiableSet()));
        return Map.copyOf(exempt);
    }

    @Test
    void noTrackedFilePublishesAPrivateMarker() throws Exception {
        Path root = repositoryRoot();
        List<String> tracked = trackedFiles(root);

        var scan = scan(root, tracked);

        assertTrue(scan.violations().isEmpty(),
                "tracked files would publish private markers — scrub them before this can be pushed:\n"
                        + String.join("\n", scan.violations()));
        assertTrue(scan.filesRead() >= tracked.size() * 9 / 10,
                "the scan read only " + scan.filesRead() + " of " + tracked.size() + " tracked files, so a"
                        + " clean result would mean it looked at almost nothing rather than found nothing");
    }

    /**
     * The scan LOOP's positive control. The marker fixtures prove the patterns match strings; only this proves
     * a violating FILE is reported, so an inverted match or a misplaced skip cannot pass unnoticed.
     */
    @Test
    void theScanReportsAViolatingFile(@TempDir Path sandbox) throws Exception {
        Files.writeString(sandbox.resolve("leaky.md"), "url = https://artifacts.example-corp.com/repo\n");
        Files.writeString(sandbox.resolve("clean.md"), "Nothing private lives in this file.\n");

        var scan = scan(sandbox, List.of("leaky.md", "clean.md"));

        assertEquals(List.of("leaky.md: private registry hostname"), scan.violations());
        assertEquals(2, scan.filesRead());
    }

    @Test
    void everyMarkerCatchesTheLeakShapeItExistsFor() {
        var missed = new ArrayList<String>();
        for (Marker marker : MARKERS) {
            assertFalse(marker.mustCatch().isEmpty(),
                    marker.name() + " names no leak shape, so nothing proves it still works");
            marker.mustCatch().stream()
                    .filter(fixture -> !marker.pattern().matcher(fixture).find())
                    .forEach(fixture -> missed.add(marker.name() + " no longer catches: " + fixture));
        }
        assertTrue(missed.isEmpty(), "a marker stopped detecting its leak shape:\n" + String.join("\n", missed));
    }

    @Test
    void noMarkerFlagsAStringThisRepositoryLegitimatelyContains() {
        var falsePositives = new ArrayList<String>();
        for (String legitimate : MUST_NOT_CATCH) {
            // Through the same helper the scan uses, so the allowlist is exercised rather than bypassed.
            MARKERS.stream()
                    .filter(marker -> matchesBeyondPublicHosts(marker, legitimate))
                    .forEach(marker -> falsePositives.add(marker.name() + " would fail the build on: " + legitimate));
        }
        assertTrue(falsePositives.isEmpty(),
                "a marker would reject legitimate content:\n" + String.join("\n", falsePositives));
    }

    @Test
    void everyExemptionStillCarriesTheMarkerItClaims() throws IOException {
        Path root = repositoryRoot();
        var stale = new ArrayList<String>();

        for (var entry : EXEMPT.entrySet()) {
            Path file = root.resolve(entry.getKey());
            if (!Files.isRegularFile(file)) {
                stale.add(entry.getKey() + ": the file is gone, so this exemption covers nothing");
                continue;
            }
            String text = Files.readString(file);
            for (String markerName : entry.getValue()) {
                Marker marker = MARKERS.stream().filter(m -> m.name().equals(markerName)).findFirst()
                        .orElseThrow(() -> new IllegalStateException("unknown marker exempted: " + markerName));
                if (!marker.pattern().matcher(text).find()) {
                    stale.add(entry.getKey() + ": no longer carries \"" + markerName
                            + "\", so exempting it only widens the blind spot");
                }
            }
        }

        assertTrue(stale.isEmpty(), "the exemption list drifted:\n" + String.join("\n", stale));
    }

    private record Scan(List<String> violations, int filesRead) {}

    private static Scan scan(Path root, List<String> relativePaths) throws IOException {
        var violations = new ArrayList<String>();
        int filesRead = 0;

        for (String relative : relativePaths) {
            String text = readTextOrNull(root.resolve(relative));
            if (text == null) {
                continue;
            }
            filesRead++;
            Set<String> exempt = EXEMPT.getOrDefault(relative, Set.of());
            for (Marker marker : MARKERS) {
                if (exempt.contains(marker.name())) {
                    continue;
                }
                if (matchesBeyondPublicHosts(marker, text)) {
                    violations.add(relative + ": " + marker.name());
                }
            }
        }
        return new Scan(List.copyOf(violations), filesRead);
    }

    /**
     * Ignores a hostname match that IS an allowlisted public host. Filtering the matches rather than blanking
     * the text matters twice over: blanking hid `artifacts.spring.io.build.example-corp.de` (a private host
     * carrying a public one as a prefix), and it could splice two harmless fragments into a false positive.
     * The allowlist also applies to this marker only, never to tokens, keys or machine-local paths.
     */
    private static boolean matchesBeyondPublicHosts(Marker marker, String text) {
        var matcher = marker.pattern().matcher(text);
        while (matcher.find()) {
            if (!marker.name().equals("private registry hostname") || !PUBLIC_HOSTS.containsKey(matcher.group())) {
                return true;
            }
        }
        return false;
    }

    /** {@code git ls-files} is the published set. Failing loudly beats scanning nothing and passing. */
    private static List<String> trackedFiles(Path root) throws IOException, InterruptedException {
        var builder = new ProcessBuilder("git", "ls-files").directory(root.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();

        List<String> lines;
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            lines = reader.lines().toList();
        }

        assertEquals(0, process.waitFor(),
                "git ls-files failed, so the scanned set is unknown rather than clean: " + lines);
        assertFalse(lines.isEmpty(),
                "git ls-files returned nothing, so this guard would scan an empty set and pass vacuously");
        return lines;
    }

    /** Null for a binary blob or a tracked path with no working-tree file (a submodule, a pending delete). */
    private static String readTextOrNull(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        byte[] bytes = Files.readAllBytes(file);
        int probe = Math.min(bytes.length, 8_000);
        for (int index = 0; index < probe; index++) {
            if (bytes[index] == 0) {
                return null;
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** The repository root is the one directory carrying both the settings script and the build wrapper. */
    private static Path repositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            if (Files.isRegularFile(directory.resolve("settings.gradle"))
                    && Files.isRegularFile(directory.resolve("build.sh"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("could not locate the repository root from " + Path.of("").toAbsolutePath());
    }
}
