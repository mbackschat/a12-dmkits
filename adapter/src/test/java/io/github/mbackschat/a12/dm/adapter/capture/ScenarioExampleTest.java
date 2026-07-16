package io.github.mbackschat.a12.dm.adapter.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.adapter.capture.json.Digests;

/**
 * The consumer-facing authoring example ({@code adapter/capture-examples/scenario-example/} — a complete,
 * runnable scenario root) stays EXECUTED, never prose: its declared model digest must match the committed
 * model bytes, and the REAL {@code validate-scenarios} command must accept it. The example doubles as the
 * tests' canonical valid fixture (one source — it cannot drift from what the tool actually accepts).
 */
class ScenarioExampleTest {

    @Test
    void theCommittedExampleDeclaresTheRealModelDigest() {
        String declared = CaptureFixtures.validScenarioText();
        String actual = Digests.sha256(CaptureFixtures.resourceBytes(CaptureFixtures.QUOTE_PREMIUM));
        assertTrue(declared.contains("\"sha256\": \"" + actual + "\""),
                "scenario-example/scenarios.json must declare the exact digest of its committed model ("
                        + actual + ")");
    }

    @Test
    void theRealCommandAcceptsTheCommittedExample(@TempDir Path work) {
        Path scenario = CaptureFixtures.writeValidScenario(work.resolve("root"));
        CaptureCommandTest.Run run = CaptureCommandTest.run(work, CaptureCommandTest.CLEAN,
                "validate-scenarios", "--scenarios", scenario.toString());
        assertEquals(0, run.exit(), run::err);
        assertTrue(run.out().contains("ACCEPTED"), run::out);
    }
}
