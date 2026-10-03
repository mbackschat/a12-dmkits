package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.readDocumentJson
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals

/** Exact typed-authored fixtures, with the observations independently checked on both kernel strategies. */
class SpecSyncRuntimeTest {
    @Test
    fun filteredDeclaredSlotsAndDecidingPrefixesReplayOnThisHost() {
        val fixture = Json.parseToJsonElement(SpecSyncFixtureData.fill).jsonObject
        val model = ModelLoader.load(fixture.getValue("model").jsonPrimitive.content)
        val rows = fixture.getValue("rows").jsonArray
        assertEquals(18, rows.size)
        assertEquals(rows.size, rows.map { it.jsonObject.getValue("id") }.toSet().size)
        for (value in rows) {
            val row = value.jsonObject
            val doc = model.readDocumentJson(row.getValue("document").jsonPrimitive.content)
            val interpreter = DmInterpreter(model)
            val messages = if (row.getValue("partial").jsonPrimitive.boolean)
                interpreter.validatePart(doc, setOf(RelevantEntity("/Invoice", listOf(1)))).messages
                else interpreter.validateFull(doc)
            val actual = messages.filter { it.code.startsWith("F") }.map {
                "${it.code}|${it.errorPath}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}"
            }.sorted()
            assertEquals(row.getValue("messages").jsonArray.map { it.jsonPrimitive.content }, actual,
                row.getValue("id").jsonPrimitive.content)
        }
    }

    @Test
    fun containingGroupDependenciesReplayOnThisHost() {
        val fixture = Json.parseToJsonElement(SpecSyncFixtureData.groups).jsonObject
        val identities = mutableSetOf<String>()
        var observations = 0
        for (value in fixture.getValue("groups").jsonArray) {
            val group = value.jsonObject
            for (entry in group.getValue("rows").jsonArray) {
                val row = entry.jsonObject
                val identity = row.getValue("id").jsonPrimitive.content
                assertEquals(true, identities.add(identity), identity)
                val model = ModelLoader.load(group.getValue("prefix").jsonPrimitive.content +
                    row.getValue("modelMiddle").jsonPrimitive.content + group.getValue("suffix").jsonPrimitive.content)
                if (identity == "PAIR/false") {
                    val plans = ModelStaticPlans.of(model)
                    val operands = setOf("/Invoice/Gate", "/Invoice/X", "/Invoice/Y", "/Invoice/Local")
                    for (computation in model.computations) {
                        assertEquals(operands, plans.computationDepRefs(plans.computation(computation)).toSet(),
                            "protected producers remain in the complete operand inventory")
                    }
                }
                for (runtime in row.getValue("runtime").jsonArray) {
                    val observation = runtime.jsonObject
                    val doc = model.readDocumentJson(observation.getValue("document").jsonPrimitive.content)
                    val expected = observation.getValue("values").jsonObject.mapValues { it.value.jsonPrimitive.contentOrNull }
                    val actual = DmInterpreter(model).compute(doc)
                    assertEquals(expected.size, actual.size)
                    assertEquals(expected, actual.associate { it.path to it.value }, observation.getValue("id").jsonPrimitive.content)
                    observations++
                }
            }
        }
        assertEquals(21, identities.size)
        assertEquals(14, observations)
    }
}
