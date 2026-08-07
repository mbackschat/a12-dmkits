package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Hygiene guard for the test-suite-driven native reflection capture (docs/NATIVE-IMAGE-GRAALVM.md §3.3/§5).
 *
 * <p>The capture runs the {@code :cli} JUnit suite under the GraalVM agent, so without a caller-filter it
 * would record JUnit's reflective instantiation of the {@code *Test} classes straight into the shipped
 * metadata — exactly the noise the earlier curated capture avoided by NOT running tests. The caller filter
 * drops JUnit/Gradle-originated access; {@code :cli:generateNativeAgentAccessFilter} independently derives
 * every own compiled test-artifact target, including nested classes reached through production reflection.
 * This test LOCKS their combined output, so a future re-capture cannot silently reship test metadata.
 *
 * <p><b>Scope:</b> our own test classes ({@code io.github.mbackschat …Test}/{@code …Tests}). A few
 * ServiceLoader-loaded JUnit-infra classes and the Groovy-runtime classes the JVM capture records are left
 * as build-ignored noise (excluded from the native classpath, ignored at build), so we do not assert their
 * absence — that would force a disproportionate extra access-filter pass and would wrongly flag the correct
 * output of the caller-filter. The real regression this locks is our own {@code *Test} classes.
 */
class NativeImageMetadataHygieneTest {

    private static final String RESOURCE = "META-INF/native-image/rulekit-agent/reachability-metadata.json";

    @Test
    void shippedMetadataHasNoOwnTestClasses() throws Exception {
        List<String> offenders = testArtifactTypes(reflectionTypes(RESOURCE));
        assertTrue(offenders.isEmpty(),
                "native reflection metadata leaks own test classes — re-run scripts/capture-native-config.sh with "
                        + "the caller + derived access filters active (docs/NATIVE-IMAGE-GRAALVM.md §5). Offenders: "
                        + offenders);
    }

    /**
     * <b>A shipped resource glob must name a resource that ships.</b> Two ways it stops doing so, one of each
     * found on 2026-08-07: the file is DELETED after the capture (`schema/add.schema.json` went stale 14 hours
     * after the capture that recorded it, and no capture ran for four days), or the glob was never a product
     * resource at all (`schema/broken-ref.schema.json` is a TEST fixture that production `SpecKeys.forSchema`
     * loads on the test classpath, so the agent recorded it — the derived access filter removes test
     * <em>classes</em>, and nothing removed test <em>resources</em>).
     *
     * <p>Neither crashes a native image; both are dead weight that only a capture would ever notice, which is
     * why this is a STATIC check instead. Scoped by GLOB PREFIX — {@code schema/} and {@code catalog/} — not by
     * which artifact serves the resource, so a dependency jar shipping one of those prefixes would false-positive
     * here. None does: all 33 own-prefix globs in the committed metadata resolve inside this repository's own
     * main resource trees (measured 2026-08-07), and the prefixes are ours by construction. The roots are
     * DERIVED rather than listed, so a new module providing one of them is covered without an edit here.
     */
    @Test
    void everyOwnResourceGlobStillNamesAShippedResource() throws Exception {
        List<Path> roots = mainResourceRoots();
        List<String> offenders = new ArrayList<>();
        for (String glob : resourceGlobs(RESOURCE)) {
            if (!glob.startsWith("schema/") && !glob.startsWith("catalog/")) {
                continue;
            }
            if (roots.stream().noneMatch(root -> provides(root, glob))) {
                offenders.add(glob);
            }
        }
        assertTrue(offenders.isEmpty(),
                "shipped native metadata registers own-resource globs that no main resource tree provides — "
                        + "either the file was deleted since the capture, or it is a TEST fixture the capture "
                        + "recorded through production code. Drop them from the committed metadata (and from "
                        + "the next capture). Offenders: " + offenders);
    }

    @Test
    void shippedMetadataHasNoJavacResourcesReachedOnlyByTheStructuralTestHarness() throws Exception {
        List<String> offenders = javacResources(metadata(RESOURCE).path("resources"));
        assertTrue(offenders.isEmpty(),
                "native metadata leaks the JDK compiler used only by MutationTailSingleSourceTest; keep "
                        + "repository-structure guard tasks outside the traced product test: " + offenders);
    }

    @Test
    void javacResourceDetectorFiresWithoutRejectingRuntimeServiceProviders() throws Exception {
        JsonNode resources = new ObjectMapper().readTree("""
                [
                  {"glob":"META-INF/services/com.sun.source.util.Plugin"},
                  {"module":"jdk.compiler","glob":"com/sun/tools/javac/resources/compiler.properties"},
                  {"bundle":"com.sun.tools.javac.resources.javac"},
                  {"glob":"META-INF/services/io.github.mbackschat.a12.dm.core.spi.BackendInfo"}
                ]
                """);
        assertEquals(3, javacResources(resources).size());
    }

