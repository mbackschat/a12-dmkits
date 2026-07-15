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
 * caller-owned directory a consumer would supply) from the committed own-domain models under
 * {@code capture/models/}, with correct digests. Tests derive rejection fixtures by mutating the valid
 * scenario text ({@link #writeScenario(Path, UnaryOperator)}).
 */
public final class CaptureFixtures {

    public static final String QUOTE_PREMIUM = "capture/models/quote-premium.json";
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

    /** The canonical VALID self-test scenario text over {@code quote-premium.json} (digest spliced in). */
    public static String validScenarioText(String modelSha256) {
        return """
                {
                  "schema": "capture-scenario-set-v1",
                  "scenarioSet": { "id": "capture-selftest-quote-v1", "version": "1" },
                  "operationSchema": "compute-observation-v1",
                  "requiredCapabilities": { "schema": "a12-dmkits-capture-capabilities-v1", "version": "1" },
                  "world": { "profile": "local-wallclock-v1", "locale": "en_US" },
                  "models": [ { "ref": "models/quote-premium.json", "sha256": "%s" } ],
                  "requiredRunners": [ "kernel-groovy-dynamic", "kernel-java-static", "a12-dmkits-interpreter" ],
                  "qualificationPolicy": "kernel-route-confirmed-v1",
                  "observationRequirement": {
                    "id": "quote-selftest-requirement", "version": "1",
                    "requiredChannels": [ "withoutErrors", "changedSubset", "withErrors", "cleared",
                                          "formalErrorsInOperands", "appliedState" ],
                    "comparisonProjections": [ "compute-projection-kernel-route-v1" ]
                  },
                  "cases": [
                    {
                      "caseId": "premium-computes",
                      "modelRef": "models/quote-premium.json",
                      "operation": "compute",
                      "placements": [
                        { "kind": "GROUP", "path": "/Quote", "reps": [1] },
                        { "kind": "FIELD", "path": "/Quote/BaseRate", "reps": [1, 1], "value": "10.25" },
                        { "kind": "EMPTY", "path": "/Quote/Premium", "reps": [1, 1] }
                      ],
                      "probes": [ "/Quote[1]/Premium" ]
                    }
                  ]
                }
                """.formatted(modelSha256);
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

    /** As {@link #writeValidScenario}, with {@code mutate} applied to the valid scenario text first. */
    public static Path writeScenario(Path root, UnaryOperator<String> mutate) {
        String sha = placeModel(root, QUOTE_PREMIUM, "quote-premium.json");
        Path file = root.resolve("scenarios.json");
        try {
            Files.writeString(file, mutate.apply(validScenarioText(sha)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }
}
