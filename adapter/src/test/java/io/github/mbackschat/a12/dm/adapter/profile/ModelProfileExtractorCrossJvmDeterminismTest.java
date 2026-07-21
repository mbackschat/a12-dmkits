package io.github.mbackschat.a12.dm.adapter.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

/** Locks profile-extraction byte determinism across separate JVM launches. */
class ModelProfileExtractorCrossJvmDeterminismTest {

    @Test
    void theSameModelProducesByteIdenticalProfilesAcrossJvmLaunches() {
        var outputs = IntStream.range(0, 4)
                .mapToObj(ignored -> runInFreshJvm())
                .toList();

        assertEquals(
                1,
                outputs.stream().distinct().count(),
                "the same expanded model must extract to byte-identical profile JSON in every JVM");
    }

    private static String runInFreshJvm() {
        var javaBinary = System.getProperty("java.home")
                + java.io.File.separator
                + "bin"
                + java.io.File.separator
                + "java";
        var processBuilder = new ProcessBuilder(
                javaBinary,
                "-cp",
                System.getProperty("java.class.path"),
                ModelProfileExtractorDeterminismMain.class.getName());
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
            throw new IllegalStateException("fresh-JVM extraction was interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("could not run extraction in a fresh JVM", exception);
        }
    }
}
