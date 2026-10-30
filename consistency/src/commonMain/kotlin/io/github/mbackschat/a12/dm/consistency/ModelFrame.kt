package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a member of a model's FRAME costs A12 when it is missing or of a kind A12 cannot bind: the `header` and
 * `content` objects and the members under them, down to the elements, type definitions and locale entries their own
 * readers judge ([KERNEL-FINDINGS KF650]).
 *
 * [READ]: A12's reader refuses the source, so the model never loads, in every model the entry reaches.
 * [CHECK]: A12 builds the model and then cannot run its consistency check, which costs only the ENTRY model: a
 * reached model's version, model info and character restriction are never asked for (`adapter.WorkspaceReachedModelDiffTest`).
 */
internal enum class FrameTier { READ, CHECK }

/** One frame member A12 cannot use, as a sentence naming it. */
internal data class FrameDefect(val tier: FrameTier, val sentence: String)

/**
 * Every frame member of [this] model A12 cannot use, in document order, a member's own defect hiding its members'.
 *
 * Measured member by member through the Kernel gate (kernel `30.8.1`, 2026-10-29), absent and as `null`, an object,
 * an array, a text, a number and a boolean; the kinds follow the kernel's own model classes (`HeaderImpl`,
 * `DocumentModelContent`, `DocumentModelInfo`, `DocumentModelConfig`). A text takes any JSON scalar and no object or
 * array, a list takes only an array and an object only an object, and `null` costs only where the kernel marks the
 * member non-null. The condition language binds as a locale: a text, or an object whose `code` is a text.
 */
internal fun JsonElement?.frameDefects(): List<FrameDefect> = buildList {
    if (this@frameDefects !is JsonObject) {
        add(FrameDefect(FrameTier.READ, "the model is not a JSON object"))
    } else {
        FRAME.forEach { inspect(it, this@frameDefects[it.name], it.name) }
    }
}

private fun MutableList<FrameDefect>.inspect(member: FrameMember, value: JsonElement?, path: String) {
    val cost = when {
        value == null -> member.absent
        value is JsonNull -> member.nulled
        member.bind.admits(value) -> null
        else -> FrameTier.READ
    }
    if (cost != null) {
        add(FrameDefect(cost, "`$path` ${describe(value)}"))
        return
    }
    when (value) {
        is JsonObject -> member.members.forEach { inspect(it, value[it.name], "$path.${it.name}") }
        is JsonArray -> member.entries?.let { entry -> value.forEachIndexed { i, it -> inspect(entry, it, "$path[$i]") } }
        else -> Unit
    }
}

private fun describe(value: JsonElement?): String = when (value) {
    null -> "is absent"
    is JsonNull -> "holds `null`"
    is JsonObject -> "holds an object"
    is JsonArray -> "holds an array"
    is JsonPrimitive -> "holds `$value`"
}

private enum class Bind {
    OBJECT, LIST, TEXT, LOCALE;

    fun admits(value: JsonElement): Boolean = when (this) {
        OBJECT -> value is JsonObject
        LIST -> value is JsonArray
        TEXT -> value is JsonPrimitive
        LOCALE -> value is JsonPrimitive && value.isString ||
            value is JsonObject && (value["code"] as? JsonPrimitive)?.isString == true
    }
}

/**
 * One frame member: how A12 binds it and what it costs [absent] or [nulled], null where it costs nothing. An absent
 * member costs nothing unless the kernel needs it; `null` in a member marked non-null costs a [FrameTier.READ].
 */
private class FrameMember(
    val name: String,
    val bind: Bind,
    val absent: FrameTier? = null,
    val nulled: FrameTier? = null,
    val members: List<FrameMember> = emptyList(),
    val entries: FrameMember? = null,
)

private fun text(name: String) = FrameMember(name, Bind.TEXT)

private fun list(name: String) = FrameMember(name, Bind.LIST)

/**
 * The frame, from the measured grid. The members other readers own are left out: a locale entry
 * ([declaredLocales]), a type definition, an element, and the base year and three `boolean`s
 * ([PreparedModel.unbindableBooleans]).
 */
private val FRAME = listOf(
    FrameMember("header", Bind.OBJECT, absent = FrameTier.READ, nulled = FrameTier.READ, members = listOf(
        text("id"), text("modelType"), text("description"),
        FrameMember("modelVersion", Bind.TEXT, absent = FrameTier.CHECK, nulled = FrameTier.CHECK),
        FrameMember("locales", Bind.LIST, absent = FrameTier.CHECK, nulled = FrameTier.CHECK),
        list("labels"), list("annotations"), list("modelReferences"),
    )),
    FrameMember("content", Bind.OBJECT, absent = FrameTier.READ, nulled = FrameTier.READ, members = listOf(
        FrameMember("modelInfo", Bind.OBJECT, absent = FrameTier.CHECK, nulled = FrameTier.READ, members = listOf(
            // An absent name builds and draws MVK_INVALID_NAME_4_GENERATED_CODE, judged with the name A12 derives
            // (FrameValues.kt).
            FrameMember("name", Bind.TEXT, nulled = FrameTier.READ),
            text("variant"), text("branch"), text("revision"), text("comment"), text("joinedModelsInfo"),
        )),
        FrameMember("modelConfig", Bind.OBJECT, nulled = FrameTier.READ, members = listOf(
            FrameMember("supportedCharacters", Bind.LIST, nulled = FrameTier.READ,
                entries = FrameMember("", Bind.TEXT, nulled = FrameTier.CHECK)),
            FrameMember("decimalSeparator", Bind.TEXT, nulled = FrameTier.READ),
            FrameMember("timeZone", Bind.TEXT, nulled = FrameTier.READ),
            FrameMember("conditionLanguage", Bind.LOCALE, nulled = FrameTier.READ),
        )),
        FrameMember("typeDefinitions", Bind.LIST, nulled = FrameTier.READ),
        FrameMember("modelRoot", Bind.OBJECT, nulled = FrameTier.READ, members = listOf(
            FrameMember("rootGroups", Bind.LIST, nulled = FrameTier.READ),
        )),
    )),
)
