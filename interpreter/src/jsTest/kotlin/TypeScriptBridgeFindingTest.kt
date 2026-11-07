import io.github.mbackschat.a12.dm.interpreter.A12PartiallyKnownMultiPointer
import io.github.mbackschat.a12.dm.interpreter.ValidationFinding
import io.github.mbackschat.a12.dm.interpreter.ValidationFindingType
import io.github.mbackschat.a12.dm.interpreter.ValidationSeverity
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A finding's groups of possibly-causing entities cross the bridge as they left the engine: the groups in their
 * order, each pointer whole (KERNEL-FINDINGS KF683). Package-less for the reason `TypeScriptBridgePointerFormatTest`
 * gives: the bridge is declared in the root package.
 */
class TypeScriptBridgeFindingTest {

    @Test
    fun anOmissionKeepsItsEmptyGroupFirstAndItsGivenGroupSecond() {
        val empty = A12PartiallyKnownMultiPointer.of("/Order/Note", listOf(1, 1))
        val given = A12PartiallyKnownMultiPointer.of("/Order/Lines/Amount", listOf(1, 0, 1))
        val total = A12PartiallyKnownMultiPointer.of("/Order/Total", listOf(1, 1))
        val finding = ValidationFinding(
            "NOTE", "/Order/NOTE", empty, ValidationSeverity.ERROR, ValidationFindingType.OMISSION, "Note.",
            listOf(given, total, empty), listOf(empty), listOf(setOf(empty), setOf(given, total)),
        )

        val crossed = validationFinding(finding).possiblyCausing

        assertEquals(
            listOf(listOf("/Order/Note 1,1"), listOf("/Order/Lines/Amount 1,0,1", "/Order/Total 1,1")),
            crossed.map { group -> group.map { "${it.fullName} ${it.repetitionIndexes.joinToString(",")}" } },
        )
    }
}
