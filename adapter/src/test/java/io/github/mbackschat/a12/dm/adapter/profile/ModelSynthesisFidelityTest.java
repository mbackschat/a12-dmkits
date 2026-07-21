package io.github.mbackschat.a12.dm.adapter.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ModelSynthesisFidelityTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> FIXTURES =
            List.of("compact.json", "breadth-shaped.json", "depth-shaped.json");
    private static final long[] SEEDS = {101, 202, 303, 401, 502, 603, 704, 805};

    @Test
    void exactGuaranteesHoldAcrossThePermanentSeedSet() throws Exception {
        Path directory = Path.of(System.getProperty("profile.fixture.dir"));
        for (String fileName : FIXTURES) {
            String target = Files.readString(directory.resolve(fileName));
            for (long seed : SEEDS) {
                String model = ModelSynthesizer.synthesize(target, seed);
                var expected = ModelProfileJson.read(target);
                var actual = ModelProfileJson.read(ModelProfiles.extract(model));
                String context = fileName + " seed " + seed;
                assertEquals(expected.structure().fieldCount(), actual.structure().fieldCount(), context);
                assertEquals(expected.structure().groupCount(), actual.structure().groupCount(), context);
                assertEquals(expected.structure().maxNestingDepth(), actual.structure().maxNestingDepth(), context);
                assertEquals(
                        expected.structure().repeatability().repeatableGroupCount(),
                        actual.structure().repeatability().repeatableGroupCount(),
                        context);
                assertEquals(
                        expected.structure().repeatability().maxRepeatableNestingDepth(),
                        actual.structure().repeatability().maxRepeatableNestingDepth(),
                        context);
                assertEquals(
                        expected.structure().repeatability().nestingDepthExact(),
                        actual.structure().repeatability().nestingDepthExact(),
                        context);
                assertEquals(expected.computations().maxCascadeDepth(), actual.computations().maxCascadeDepth(), context);
                assertEquals(
                        expected.computations().constructs().getOrDefault("GROUP_FILL", 0),
                        actual.computations().constructs().getOrDefault("GROUP_FILL", 0),
                        context);

                var comparison = JSON.readTree(ModelProfiles.compare(target, ModelProfileJson.write(actual)));
                assertTrue(comparison.path("axes").size() > 1, context);
            }
        }
    }
}
