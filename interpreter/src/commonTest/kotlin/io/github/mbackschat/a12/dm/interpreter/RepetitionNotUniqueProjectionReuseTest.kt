package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** The immutable rich-message projection of one duplicate cluster is prepared once per validation call. */
class RepetitionNotUniqueProjectionReuseTest {

    @Test
    fun everyPureRnuMessageSharesTheClusterReferenceProjection() {
        val model = ModelDef(
            fields = listOf(
                FieldDef(SKU, Kind.STRING),
                FieldDef(COUNT, Kind.NUMBER),
            ),
            rules = listOf(
                RuleDef("/Order/RU", "/Order", "RepetitionNotUnique(Items/Count)", "RU", "../Items/Count"),
            ),
            repeatableGroups = setOf(ITEMS),
            declaredReps = mapOf(ITEMS to 10),
            indexFields = setOf(SKU),
        )
        val document = model.newDocument().group("/Order", 1).also { doc ->
            repeat(4) { index ->
                val row = index + 1
                doc.group(ITEMS, 1, row)
                    .field(SKU, "K$row", 1, row, 1)
                    .field(COUNT, "5", 1, row, 1)
            }
        }

        val messages = DmInterpreter(model).validateFull(document).filter { it.code == "RU" }

        assertEquals(4, messages.size)
        assertEquals(4, messages.first().referenced.size)
        messages.drop(1).forEach { message ->
            assertSame(
                messages.first().referenced,
                message.referenced,
                "one duplicate cluster must not allocate the same peer-pointer list once per firing row",
            )
        }
    }

    private companion object {
        const val ITEMS = "/Order/Items"
        const val SKU = "/Order/Items/Sku"
        const val COUNT = "/Order/Items/Count"
    }
}
