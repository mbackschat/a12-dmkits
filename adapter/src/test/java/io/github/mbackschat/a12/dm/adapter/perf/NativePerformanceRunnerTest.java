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
        assertEquals(3, ((java.util.List<?>) report.get("unattributedCpuUtilisation")).size(),
                "every sample carries a demand slot, present or null, so a reader can tell which ones reported");
    }

    /**
     * The contention verdict, on scripted readings — the only way to check it, since the live one is the
     * machine's and a fake binary returns instantly.
     *
     * <p>The measured arms behind the floor: unloaded 0.184–0.412 across two control arms, four busy spinners
     * 0.883–0.928, sixteen 0.993–0.998 (2026-09-20, the real binary, 8 samples per arm).
     */
    @Test
    void contentionIsCalledOnAMajorityOfTheSamplesThatREPORTED() {
        var quiet = NativePerformanceRunner.contentionSummary(java.util.Arrays.asList(0.412, null, 0.184, null));
        assertEquals(2, quiet.get("demandReadings"));
        assertEquals(0L, quiet.get("contendedSamples"));
        assertEquals(false, quiet.get("contended"), "the worst measured QUIET reading must not read as contention");

        var busy = NativePerformanceRunner.contentionSummary(java.util.Arrays.asList(0.883, 0.928, null));
        assertEquals(2, busy.get("demandReadings"));
        assertEquals(2L, busy.get("contendedSamples"));
        assertEquals(true, busy.get("contended"), "the best measured CONTAMINATED reading must read as contention");
    }

    /**
     * One contended reading among several quiet ones does not condemn the run.
     *
     * <p>A burst of foreign load lands in one window, and this lane produces so few readings that a single-sample
     * rule would flag a run on one burst. The majority rule is what makes the verdict about the run rather than
     * about a moment — and the count travels with it so a reader can see how thin the evidence is.
     */
    @Test
    void oneContendedReadingAmongQuietOnesDoesNotCondemnTheRun() {
        var mixed = NativePerformanceRunner.contentionSummary(java.util.Arrays.asList(0.95, 0.20, 0.25));

        assertEquals(3, mixed.get("demandReadings"));
        assertEquals(1L, mixed.get("contendedSamples"));
        assertEquals(false, mixed.get("contended"));
    }

    /**
     * No readings at all is reported as nothing observed, never as a quiet machine.
     *
     * <p>Measured 2026-09-20: 3 to 6 of every 8 samples on this lane produce no usable reading, because the
     * parent spends the window blocked on the child. An all-null run is therefore a real outcome, and calling it
     * quiet would be the absence-versus-zero error one layer up from the field itself.
     */
    @Test
    void noReadingsAtAllIsNotEvidenceOfAQuietMachine() {
        var blind = NativePerformanceRunner.contentionSummary(java.util.Arrays.asList(null, null));

        assertEquals(0, blind.get("demandReadings"));
        assertEquals(0L, blind.get("contendedSamples"));
        assertEquals(false, blind.get("contended"));
    }

    /**
     * The floor sits strictly inside the measured gap — the vacuity control.
     *
     * <p>Without it a floor of 0.0 would flag every run and a floor of 1.1 none, and both would satisfy one of
     * the two directions above. This keeps meaning after a re-derivation on a quieter machine.
     */
    @Test
    void theNativeFloorSitsStrictlyInsideTheMeasuredGap() {
        org.junit.jupiter.api.Assertions.assertTrue(
                NativePerformanceRunner.MAX_UNATTRIBUTED_CPU_UTILISATION > 0.412,
                "a floor at or below the worst measured quiet reading would flag an honest native run");
        org.junit.jupiter.api.Assertions.assertTrue(
                NativePerformanceRunner.MAX_UNATTRIBUTED_CPU_UTILISATION < 0.883,
                "a floor at or above the best measured contaminated reading would never fire");
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
