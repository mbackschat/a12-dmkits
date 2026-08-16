package io.github.mbackschat.a12.dm.adapter.perf

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Integrity of the committed anchor resource — cheap, untagged, and therefore run on every push, unlike the gate
 * that consumes it.
 *
 * `PerfAnchors` reads this file lazily inside a perf-tagged lane, so a gutted or malformed anchor would first
 * surface in a nightly, and some corruptions would not surface at all: a renamed environment block reads as the
 * bootstrap state (never fails), a renamed `observations` member reads as an anchored-but-empty class, and a
 * non-numeric value degrades through `asDouble()` to `0.0`, i.e. a 1 ms ceiling that fails the lane while naming
 * an anchor "of 0.00ms". Each of those is a way for the only uniform-regression bar in the estate to disappear or
 * misdiagnose, so the file's shape is asserted here rather than trusted.
 */
class PerfAnchorResourceTest {

    private val root = PerfObservations.MAPPER.readTree(
        checkNotNull(PerfAnchorResourceTest::class.java.getResourceAsStream(PerfAnchors.ANCHOR_RESOURCE)) {
            "the committed anchor ${PerfAnchors.ANCHOR_RESOURCE} must be on the test classpath"
        },
    )

    private val environments get() = root.path("environments")

    @Test
    fun theResourceCarriesAtLeastOneRunnerClass() {
        assertTrue(environments.isObject && !environments.isEmpty) {
            "${PerfAnchors.ANCHOR_RESOURCE} must anchor at least one runner class: $environments"
        }
    }

    /** A renamed `observations` member, or an emptied one, reads to `PerfAnchors` as "anchored with no keys". */
    @Test
    fun everyRunnerClassAnchorsAtLeastOneObservation() {
        environments.properties().forEach { (environment, block) ->
            val observations = block.path("observations")
            assertTrue(observations.isObject && !observations.isEmpty) {
                "$environment carries no observations, which reads as an anchored class with the bar switched off"
            }
        }
    }

    @Test
    fun everyAnchorIsAFinitePositiveNumberOfMilliseconds() {
        environments.properties().forEach { (environment, block) ->
            block.path("observations").properties().forEach { (key, value) ->
                assertTrue(value.isNumber && value.asDouble().isFinite() && value.asDouble() > 0.0) {
                    "$environment / $key is not a positive number of milliseconds: $value — a non-numeric value " +
                        "degrades to a 1 ms ceiling instead of failing loudly"
                }
            }
        }
    }

    /**
     * A block the gated lane will not assert against is a bar that never fires, and `machine` is what it matches on
     * ([MachineIdentity.check]) — so a block for a class that GATES must name the machine it was captured on, and its
     * processor count, or the whole class silently reads as bootstrap.
     *
     * A hosted class (`-ci`) is the one kind that legitimately names none: its runner is a VM the provider chooses
     * per job, so that block is a bar over the class rather than over one machine and `TimingEnforcement` flags it
     * instead of failing. The suffix is the same discriminator `PerfEnvironment` appends to its key, which is why it
     * can be read here without a second source of truth.
     */
    @Test
    fun everyGatedRunnerClassNamesTheMachineItWasCapturedOn() {
        environments.properties().filterNot { (environment, _) -> environment.endsWith("-ci") }
            .forEach { (environment, block) ->
                assertTrue(block.path("machine").isTextual && block.path("machine").asText().isNotBlank()) {
                    "$environment does not name its machine, so PerfAnchors will not assert against it and the " +
                        "only bar that sees a uniform regression is off: refresh it (scripts/perf-anchor.sh)"
                }
                assertTrue(block.path("processors").isInt) {
                    "$environment does not name its processor count, which is part of the machine's identity"
                }
            }
    }

    /**
     * **The complementary partition, which nothing asserted.** The rule above SKIPS a hosted class, so the policy
     * "a hosted block records no machine" lived only in the refresh script's output and never in the committed
     * artifact. A hand edit, a merge, or a refresh run from an older checkout of the script silently restored the
     * field, and a cold review's mutant re-adding it survived all ten anchor-reading guards. Assert the absence
     * where the artifact is, not only where the writer is.
     */
    @Test
    fun aHostedRunnerClassRecordsNoMachineAndNoProcessorCount() {
        environments.properties().filter { (environment, _) -> environment.endsWith("-ci") }
            .forEach { (environment, block) ->
                assertTrue(block.path("machine").isMissingNode) {
                    "$environment names a machine, but a hosted class does not have one — the provider picks the " +
                        "CPU per job and the fleet is not enumerable, so naming one states an identity the class " +
                        "cannot honour and makes the next refresh wait on a lottery. Drop the field."
                }
                assertTrue(block.path("processors").isMissingNode) {
                    "$environment names a processor count, which is part of a machine identity a hosted class " +
                        "does not have; it is not compared on a fold either, so writing it records the last " +
                        "input's value as if it were the class's"
                }
            }
    }

    /** The key shape `PerfAnchors` matches on, so a hand-edited or truncated key cannot silently stop matching. */
    @Test
    fun everyKeyCarriesItsLaneScenarioFingerprintScaleEngineAndClock() {
        val shape = Regex("""^[^|]+\|\S+@\d+ fingerprint=[0-9a-f]{64}\|n=\d+\|interpreter\|(cpu|wall)$""")
        environments.properties().forEach { (environment, block) ->
            block.path("observations").properties().forEach { (key, _) ->
                assertTrue(shape.matches(key)) { "$environment / '$key' is not a well-formed observation key" }
            }
        }
    }
}
