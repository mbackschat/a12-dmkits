package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * IG77 → IF158 — the default B-scenario inventory ([PerfInventory]) is the single, **versioned** source of the
 * kernel-ratio benchmark sweep ([PerfBenchTest] derives its sweep from it, so registration here IS benchmark
 * inclusion — a fair-Java-capable scenario can't be silently dropped). This guard locks the inventory's shape so a
 * case can't drift from its declared identity: unique versioned ids, reviewed content fingerprints and expected
 * cardinalities, identity/model agreement, B9 Europe/Berlin present, and zero unsupported constructs. It is
 * **untagged** (runs in the default `:adapter:test`, not gated behind `-PrunPerf`) because it is a cheap structural
 * contract guard, not a timing benchmark.
 */
class ScenarioInventoryTest {

    @Test
    fun closedCatalogIsExhaustivelyDispositionedAndPinsTheRequiredCases() {
        val expected = setOf(
            "B1-per-row",
            "B2-star-aggregate",
            "B3-nested-per-sub",
            "B4-cross-level-ref",
            "B5-deep-nested-star",
            "B6-many-rules",
            "B7-compute",
            "B8-combined",
            "B9-berlin-datetime",
            "B9-utc-control",
            "tiered-compute",
            "parallel-iteration",
        )
        assertEquals(expected, BenchmarkScenario.entries.map { it.id }.toSet(),
            "removing a required benchmark case must fail the closed-catalog guard")
        assertEquals(BenchmarkScenario.entries.toSet(), PerfInventory.dispositions.keys,
            "every closed-catalog case must be included or carry an explicit exclusion")
        PerfInventory.dispositions.values.filterIsInstance<InventoryDisposition.Excluded>().forEach {
            assertTrue(it.reason.isNotBlank(), "an inventory exclusion must explain why the case is not benchmarked")
        }
    }

    @Test
    fun exactScenarioSelectionIncludesThePinnedB9PairAndRejectsUnknownIds() {
        assertEquals(listOf("B9-berlin-datetime"),
            PerfInventory.select("B9-berlin-datetime").map { it.id.id })
        assertEquals(setOf("B9-berlin-datetime", "B9-utc-control"),
            PerfInventory.cases.map { it.id.id }.filter { it.startsWith("B9-") }.toSet())
        assertThrows(IllegalArgumentException::class.java) {
            PerfInventory.select("not-a-benchmark-scenario")
        }
    }

    @Test
    fun everyIncludedCaseDeclaresTheGeneratedJavaLeg() {
        assertTrue(PerfInventory.cases.all { it.id.fairJavaCapable },
            "an included case without generated-Java support must instead have an explicit reasoned disposition")
    }

    @Test
    fun everyCaseHasAUniqueVersionedId() {
        val ids = PerfInventory.cases.map { it.id.id }
        val dupes = ids.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        assertTrue(dupes.isEmpty(), "duplicate scenario ids in the inventory: $dupes")
        PerfInventory.cases.forEach { case ->
            assertTrue(case.id.version >= 1, "${case.id.id}: version must be >= 1 (bumped when the scenario changes)")
            assertTrue(case.n > 0, "${case.id.id}: benchmark scale n must be positive")
        }
    }

    @Test
    fun everyCaseIdentityMatchesTheBuiltScenario() {
        PerfInventory.cases.forEach { case ->
            val sc = case.scenario()
            assertEquals(case.id.op, sc.op, "${case.id.id}: declared op ${case.id.op} != the scenario's actual op ${sc.op}")
            assertEquals(case.id.zone, sc.interpModel.timeZone,
                "${case.id.id}: declared zone ${case.id.zone} != the model's timeZone ${sc.interpModel.timeZone}")
        }
    }

    @Test
    fun theInventoryIncludesB9EuropeBerlin() {
        assertTrue(PerfInventory.cases.any { it.id.zone == ModelTimeZone.EUROPE_BERLIN },
            "the default inventory must include B9 Europe/Berlin (the DST / civil-calendar zone axis)")
    }

    @Test
    fun everyCaseIsExecutableAndFullySupported() {
        // Reuse the harness preflight (IF156): each case must build and evaluate with ZERO unsupported constructs —
        // a case that can't run (or silently degrades) is not a valid benchmark. Cheap: preflight caps the row count.
        PerfInventory.cases.forEach { case -> PerfHarness.preflight(case.scenario(), case.n) }
    }

    @Test
    fun reviewedFingerprintsAndExpectedCardinalitiesMatchCurrentContent() {
        PerfInventory.cases.forEach { case ->
            val scenario = case.unboundScenario()
            val count = InterpreterPerfEngine.build(scenario).instance(case.n).outcome().signatures.size
            assertEquals(case.id.expectedResultCardinality, listOf(count),
                "${case.id.id}: update the scenario version, expected cardinality, and reviewed fingerprint together")
            assertEquals(case.id.reviewedFingerprint, case.id.fingerprintFor(scenario, case.n),
                "${case.id.id}: update the scenario version and reviewed fingerprint together")
        }
    }
}
