package io.github.mbackschat.a12.dm.adapter.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import io.github.mbackschat.a12.dm.adapter.capture.json.Json;
import io.github.mbackschat.a12.dm.adapter.capture.schema.Capabilities;

/**
 * The capability COMPATIBILITY FIXTURE (proposal §8): the {@code capabilities} output is a pure function
 * of the compile-time registry and must equal its committed golden byte-for-byte — so a schema, channel,
 * runner, projection, or policy mutation UNDER AN EXISTING ID fails here (a changed surface requires a new
 * id, and then a deliberate golden regeneration reviewed in the same change). Regenerate with
 * {@code ./build.sh :adapter:test --tests '*CapabilitiesTest' -Dcapture.golden.fix=true} — the fixer runs
 * in place of the guard, never in CI.
 */
class CapabilitiesTest {

    private static Path golden() {
        // The Gradle test working dir is the module dir (adapter/).
        return Path.of(System.getProperty("user.dir"))
                .resolve("src/test/resources/capture/capabilities-v1.json");
    }

    @Test
    @DisabledIfSystemProperty(named = "capture.golden.fix", matches = "true")
    void capabilitiesOutputMatchesTheCommittedGolden() {
        byte[] live = Json.canonicalBytes(Capabilities.emit());
        String committed = new String(CaptureFixtures.resourceBytes("capture/capabilities-v1.json"),
                StandardCharsets.UTF_8);
        assertEquals(committed, new String(live, StandardCharsets.UTF_8),
                "the capabilities output DRIFTED from its committed golden — an in-place mutation under an"
                        + " existing schema/projection/policy id is forbidden (mint a new id), and a deliberate"
                        + " surface change regenerates the golden with -Dcapture.golden.fix=true and reviews the"
                        + " diff in the same change");
    }

    @Test
    @EnabledIfSystemProperty(named = "capture.golden.fix", matches = "true")
    void regenerateTheGolden() throws IOException {
        Files.createDirectories(golden().getParent());
        Files.write(golden(), Json.canonicalBytes(Capabilities.emit()));
    }
}
