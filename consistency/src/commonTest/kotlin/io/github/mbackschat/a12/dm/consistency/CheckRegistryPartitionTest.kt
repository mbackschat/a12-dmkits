package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * That [CheckFamily]'s constants partition into the composed families and the registered producers, so none is
 * advertised by neither. It reads the registry alone; `CheckFamilyWiringTest` owns the fixture-driven rows.
 */
class CheckRegistryPartitionTest {

    @Test
    fun onlyDeclaredTargetsReachTheReportWithoutACell() {
        // R5 condition references carry occurrence/site identity in cells; only target resolution is composed.
        assertEquals(
            setOf(CheckFamily.DECLARED_REFERENCES),
            COMPOSED_FAMILIES,
            "a family outside the registry bypasses the cell store; register it as a producer instead",
        )

        // …and the other direction, so the pin cannot be satisfied by shrinking the denominator: every
        // family is either composed or registered, which is what makes the two sets a partition.
        assertEquals(
            CheckFamily.entries.toSet(),
            COMPOSED_FAMILIES + CHECK_PRODUCERS.flatMap { it.completes },
            "every CheckFamily must be composed or registered, or it is advertised by neither",
        )
    }
}
