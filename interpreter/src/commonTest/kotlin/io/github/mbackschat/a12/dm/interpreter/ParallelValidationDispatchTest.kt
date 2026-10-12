package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.readDocumentJson
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

class ParallelValidationDispatchTest {
    private fun model(repeated: Boolean, frame: Int? = null): ModelDef {
        val left = if (frame == null) "/Warehouse/Orders" else "/Warehouse/Batches/Orders"
        val right = "/Warehouse/Stock"
        val groups = setOf("/Warehouse", left, right) + if (frame == null) emptySet() else setOf("/Warehouse/Batches")
        return ModelDef(
            fields = listOf(left, right).flatMap { group -> listOf("Code", "Quantity").map { FieldDef("$group/$it", Kind.NUMBER, scale = 0) } },
            rules = listOf(RuleDef("$left/Check", left, if (frame == null)
                "GroupFilled($left) Or FieldFilled($right/Quantity)" else "[$left/Quantity] < [$right/Quantity]", "Check", "../Quantity")),
            groupPaths = groups,
            declaredReps = buildMap {
                put(left, 5); put(right, 5)
                if (repeated) put("/Warehouse", 2)
                if (frame != null) put("/Warehouse/Batches", frame)
            },
            repeatableGroups = groups - if (repeated) emptySet() else setOf("/Warehouse"),
            indexFields = setOf("$left/Code", "$right/Code"),
        )
    }

    @Test
    fun dispatchCompressesErrorSelectorsWithoutRestrictingFixedRootKeys() {
        for (repeated in listOf(true, false)) {
            val model = model(repeated)
            val body = """{"Orders":[{"Code":1,"Quantity":1},{"Code":2,"Quantity":1}],"Stock":[{"Code":1,"Quantity":1},{"Code":3,"Quantity":1}]}"""
            val doc = model.readDocumentJson("""{"Warehouse":${if (repeated) "[$body]" else body}}""")
            for (rows in listOf(listOf(1), listOf(1, 2))) {
                val selected = setOf(RelevantEntity("/Warehouse/Orders/Code", listOf(0, 0, 0)),
                    RelevantEntity("/Warehouse/Stock", listOf(0, 0))) +
                    rows.map { RelevantEntity("/Warehouse/Orders/Quantity", listOf(1, it, 1)) }
                val report = DmInterpreter(model).validatePart(doc, selected)
                assertEquals(emptyList(), report.unsupported)
                assertEquals(if (repeated && rows.size == 1) 0 else 3, report.messages.count { it.code == "Check" })
            }
        }
    }

    @Test
    fun smallFramesVisitUninstantiatedSlotsOnBothValidationRoutes() {
        for (bound in listOf(3, 6)) {
            val model = model(false, bound)
            val doc = model.readDocumentJson("""{"Warehouse":{"Batches":[{"Orders":[{"Code":1,"Quantity":1}]},{"Orders":[{"Code":2,"Quantity":1}]}],"Stock":[{"Code":1,"Quantity":2}]}}""")
            val engine = DmInterpreter(model)
            val full = engine.validateFull(doc)
            val partial = engine.validatePart(doc, setOf(RelevantEntity("/Warehouse", listOf(0)))).messages
            assertEquals(if (bound == 3) 3 else 2, full.count { it.code == "Check" })
            assertEquals(full, partial)
        }
    }
}
