package io.github.mbackschat.a12.dm.interpreter.load

import io.github.mbackschat.a12.dm.interpreter.javaTrimmed
import io.github.mbackschat.a12.dm.interpreter.configFlag
import io.github.mbackschat.a12.dm.interpreter.orRefuse
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import io.github.mbackschat.a12.dm.interpreter.model.RangeYearInterpretation
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldTypeDeclaration
import io.github.mbackschat.a12.dm.interpreter.model.DatePrecision
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.isA12DecimalText
import io.github.mbackschat.a12.dm.interpreter.model.printedNumberBound
import io.github.mbackschat.a12.dm.interpreter.model.resolveFractionalPair
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.Requiredness

/*
 * A declared field's `fieldType` read into a [FieldDef]: its kind, format, bounds, enumeration and
 * requiredness, each bound the way A12's reader binds it. Split from [ModelLoader], whose tree walk calls
 * [fieldDef] once per field.
 */

internal fun fieldDef(node: JsonElement, id: String): FieldDef {
    requireConsistentEnumSValue(node, id)
    val declaredMinimumFraction = typedPrimitiveInt(node, id, "NumberType", "minFractionalDigits")
    val declaredMaximumFraction = scaleOf(node, id)
    val fraction = declaredMaximumFraction?.let { resolveFractionalPair(declaredMinimumFraction, it) }
    return FieldDef(
        path = id,
        kind = kindOf(node, id),
        labels = localizedTexts(node.path("Field").path("label")),
        scale = fraction?.maximum,
        fractionalPartChecked = fraction?.checked ?: true,
        signed = !typedBool(node, id, "NumberType", "positivesOnly"),
        required = requirednessOf(node),
        format = formatOf(node),
        rangeSeparator = rangeSeparatorOf(node),
        rangeYearInterpretation = rangeYearInterpretationOf(node),
        datePrecision = datePrecisionOf(node, id),
        // The opt-in additional check rides THREE declaration objects, not one: `DateType`,
        // `DateFragmentType` and `DateRangeType` each declare `youngerThan1900Check` (kernel-md a12internal
        // fieldtypes at 30.8.1). Reading only `DateType` left the flag inert on the other two, so a legal
        // model carrying it there validated as if it were absent (KF253).
        rejectBefore1900 = typedBool(node, id, "DateType", "youngerThan1900Check")
            || typedBool(node, id, "DateFragmentType", "youngerThan1900Check")
            || typedBool(node, id, "DateRangeType", "youngerThan1900Check"),
        enumValues = enumValuesOf(node),
        enumValueLabels = enumValueLabelsOf(node),
        enumCategories = enumCategoriesOf(node),
        enumSValue = enumSValueOf(node),
        customType = customFieldTypeOf(node, id),
        minLength = typedInteger(node, id, "StringType", "minLength"),
        maxLength = typedInteger(node, id, "StringType", "maxLength"),
        pattern = stringTypeText(node, "pattern"),
        patternMessages = localizedTexts((typed(node, "StringType") ?: typed(node, "EnumerationType"))
            .path("errorMessage")),
        requiredMessages = localizedTexts(node.path("Field").path("requirednessConfig").path("errorMessage")),
        noValueValidation = typedBool(node, id, "StringType", "noValueValidation"),
        minValue = declaredMaximumFraction?.let {
            printedNumberBound(numberBoundText(node, id, "minValue"), declaredMinimumFraction, it)
        },
        maxValue = declaredMaximumFraction?.let {
            printedNumberBound(numberBoundText(node, id, "maxValue"), declaredMinimumFraction, it)
        },
        minFractionalDigits = fraction?.minimum,
        lineBreaksPermitted = typedBool(node, id, "StringType", "lineBreaksPermitted"),
        leadingZerosAllowed = typedBool(node, id, "NumberType", "leadingZerosAllowed"),
        zeroNotAllowed = typedBool(node, id, "NumberType", "zeroNotAllowed"),
        maxIntegerDigits = typedInteger(node, id, "NumberType", "maxIntegerDigits"),
        amountTrait = "amount" == numberTypeText(node, "trait"),
        numberMinLength = typedInteger(node, id, "NumberType", "minLength"),
        numberMaxLength = typedInteger(node, id, "NumberType", "maxLength"),
        global = configFlag(node.path("Field").path("global")).orRefuse { throw unbindableFlag("$id: Field.global") },
    )
}

