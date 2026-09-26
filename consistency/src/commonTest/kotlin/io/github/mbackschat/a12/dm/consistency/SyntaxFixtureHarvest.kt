package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.ConditionLanguage
import io.github.mbackschat.a12.dm.consistency.syntax.SyntaxEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** T1 records actual helper inputs before R3a retires their tests; the committed population owns the result. */
internal class SyntaxFixtureHarvest(private val source: String) {
    private var ordinal = 0

    fun record(text: String, language: ConditionLanguage, entry: SyntaxEntry, catalogId: String? = null,
            model: String? = null) {
        println("CHECKER_HARVEST\t" + buildJsonObject {
            put("source", source)
            put("row", ++ordinal)
            put("text", text)
            put("language", language.name)
            put("entry", entry.name)
            catalogId?.let { put("catalogId", it) }
            model?.let { put("originalModel", it) }
        })
    }

    fun recordModel(model: String) {
        val content = Json.parseToJsonElement(model).jsonObject.getValue("content").jsonObject
        val root = content.getValue("modelRoot").jsonObject.getValue("rootGroups").jsonArray.single().jsonObject
        val elements = root.getValue("Group").jsonObject.getValue("elements").jsonArray
        val rule = elements.single { it.jsonObject["Rule"] != null }.jsonObject.getValue("Rule").jsonObject
        val code = content["modelConfig"]?.jsonObject?.get("conditionLanguage")?.jsonObject
            ?.get("code")?.jsonPrimitive?.content ?: "en_US"
        val language = checkNotNull(ConditionLanguage.of(code))
        record(rule.getValue("errorCondition").jsonPrimitive.content, language, SyntaxEntry.CONDITION, model = model)
    }
}
