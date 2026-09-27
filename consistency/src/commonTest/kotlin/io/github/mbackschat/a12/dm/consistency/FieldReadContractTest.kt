package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class FieldReadContractTest {
    @Test
    fun harvestedAssertionsKeepTheirCodeFilterAndMultiplicity() {
        assertEquals(50, SyntaxFixtureData.fieldReads.size)
        for (record in SyntaxFixtureData.fieldReads) {
            val row = Json.parseToJsonElement(record).jsonObject
            val onlyCode = row.getValue("onlyCode").jsonPrimitive.contentOrNull
            val actual = checkRead(ModelSource(row.getValue("model").jsonPrimitive.content)).findings
                .filter { if (onlyCode == null) it.category == FindingCategory.A12_CONSTRAINT else it.code == onlyCode }
                .map { it.code }.sorted()
            assertEquals(row.getValue("classes").jsonArray.map { it.jsonPrimitive.content }, actual,
                row.getValue("id").jsonPrimitive.content)
        }
    }
}