/**
 * Reject a foreign / hand-authored enum whose S-value is OUTSIDE its declared value domain — the kernel's
 * `MVK_S_VALUE_INVALID` model-consistency error (IF221). Such a model is not kernel-consistent: BOTH kernel
 * strategies REFUSE to even generate validation code for it (there is no runtime "staged-formal-check" path),
 * so a valid model can never carry it. The typed builders fail-fast on it too (`FieldTypeSpec.EnumSpec`), but
 * a raw DM-JSON foreign model could — and the loader must reject it LOUD rather than silently stage the
 * out-of-domain token and fabricate a runtime formal error (the sibling of IF145's prepared-boundary gate).
 */
private fun requireConsistentEnumSValue(node: JsonElement, id: String) {
    val sValue = enumSValueOf(node) ?: return
    val values = enumValuesOf(node)
    // A12 also admits a padded or blank S-value by its trim, Java's `String.trim` (KF569), and then stages the
    // text as written, filled and without a formal check (KF574), which the staging path does as it stands.
    val trimmed = sValue.javaTrimmed()
    if (sValue in values || trimmed.isEmpty() || values.any { it.javaTrimmed() == trimmed }) return
    throw IllegalArgumentException(
        "Field '$id': the enum S-value '$sValue' is not among the declared enumeration values $values " +
            "(kernel MVK_S_VALUE_INVALID). An out-of-domain s-value makes the model kernel-inconsistent and " +
            "ungeneratable; repair the model upstream before loading.")
}

// ── Field-type readers ─────────────────────────────────────────────────────────────────────────

private fun kindOf(fieldNode: JsonElement?, path: String): Kind =
    when (val fieldType = fieldNode.path("Field").path("fieldType").path("type").asText()) {
        "NumberType" -> Kind.NUMBER
        "DateType" -> Kind.DATE
        // The kernel collapses a DateFragmentType to a plain Date (KERNEL-FINDINGS §6 "a date fragment IS a
        // DateOperand"); the interpreter loads it as a DATE with a FRAGMENT precision + the fragment format,
        // and the eval completes the missing components (Document.resolveValue / completeFragmentToIso).
        "DateFragmentType" -> Kind.DATE
        "DateTimeType" -> Kind.DATE_TIME
        "TimeType" -> Kind.TIME
        "DateRangeType" -> Kind.DATE_RANGE
        "BooleanType" -> Kind.BOOLEAN
        "ConfirmType" -> Kind.CONFIRM
        "StringType", "EnumerationType", "CustomFieldType" -> Kind.STRING
        else -> throw InvalidPreparedModelSourceException(
            path,
            "unknown field type '$fieldType' at '$path'",
        )
    }

/** A date-ish field's rendering format, read for DATE / DATE_TIME / DATE_RANGE / TIME as `<Type>.format`,
 *  and for a DateFragmentType as its `formatOfFragment` (a partial format, one of `yyyy-MM`/`yyyy`/`MM`/
 *  `MM-dd`) — which is what drives the fragment completion (`completeFragmentToIso`); else null. (TIME is
 *  carried for the standalone interpreter's format-driven parse — the kernel itself constrains a TimeType
 *  format to the default, but a hand-built model may declare another — IF56.) */
