package io.github.mbackschat.a12.dm.adapter.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class ProfileFixtureGuardTest {

    private static final List<String> FIXTURES =
            List.of("compact.json", "breadth-shaped.json", "depth-shaped.json");

    @Test
    void committedPrProfilesRemainReadableAndNontrivial() throws Exception {
        Path directory = Path.of(System.getProperty("profile.fixture.dir"));
        for (String fileName : FIXTURES) {
            var profile = ModelProfileJson.read(Files.readString(directory.resolve(fileName)));
            assertEquals(profile, ModelProfileJson.read(ModelProfileJson.write(profile)), fileName);
            assertTrue(profile.structure().fieldCount() > 0, fileName);
            assertTrue(profile.structure().groupCount() > 0, fileName);
            assertTrue(profile.rules().count() + profile.computations().count() > 0, fileName);
        }
    }
}
