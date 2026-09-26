package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tri-engine law for a `RepetitionNotUnique` rule declared at a repeatable ROOT.
 *
 * A12 admits keys that sit directly in the root with no repeatable group between the rule and them
 * ([KERNEL-FINDINGS KF355], `rulekit.validate.laws.RepetitionNotUniquePairedGateLawsTest`), so the rows compared
 * are the root's own, across the document. This locks what the admitted rule fires on: the duplicate tuple across
 * root rows, the empty component that makes a duplicate an omission, and the distinct control. The rule scoped
 * explicitly `@From` the root is the control that the default reference group is the root itself, and a key in a
 * repeatable subgroup of the root is the control that the other branch still compares per root row.
 */
class RepetitionNotUniqueRepeatableRootDiffTest {

    private val modelJson = DocumentModelDraft.named("rnu-repeatable-root").locale("en_US")
        .rootGroup("Fleet") { fleet ->
            fleet.repeatable(5).stringField("Plate") { }.stringField("Vin") { }
                .group("Drivers") { drivers -> drivers.repeatable(3).stringField("License") { } }
        }
        .build().toJson()
        .let(EditSession::read)
        .also { session ->
            session.addRule { bound ->
                bound.rule(bound.group(FLEET)).name("PlateAndVin").code(PAIR)
                    .errorField(bound.str(PLATE))
                    .`when`(Dsl.repetitionNotUnique(bound.field(PLATE), bound.field(VIN)))
                    .severity(Severity.ERROR).message(Dsl.en("The plate and VIN pair is duplicated.")).build()
            }
            session.addRule { bound ->
                bound.rule(bound.group(FLEET)).name("PlateAlone").code(SOLE)
                    .errorField(bound.str(PLATE))
                    .`when`(Dsl.repetitionNotUnique(bound.field(PLATE)))
                    .severity(Severity.ERROR).message(Dsl.en("The plate is duplicated.")).build()
            }
            session.addRule { bound ->
                bound.rule(bound.group(FLEET)).name("PlateFromFleet").code(SCOPED)
                    .errorField(bound.str(PLATE))
                    .`when`(Dsl.repetitionNotUnique(bound.field(PLATE)).from(bound.group(FLEET)))
                    .severity(Severity.ERROR).message(Dsl.en("The plate is duplicated across the fleet.")).build()
            }
            session.addRule { bound ->
                bound.rule(bound.group(FLEET)).name("LicensePerVehicle").code(LICENSE_CODE)
                    .errorField(bound.str(LICENSE))
                    .`when`(Dsl.repetitionNotUnique(bound.field(LICENSE)))
                    .severity(Severity.ERROR).message(Dsl.en("A driver's license is listed twice.")).build()
            }
            session.validate()
        }
        .toJson()

    @Test
    fun theRootsOwnRowsAreCompared() {
        assertTriEngine(
            label = "two root rows share the whole tuple",
            cells = vehicles("A" to "1", "A" to "1", "B" to "2"),
            expected = setOf(
                "$PAIR|VALUE_ERROR@/Fleet[1]/Plate",
                "$PAIR|VALUE_ERROR@/Fleet[2]/Plate",
                "$SOLE|VALUE_ERROR@/Fleet[1]/Plate",
                "$SOLE|VALUE_ERROR@/Fleet[2]/Plate",
                "$SCOPED|VALUE_ERROR@/Fleet[1]/Plate",
                "$SCOPED|VALUE_ERROR@/Fleet[2]/Plate",
            ),
        )
        assertTriEngine(
            label = "a shared empty component makes the pair an omission",
            cells = vehicles("A" to null, "A" to null, "B" to "2"),
            expected = setOf(
                "$PAIR|OMISSION_ERROR@/Fleet[1]/Plate",
                "$PAIR|OMISSION_ERROR@/Fleet[2]/Plate",
                "$SOLE|VALUE_ERROR@/Fleet[1]/Plate",
                "$SOLE|VALUE_ERROR@/Fleet[2]/Plate",
                "$SCOPED|VALUE_ERROR@/Fleet[1]/Plate",
                "$SCOPED|VALUE_ERROR@/Fleet[2]/Plate",
            ),
        )
        assertTriEngine(
            label = "distinct plates are the non-firing control",
            cells = vehicles("A" to "1", "B" to "1", "C" to "2"),
            expected = emptySet(),
        )
    }

