package io.github.mbackschat.a12.dm.adapter.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Locks the {@code ModelSynthesizer.synthesize(profile, seed)} determinism contract (its own Javadoc +
 * MODEL-PROFILE-SPEC: "a given (profile, seed) reproduces the same model") ACROSS SEPARATE JVM LAUNCHES, not
 * just within one process — the distinction that let the bug hide: a per-process JVM quirk (a launch-
 * randomized iteration order, {@link Distribution}'s former {@code Map.copyOf}) is invisible to an in-process
 * JUnit assertion, since the randomization is fixed for the whole test run. Spawns {@link
 * ModelSynthesizerDeterminismMain} as a real child process twice, with the SAME seed, and diffs stdout.
 *
 * <p>Root cause (found + fixed alongside this test): {@code Distribution}'s compact constructor called {@code
 * Map.copyOf(buckets)} — {@code java.util.ImmutableCollections}' factories deliberately randomize their
 * iteration order per JVM launch (stable within one process, different across separate ones) as an anti-
 * reliance safeguard. {@code ModelSynthesizer.sampleMultiset} iterates a {@code Distribution}'s buckets while
 * consuming a shared seeded {@link java.util.Random} sequentially, so that per-launch shuffle changed which
 * bucket got a value from a length-1 draw first — silently changing chainProduct-derived structure targets on
 * every relaunch even with an identical (profile, seed). Fixed by sorting {@code Distribution.buckets()} into
 * a deterministic (bucket-value-ordered) map in the compact constructor, immune to the source map's own order.
 */
class ModelSynthesizerCrossJvmDeterminismTest {

    @Test
    void sameProfileAndSeedProducesByteIdenticalModelAcrossSeparateJvmLaunches() throws IOException, InterruptedException {
        long seed = 20260703L;
        String run1 = runInFreshJvm(seed);
        String run2 = runInFreshJvm(seed);
        assertEquals(run1, run2,
                "the SAME (profile, seed) must synthesize byte-identical model JSON across separate JVM "
                        + "launches — a divergence here means a JVM-launch-randomized collection (like the "
                        + "former Distribution#buckets Map.copyOf) has crept back into the structure/value "
                        + "assignment path");
    }

    /** Spawns a fresh {@code java} process running {@link ModelSynthesizerDeterminismMain} with {@code seed},
     *  reusing this test JVM's own classpath — the only way to actually exercise a NEW JVM launch. */
    private static String runInFreshJvm(long seed) throws IOException, InterruptedException {
        String javaBin = System.getProperty("java.home") + File.separator + "bin"
                + File.separator + "java";
        ProcessBuilder pb = new ProcessBuilder(javaBin, "-cp", System.getProperty("java.class.path"),
                ModelSynthesizerDeterminismMain.class.getName(), Long.toString(seed));
        pb.redirectErrorStream(false);
        Process process = pb.start();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("child JVM exited " + exit + ":\n" + stderr);
        }
        return stdout;
    }
}
