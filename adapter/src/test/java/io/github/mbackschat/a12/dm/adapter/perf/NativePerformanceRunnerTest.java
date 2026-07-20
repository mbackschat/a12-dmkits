package io.github.mbackschat.a12.dm.adapter.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativePerformanceRunnerTest {

    @TempDir
    Path temporary;

    @Test
    void parentClockCollectsIndependentSuccessfulEnvelopes() throws Exception {
        var binary = fakeBinary("""
                #!/bin/sh
                printf '{"ok":true,"data":{"fired":[],"unsupported":[]}}'
                """);
        var model = Files.writeString(temporary.resolve("model.json"), "{}");
        var instance = Files.writeString(temporary.resolve("instance.json"), "{}");

        var report = NativePerformanceRunner.measure(
                binary, model, instance, 3, "12345678", "pinned-test-runner");

        assertEquals(3, report.get("sampleCount"));
        assertEquals("native-48-rules-500-rows-first-answer", report.get("scenario"));
        assertEquals("native-48-rules-500-rows-first-answer@1", report.get("identityLabel"));
        assertEquals(3, ((java.util.List<?>) report.get("samplesMillis")).size());
    }

    @Test
    void unsupportedEnvelopeFailsInsteadOfBecomingTimingEvidence() throws Exception {
        var binary = fakeBinary("""
                #!/bin/sh
                printf '{"ok":true,"data":{"fired":[],"unsupported":[{"name":"x"}]}}'
                """);
        var model = Files.writeString(temporary.resolve("model.json"), "{}");
        var instance = Files.writeString(temporary.resolve("instance.json"), "{}");

        assertThrows(IllegalStateException.class, () ->
                NativePerformanceRunner.measure(
                        binary, model, instance, 1, "12345678", "pinned-test-runner"));
    }

    private Path fakeBinary(String source) throws Exception {
        var binary = Files.writeString(temporary.resolve("fake-dmtool-" + System.nanoTime()), source);
        if (!binary.toFile().setExecutable(true)) {
            throw new IllegalStateException("could not mark fake native binary executable");
        }
        return binary;
    }
}