    /** Proves the detector actually fires (guards against a vacuous green) and spares the {@code RuleTestCommand} verb. */
    @Test
    void detectorFlagsTestClassesButNotProductionVerbs() {
        List<String> probe = List.of(
                "io.github.mbackschat.a12.dm.cli.ApplyVerbTest",      // a test class — flag
                "io.github.mbackschat.a12.dm.cli.ApplyVerbTest$1",    // its inner class — flag
                "io.github.mbackschat.a12.dm.cli.rule.RuleTestCommand",    // PRODUCTION verb (`rule eval`) — keep
                "com.mgmtp.a12.kernel.md.model.a12internal.StringType"); // kernel POJO — keep
        assertEquals(
                List.of("io.github.mbackschat.a12.dm.cli.ApplyVerbTest",
                        "io.github.mbackschat.a12.dm.cli.ApplyVerbTest$1"),
                testArtifactTypes(probe));
    }

    /** A type is one of OUR test classes iff its (outer) simple name ends in {@code Test}/{@code Tests}. */
    private static List<String> testArtifactTypes(List<String> types) {
        List<String> out = new ArrayList<>();
        for (String t : types) {
            if (!t.startsWith("io.github.mbackschat.")) {
                continue;
            }
            String outer = t.contains("$") ? t.substring(0, t.indexOf('$')) : t;
            String simple = outer.substring(outer.lastIndexOf('.') + 1);
            if (simple.endsWith("Test") || simple.endsWith("Tests")) {
                out.add(t);
            }
        }
        return out;
    }

    private static List<String> reflectionTypes(String resource) throws Exception {
        List<String> types = new ArrayList<>();
        for (JsonNode e : metadata(resource).path("reflection")) {
            JsonNode t = e.path("type");
            if (t.isTextual()) {   // `type` can also be an object (e.g. proxies) — only plain class names matter here
                types.add(t.asText());
            }
        }
        return types;
    }

    /**
     * Every module's main resource tree, derived from the repository layout. An empty derivation cannot go
     * unnoticed: it turns every own-prefix glob into an offender, so this fails loud rather than vacuously green.
     */
    private static List<Path> mainResourceRoots() throws Exception {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("settings.gradle"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("no settings.gradle above " + Path.of("").toAbsolutePath());
        }
        try (Stream<Path> modules = Files.list(dir)) {
            return modules.map(module -> module.resolve("src/main/resources")).filter(Files::isDirectory).sorted()
                    .toList();
        }
    }

    /**
     * Case-EXACT, unlike {@link Files#exists}: macOS resolves {@code schema/RESULT.schema.json} and the Linux
     * native build does not, so a wrongly-cased glob would pass here and miss where the image is actually built.
     */
    private static boolean provides(Path root, String glob) {
        Path candidate = root.resolve(glob);
        Path parent = candidate.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return false;
        }
        try (Stream<Path> siblings = Files.list(parent)) {
            return siblings.anyMatch(sibling -> sibling.getFileName().equals(candidate.getFileName()));
        } catch (IOException unreadable) {
            return false;
        }
    }

    /** Proves the resolver is case-exact and not vacuous — {@code Files.exists} answers yes to both on macOS. */
    @Test
    void resourceResolverAcceptsTheExactNameAndRejectsAMiscasedOne() throws Exception {
        List<Path> roots = mainResourceRoots();
        assertTrue(roots.stream().anyMatch(root -> provides(root, "catalog/operators.json")),
                "the operator catalog must resolve under a derived main resource root: " + roots);
        assertTrue(roots.stream().noneMatch(root -> provides(root, "catalog/OPERATORS.json")),
                "a wrongly-cased glob must not resolve — the Linux native build is case-sensitive");
    }

    private static List<String> resourceGlobs(String resource) throws Exception {
        List<String> globs = new ArrayList<>();
        for (JsonNode e : metadata(resource).path("resources")) {
            JsonNode glob = e.path("glob");
            if (glob.isTextual()) {
                globs.add(glob.asText());
            }
        }
        return globs;
    }

    private static List<String> javacResources(JsonNode resources) {
        List<String> offenders = new ArrayList<>();
        for (JsonNode resource : resources) {
            String text = resource.toString();
            if (text.contains("com.sun.source.util.Plugin")
                    || text.contains("com/sun/tools/javac/")
                    || text.contains("com.sun.tools.javac.")
                    || text.contains("\"module\":\"jdk.compiler\"")) {
                offenders.add(text);
            }
        }
        return offenders;
    }

    private static JsonNode metadata(String resource) throws Exception {
        var mapper = new ObjectMapper();
        try (InputStream in = NativeImageMetadataHygieneTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("native metadata resource not on classpath: " + resource);
            }
            return mapper.readTree(in);
        }
    }
}
