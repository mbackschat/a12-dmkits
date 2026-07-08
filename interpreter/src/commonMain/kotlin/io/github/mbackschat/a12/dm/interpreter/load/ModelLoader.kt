package io.github.mbackschat.a12.dm.interpreter.load

import kotlin.jvm.JvmStatic
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import io.github.mbackschat.a12.dm.interpreter.model.ComputationAlternative
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.DatePrecision
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.Requiredness
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.interpreter.model.Severity

/**
 * The interpreter's own DM-JSON → [ModelDef] loader — kernel-free and **multiplatform** (JVM + JS), so a
 * standalone consumer (the browser library, a JVM library user) can load a real model without hand-building a
 * [ModelDef] and without the kernel. It is the engine dmtool's native runtime verbs sit on: `InterpreterModelLoader`
 * (in `:cli`) is a thin JVM delegate over this.
 *
 * ## Approach — tree API, raw-text fidelity
 * The mapper reads via kotlinx-serialization-json's **tree API** ([Json.parseToJsonElement] + a hand-written
 * [JsonElement] → [ModelDef] walk) — no `@Serializable`, no compiler plugin (proposal §11 fork F1). A number's
 * **raw literal is preserved** verbatim ([JsonPrimitive.content], e.g. `100.50` stays `"100.50"`): the value is
 * never round-tripped through a `Double`/JS number, so a declared `minValue`/`maxValue` keeps its exact scale.
 *
 * ## The walk
 * A flat scan-by-type — every model node carries a `type` + full-path `id`, so the walk doesn't depend on the exact
 * element nesting: it records a node iff it has BOTH keys, then recurses into every child. Fields/rules/computations
 * are collected in **document order** (kotlinx [JsonObject] iteration is insertion order, matching the DM-JSON).
 *
 * ## v1 scope — EXPANDED, SELF-CONTAINED models only
 * The loader handles a **single, fully-expanded** model: no `modelReferences`/`imports` resolution and no
 * `TypeDefType` de-referencing. Includes and type-definition libraries are resolved **upstream** by the kernel's
 * `expand()` (native-safe via the read tier) before the DM-JSON reaches here; a model that still carries a
 * `TypeDefType` reference flattens to a [Kind.STRING] field (its underlying kind/constraints unresolved). This
 * mirrors the interpreter's evaluation contract (INTERPRETER-SPEC).
 */
object ModelLoader {

    /** Load an expanded, self-contained DM-JSON model into a kernel-free [ModelDef]. */
    @JvmStatic
    fun load(dmJson: String): ModelDef {
        val root = try {
            Json.parseToJsonElement(dmJson)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("not valid DM-JSON", e)
        }
        val sink = Sink()
        walk(root, sink)
        // The model's base year (content.modelInfo.baseYear, a string) — the value of the BaseYear constant.
        val by = root.path("content").path("modelInfo").path("baseYear").asText()
        val baseYear = if (by.isEmpty()) null else by.trim().toIntOrNull()
        return ModelDef(sink.fields, sink.rules, sink.comps, sink.repeatable, sink.declaredReps, sink.indexFields, baseYear)
    }

    /** The mutable accumulators of one [load] pass — insertion-ordered to match the DM-JSON document order. */
    private class Sink {
        val fields = mutableListOf<FieldDef>()
        val rules = mutableListOf<RuleDef>()
        val comps = mutableListOf<ComputationDef>()
        val repeatable = linkedSetOf<String>()
        val declaredReps = linkedMapOf<String, Int>()
        val indexFields = linkedSetOf<String>()
    }

