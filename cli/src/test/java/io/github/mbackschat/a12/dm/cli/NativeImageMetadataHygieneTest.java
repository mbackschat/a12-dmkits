package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

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
        var mapper = new ObjectMapper();
        try (InputStream in = NativeImageMetadataHygieneTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("native metadata resource not on classpath: " + resource);
            }
            List<String> types = new ArrayList<>();
            for (JsonNode e : mapper.readTree(in).path("reflection")) {
                JsonNode t = e.path("type");
                if (t.isTextual()) {   // `type` can also be an object (e.g. proxies) — only plain class names matter here
                    types.add(t.asText());
                }
            }
            return types;
        }
    }
}
