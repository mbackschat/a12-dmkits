package io.github.mbackschat.a12.dm.adapter.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ProfileFixtureGuardTest {

    private static final List<String> FIXTURES =
            List.of("compact.json", "breadth-shaped.json", "depth-shaped.json");

    @Test
    void committedProfilesStayCurrentCanonicalAndDistinctive() throws IOException {
        ModelProfile compact = readCanonical("compact.json");
        assertEquals(
                Map.of("FIELD", 104L, "GROUP", 37L, "RULE", 117L, "COMPUTATION", 1L),
                compact.structure().elements().counts());
        assertEquals(2, compact.structure().repeatability().topology().maxDepth());
        assertEquals(
                776_657,
                compact.structure().repeatability().capacities().maxRepetitions().bounded().max());
        assertEquals(
                776_657,
                compact.rules().runtime().evaluation().combinedWork().capacity().bounded().max());
        assertEquals(1, compact.computations().dependencies().maxCascadeDepth());

        ModelProfile breadth = readCanonical("breadth-shaped.json");
        assertEquals(
                Map.of("FIELD", 2_827L, "GROUP", 1_267L, "RULE", 2_181L, "COMPUTATION", 307L),
                breadth.structure().elements().counts());
        assertEquals(3, breadth.structure().repeatability().topology().maxDepth());
        assertEquals(
                961,
                breadth.structure().repeatability().capacities().maxRepetitions().bounded().max());
        assertEquals(
                120_125,
                breadth.rules().runtime().evaluation().combinedWork().capacity().bounded().max());
        assertEquals(12, breadth.computations().dependencies().maxCascadeDepth());

        ModelProfile depth = readCanonical("depth-shaped.json");
        assertEquals(
                Map.of("FIELD", 1_726L, "GROUP", 323L, "RULE", 696L, "COMPUTATION", 851L),
                depth.structure().elements().counts());
        assertEquals(4, depth.structure().repeatability().topology().maxDepth());
        assertEquals(
                100_000,
                depth.structure().repeatability().capacities().maxRepetitions().bounded().max());
        assertEquals(
                10_000_000_000L,
                depth.rules().runtime().evaluation().combinedWork().capacity().bounded().max());
        assertEquals(16, depth.computations().dependencies().maxCascadeDepth());
    }

    @Test
    void committedProfilesCannotSlipOntoTheLegacyVersionedShape() throws IOException {
        for (String fileName : FIXTURES) {
            String source = Files.readString(fixture(fileName), StandardCharsets.UTF_8);
            String versioned = source.replaceFirst(
                    "\\{",
                    "{\n  \"profileVersion\" : 1,");

            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> ModelProfileJson.read(versioned),
                    fileName);

            assertTrue(
                    error.getMessage().contains("$.profileVersion: unknown field"),
                    () -> fileName + ": " + error.getMessage());
        }
    }

    private static ModelProfile readCanonical(String fileName) throws IOException {
        String source = Files.readString(fixture(fileName), StandardCharsets.UTF_8);
        ModelProfile profile = ModelProfileJson.read(source);
        String canonical = ModelProfileJson.write(profile);

        assertFalse(source.contains("\"profileVersion\""), fileName);
        assertEquals(source, canonical, fileName + " must stay canonical");
        assertEquals(
                profile,
                ModelProfileJson.read(canonical),
                fileName + " must survive a canonical write/read");
        return profile;
    }

    private static Path fixture(String fileName) {
        String directory = System.getProperty("profile.fixture.dir");
        if (directory == null || directory.isBlank()) {
            throw new IllegalStateException(
                    "profile.fixture.dir must point at the committed profile fixture directory");
        }
        return Path.of(directory, fileName);
    }
}
