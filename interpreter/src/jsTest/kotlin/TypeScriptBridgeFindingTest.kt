import io.github.mbackschat.a12.dm.interpreter.A12PartiallyKnownMultiPointer
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.UnknownIndexKey
import io.github.mbackschat.a12.dm.interpreter.ValidationFinding
import io.github.mbackschat.a12.dm.interpreter.ValidationFindingType
import io.github.mbackschat.a12.dm.interpreter.ValidationSeverity
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import io.github.mbackschat.a12.dm.interpreter.validationFinding as publishedFinding

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

        val crossed = FindingCrossing().finding(finding).possiblyCausing

        assertEquals(
            listOf(listOf("/Order/Note 1,1"), listOf("/Order/Lines/Amount 1,0,1", "/Order/Total 1,1")),
            crossed.map { group -> group.map { "${it.fullName} ${it.repetitionIndexes.joinToString(",")}" } },
        )
    }

    /**
     * A duplicate cluster's findings share their `referenced` list and their cause group in the engine
     * (`PublishedCauseGroupNoCopyTest`), and the crossing keeps that sharing: copying them per finding allocates
     * N² pointers for an N-row cluster (INTERPRETER-FINDINGS IF256).
     */
    @Test
    fun aClusterCrossesItsSharedPointersOnce() {
        val model = ModelDef(
            fields = listOf(FieldDef(SKU, Kind.STRING), FieldDef(COUNT, Kind.NUMBER)),
            rules = listOf(RuleDef("/Order/RU", "/Order", "RepetitionNotUnique(Items/Count)", "RU", "../Items/Count")),
            repeatableGroups = setOf(ITEMS),
            declaredReps = mapOf(ITEMS to 10),
            indexFields = setOf(SKU),
        )
        val document = model.newDocument().group("/Order", 1).also { doc ->
            (1..4).forEach { row -> doc.group(ITEMS, 1, row).field(SKU, "K$row", 1, row, 1).field(COUNT, "5", 1, row, 1) }
        }
        val findings = DmInterpreter(model).validateFull(document).filter { it.code == "RU" }.map(::publishedFinding)
        assertEquals(4, findings.size, "every row of the cluster must fire")

        val crossing = FindingCrossing()
        val crossed = findings.map(crossing::finding)

        val first = crossed.first()
        assertTrue(crossed.all { it.referenced === first.referenced }, "the shared referenced list crossed per finding")
        assertTrue(crossed.all { it.possiblyCausing.single() === first.possiblyCausing.single() },
            "the shared cause group crossed per finding")
    }

    private companion object {
        const val ITEMS = "/Order/Items"
        const val SKU = "/Order/Items/Sku"
        const val COUNT = "/Order/Items/Count"
    }

    /** A missed key crosses as A12's TypeScript runtime types it: the text, or a number (KERNEL-FINDINGS KF683). */
    @Test
    fun aMissedKeyCrossesAsTextOrNumber() {
        val missed = A12PartiallyKnownMultiPointer.of("/Portfolio/Fees/Rate", listOf(1, A12PartiallyKnownMultiPointer.UNKNOWN, 1))

        assertEquals<Any?>("A", bridgePartiallyKnown(missed.withUnknownIndexKey(UnknownIndexKey.Text("A"))).semanticIndexValueForUnknownIndexGroup)
        assertEquals<Any?>(8.5, bridgePartiallyKnown(missed.withUnknownIndexKey(UnknownIndexKey.Decimal("8.50"))).semanticIndexValueForUnknownIndexGroup)
        assertEquals<Any?>(null, bridgePartiallyKnown(missed).semanticIndexValueForUnknownIndexGroup)
    }
}
