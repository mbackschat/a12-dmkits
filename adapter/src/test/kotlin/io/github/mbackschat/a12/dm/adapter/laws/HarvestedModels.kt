package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.security.MessageDigest
import java.util.HexFormat

/** Reconstruction preserves captured UTF-8 bytes rather than normalizing model JSON (CONFORMANCE-PLAN A2). */
internal object HarvestedModels {
    internal fun index(root: JsonNode): Map<String, JsonNode> = buildMap {
        for (row in expand(root)) {
            val identity = row["id"]
            require(identity?.isTextual == true) { "Missing or non-text harvest id" }
            val id = identity.asText()
            require(id !in this) { "Duplicate harvest identity: $id" }
            put(id, row)
        }
    }

    internal fun expand(root: JsonNode): List<JsonNode> {
        fun text(node: JsonNode, name: String): String {
            val value = node[name]
            require(value?.isTextual == true) { "Missing or non-text harvest $name" }
            return value.asText()
        }
        val groups = root["groups"]
        require(groups?.isArray == true) { "Missing harvest groups" }
        return groups.flatMap { group ->
            val prefix = text(group, "prefix")
            val suffix = text(group, "suffix")
            val rows = group["rows"]
            require(rows?.isArray == true) { "Missing harvest rows" }
            rows.map { row ->
                require(row is ObjectNode && !row.has("model")) { "Expected factored harvest row" }
                val model = prefix + text(row, "modelMiddle") + suffix
                val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(model.toByteArray(Charsets.UTF_8)))
                require(digest == text(row, "modelSha256")) { "Harvest model bytes changed: ${row["id"]}" }
                row.deepCopy().apply {
                    remove(listOf("modelMiddle", "modelSha256"))
                    put("model", model)
                }
            }
        }
    }
}