    private fun walk(node: JsonElement, sink: Sink) {
        when (node) {
            is JsonObject -> {
                val type = node.path("type").asTextOrNull()
                val id = node.path("id").asTextOrNull()
                if (type != null && id != null) {
                    when (type) {
                        "Field" -> if (acceptedByVariantFilter(node.path("Field"))) sink.fields.add(fieldDef(node, id))
                        "Group" -> {
                            val g = node.path("Group")
                            if (g.path("repeatability").asInt(0) > 1) {
                                sink.repeatable.add(id)
                                sink.declaredReps[id] = g.path("repeatability").asInt(0)
                            }
                            // The group's index field (the kernel auto-requires it) — resolve its full path.
                            val indexField = g.path("indexFieldName").asTextOrNull()
                            if (!indexField.isNullOrEmpty()) sink.indexFields.add("$id/$indexField")
                        }
                        "Rule" -> {
                            val r = node.path("Rule")
                            if (acceptedByVariantFilter(r)) sink.rules.add(
                                RuleDef(
                                    path = id,
                                    group = parent(id),
                                    conditionText = r.path("errorCondition").asText(),
                                    errorCode = r.path("errorCode").asText(),
                                    errorEntityRelPath = r.path("errorEntityRelPath").asText(),
                                    severity = Severity.valueOf(r.path("severity").asText("ERROR")),
                                    messages = localizedTexts(r.path("errorMessage")),
                                ),
                            )
                        }
                        "Computation" -> {
                            val c = node.path("Computation")
                            val target = resolveRel(id, c.path("computedFieldRelPath").asText())
                            // A tiered computation: every alternative's (precondition?, operation), under an optional
                            // commonPrecondition — the kernel selects the first alternative whose precondition holds
                            // (IF21 / KERNEL-FINDINGS §2). The DSL text stays raw (relative to the group); the
                            // interpreter parses it with the computation's group.
                            val common = if (c.hasNonNull("commonPrecondition")) c.path("commonPrecondition").asText() else null
                            val alts = c.path("computationAlternatives").elements().map { alt ->
                                val precond = if (alt.hasNonNull("precondition")) alt.path("precondition").asText() else null
                                ComputationAlternative(precond, alt.path("operation").asText())
                            }
                            // The computation's element name is the error code of its implicit validation rule (IF32).
                            val compName = if (node.hasNonNull("name")) node.path("name").asText() else null
                            if (acceptedByVariantFilter(c)) sink.comps.add(ComputationDef(target, parent(id), alts, common, compName))
                        }
                        else -> { /* header / other nodes carry no rule surface */ }
                    }
                }
                node.values.forEach { walk(it, sink) }
            }
            is JsonArray -> node.forEach { walk(it, sink) }
            else -> { /* scalar leaf — nothing to collect */ }
        }
    }

    private fun fieldDef(node: JsonElement, id: String): FieldDef = FieldDef(
        path = id,
        kind = kindOf(node),
        labels = localizedTexts(node.path("labels")),
        scale = scaleOf(node),
        signed = signed(node),
        required = requirednessOf(node),
        format = formatOf(node),
        rangeSeparator = rangeSeparatorOf(node),
        datePrecision = datePrecisionOf(node),
        enumValues = enumValuesOf(node),
        enumCategories = enumCategoriesOf(node),
        customTypeName = customTypeNameOf(node),
        minLength = typedInt(node, "StringType", "minLength"),
        maxLength = typedInt(node, "StringType", "maxLength"),
        pattern = stringTypeText(node, "pattern"),
        noValueValidation = typedBool(node, "StringType", "noValueValidation"),
        minValue = numberTypeText(node, "minValue"),
        maxValue = numberTypeText(node, "maxValue"),
        minFractionalDigits = typedInt(node, "NumberType", "minFractionalDigits"),
        lineBreaksPermitted = typedBool(node, "StringType", "lineBreaksPermitted"),
        leadingZerosAllowed = typedBool(node, "NumberType", "leadingZerosAllowed"),
        zeroNotAllowed = typedBool(node, "NumberType", "zeroNotAllowed"),
        maxIntegerDigits = typedInt(node, "NumberType", "maxIntegerDigits"),
        amountTrait = "amount" == numberTypeText(node, "trait"),
        numberMinLength = typedInt(node, "NumberType", "minLength"),
        numberMaxLength = typedInt(node, "NumberType", "maxLength"),
        global = node.path("Field").path("global").asBoolean(false),
    )

    // ── Field-type readers (ported 1:1 from InterpreterModelLoader). ────────────────────────────────

