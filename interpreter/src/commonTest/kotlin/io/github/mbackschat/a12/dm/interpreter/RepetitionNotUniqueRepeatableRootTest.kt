package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for a `RepetitionNotUnique` rule at a repeatable ROOT whose key sits in it: the
 * root's rows are compared across the document (IF343). The kernel differential is
 * `adapter.laws.RepetitionNotUniqueRepeatableRootDiffTest` (JVM only); this runs the same evaluator on both targets.
 */
class RepetitionNotUniqueRepeatableRootTest {

    private val plate = "/Fleet/Plate"

    private val model = ModelDef(
        fields = listOf(FieldDef(plate, Kind.STRING)),
        rules = listOf(RuleDef("/Fleet/RU", "/Fleet", "RepetitionNotUnique(Plate)", "RU", "../Plate")),
        repeatableGroups = setOf("/Fleet"),
        declaredReps = mapOf("/Fleet" to 5),
    )

    private fun fired(vararg plates: String): List<String> {
        val doc = model.newDocument()
        plates.forEachIndexed { index, value -> doc.group("/Fleet", index + 1).field(plate, value, index + 1, 1) }
        return DmInterpreter(model).validateFull(doc).filter { it.code == "RU" }
            .map { "${it.errorPath}|${it.type}" }.sorted()
    }

    @Test
    fun aDuplicateAcrossRootRowsFires() {
        assertEquals(listOf("/Fleet[1]/Plate|VALUE", "/Fleet[3]/Plate|VALUE"), fired("A", "B", "A"))
    }

    @Test
    fun distinctRootRowsDoNotFire() {
        assertEquals(emptyList(), fired("A", "B", "C"))
    }
}
