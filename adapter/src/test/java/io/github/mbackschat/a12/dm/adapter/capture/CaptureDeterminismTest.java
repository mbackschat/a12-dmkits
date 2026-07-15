package io.github.mbackschat.a12.dm.adapter.capture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * DETERMINISM (proposal §2/§7): two executions at the same source, runner, kernel artifact, model, input,
 * and world produce BYTE-IDENTICAL packet contents — no timestamps, no host metadata, canonical ordering
 * throughout. The double-capture runs the REAL command twice over the three-case scenario and compares
 * every artifact byte-for-byte.
 */
class CaptureDeterminismTest {

    @Test
    void doubleCaptureIsByteIdentical(@TempDir Path work) throws IOException {
        Path scenario = CaptureFixtures.writeThreeCaseScenario(work.resolve("root"));
        Path first = work.resolve("packet-1");
        Path second = work.resolve("packet-2");
        assertEquals(0, CaptureCommandTest.run(work, CaptureCommandTest.CLEAN,
                "capture", "--scenarios", scenario.toString(), "--output", first.toString()).exit());
        assertEquals(0, CaptureCommandTest.run(work, CaptureCommandTest.CLEAN,
                "capture", "--scenarios", scenario.toString(), "--output", second.toString()).exit());

        List<Path> firstFiles = filesUnder(first);
        List<Path> secondFiles = filesUnder(second);
        assertEquals(
                firstFiles.stream().map(first::relativize).toList(),
                secondFiles.stream().map(second::relativize).toList(),
                "identical inventories");
        for (Path file : firstFiles) {
            Path relative = first.relativize(file);
            assertArrayEquals(Files.readAllBytes(file), Files.readAllBytes(second.resolve(relative)),
                    "byte drift in " + relative);
        }
    }

    private static List<Path> filesUnder(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        }
    }
}
