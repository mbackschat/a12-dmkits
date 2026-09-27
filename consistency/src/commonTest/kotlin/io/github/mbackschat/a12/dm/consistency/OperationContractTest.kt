package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class OperationContractTest {
    @Test
    fun harvestedOperationsKeepTheirOriginalAssertions() {
        assertEquals(183, SyntaxFixtureData.operationChecks.size)
        for (record in SyntaxFixtureData.operationChecks) {
            val row = Json.parseToJsonElement(record).jsonObject
            val filter = row.getValue("onlyCodes").takeUnless { it == JsonNull }
                ?.jsonArray?.map { it.jsonPrimitive.content }?.toSet()
            val actual = checkRead(ModelSource(row.getValue("model").jsonPrimitive.content))
                .findings.map { it.code }.filter { filter == null || it in filter }
            assertEquals(row.getValue("classes").jsonArray.map { it.jsonPrimitive.content }, actual,
                row.getValue("id").jsonPrimitive.content)
        }
    }
}