private fun formatOf(fieldNode: JsonElement?): String? {
    val type = fieldNode.path("Field").path("fieldType")
    val typeName = type.path("type").asText()
    return when (typeName) {
        "DateType", "DateTimeType", "DateRangeType", "TimeType" -> type.path(typeName).path("format").asText().ifEmpty { null }
        "DateFragmentType" -> type.path(typeName).path("formatOfFragment").asText().ifEmpty { null }
        else -> null
    }
}

/** An ENUMERATION field's XML values in declaration order — the positional key for `EnumCategory`. */
private fun enumValuesOf(fieldNode: JsonElement?): List<String> =
    fieldNode.path("Field").path("fieldType").path("EnumerationType").path("values").elements()
        .map { it.path("value").asText() }

/** An ENUMERATION field's per-value localized labels (stored value -> locale -> text) — what a rule
 *  message's `$field.value$` renders in place of the stored token when the declaration carries one. */
private fun enumValueLabelsOf(fieldNode: JsonElement?): Map<String, Map<String, String>> {
    val labels = linkedMapOf<String, Map<String, String>>()
    for (v in fieldNode.path("Field").path("fieldType").path("EnumerationType").path("values").elements()) {
        val perLocale = linkedMapOf<String, String>()
        for (l in v.path("label").elements()) {
            perLocale[l.path("locale").asText()] = l.path("text").asText()
        }
        if (perLocale.isNotEmpty()) labels[v.path("value").asText()] = perLocale
    }
    return labels
}

/** An ENUMERATION field's declared stored-token S-value (DM-JSON `sValue`) — the model-owned index
 *  default (SPEC-2026-07-23-14); null when absent. */
private fun enumSValueOf(fieldNode: JsonElement?): String? =
    fieldNode.path("Field").path("fieldType").path("EnumerationType").path("sValue").asTextOrNull()
        ?.ifEmpty { null }

/** An ENUMERATION field's categories (name → positional value list) — the `EnumCategory` mapping. */
private fun enumCategoriesOf(fieldNode: JsonElement?): Map<String, List<String>> {
    val e = fieldNode.path("Field").path("fieldType").path("EnumerationType")
    val categories = linkedMapOf<String, List<String>>()
    for (c in e.path("categories").elements()) {
        categories[c.path("name").asText()] = c.path("values").elements().map { it.asText() }
    }
    return categories
}

/** A DATE_RANGE field's `interpretationOfYear` (`DateRangeType.interpretationOfYear`); STANDARD when the
 *  property is absent, which is how the declaration expresses the default. */
private fun rangeYearInterpretationOf(fieldNode: JsonElement?): RangeYearInterpretation {
    val type = fieldNode.path("Field").path("fieldType")
    if (type.path("type").asText() != "DateRangeType") return RangeYearInterpretation.STANDARD
    return when (type.path("DateRangeType").path("interpretationOfYear").asText()) {
        "FROM" -> RangeYearInterpretation.FROM
        "TO" -> RangeYearInterpretation.TO
        else -> RangeYearInterpretation.STANDARD
    }
}

/** A DATE_RANGE field's two-date separator (`DateRangeType.rangeSeparator`), else null.
 *
 *  **An absent and an EMPTY separator are both A12's default `/`** on kernel 31.1.1: the builder pre-initialises the
 *  member, and the expand copy omits an empty string, so `""` is read back as that default — `0102` under `MM` with
 *  `""` draws `datumBereichTrennerFehlt` (KERNEL-FINDINGS KF658, `adapter.laws.EmptyDeclarationMemberDiffTest`). On
 *  30.8.1 `""` was a separator of its own that split the value in the middle (IF306). A null here would drop the field
 *  out of [ModelDef.rangeConfig] and leave every stored value unchecked, so only a non-range field yields one. */
private fun rangeSeparatorOf(fieldNode: JsonElement?): String? {
    val type = fieldNode.path("Field").path("fieldType")
    if (type.path("type").asText() != "DateRangeType") return null
    val declared = type.path("DateRangeType").path("rangeSeparator").asTextOrNull()
    return if (declared.isNullOrEmpty()) DEFAULT_RANGE_SEPARATOR else declared
}

