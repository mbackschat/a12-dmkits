package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/*
 * How A12's text-map reader binds a localized-text member: one statement for every member that binds through
 * `LocalizedTextMapDeserializer` (KERNEL-FINDINGS KF646, `adapter.laws.CheckerLocalizedTextShapeAgreementTest`,
 * measured on twelve carriers).
 *
 * The reader loops over whatever node the member holds, so a list yields its elements, an object its values,
 * and a scalar or an empty list yields nothing and binds an empty map. Each entry must be an object whose
 * `locale` and `text` are both JSON strings, the locale in a spelling A12's parser accepts; any other entry
 * refuses the source, and so does a `null` member. A refused source leaves no model, so the verdict is
 * [CK_MODEL_NOT_BUILDABLE] wherever the member sits.
 */

/** The entries A12 reads from a localized-text [member]: a list's elements or an object's values. */
internal fun localizedTextEntries(member: JsonElement?): List<JsonObject> = when (member) {
    is JsonArray -> member.filterIsInstance<JsonObject>()
    is JsonObject -> member.values.filterIsInstance<JsonObject>()
    else -> emptyList()
}

/** Why A12's reader refuses this localized-text [member], completing "the member …", or null when it binds. */
internal fun localizedTextRefusal(member: JsonElement?): String? {
    val entries = when (member) {
        is JsonNull -> return "is `null`, which A12's text map cannot be"
        is JsonArray -> member.toList()
        is JsonObject -> member.values.toList()
        else -> return null
    }
    for (entry in entries) {
        val body = entry as? JsonObject ?: return "holds an entry that is not a JSON object"
        val locale = body.string("locale") ?: return "holds an entry without a string `locale`"
        body.string("text") ?: return "holds an entry without a string `text`"
        // The empty code is the one A12 READS without producing a locale, so it binds and covers nothing.
        if (locale.isNotEmpty() && localeKeyOrNull(locale) == null) {
            return "names `$locale`, a locale code A12 cannot build a model with"
        }
    }
    return null
}

/**
 * Each localized-text member of one declaration that no other family reads, with the reason A12's reader
 * refuses it. A rule's and a computation's `errorMessage` and a string type's are [authoredMessages]'.
 */
internal fun JsonObject.declarationTextRefusals(kind: String): List<String> {
    val envelope = this[kind] as? JsonObject
    val members = mutableListOf(
        "`externalDescription`" to this["externalDescription"],
        "`internalDescription`" to this["internalDescription"],
    )
    when (DeclaredKind.of(kind)) {
        DeclaredKind.FIELD -> envelope?.let { field ->
            members += "`label`" to field["label"]
            members += "`helperText`" to field["helperText"]
            members += "`requirednessConfig.errorMessage`" to (field["requirednessConfig"] as? JsonObject)?.get("errorMessage")
            members += (field["fieldType"] as? JsonObject).fieldTypeTextMembers(withStringMessage = false)
        }
        DeclaredKind.GROUP -> members += "`label`" to envelope?.get("label")
        else -> Unit
    }
    return members.refusals()
}

/** Each localized-text member of one `content.typeDefinitions` entry, with the reason A12's reader refuses it. */
internal fun JsonObject.typeDefinitionTextRefusals(): List<String> = (
    listOf(
        "`externalDescription`" to this["externalDescription"],
        "`internalDescription`" to this["internalDescription"],
    ) + (this["fieldType"] as? JsonObject).fieldTypeTextMembers(withStringMessage = true)
    ).refusals()

/**
 * A field type's text members: an Enumeration's value labels and message, and optionally a string type's message.
 * Only the body the discriminator names is read, as `FieldtypeDeserializer.deriveFieldtype` reads only its case's.
 */
private fun JsonObject?.fieldTypeTextMembers(withStringMessage: Boolean): List<Pair<String, JsonElement?>> {
    val type = this?.namedFieldType()
    val enumeration = FieldType.ENUMERATION.takeIf { it == type }?.bodyIn(this)
    val labels = (enumeration?.get("values") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        .map { value -> "the `label` of the enumeration value `${value.string("value")}`" to value["label"] }
    val messages = listOfNotNull(
        enumeration?.let { "the enumeration's `errorMessage`" to it["errorMessage"] },
        FieldType.STRING.takeIf { withStringMessage && it == type }?.bodyIn(this)
            ?.let { "the string type's `errorMessage`" to it["errorMessage"] },
    )
    return labels + messages
}

private fun List<Pair<String, JsonElement?>>.refusals(): List<String> =
    mapNotNull { (name, member) -> localizedTextRefusal(member)?.let { "$name $it" } }
