package io.github.mbackschat.a12.dm.interpreter.workspace

import io.github.mbackschat.a12.dm.interpreter.ConfigFlag
import io.github.mbackschat.a12.dm.interpreter.JavaIntBinding
import io.github.mbackschat.a12.dm.interpreter.bindJavaInt
import io.github.mbackschat.a12.dm.interpreter.bindJavaPrimitiveInt
import io.github.mbackschat.a12.dm.interpreter.configFlag
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The first integer or boolean member of a type definition's body that A12's reader cannot bind, as a refusal
 * reason; null when every one binds.
 *
 * A12's reader binds a `content.typeDefinitions` entry whether or not a field names it, so an unbindable member there
 * leaves no model (KERNEL-FINDINGS KF569, KF593, `adapter.laws.FieldTypeIntMemberBindingDiffTest`). A referenced
 * definition is inlined and read again as the field's own type, which judges its members a second time. An
 * unreferenced one is never inlined, so this pass is the only place its members are read.
 */
internal fun unbindableTypeMember(fieldType: JsonObject): String? {
    val typeName = (fieldType["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    val body = fieldType[typeName] as? JsonObject ?: return null
    INTEGER_MEMBERS[typeName].orEmpty().forEach { (member, primitive) ->
        val value = body[member] ?: return@forEach
        if (!value.bindsInteger(primitive)) return "$typeName.$member $value is not a whole number A12 can bind"
    }
    BOOLEAN_MEMBERS[typeName].orEmpty().forEach { member ->
        val value = body[member] ?: return@forEach
        if (configFlag(value) == ConfigFlag.UNREADABLE) return "$typeName.$member $value is not a boolean A12 can bind"
    }
    return null
}

private fun JsonElement.bindsInteger(primitive: Boolean): Boolean =
    if (primitive) bindJavaPrimitiveInt() != null else bindJavaInt() != JavaIntBinding.Unbindable

/**
 * Each type body's `int` (true) and `Integer` (false) members, from the kernel's `a12internal.fieldtypes` classes
 * (kernel 31.1.1, `kernel-md-documentmodel` at eec9134). The checker keeps the same table as
 * `FieldType.integerMembers`; this module's dependency gate admits no project edge, so the two cannot share a source.
 */
private val INTEGER_MEMBERS: Map<String, List<Pair<String, Boolean>>> = mapOf(
    "NumberType" to listOf(
        "minFractionalDigits" to true, "maxFractionalDigits" to true,
        "minLength" to false, "maxLength" to false, "maxIntegerDigits" to false,
    ),
    "StringType" to listOf("minLength" to false, "maxLength" to false),
    "CustomFieldType" to listOf("minLength" to false, "maxLength" to false),
)

/** Each type body's primitive `boolean` members, from the same classes (the checker's `FieldType.booleanMembers`). */
private val BOOLEAN_MEMBERS: Map<String, List<String>> = mapOf(
    "StringType" to listOf("lineBreaksPermitted", "noValueValidation", "alphabeticalSorting"),
    "NumberType" to listOf("zeroNotAllowed", "positivesOnly", "leadingZerosAllowed"),
    "DateType" to listOf("youngerThan1900Check"),
    "DateRangeType" to listOf("youngerThan1900Check"),
    "DateFragmentType" to listOf("youngerThan1900Check"),
    "EnumerationType" to listOf("alphabeticalSorting"),
)
