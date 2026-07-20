package io.github.mbackschat.a12.dm.adapter.profile;

import java.util.Random;

/** Creates stable, purpose-specific random streams for seeded synthesis. */
final class SynthesisRandom {

    private SynthesisRandom() {}

    /**
     * Derives a stream from the synthesis seed and a stable concern name. Separate streams prevent a
     * change in one planner's traversal from perturbing unrelated structure, rule, or computation choices.
     */
    static Random forPurpose(long seed, String purpose) {
        long hash = purpose.hashCode();
        long mixed = seed + 0x9E3779B97F4A7C15L * (hash + 0x100000001L);
        mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
        return new Random(mixed ^ (mixed >>> 31));
    }
}
