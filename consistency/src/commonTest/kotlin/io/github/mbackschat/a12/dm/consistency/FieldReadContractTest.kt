package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.ConditionLanguage
import io.github.mbackschat.a12.dm.consistency.syntax.SyntaxEntry
import io.github.mbackschat.a12.dm.consistency.syntax.fieldValueReadsIn
import kotlinx.serialization.json.int
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class FieldReadContractTest {
    @Test
    fun recognizedOriginsMatchTheKernelRecords() {
        assertEquals(4, SyntaxFixtureData.fieldReadOrigins.size)
        for (record in SyntaxFixtureData.fieldReadOrigins) {
            val row = Json.parseToJsonElement(record).jsonObject
            val text = row.getValue("text").jsonPrimitive.content
            val read = fieldValueReadsIn(text, ConditionLanguage.EN, SyntaxEntry.CONDITION).single()
            assertEquals(row.getValue("start").jsonPrimitive.int, read.at, text)
            assertEquals(row.getValue("end").jsonPrimitive.int, read.at + 1, text)
        }
    }

    @Test
    fun harvestedAssertionsKeepTheirCodeFilterAndMultiplicity() {
        assertEquals(50, SyntaxFixtureData.fieldReads.size)
        for (record in SyntaxFixtureData.fieldReads) {
            val row = Json.parseToJsonElement(record).jsonObject
            val onlyCode = row.getValue("onlyCode").jsonPrimitive.contentOrNull
            val actual = checkRead(checkableSource(row.getValue("model").jsonPrimitive.content)).findings
                .filter { if (onlyCode == null) it.category == FindingCategory.A12_CONSTRAINT else it.code == onlyCode }
                .map { it.code }.sorted()
            assertEquals(row.getValue("classes").jsonArray.map { it.jsonPrimitive.content }, actual,
                row.getValue("id").jsonPrimitive.content)
        }
    }
}
