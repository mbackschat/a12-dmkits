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
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Repository-wide leak scan: no git-tracked file may publish a private registry, an access token, or a key.
 *
 * <p>The denominator is {@code git ls-files}, because that is exactly the set a push serves. Four occurrences
 * of a private registry reached the public repository before this guard existed, and all four were found by
 * hand, after the fact. Grepping catches what someone remembers to grep for; this runs on every build.
 *
 * <p>The markers carry their own controls. Each one names the leak shapes it must catch and the legitimate
 * strings it must not, and both sets are asserted in this JVM's regex engine. That is deliberate: an earlier
 * version of these patterns was validated with {@code git grep}, whose POSIX engine does not support
 * {@code \b}, so a marker that matched nothing looked like a marker that found nothing.
 */
class PrivateMarkerLeakGuardTest {

    private record Marker(String name, Pattern pattern, List<String> mustCatch) {}

    /**
     * Note what is absent: the company name. All three prose leaks said "Artifactory", so the product name
     * alone discriminates, and this project has no legitimate reason to name that product. A marker built
     * from the company name would instead have to be excluded from ~1900 legitimate lines.
     */
    private static final List<Marker> MARKERS = List.of(
            // No trailing \b: `_` is a word character, so `\bartifactory\b` missed artifactory_user=,
            // artifactoryUrl and ARTIFACTORY_TOKEN — the canonical Gradle/Jenkins credential spellings, and
            // the likeliest way a credential would actually leak from here.
            new Marker("private registry product name",
                    Pattern.compile("(?i)\\bartifactory"),
                    List.of("routes through the private Artifactory registry",
                            "can't resolve plugins from the company Artifactory; GRAALVM_HOME feeds the rest",
                            "artifactory_user=jdoe", "artifactoryUrl = 'https://example'",
                            "ARTIFACTORY_TOKEN=abc", "systemProp.artifactoryPassword=s3cret")),
            new Marker("private registry hostname",
                    Pattern.compile("(?i)\\b(?:artifacts|artifactory)\\.[a-z0-9-]+\\.(?:com|net|org|io|dev|de|eu)\\b"),
                    List.of("https://artifacts.example-corp.com/artifactory/maven-repos",
                            "request.url().includes(\"artifacts.example-corp.de\")")),
            new Marker("access token",
                    Pattern.compile("(?i)(?:gh[pousr]|github_pat|npm)_[A-Za-z0-9_]{20,}"),
                    List.of("credential: ghp_" + "0123456789abcdefghijklmnopqrst")),
            new Marker("private key",
                    Pattern.compile("-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
                    List.of("-----BEGIN OPENSSH PRIVATE KEY-----", "-----BEGIN PRIVATE KEY-----")));

    /**
     * Strings this repository legitimately contains. The first two are the reason the first draft of the
     * hostname marker was wrong: it matched Gradle's own {@code org.gradle.api.artifacts.component} package
     * on nine lines while missing three of the four real leaks.
     */
    private static final List<String> MUST_NOT_CATCH = List.of(
            "org.gradle.api.artifacts.component.ModuleComponentIdentifier",
            "def artifacts = configuration.incoming.artifactView {}.artifacts.artifacts.collect { artifact ->",
            "path: ~/.m2/repository/com/mgmtp",
            "Please contact a12-license@mgm-tp.com for more information.",
            "A personal exploration, not affiliated with or endorsed by mgm",
            "Not an official A12 / mgm artifact.");

    /**
     * Files exempt from the scan because they ARE the detectors: each must contain a marker to do its job.
     * The exemption is narrow and self-checking — {@link #everyAllowlistedDetectorStillExistsAndCarriesAMarker}
     * fails if one of these stops carrying a marker, so a stale entry cannot quietly become a blanket hole.
     */
    private static final Set<String> DETECTORS = Set.of(
            "interpreter/build.gradle",
            "interpreter/src/jvmTest/kotlin/io/github/mbackschat/a12/dm/interpreter/InterpreterApiDocumentationTest.kt",
            "interpreter/consumer-tests/browser/scripts/offline.ts",
            "src/test/java/io/github/mbackschat/a12/dm/PrivateMarkerLeakGuardTest.java");

    @Test
    void noTrackedFilePublishesAPrivateRegistryTokenOrKey() throws Exception {
        Path root = repositoryRoot();
        var violations = new ArrayList<String>();

        for (String relative : trackedFiles(root)) {
            if (DETECTORS.contains(relative)) {
                continue;
            }
            String text = readTextOrNull(root.resolve(relative));
            if (text == null) {
                continue;
            }
            for (Marker marker : MARKERS) {
                if (marker.pattern().matcher(text).find()) {
                    violations.add(relative + ": " + marker.name());
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "tracked files would publish private markers — scrub them before this can be pushed:\n"
                        + String.join("\n", violations));
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
            MARKERS.stream()
                    .filter(marker -> marker.pattern().matcher(legitimate).find())
                    .forEach(marker -> falsePositives.add(marker.name() + " would fail the build on: " + legitimate));
        }
        assertTrue(falsePositives.isEmpty(),
                "a marker would reject legitimate content:\n" + String.join("\n", falsePositives));
    }

    @Test
    void everyAllowlistedDetectorStillExistsAndCarriesAMarker() throws IOException {
        Path root = repositoryRoot();
        var stale = new ArrayList<String>();

        for (String detector : DETECTORS) {
            Path file = root.resolve(detector);
            if (!Files.isRegularFile(file)) {
                stale.add(detector + ": the file is gone, so this entry now exempts nothing");
                continue;
            }
            String text = Files.readString(file);
            if (MARKERS.stream().noneMatch(marker -> marker.pattern().matcher(text).find())) {
                stale.add(detector + ": carries no marker, so exempting it only widens the blind spot");
            }
        }

        assertTrue(stale.isEmpty(), "the detector allowlist drifted:\n" + String.join("\n", stale));
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
