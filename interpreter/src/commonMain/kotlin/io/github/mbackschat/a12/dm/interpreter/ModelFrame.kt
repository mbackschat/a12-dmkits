package io.github.mbackschat.a12.dm.interpreter

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The path of the first member of [this] model's FRAME that A12's reader cannot bind, so that it never loads the
 * model, or null: the `header` and `content` objects and the members under them, down to the elements, type
 * definitions and locale entries their own readers judge (KF650).
 *
 * Measured member by member through the Kernel gate (kernel `30.8.1`, 2026-10-29), absent and as `null`, an object,
 * an array, a text, a number and a boolean, in every model a workspace reaches (`adapter.WorkspaceReachedModelDiffTest`).
 * A text takes any JSON scalar and no object or array, a list takes only an array and an object only an object, and
 * `null` refuses only where the kernel marks the member non-null. The condition language binds as a locale: a text, or
 * an object whose `code` is a text. A member A12 binds but needs later is not refused here: A12 reads and expands
 * such a model, and only evaluation refuses it ([runtimeUnevaluableFrameMember]).
 */
internal fun JsonObject.unbindableFrameMember(): String? = FRAME.firstNotNullOfOrNull { it.unbindableIn(this[it.name], it.name) }

/**
 * The path of the first member of [this] model's frame that A12 binds and its runtime cannot evaluate the model
 * without, or null: a missing `content.modelInfo`, absent or `null` `header.locales`, or a `null` supported
 * character. Each stops both Kernel strategies before any rule runs, with or without a rule in the model, while A12
 * still reads and expands it; a missing `header.modelVersion` costs only the consistency check and is evaluated
 * (KF650; `adapter.laws.CheckOnlyFrameRuntimeDiffTest`, kernel `30.8.1`, 2026-10-30). Read on the expanded entry,
 * whose frame is the only one expansion keeps.
 */
internal fun JsonObject.runtimeUnevaluableFrameMember(): String? {
    val header = this["header"] as? JsonObject
    val content = this["content"] as? JsonObject
    if (content != null && "modelInfo" !in content) return "content.modelInfo"
    if (header?.get("locales").let { it == null || it is JsonNull }) return "header.locales"
    val characters = (content?.get("modelConfig") as? JsonObject)?.get("supportedCharacters") as? JsonArray
    return characters?.indexOfFirst { it is JsonNull }?.takeIf { it >= 0 }?.let { "content.modelConfig.supportedCharacters[$it]" }
}

private fun FrameMember.unbindableIn(value: JsonElement?, path: String): String? = when {
    value == null -> path.takeIf { required }
    value is JsonNull -> path.takeIf { !nullable }
    !bind.admits(value) -> path
    value is JsonObject -> members.firstNotNullOfOrNull { it.unbindableIn(value[it.name], "$path.${it.name}") }
    value is JsonArray -> entries?.let { entry -> value.withIndex().firstNotNullOfOrNull { (i, it) -> entry.unbindableIn(it, "$path[$i]") } }
    else -> null
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

/** One frame member: how A12 binds it, whether its absence refuses the source, and whether `null` binds. */
private class FrameMember(
    val name: String,
    val bind: Bind,
    val required: Boolean = false,
    val nullable: Boolean = true,
    val members: List<FrameMember> = emptyList(),
    val entries: FrameMember? = null,
)

private fun text(name: String) = FrameMember(name, Bind.TEXT)

private fun list(name: String) = FrameMember(name, Bind.LIST)

private fun nonNull(name: String, bind: Bind, members: List<FrameMember> = emptyList(), entries: FrameMember? = null) =
    FrameMember(name, bind, nullable = false, members = members, entries = entries)

/**
 * The frame, from the measured grid. The members other readers own are left out: a locale entry
 * ([declaredLocaleCodes]), a type definition, an element, and the base year and three `boolean`s
 * ([unbindableModelMember]). A `null` time zone A12 refuses as well, and the time-zone reader owns it, refusing it with
 * `InvalidModelTimeZoneException` as it refuses an empty or blank one (`ModelTimeZoneTest`).
 */
private val FRAME = listOf(
    FrameMember("header", Bind.OBJECT, required = true, nullable = false, members = listOf(
        text("id"), text("modelType"), text("description"), text("modelVersion"),
        list("locales"), list("labels"), list("annotations"), list("modelReferences"),
    )),
    FrameMember("content", Bind.OBJECT, required = true, nullable = false, members = listOf(
        nonNull("modelInfo", Bind.OBJECT, members = listOf(
            nonNull("name", Bind.TEXT), text("variant"), text("branch"), text("revision"), text("comment"), text("joinedModelsInfo"),
        )),
        nonNull("modelConfig", Bind.OBJECT, members = listOf(
            nonNull("supportedCharacters", Bind.LIST, entries = text("")),
            nonNull("decimalSeparator", Bind.TEXT),
            text("timeZone"),
            nonNull("conditionLanguage", Bind.LOCALE),
        )),
        nonNull("typeDefinitions", Bind.LIST),
        nonNull("modelRoot", Bind.OBJECT, members = listOf(nonNull("rootGroups", Bind.LIST))),
    )),
)
