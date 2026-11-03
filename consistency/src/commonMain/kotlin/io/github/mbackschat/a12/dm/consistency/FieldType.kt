package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The field types a DocumentModel can declare, by the `fieldType.type` discriminator A12 reads.
 *
 * The twelve are the case list of the single `switch` in the Kernel's `FieldtypeDeserializer.deriveFieldtype`
 * (`kernel-md-serializer`, re-derived on kernel 31.1.1 at `a12-kernel` eec9134), whose `default` raises, so they
 * are the vocabulary by construction rather than by survey, and any other spelling is a source A12 cannot read
 * ([fieldTypeBindingFindings]); `UnspecifiedType` left it in 31.1. The same spelling keys the type's own body
 * beside the discriminator, which is what [bodyIn] reads.
 *
 * One enum rather than a string at each reader because a misspelling is silent — it simply never matches — and
 * one shipped that way: a base-type row keyed `CustomType`, a token no model carries. An undeclared spelling
 * reads as no type at all, so no family judges a type A12 never reads; the model draws
 * `CK_MODEL_NOT_BUILDABLE`, beside which no consistency verdict exists in either direction.
 */
internal enum class FieldType(val wire: String) {
    STRING("StringType"),
    NUMBER("NumberType"),
    DATE("DateType"),
    DATE_TIME("DateTimeType"),
    DATE_RANGE("DateRangeType"),
    DATE_FRAGMENT("DateFragmentType"),
    ENUMERATION("EnumerationType"),
    TIME("TimeType"),
    TYPE_DEF("TypeDefType"),
    CUSTOM_FIELD("CustomFieldType"),
    CONFIRM("ConfirmType"),
    BOOLEAN("BooleanType"),
    ;

    /**
     * This type's own body in a declared `fieldType` object, or null where it is not an object.
     *
     * An ABSENT body reads as an empty one: kernel 31.1.1's deserializer builds the type from its defaults when the
     * member is missing, and every type's verdict is then the one an empty body draws, on all twelve
     * (`rulekit.validate.laws.FieldTypeBindingLawsTest`, KERNEL-FINDINGS KF654). On 30.8.1 eight of them refused it.
     */
    fun bodyIn(declared: JsonObject?): JsonObject? = when (val body = declared?.get(wire)) {
        null -> declared?.let { EMPTY_BODY }
        else -> body as? JsonObject
    }

    /** A12's spelling, because every message names the type as the model declares it. */
    override fun toString(): String = wire

    companion object {
        private val EMPTY_BODY = JsonObject(emptyMap())

        /** Case-sensitive, as the Kernel's `switch` is: `"numberType"` is no type at all. */
        fun of(wire: String?): FieldType? = entries.firstOrNull { it.wire == wire }
    }
}

/** The type a declared `fieldType` object names, or null where it names none A12 declares. */
internal fun JsonObject.namedFieldType(): FieldType? = FieldType.of((this["type"] as? JsonPrimitive)?.contentOrNull)
