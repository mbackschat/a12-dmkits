package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

/** Guards every owned Node workflow consumer against a moving or divergent runtime version. */
class NodeRuntimePinGuardTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Pattern EXACT_PATCH = Pattern.compile("\\d+\\.\\d+\\.\\d+");
    private static final String EXPECTED_NODE_VERSION = "24.20.0";
    private static final Set<String> EXPECTED_CONSUMERS = Set.of(
            ".github/workflows/ci.yml#build",
            ".github/interpreter-mirror-workflows/build-release.yml#build-release",
            ".github/mirror-workflows/build-native.yml#build-native");

    private record Setup(String consumer, String version) {}

    @Test
    void everyOwnedNodeConsumerUsesOneExactPatchVersion() throws Exception {
        Path root = repositoryRoot();
        List<String> workflows = trackedWorkflows(root);
        var setups = new ArrayList<Setup>();
        for (String relative : workflows) {
            setups.addAll(setupsIn(relative, Files.readString(root.resolve(relative), StandardCharsets.UTF_8)));
        }

        Set<String> consumers = setups.stream().map(Setup::consumer)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<Setup> unpinned = setups.stream().filter(setup -> !EXACT_PATCH.matcher(setup.version()).matches()).toList();
        Set<String> versions = setups.stream().map(Setup::version).collect(java.util.stream.Collectors.toSet());
        assertAll(
                () -> assertEquals(EXPECTED_CONSUMERS, consumers,
                        "Node is load-bearing in the main TypeScript/demo lane, interpreter mirror build, and Linux "
                                + "native demo gate; each owning job needs an explicit setup-node step"),
                () -> assertEquals(List.of(), unpinned,
                        "every setup-node declaration must name major.minor.patch; a major or minor follows the latest "
                                + "provider release instead of a reviewed repository edit"),
                () -> assertEquals(EXPECTED_CONSUMERS.size(), setups.size(),
                        "each owned Node consumer must contain exactly one setup-node declaration"),
                () -> assertEquals(Set.of(EXPECTED_NODE_VERSION), versions,
                        "owned Node consumers must use the reviewed patch named by BUILD-ENVIRONMENT and "
                                + "TESTING-SPEC; a version bump travels with both docs: " + versions));
    }

    @Test
    void theRuleRejectsMissingMovingAndDivergentSetups() throws Exception {
        assertEquals(List.of(new Setup("fixture.yml#build", "24")), setupsIn("fixture.yml", workflow("24")));
        assertFalse(EXACT_PATCH.matcher("24").matches());
        assertFalse(EXACT_PATCH.matcher("24.20").matches());
        assertEquals(List.of(new Setup("fixture.yml#build", "24.20.0")),
                setupsIn("fixture.yml", workflow("24.20.0")));
        assertEquals(List.of(), setupsIn("fixture.yml", "jobs:\n  build:\n    runs-on: ubuntu-24.04\n"));
    }

    private static String workflow(String version) {
        return """
                jobs:
                  build:
                    runs-on: ubuntu-24.04
                    steps:
                      - uses: actions/setup-node@v6
                        with:
                          node-version: '%s'
                """.formatted(version);
    }

    private static List<Setup> setupsIn(String file, String yaml) throws IOException {
        var setups = new ArrayList<Setup>();
        JsonNode jobs = YAML.readTree(yaml).path("jobs");
        if (!jobs.isObject()) {
            return setups;
        }
        for (Map.Entry<String, JsonNode> job : jobs.properties()) {
            for (JsonNode step : job.getValue().path("steps")) {
                String action = step.path("uses").asText();
                if (action.startsWith("actions/setup-node@")) {
                    setups.add(new Setup(file + "#" + job.getKey(), step.path("with").path("node-version").asText()));
                }
            }
        }
        return setups;
    }

    private static List<String> trackedWorkflows(Path root) throws IOException, InterruptedException {
        var process = new ProcessBuilder("git", "ls-files", ".github")
                .directory(root.toFile()).redirectErrorStream(true).start();
        List<String> tracked;
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            tracked = reader.lines().toList();
        }
        assertEquals(0, process.waitFor(), "git ls-files .github failed, so the workflow denominator is unknown");
        assertFalse(tracked.isEmpty(), "git ls-files .github returned nothing, so the guard would pass vacuously");
        return tracked.stream().filter(path -> path.endsWith(".yml") || path.endsWith(".yaml")).toList();
    }

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
