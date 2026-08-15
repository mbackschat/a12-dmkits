package io.github.mbackschat.a12.dm.adapter.perf

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **Every anchored runner class must carry the SAME observation keys.** This is the guard that makes a green
 * local perf run mean a green nightly, and it exists because that implication was false and cost a night.
 *
 * ## The mechanism it closes
 *
 * An observation key is `lane|scenario@version fingerprint=<sha256>|n|engine|clock`, and the fingerprint hashes
 * the scenario's CONTENT — model, generator parameters, instance placements ([ScenarioIdentity]). **Nothing in
 * it depends on the machine.** Only the millisecond values do. So the key set the gate will demand on a hosted
 * runner is fully determined by the source and is computable anywhere.
 *
 * The gate refuses an observation whose key is absent from the committed block for the *current* runner class —
 * correctly, since that is how a bar silently disappears. But it only ever looks at its own class. So when a
 * source change moves a fingerprint:
 *
 * - the `darwin-aarch64` block is refreshed from a local run, and local goes green;
 * - the `linux-amd64-ci` block still holds the OLD keys, because refreshing it needs observations measured on
 *   that runner class, which only CI produces;
 * - nothing compares the two, so the staleness is invisible until the nightly fails.
 *
 * That is not a missed step, it is a hole: the failure was **undetectable locally and un-preemptable**, because
 * repairing the CI block requires the very job that fails. On 2026-08-15 the blocks had drifted to 66 keys
 * against 36 and the nightly reported ~20 UNANCHORED lanes.
 *
 * ## Why key parity is sufficient
 *
 * On a hosted runner a timing breach is **flagged, never failed** (`TimingEnforcement.FLAGGED`), so an
 * unanchored key is the only way this gate fails CI. The three states are then covered without a gap:
 *
 * | state | caught by |
 * |---|---|
 * | the local block is stale | the local perf gate itself — it goes UNANCHORED and red |
 * | the CI block is stale while local is fresh | **this test** |
 * | both blocks are stale | the local perf gate, as above |
 *
 * So with this guard, a green local perf run does imply a green nightly *for this gate*. It cannot speak for a
 * genuine slowdown that only manifests on other hardware — but that is flagged, not failed, by design.
 *
 * ## What to do when it fails
 *
 * Do not delete keys to make it pass. Refresh the lagging class from a run measured on it: dispatch the nightly
 * (or the perf workflow), download its `perf-observations` artifact, and
 * `scripts/perf-anchor.sh refresh <that>.jsonl`. The refreshed block belongs in the same commit as the change
 * that moved the fingerprint.
 */
class AnchorBlockKeyParityTest {

    private val environments: Map<String, Set<String>> = run {
        val stream = requireNotNull(PerfAnchors::class.java.getResourceAsStream(PerfAnchors.ANCHOR_RESOURCE)) {
            "missing ${PerfAnchors.ANCHOR_RESOURCE} — the anchor block is the bar this guard protects"
        }
        val root = stream.use { ObjectMapper().readTree(it) }
        root.path("environments").fields().asSequence().associate { (name, block) ->
            name to block.path("observations").fieldNames().asSequence().toSet()
        }
    }

    /**
     * THE case. Asserted as whole sets rather than counts, because two blocks can agree on size while naming
     * different keys — which is exactly what a fingerprint move produces.
     */
    @Test
    fun everyAnchoredRunnerClassCarriesTheSameObservationKeys() {
        val union = environments.values.flatten().toSet()
        val missingPerEnvironment = environments
            .mapValues { (_, keys) -> (union - keys).sorted() }
            .filterValues { it.isNotEmpty() }

        assertEquals(emptyMap<String, List<String>>(), missingPerEnvironment) {
            "an anchored runner class is missing keys another one has, so the nightly will report them " +
                "UNANCHORED and fail while the local gate stays green. The key is machine-independent — only " +
                "the timings are not — so this is a stale BLOCK, never a machine difference. Refresh the " +
                "lagging class from a run measured on it (scripts/perf-anchor.sh refresh <observations>.jsonl); " +
                "do not delete keys to go green."
        }
    }

    /**
     * The control. With one environment, or with empty blocks, the assertion above is vacuously true and would
     * keep passing through exactly the drift it exists to catch.
     */
    @Test
    fun theParityCheckHasSomethingToCompare() {
        assertTrue(environments.size >= 2) {
            "only ${environments.size} anchored runner class(es) — parity is vacuous until a second one exists, " +
                "and it is the SECOND class that this guard protects"
        }
        environments.forEach { (name, keys) ->
            assertTrue(keys.isNotEmpty()) { "$name carries no observation keys, so parity against it proves nothing" }
        }
    }
}
