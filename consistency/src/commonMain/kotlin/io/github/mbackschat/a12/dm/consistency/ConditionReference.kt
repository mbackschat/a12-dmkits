package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.referencesIn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * An entity named inside condition or operation text.
 *
 * @param declaredAt the rule or computation that carries the text.
 * @param member the text member it was written in, so a report can name which of a computation's several
 *     texts is at fault rather than only the computation.
 * @param written the reference exactly as authored, which is what a reader has to find and edit.
 */
data class ConditionReference(
    val declaredAt: String,
    val member: String,
    val written: String,
    val target: Reference,
)

/**
 * Every entity a rule's condition or a computation's operations name, bound to what it resolves to.
 *
 * The texts are the model's own: a rule's `errorCondition`, and a computation's `commonPrecondition`, each
 * alternative's `precondition` and its `operation`. They are read as text because that is how A12 stores
 * them — unlike the error and computed targets, which are structured members ([declaredReferences]).
 *
 * References resolve from the declaring GROUP, not the declaring element, which is the opposite of those
 * structured members; [resolveEntity] carries the measurement. Keywords are decided by the model's own
 * [PreparedModel.conditionLanguage], so a field whose name spells an operator of the *other* language is
 * still a reference here.
 */
fun PreparedModel.conditionReferences(): List<ConditionReference> = elements.flatMap { element ->
    val body = element.body ?: return@flatMap emptyList()
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    conditionTexts(element.kind, body).flatMap { (member, text) ->
        referencesIn(text, conditionLanguage).map { written ->
            ConditionReference(
                element.path,
                member,
                written,
                resolveEntity(group, written, element.origin),
            )
        }
    }
}

/**
 * The DSL-bearing members of one declaration, paired with the name a report should use for each.
 *
 * A computation carries up to three kinds at once and every one of them is checked, so naming only the
 * computation would leave an author looking through all of them. Alternatives are numbered by position
 * because they have no names of their own.
 */
private fun conditionTexts(kind: String, body: JsonObject): List<Pair<String, String>> = when (kind) {
    "Rule" -> listOfNotNull(body.text("errorCondition")?.let { "errorCondition" to it })
    "Computation" -> buildList {
        body.text("commonPrecondition")?.let { add("commonPrecondition" to it) }
        (body["computationAlternatives"] as? JsonArray).orEmpty().forEachIndexed { index, alternative ->
            val alt = alternative as? JsonObject ?: return@forEachIndexed
            alt.text("precondition")?.let { add("computationAlternatives[$index].precondition" to it) }
            alt.text("operation")?.let { add("computationAlternatives[$index].operation" to it) }
        }
    }
    else -> emptyList()
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
