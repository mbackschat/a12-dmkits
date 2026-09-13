package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Whether A12's reader can BIND a field's declared `fieldType` — the discriminated union, not its contents.
 *
 * A `fieldType` A12 cannot bind refuses the source outright: no diagnostic, no code, the model simply does
 * not load, so a clean report about it would present silence as a verdict. Measured across the whole
 * discriminator vocabulary and the shapes around it ([KERNEL-FINDINGS KF308],
 * `rulekit.validate.laws.FieldTypeBindingLawsTest`), on a field no declaration references — the ordering
 * family's rows all sit on a field some group orders by, so a refusal measured there cannot be attributed to
 * the type alone.
 *
 * **The admitted vocabulary is DERIVED, which is what makes reporting an unknown name safe.** A checker that
 * refuses a spelling it does not recognise is claiming to know the complete set, and a hand-assembled set
 * that is short by one member turns every field using that member into a false rejection — the direction
 * this checker must never take. [DECLARED_FIELD_TYPES] is not assembled: it is the case list of the single
 * `switch` in `FieldtypeDeserializer.deriveFieldtype`, whose `default` raises, so the thirteen names are the
 * vocabulary by construction rather than by survey.
 *
 * **Two tiers, and only the reader's is this family's.** A body a type cannot construct from
 * (`"EnumerationType":{}`) and an ABSENT `fieldType` member both leave a model A12 read perfectly well and
 * then failed to build. Those belong to a separate slice, tracked as its own coverage gap, because the
 * absent-member case is true of most field declarations anywhere and reporting it is a change of
 * denominator rather than an added row. What this family reports is only what A12's READER refuses.
 */
fun PreparedModel.fieldTypeBindingFindings(): List<Finding> =
    elements.filter { it.declaredKind == DeclaredKind.FIELD }.mapNotNull { field ->
        val declared = field.body?.get("fieldType") ?: return@mapNotNull null
        unbindableFieldType(field, declared)
    }

/**
 * The thirteen names `FieldtypeDeserializer.deriveFieldtype` switches on, paired with whether that case
 * guards a missing body.
 *
 * `true` means the case reads `if (typeNode == null) …builder().build()` and binds without the body; `false`
 * means it dereferences the body behind a `// typeNode is never null` comment, which is a claim rather than
 * a guard — and the measurement confirms all eight of those refuse the reader when the body is absent.
 * Source-read of kernel `30.8.1`, 2026-09-13; the split is measured row by row against its control grid.
 */
private val DECLARED_FIELD_TYPES = mapOf(
    "StringType" to true,
    "NumberType" to true,
    "ConfirmType" to true,
    "BooleanType" to true,
    "UnspecifiedType" to true,
    "DateType" to false,
    "DateTimeType" to false,
    "DateRangeType" to false,
    "DateFragmentType" to false,
    "EnumerationType" to false,
    "TimeType" to false,
    "TypeDefType" to false,
    "CustomFieldType" to false,
)

/**
 * One field's `fieldType`, judged as A12's reader binds it.
 *
 * The order mirrors the reader's own: the member must be an object, that object must carry a `type` the
 * deserializer switches on, and a case that does not guard its body must find one.
 */
private fun unbindableFieldType(field: DeclaredElement, declared: JsonElement): Finding? {
    // An explicit `null` is not an absent member: Jackson reaches `Field.fieldType`'s Lombok `@NonNull`
    // setter with it and the reader raises, where an omitted member never calls the setter at all. The two
    // land in different tiers, which is why this arm cannot simply treat null as nothing declared.
    if (declared is JsonNull) {
        return unbindable(field, "`fieldType` is explicitly null, which A12's reader refuses")
    }
    if (declared !is JsonObject) {
        return unbindable(field, "`fieldType` holds a value rather than a typed object")
    }
    val discriminator = declared["type"]?.asBoundText()
        ?: return unbindable(field, "`fieldType` names no `type`, so A12 binds nothing and refuses the null")
    val guardsItsBody = DECLARED_FIELD_TYPES[discriminator]
        ?: return unbindable(
            field,
            "`fieldType` names the type `$discriminator`, which A12 does not declare — the vocabulary is " +
                "case-sensitive and holds exactly: ${DECLARED_FIELD_TYPES.keys.sorted().joinToString(", ")}",
        )
    if (!guardsItsBody && declared[discriminator] == null) {
        return unbindable(
            field,
            "`fieldType` names `$discriminator` but carries no `$discriminator` body, which that type " +
                "reads without checking",
        )
    }
    return null
}

/**
 * The discriminator as A12 reads it — `JsonNode.asText()`, which stringifies any scalar rather than demanding
 * a JSON string, so a numeric or null `type` becomes a name that simply is not in the vocabulary and falls to
 * the same raising default. A container has no such reading and is reported as the missing discriminator it
 * is. Excluding null here would be a branch with no verdict of its own, since both paths end unbindable.
 */
private fun JsonElement.asBoundText(): String? = (this as? JsonPrimitive)?.content

private fun unbindable(field: DeclaredElement, complaint: String) = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    CK_MODEL_NOT_BUILDABLE,
    "$complaint — A12's own reader refuses the source outright, so the model never loads and no " +
        "consistency verdict about it exists.",
    field.path,
)