    private fun kindOf(fieldNode: JsonElement?): Kind =
        when (fieldNode.path("Field").path("fieldType").path("type").asText()) {
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
            else -> Kind.STRING   // String / Enumeration — STRING-shaped for the operators modeled so far
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

    /** An ENUMERATION field's categories (name → positional value list) — the `EnumCategory` mapping. */
    private fun enumCategoriesOf(fieldNode: JsonElement?): Map<String, List<String>> {
        val e = fieldNode.path("Field").path("fieldType").path("EnumerationType")
        val categories = linkedMapOf<String, List<String>>()
        for (c in e.path("categories").elements()) {
            categories[c.path("name").asText()] = c.path("values").elements().map { it.asText() }
        }
        return categories
    }

    /** A DATE_RANGE field's two-date separator (`DateRangeType.rangeSeparator`), else null. */
    private fun rangeSeparatorOf(fieldNode: JsonElement?): String? {
        val type = fieldNode.path("Field").path("fieldType")
        if (type.path("type").asText() != "DateRangeType") return null
        return type.path("DateRangeType").path("rangeSeparator").asText().ifEmpty { null }
    }

    /** A DATE field's precision: a `DateFragmentType` is [DatePrecision.FRAGMENT] (its format carries only some
     *  components — the eval completes the rest); a `DateType` with `datePrecision: DAY_OPTIONAL` permits
     *  `00.MM.yyyy`; else [DatePrecision.FULL]. */
    private fun datePrecisionOf(fieldNode: JsonElement?): DatePrecision {
        val type = fieldNode.path("Field").path("fieldType")
        return when {
            type.path("type").asText() == "DateFragmentType" -> DatePrecision.FRAGMENT
            type.path("type").asText() == "DateType" &&
                type.path("DateType").path("datePrecision").asText() == "DAY_OPTIONAL" -> DatePrecision.DAY_OPTIONAL
            else -> DatePrecision.FULL
        }
    }

    /** A field's requiredness (`Field.requirednessConfig.mode`); absent / `notRequired` → not required. */
    private fun requirednessOf(fieldNode: JsonElement?): Requiredness =
        when (fieldNode.path("Field").path("requirednessConfig").path("mode").asText()) {
            "absoluteOrRelativeToNextRepAncestor" -> Requiredness.ABSOLUTE
            "relativeToParent" -> Requiredness.RELATIVE_TO_PARENT
            else -> Requiredness.NOT_REQUIRED
        }

    /** A NUMBER field's scale (`NumberType.maxFractionalDigits`), or null for a non-number field. The kernel's
     *  `NumberType.maxFractionalDigits` is a **primitive int defaulting to 0** (kernel-md-a12internal), so a
     *  NumberType that omits it is scale-0 — an INTEGER: any decimal separator is `zahlHatDezimalTrenner`. A
     *  null return here means "not a number field", NOT "no scale constraint" (a number is never unconstrained). */
    private fun scaleOf(fieldNode: JsonElement?): Int? {
        val type = fieldNode.path("Field").path("fieldType")
        if (type.path("type").asText() != "NumberType") return null
        val max = type.path("NumberType").path("maxFractionalDigits")
        return if (max.isIntNode()) max.asInt(0) else 0
    }

    /** Whether a NUMBER field is signed (unsigned iff `NumberType.positivesOnly`); non-number defaults to signed. */
    private fun signed(fieldNode: JsonElement?): Boolean =
        !fieldNode.path("Field").path("fieldType").path("NumberType").path("positivesOnly").asBoolean(false)

    /** The `name` of a `CustomFieldType` field — the predefined type its values are validated against; else null. */
    private fun customTypeNameOf(fieldNode: JsonElement?): String? {
        val v = typed(fieldNode, "CustomFieldType").path("name")
        return if (v.isTextual()) v.asText() else null
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

    /** An `int` property of a `<typeName>` field type, or null (Jackson `isInt()` — a plain integer literal). */
    private fun typedInt(fieldNode: JsonElement?, typeName: String, prop: String): Int? {
        val v = typed(fieldNode, typeName).path(prop)
        return if (v.isIntNode()) v.asInt(0) else null
    }

    /** A `boolean` property of a `<typeName>` field type (absent → false, the kernel default). */
    private fun typedBool(fieldNode: JsonElement?, typeName: String, prop: String): Boolean =
        typed(fieldNode, typeName).path(prop).asBoolean(false)

    /** The `Field.fieldType.<typeName>` object, but only when the field's declared `type` matches (else null). */
    private fun typed(fieldNode: JsonElement?, typeName: String): JsonElement? {
        val type = fieldNode.path("Field").path("fieldType")
        return if (typeName == type.path("type").asText()) type.path(typeName) else null
    }

    /**
     * Whether an element (Field/Rule/Computation, given its type sub-object) is accepted under the interpreter's
     * NULL variant. The kernel's `VariantFiltering` drops a `variantFilter: {filterType: "ONLY"}` element when the
     * variant is null and keeps `ALL_EXCEPT`; the interpreter has no variant, so it mirrors the null-variant
     * decision (IF36). No filter → kept.
     */
    private fun acceptedByVariantFilter(typeSub: JsonElement?): Boolean {
        val vf = typeSub.path("variantFilter")
        return vf == null || vf is JsonNull || vf.path("filterType").asText() != "ONLY"
    }

    /** Read a `[{locale, text}]` array into a locale→text map (a rule's `errorMessage` / a field's `labels`). */
    private fun localizedTexts(arr: JsonElement?): Map<String, String> {
        val m = linkedMapOf<String, String>()
        for (e in arr.elements()) m[e.path("locale").asText()] = e.path("text").asText()
        return m
    }

    /** The parent group path of a full id, e.g. `/Order/X` → `/Order`. */
    private fun parent(id: String): String {
        val i = id.lastIndexOf('/')
        return if (i <= 0) "" else id.substring(0, i)
    }

    /** Resolve a relpath against the NODE's own id (`../F` pops the node name, then appends). */
    private fun resolveRel(nodeId: String, rel: String): String {
        var base = nodeId
        var r = rel
        while (r.startsWith("../")) {
            base = base.substring(0, base.lastIndexOf('/'))
            r = r.substring(3)
        }
        return if (r.isEmpty()) base else "$base/$r"
    }

    // ── JsonElement helpers — mirror the Jackson JsonNode accessors the port relies on. ─────────────
    //   `path` chains through a missing key as null (like Jackson's MissingNode); the typed readers give the
    //   Jackson defaults ("" / the supplied default / null) so the walk stays faithful even on sparse nodes.

    private fun JsonElement?.path(key: String): JsonElement? = (this as? JsonObject)?.get(key)

    private fun JsonElement?.elements(): List<JsonElement> = this as? JsonArray ?: emptyList()

    private fun JsonElement?.hasNonNull(key: String): Boolean {
        val child = (this as? JsonObject)?.get(key) ?: return false
        return child !is JsonNull
    }

    /** The scalar's text, or "" for a missing / null / non-scalar node (Jackson `asText()`). */
    private fun JsonElement?.asText(): String = asTextOrNull() ?: ""

    /** The scalar's text, or [default] for a missing / null node (Jackson `asText(default)`). */
    private fun JsonElement?.asText(default: String): String = asTextOrNull() ?: default

    /** The scalar's text, or null for a missing / null / non-scalar node (Jackson `asText(null)`). */
    private fun JsonElement?.asTextOrNull(): String? {
        val p = this as? JsonPrimitive ?: return null
        return if (p is JsonNull) null else p.content
    }

    private fun JsonElement?.asInt(default: Int): Int {
        val p = this as? JsonPrimitive ?: return default
        if (p is JsonNull) return default
        return p.content.toIntOrNull() ?: p.content.toDoubleOrNull()?.toInt() ?: default
    }

    private fun JsonElement?.asBoolean(default: Boolean): Boolean {
        val p = this as? JsonPrimitive ?: return default
        if (p is JsonNull) return default
        return when (p.content) {
            "true" -> true
            "false" -> false
            else -> default
        }
    }

    /** Jackson `isInt()` — a plain integer literal that fits an `Int` (not a string, not a decimal/oversized). */
    private fun JsonElement?.isIntNode(): Boolean {
        val p = this as? JsonPrimitive ?: return false
        if (p is JsonNull || p.isString) return false
        return p.content.toIntOrNull() != null
    }

    /** Jackson `isNumber()` — a numeric literal (not a string, not a boolean). */
    private fun JsonElement?.isNumberNode(): Boolean {
        val p = this as? JsonPrimitive ?: return false
        if (p is JsonNull || p.isString) return false
        if (p.content == "true" || p.content == "false") return false
        return p.content.toDoubleOrNull() != null
    }

    /** Jackson `isTextual()` — a JSON string. */
    private fun JsonElement?.isTextual(): Boolean {
        val p = this as? JsonPrimitive ?: return false
        return p !is JsonNull && p.isString
    }
}
