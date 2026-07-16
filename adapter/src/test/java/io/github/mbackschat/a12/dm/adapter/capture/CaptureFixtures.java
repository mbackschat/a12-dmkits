package io.github.mbackschat.a12.dm.adapter.capture;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.UnaryOperator;

import io.github.mbackschat.a12.dm.adapter.capture.json.Digests;

/**
 * Shared fixture plumbing for the capture self-tests: builds a scenario ROOT in a test temp dir (the
 * caller-owned directory a consumer would supply). The canonical VALID scenario is the committed
 * consumer-facing authoring example {@code adapter/capture-examples/scenario-example/} (exposed on the
 * test classpath as a resource root) — ONE source for the published example, the executed fixture, and
 * the mutation harness's scratch scenario, so the example can never rot unexecuted
 * ({@code ScenarioExampleTest} guards the digest + real-command acceptance). Tests derive rejection
 * fixtures by mutating the example text ({@link #writeScenario(Path, UnaryOperator)}).
 */
public final class CaptureFixtures {

    public static final String SCENARIO_EXAMPLE = "scenario-example/scenarios.json";
    public static final String QUOTE_PREMIUM = "scenario-example/models/quote-premium.json";
    public static final String QUOTE_LINES_REP = "capture/models/quote-lines-rep.json";

    private CaptureFixtures() {
    }

    public static byte[] resourceBytes(String classpathLocation) {
        try (InputStream in = CaptureFixtures.class.getClassLoader().getResourceAsStream(classpathLocation)) {
            if (in == null) {
                throw new IllegalArgumentException("test resource not found: " + classpathLocation);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Copy the model resource into {@code root/models/<fileName>} and return its SHA-256. */
    public static String placeModel(Path root, String resource, String fileName) {
        try {
            byte[] bytes = resourceBytes(resource);
            Path models = root.resolve("models");
            Files.createDirectories(models);
            Files.write(models.resolve(fileName), bytes);
            return Digests.sha256(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The canonical VALID scenario text — the committed authoring example's exact bytes (its declared
     *  model digest is the real one; {@code ScenarioExampleTest} keeps it fresh). */
    public static String validScenarioText() {
        return new String(resourceBytes(SCENARIO_EXAMPLE), StandardCharsets.UTF_8);
    }

    /** Build a complete valid scenario root in {@code root}; returns the scenario file path. */
    public static Path writeValidScenario(Path root) {
        return writeScenario(root, UnaryOperator.identity());
    }

    /** The THREE-case variant (reorder/filter equivalence, determinism): the base case plus an
     *  alternative-value case and an empty-source case, all over {@code quote-premium.json}. */
    public static Path writeThreeCaseScenario(Path root) {
        String extraCases = """
                {
                      "caseId": "premium-alt-rate",
                      "modelRef": "models/quote-premium.json",
                      "operation": "compute",
                      "placements": [
                        { "kind": "GROUP", "path": "/Quote", "reps": [1] },
                        { "kind": "FIELD", "path": "/Quote/BaseRate", "reps": [1, 1], "value": "4.00" }
                      ],
                      "probes": [ "/Quote[1]/Premium" ]
                    },
                    {
                      "caseId": "premium-source-absent",
                      "modelRef": "models/quote-premium.json",
                      "operation": "compute",
                      "placements": [
                        { "kind": "GROUP", "path": "/Quote", "reps": [1] }
                      ],
                      "probes": [ "/Quote[1]/Premium" ]
                    },""";
        return writeScenario(root, text -> {
            String injected = text.replace("\"cases\": [", "\"cases\": [\n    " + extraCases);
            if (injected.equals(text)) {
                throw new IllegalStateException("three-case injection needle did not match the scenario text");
            }
            return injected;
        });
    }

    /** The conformance-DECLARING variant of the valid scenario: policy
     *  {@code dmkits-projected-conformance-v1} with {@code requiredChannels} limited to what the
     *  interpreter's registry fidelity exposes (the qualifying-runner channel gate refuses more,
     *  pre-execution, by design). */
    public static UnaryOperator<String> declareConformance() {
        return text -> text
                .replace("\"kernel-route-confirmed-v1\"", "\"dmkits-projected-conformance-v1\"")
                .replace("\"changedSubset\", ", "")
                .replace("\"formalErrorsInOperands\", ", "");
    }

    /** The characterization-DECLARING variant of the valid scenario. */
    public static UnaryOperator<String> declareCharacterization() {
        return text -> text.replace("\"kernel-route-confirmed-v1\"", "\"characterization-v1\"");
    }

    /** As {@link #writeValidScenario}, with {@code mutate} applied to the valid scenario text first. */
    public static Path writeScenario(Path root, UnaryOperator<String> mutate) {
        String sha = placeModel(root, QUOTE_PREMIUM, "quote-premium.json");
        String text = validScenarioText();
        if (!text.contains(sha)) {
            throw new IllegalStateException("the committed authoring example's declared model digest no"
                    + " longer matches models/quote-premium.json — refresh scenario-example/scenarios.json");
        }
        Path file = root.resolve("scenarios.json");
        try {
            Files.writeString(file, mutate.apply(text), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }
}
