package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Exact model bytes remain evidence after storage factoring (CONFORMANCE-PLAN A2). */
class OperationHarvestStorageTest {
    private val mapper = ObjectMapper()

    @Test
    fun fragmentsRestoreBytesAndLeaveAssertionMetadataAlone() {
        val expected = mapper.readTree("""{"id":"sample#1","classes":["X","X"],"onlyCodes":null}""") as ObjectNode
        expected.put("model", "{\"policy\":\"🛒\"}\r\n")
        assertEquals(listOf(expected), HarvestedModels.expand(fixture()))
    }

    @Test
    fun missingFragmentsAndByteDriftCannotBecomeModels() {
        val mutations: List<(ObjectNode) -> Unit> = listOf(
            { it.remove("prefix") },
            { it.putNull("suffix") },
            { (it["rows"][0] as ObjectNode).remove("modelMiddle") },
            { it.put("prefix", " " + it["prefix"].asText()) },
            { (it["rows"][0] as ObjectNode).remove("modelSha256") },
        )
        for ((index, mutate) in mutations.withIndex()) {
            val root = fixture()
            mutate(root["groups"][0] as ObjectNode)
            assertThrows(IllegalArgumentException::class.java, { HarvestedModels.expand(root) }, "mutation $index")
        }
    }

    @Test
    fun duplicateIdentitiesCannotDisappearInALaterLookup() {
        for (differentMetadata in listOf(false, true)) {
            val root = fixture()
            val groups = root["groups"] as ArrayNode
            val duplicate = groups[0].deepCopy<ObjectNode>()
            if (differentMetadata) (duplicate["rows"][0] as ObjectNode).put("onlyCodes", "different")
            groups.add(duplicate)
            assertEquals(2, HarvestedModels.expand(root).size)
            assertThrows(IllegalArgumentException::class.java, { HarvestedModels.index(root) })
        }
        val distinct = fixture()
        val groups = distinct["groups"] as ArrayNode
        val other = groups[0].deepCopy<ObjectNode>()
        (other["rows"][0] as ObjectNode).put("id", "sample#2")
        groups.add(other)
        assertEquals(listOf("sample#1", "sample#2"), HarvestedModels.index(distinct).keys.toList())
    }

    private fun fixture() = mapper.readTree("""
        {"groups":[{"prefix":"{\"policy\":\"","suffix":"\"}\r\n","rows":[
          {"id":"sample#1","classes":["X","X"],"onlyCodes":null,"modelMiddle":"🛒",
           "modelSha256":"2b945d00336ac88be39b4ca4d9346a4ac1807079851f515e3a77ddcd13069111"}
        ]}]}
    """.trimIndent())
}