/** The kernel's `FieldTypeConstants.A12_DATE_RANGE_SEPARATOR`, measured for an absent and an empty member. */
private const val DEFAULT_RANGE_SEPARATOR = "/"

/** A DATE field's precision: a `DateFragmentType` is [DatePrecision.FRAGMENT] (its format carries only some
 *  components — the eval completes the rest); a `DateType` accepts the kernel's complete four-value enum.
 *  The Kernel refuses null, malformed, and unknown values, so they must never silently default. */
private fun datePrecisionOf(fieldNode: JsonElement?, path: String): DatePrecision {
    val type = fieldNode.path("Field").path("fieldType")
    val typeName = type.path("type").asText()
    if (typeName == "DateFragmentType") {
        return DatePrecision.FRAGMENT
    }
    if (typeName != "DateType") {
        return DatePrecision.FULL
    }
    val dateType = type.path("DateType") as? JsonObject
    val precisionNode = dateType?.get("datePrecision") ?: return DatePrecision.FULL
    val precision = (precisionNode as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?: throw InvalidPreparedModelSourceException(
            path,
            "datePrecision at '$path' must be a known string value",
        )
    return when (precision) {
        "FULL" -> DatePrecision.FULL
        "DAY_OPTIONAL" -> DatePrecision.DAY_OPTIONAL
        "MONTH_OPTIONAL" -> DatePrecision.MONTH_OPTIONAL
        "YEAR_OPTIONAL" -> DatePrecision.YEAR_OPTIONAL
        else -> throw InvalidPreparedModelSourceException(
            path,
            "unknown datePrecision '$precision' at '$path'",
        )
    }
}

/** A field's requiredness (`Field.requirednessConfig.mode`); absent / `notRequired` → not required. */
private fun requirednessOf(fieldNode: JsonElement?): Requiredness =
    when (fieldNode.path("Field").path("requirednessConfig").path("mode").asText()) {
        "absoluteOrRelativeToNextRepAncestor" -> Requiredness.ABSOLUTE
        "relativeToParent" -> Requiredness.RELATIVE_TO_PARENT
        else -> Requiredness.NOT_REQUIRED
    }

/** A NUMBER field's DECLARED scale (`NumberType.maxFractionalDigits`, `-1` included — [resolveFractionalPair]
 *  reads it), or null for a non-number field. The kernel's
 *  `NumberType.maxFractionalDigits` is a **primitive int defaulting to 0** (kernel-md-a12internal), so a
 *  NumberType that omits it is scale-0 — an INTEGER: any decimal separator is `zahlHatDezimalTrenner`. A
 *  null return here means "not a number field", NOT "no scale constraint" (a number is never unconstrained). */
private fun scaleOf(fieldNode: JsonElement?, id: String): Int? {
    val type = fieldNode.path("Field").path("fieldType")
    if (type.path("type").asText() != "NumberType") return null
    return typedPrimitiveInt(fieldNode, id, "NumberType", "maxFractionalDigits") ?: 0
}

/** A `CustomFieldType` field's validator name and optional length-bound inputs; else null. */
private fun customFieldTypeOf(fieldNode: JsonElement?, id: String): CustomFieldTypeDeclaration? {
    val type = typed(fieldNode, "CustomFieldType")
    val name = type.path("name")
    if (!name.isTextual()) return null
    return CustomFieldTypeDeclaration(
        name.asText(),
        // Bound as every `Integer` member is: which values build is measured here too
        // (`FieldTypeIntMemberBindingDiffTest`), while the bound VALUE is assumed from the other types' members, since
        // A12 only hands it to a registered validator.
        typedInteger(fieldNode, id, "CustomFieldType", "minLength"),
        typedInteger(fieldNode, id, "CustomFieldType", "maxLength"),
    )
}

/** A `String` property of a `StringType` field type (e.g. `pattern`), or null. */
private fun stringTypeText(fieldNode: JsonElement?, prop: String): String? {
    val v = typed(fieldNode, "StringType").path(prop)
    return if (v.isTextual()) v.asText() else null
}

/** A NUMBER constraint kept as raw text (`minValue`/`maxValue`/`trait`) — verbatim for an exact, scale-aware
 *  compare (F1: never round-tripped through a number); or null for a non-number field / absent property. */
private fun numberTypeText(fieldNode: JsonElement?, prop: String): String? {
    val v = typed(fieldNode, "NumberType").path(prop)
    return if (v.isNumberNode() || v.isTextual()) v.asText() else null
}

/** A primitive `int` property of a `<typeName>` field type, bound as A12's POJO reader binds it, `"2"` and `2.7`
 *  included ([boundJavaPrimitiveInt], KF593); absent → null, and a value A12 cannot bind refuses the model. */
private fun typedPrimitiveInt(fieldNode: JsonElement?, id: String, typeName: String, prop: String): Int? =
    boundJavaPrimitiveInt(typed(fieldNode, typeName).path(prop), "$id: $typeName.$prop")

/** A boxed `Integer` property of a `<typeName>` field type, bound as A12's POJO reader binds it, `"2"` and `2.7`
 *  included ([boundJavaInteger], KF593); absent or no value → null, and a value A12 cannot bind refuses the model. */
private fun typedInteger(fieldNode: JsonElement?, id: String, typeName: String, prop: String): Int? =
    boundJavaInteger(typed(fieldNode, typeName).path(prop), "$id: $typeName.$prop")

/** A `boolean` property of a `<typeName>` field type, bound as A12's POJO reader binds it — `1` and `" TRUE"`
 *  set it, `0` and `"null"` clear it (KF569) — rather than as a JSON boolean, which read both set spellings
 *  as the default (IF404). Absent → false, the kernel default; a value A12 cannot bind refuses the model. A
 *  NUMBER field is unsigned iff it declares `positivesOnly`, so any other kind reads as signed. */
private fun typedBool(fieldNode: JsonElement?, id: String, typeName: String, prop: String): Boolean =
    configFlag(typed(fieldNode, typeName).path(prop)).orRefuse { throw unbindableFlag("$id: $typeName.$prop") }

private fun unbindableFlag(member: String): InvalidPreparedModelSourceException =
    invalidElement(member, "$member is not a boolean A12 can bind, so A12 does not load the model")

/** The `Field.fieldType.<typeName>` object, but only when the field's declared `type` matches (else null). */
private fun typed(fieldNode: JsonElement?, typeName: String): JsonElement? {
    val type = fieldNode.path("Field").path("fieldType")
    return if (typeName == type.path("type").asText()) type.path(typeName) else null
}


/**
 * A NUMBER field's `minValue` or `maxValue` text, which A12's reader binds to a `BigDecimal` (KF591): after Java's
 * `trim` it must spell an ASCII decimal, so a no-break space, a non-ASCII digit or a word leaves A12 with no model
 * and this refuses it. The text `null` is the null value to that reader, so it declares no bound (KF593). An
 * unquoted number is a lexeme the strict parse has already held to JSON's grammar, and the decimal rule's scales
 * still apply to it (KF593).
 */
private fun numberBoundText(fieldNode: JsonElement?, id: String, prop: String): String? {
    val text = numberTypeText(fieldNode, prop) ?: return null
    if (!typed(fieldNode, "NumberType").path(prop).isTextual()) {
        if (isA12DecimalText(text)) return text
    } else {
        val trimmed = text.javaTrimmed()
        if (trimmed == "null") return null
        if (trimmed.isEmpty() || isA12DecimalText(trimmed)) return text
    }
    throw invalidElement("$id: NumberType.$prop", "$id: NumberType.$prop '$text' is not a decimal A12 can bind, so A12 does not load the model")
}
