package io.github.mbackschat.a12.dm.adapter.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

/** Locks seeded synthesis determinism across separate JVM launches. */
class ModelSynthesizerCrossJvmDeterminismTest {

    @Test
    void tiedMultiKindHistogramsProduceByteIdenticalModelsAcrossJvmLaunches() {
        var profile = ModelSynthesizerDeterminismMain.profile();
        assertEquals(
                Map.of("NUMBER", 2L, "STRING", 2L),
                profile.structure().fields().kinds().counts());
        assertEquals(
                Map.of("NUMBER", 1L, "STRING", 1L),
                profile.computations().shape().computedFieldKinds().counts());

        long seed = 20260720L;
        var outputs = IntStream.range(0, 4)
                .mapToObj(ignored -> runInFreshJvm(seed))
                .toList();

        assertEquals(
                1,
                outputs.stream().distinct().count(),
                "the same current profile and seed must synthesize byte-identical JSON in every JVM");
    }

    private static String runInFreshJvm(long seed) {
        var javaBinary = System.getProperty("java.home")
                + java.io.File.separator
                + "bin"
                + java.io.File.separator
                + "java";
        var processBuilder = new ProcessBuilder(
                javaBinary,
                "-cp",
                System.getProperty("java.class.path"),
                ModelSynthesizerDeterminismMain.class.getName(),
                Long.toString(seed));
        try {
            var process = processBuilder.start();
            var stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            var stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IllegalStateException("child JVM exited " + exit + ":\n" + stderr);
            }
            return stdout;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("fresh-JVM synthesis was interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("could not run synthesis in a fresh JVM", exception);
        }
    }
}