    /** Rival: the root's reference group spans the document for a subgroup key too, which also fires on vehicle 2. */
    @Test
    fun aKeyInARepeatableSubgroupIsComparedPerRootRow() {
        val cells = vehicles("A" to "1", "B" to "2") + listOf(
            Cell(LICENSE, "L-1", 1, 1), Cell(LICENSE, "L-1", 1, 2), Cell(LICENSE, "L-1", 2, 1),
        )
        assertTriEngine(
            label = "one license twice under vehicle 1 and once under vehicle 2",
            cells = cells,
            expected = setOf(
                "$LICENSE_CODE|VALUE_ERROR@/Fleet[1]/Drivers[1]/License",
                "$LICENSE_CODE|VALUE_ERROR@/Fleet[1]/Drivers[2]/License",
            ),
        )
    }

    /** One stored value, at the repetition of each group on its path from the root down. */
    private class Cell(val path: String, val value: String, vararg val rows: Int)

    private fun vehicles(vararg plateAndVin: Pair<String, String?>): List<Cell> =
        plateAndVin.withIndex().flatMap { (index, cell) ->
            listOfNotNull(Cell(PLATE, cell.first, index + 1), cell.second?.let { Cell(VIN, it, index + 1) })
        }

    private fun assertTriEngine(label: String, cells: List<Cell>, expected: Set<String>) {
        val definition = ModelLoader.load(modelJson)
        fun kernelDocument() = RuntimeLaws.doc().also { doc ->
            for (cell in cells) {
                doc.group(cell.path.substringBeforeLast('/'), *cell.rows)
                doc.field(cell.path, cell.value, *cell.rows, 1)
            }
        }
        fun interpreterDocument() = definition.newDocument().also { doc ->
            for (cell in cells) {
                doc.group(cell.path.substringBeforeLast('/'), *cell.rows)
                doc.field(cell.path, cell.value, *cell.rows, 1)
            }
        }
        val codes = setOf(PAIR, SOLE, SCOPED, LICENSE_CODE)
        fun kernelSignatures(messages: List<RuntimeLaws.Msg>) = messages.filter { it.code() in codes }
            .mapTo(sortedSetOf()) { "${it.code()}|${it.type()}@${it.field()}" }
        val dynamic = kernelSignatures(RuntimeLaws.messages(modelJson, kernelDocument()))
        val java = RuntimeLaws.sharedKernelJavaEngine(modelJson)
        val static = kernelSignatures(java.messages(java.instance(kernelDocument())))
        val interpreterResult = DmInterpreter(definition).validate(interpreterDocument())
        val interpreter = interpreterResult.messages.filter { it.code in codes }
            .mapTo(sortedSetOf()) { "${it.code}|${it.type}_ERROR@${it.errorPath}" }
        assertEquals(expected.toSortedSet(), dynamic, "$label: dynamic kernel")
        assertEquals(expected.toSortedSet(), static, "$label: static kernel")
        assertEquals(emptyList<Any>(), interpreterResult.unsupported, "$label: interpreter support")
        assertEquals(expected.toSortedSet(), interpreter, "$label: interpreter")
    }

    private companion object {
        const val FLEET = "/Fleet"
        const val PLATE = "/Fleet/Plate"
        const val VIN = "/Fleet/Vin"
        const val LICENSE = "/Fleet/Drivers/License"
        const val PAIR = "RNU_PLATE_AND_VIN"
        const val SOLE = "RNU_PLATE"
        const val SCOPED = "RNU_PLATE_FROM_FLEET"
        const val LICENSE_CODE = "RNU_LICENSE"
    }
}
