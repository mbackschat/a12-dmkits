package io.github.mbackschat.a12.dm.adapter.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import io.github.mbackschat.a12.dm.adapter.capture.json.Digests;
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

    /** The V1 FREEZE LOCK (CONFORMANCE-CORPUS-SPEC §15a): V1 froze with the external client's successful
     *  smoke run at {@code 3ebe7564} — one published capability tuple has one immutable meaning and byte
     *  identity, whether or not that run's packet was retained. The canonical capabilities bytes are
     *  pinned at the smoke-run digest, so even a deliberate golden regeneration fails CI — a changed
     *  closed shape mints a {@code -v2} (new id, new golden, new lock), never an edit under the frozen
     *  id. */
    private static final String FROZEN_V1_SHA256 =
            "b87d381e7f43446bc886292766e34beab72ca3ec179be5f0f614975e66d603ca";

    @Test
    void theFrozenV1CapabilitiesDigestHolds() {
        assertEquals(FROZEN_V1_SHA256, Digests.sha256(CaptureFixtures.resourceBytes("capture/capabilities-v1.json")),
                "capabilities-v1.json no longer matches the FROZEN V1 digest — V1 froze with the external"
                        + " client's successful smoke run at 3ebe7564 (CONFORMANCE-CORPUS-SPEC §15a); a"
                        + " changed closed shape mints a -v2, never an in-place edit under the frozen id");
    }

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
