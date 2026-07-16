package io.github.mbackschat.a12.dm.adapter.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * DECODER PARITY (proposal §7): standalone scenario validation and capture make the SAME structural
 * acceptance decision for the same fixture — both run exactly {@code ScenarioSets.load}, and this battery
 * keeps it that way: for every fixture (the valid one + each rejection class), {@code validate-scenarios}'
 * decision equals {@code capture}'s pre-execution decision, with the SAME diagnostics.
 */
class DecoderParityTest {

    private static final Map<String, UnaryOperator<String>> FIXTURES = fixtures();

    private static Map<String, UnaryOperator<String>> fixtures() {
        Map<String, UnaryOperator<String>> fixtures = new LinkedHashMap<>();
        fixtures.put("valid", UnaryOperator.identity());
        fixtures.put("unknown member", text -> text.replace("\"operation\": \"compute\",",
                "\"operation\": \"compute\", \"surprise\": 1,"));
        fixtures.put("expected member", text -> text.replace("\"probes\": [ \"/Quote[1]/Premium\" ]",
                "\"probes\": [ \"/Quote[1]/Premium\" ], \"expected\": [ \"x\" ]"));
        fixtures.put("unknown schema version", text -> text.replace("capture-scenario-set-v1",
                "capture-scenario-set-v9"));
        fixtures.put("unknown runner", text -> text.replace("\"kernel-java-static\"", "\"kernel-js\""));
        fixtures.put("digest mismatch", text -> text.replaceFirst("\"sha256\": \"[0-9a-f]{64}\"",
                "\"sha256\": \"" + "0".repeat(64) + "\""));
        fixtures.put("placement path off-model", text -> text.replace("\"path\": \"/Quote/BaseRate\"",
                "\"path\": \"/Quote/Nope\""));
        fixtures.put("mandatory projection not predeclared", text -> CaptureFixtures.declareConformance()
                .apply(text).replaceFirst(",\\s*\"compute-projection-dmkits-portable-v1\"", ""));
        fixtures.put("required channel unavailable on a qualifying runner",
                text -> CaptureFixtures.declareConformance().apply(text)
                        .replace("\"withoutErrors\", ", "\"withoutErrors\", \"changedSubset\", "));
        return fixtures;
    }

    @Test
    void validateScenariosAndCaptureDecideIdentically(@TempDir Path work) {
        int i = 0;
        for (Map.Entry<String, UnaryOperator<String>> fixture : FIXTURES.entrySet()) {
            Path caseDir = work.resolve("f" + (i++));
            Path scenario = CaptureFixtures.writeScenario(caseDir.resolve("root"), fixture.getValue());

            CaptureCommandTest.Run validate = CaptureCommandTest.run(caseDir, CaptureCommandTest.CLEAN,
                    "validate-scenarios", "--scenarios", scenario.toString());
            CaptureCommandTest.Run capture = CaptureCommandTest.run(caseDir, CaptureCommandTest.CLEAN,
                    "capture", "--scenarios", scenario.toString(),
                    "--output", caseDir.resolve("packet").toString());

            assertEquals(validate.exit() == 0, capture.exit() == 0,
                    fixture.getKey() + ": the two commands must make the SAME acceptance decision");
            assertEquals(validate.err(), capture.err(),
                    fixture.getKey() + ": a rejection carries the same diagnostics on both commands");
        }
    }
}
