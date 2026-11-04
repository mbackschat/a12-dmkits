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
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

/** Guards every owned Node workflow consumer against a moving or divergent runtime version. */
class NodeRuntimePinGuardTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Pattern EXACT_PATCH = Pattern.compile("\\d+\\.\\d+\\.\\d+");
    private static final String EXPECTED_NODE_VERSION = "24.20.0";
    private static final Set<String> EXPECTED_CONSUMERS = Set.of(
            ".github/workflows/ci.yml#build",
            ".github/workflows/nightly-performance.yml#performance",
            ".github/interpreter-mirror-workflows/build-release.yml#build-release",
            ".github/mirror-workflows/build-native.yml#build-native");

    private record Setup(String consumer, String version) {}

    @Test
    void everyOwnedNodeConsumerUsesOneExactPatchVersion() throws Exception {
        List<String> workflows = trackedWorkflows();
        var setups = new ArrayList<Setup>();
        for (String relative : workflows) {
            setups.addAll(setupsIn(relative, Files.readString(CHECKOUT.resolve(relative), StandardCharsets.UTF_8)));
        }

        Set<String> consumers = setups.stream().map(Setup::consumer)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<Setup> unpinned = setups.stream().filter(setup -> !EXACT_PATCH.matcher(setup.version()).matches()).toList();
        Set<String> versions = setups.stream().map(Setup::version).collect(Collectors.toSet());
        assertAll(
                () -> assertEquals(EXPECTED_CONSUMERS, consumers,
                        "Node is load-bearing in the main TypeScript/demo lane, the A12 kernel bootstrap the nightly "
                                + "performance job runs, the interpreter mirror build, and the Linux native demo gate; each "
                                + "owning job needs an explicit setup-node step"),
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

    /** The tracked workflow files — the denominator a workflow guard must not narrow by hand. */
    private static List<String> trackedWorkflows() {
        return CHECKOUT.tracked(".github").stream()
                .map(CHECKOUT::relative)
                .filter(path -> path.endsWith(".yml") || path.endsWith(".yaml"))
                .toList();
    }

}
