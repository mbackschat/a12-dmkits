package io.github.mbackschat.a12.dm.interpreter.load

import io.github.mbackschat.a12.dm.interpreter.A12_JSON
import kotlin.jvm.JvmStatic
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import io.github.mbackschat.a12.dm.interpreter.model.ComputationAlternative
import io.github.mbackschat.a12.dm.interpreter.model.ConditionLanguage
import io.github.mbackschat.a12.dm.interpreter.model.ToleranceBand
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldTypeDeclaration
import io.github.mbackschat.a12.dm.interpreter.model.DatePrecision
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.LegalCharset
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.Requiredness
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.interpreter.model.Severity
import io.github.mbackschat.a12.dm.interpreter.workspace.PREPARED_INCLUDE_DIRECTIVE_MEMBERS
import io.github.mbackschat.a12.dm.interpreter.workspace.PreparedElementPosition
import io.github.mbackschat.a12.dm.interpreter.workspace.PreparedElementStructure
import io.github.mbackschat.a12.dm.interpreter.workspace.PreparedElementType
import io.github.mbackschat.a12.dm.interpreter.workspace.requirePreparedElementStructure
import io.github.mbackschat.a12.dm.interpreter.workspace.requirePreparedGroupChildren

/**
 * The interpreter's own DM-JSON → [ModelDef] loader — kernel-free and **multiplatform** (JVM + JS), so a
 * standalone consumer (a Node-hosted JS library or a JVM library user) can load a real model without hand-building a
 * [ModelDef] and without the kernel. It is the engine dmtool's native runtime verbs sit on: `InterpreterModelLoader`
 * (in `:cli`) is a thin JVM delegate over this.
 *
 * ## Approach — tree API, raw-text fidelity
 * The mapper reads via kotlinx-serialization-json's **tree API** (`A12_JSON.parseToJsonElement` — the shared
 * A12-shape parse configuration, so a commented model source loads as it does in A12 — plus a hand-written
 * [JsonElement] → [ModelDef] walk) — no `@Serializable`, no compiler plugin (proposal §11 fork F1). A number's
 * **raw literal is preserved** verbatim ([JsonPrimitive.content], e.g. `100.50` stays `"100.50"`): the value is
 * never round-tripped through a `Double`/JS number, so a declared `minValue`/`maxValue` keeps its exact scale.
 *
 * ## The walk
 * Only `content.modelRoot.rootGroups` and nested `Group.elements` form the document-element tree. The same
 * presence-aware classifier used by raw workspace assembly validates that tree while the loader records it in
 * document order. Element-shaped records outside that tree are rejected rather than interpreted as model content.
 *
 * ## Scope — EXPANDED, SELF-CONTAINED models only, ENFORCED
 * The loader handles a **single, fully-expanded** model: no `modelReferences`/`imports` resolution and no
 * `TypeDefType` de-referencing. Includes, type-definition libraries, and opaque element ids are resolved
 * **upstream** by the kernel-free workspace preparation path before the DM-JSON reaches here.
 * This boundary is **enforced, not assumed** ([requirePrepared], IF145): a model that still carries model
 * references, an include directive, an unresolved `TypeDefType`, or a non-name-path (opaque) id is **rejected
 * loud** with a corrective message — never loaded as a structurally plausible but wrong model. Every CLI
 * consumer prepares upstream via `ModelReader.expand`; this is the guard that makes a future bypass fail loud
 * instead of silent.
 */
object ModelLoader {

    /** Load an expanded, self-contained DM-JSON model into a kernel-free [ModelDef]. */
    @JvmStatic
    fun load(dmJson: String): ModelDef {
        val root = try {
            A12_JSON.parseToJsonElement(dmJson)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("not valid DM-JSON", e)
        }
        val rootGroups = requirePrepared(root)
        val sink = Sink()
        rootGroups.forEach { walk(it, sink, parentPath = "", rootElement = true) }
        // The model's base year (content.modelInfo.baseYear, a string) — the value of the BaseYear constant.
        val by = root.path("content").path("modelInfo").path("baseYear").asText()
        val baseYear = if (by.isEmpty()) null else by.trim().toIntOrNull()
        // content.modelConfig.fieldRefByShortNameAllowed (default false) — enables the bare cross-group
        // short-name resolution the condition parser applies (IF119).
        val modelConfig = root.path("content").path("modelConfig")
        val shortNameRefs = modelConfig.path("fieldRefByShortNameAllowed").asBoolean(false)
        // content.modelConfig.supportedCharacters (a list of single chars / "X-Y" ranges) — the model-declared
        // legal charset the kernel enforces at runtime (ZeichenNichtImZeichensatz); empty/absent → no restriction (IF120).
        val legalCharset = LegalCharset.ofSupportedCharacters(
            modelConfig.path("supportedCharacters").elements().mapNotNull { it.asTextOrNull() })
        // Only an ABSENT timeZone slot defaults to UTC. An explicit null/blank is an invalid config value;
        // outside-set ids reach ModelTimeZone's neutral common refusal. The JVM consumer classifies those ids
        // against java.util.TimeZone before this kernel-free JVM+JS boundary.
        val timeZoneNode = (modelConfig as? JsonObject)?.get("timeZone")
        val timeZone = if (timeZoneNode == null) ModelTimeZone.UTC else ModelTimeZone.of(timeZoneNode.asTextOrNull())
        val conditionLanguage = ConditionLanguage.fromCode(
            modelConfig.path("conditionLanguage").path("code").asTextOrNull(),
        )
        return ModelDef(
            fields = sink.fields,
            rules = sink.rules,
            computations = sink.comps,
            repeatableGroups = sink.repeatable,
            declaredReps = sink.declaredReps,
            indexFields = sink.indexFields,
            groupPaths = sink.groupPaths,
            baseYear = baseYear,
            fieldRefByShortNameAllowed = shortNameRefs,
            legalCharset = legalCharset,
            timeZone = timeZone,
            conditionLanguage = conditionLanguage,
        )
    }

    /** The mutable accumulators of one [load] pass — insertion-ordered to match the DM-JSON document order. */
    private class Sink {
        val fields = mutableListOf<FieldDef>()
        val rules = mutableListOf<RuleDef>()
        val comps = mutableListOf<ComputationDef>()
        val repeatable = linkedSetOf<String>()
        val declaredReps = linkedMapOf<String, Int>()
        val indexFields = linkedSetOf<String>()
        val groupPaths = linkedSetOf<String>()
    }

    /**
     * Enforce the loader's prepared-model contract (IF145): the header has no unresolved model references, every
     * recorded element is name-path-keyed, and no include or type-definition directive remains. The header itself
     * is not path-addressed, so only its reference list participates in this guard. Throws
     * [UnpreparedModelResidueException] with the residue class and offending path.
     */
    private fun requirePrepared(node: JsonElement): JsonArray {
        val root = node as? JsonObject
            ?: throw InvalidPreparedModelSourceException("$", "prepared model source must be a JSON object")
        requirePreparedModelReferences(root)
        requireNoTypeDefinitionResidue(root)
        requireNoElementRecordsOutsideTree(root)
        return preparedElementRoots(root)
    }

    private fun preparedElementRoots(root: JsonObject): JsonArray {
        val content = root.requiredObject("content")
        val modelRoot = content.optionalObject("modelRoot") ?: return JsonArray(emptyList())
        return modelRoot.optionalArray("rootGroups") ?: JsonArray(emptyList())
    }

    private fun requirePreparedModelReferences(root: JsonObject) {
        when (val references = (root.path("header") as? JsonObject)?.get("modelReferences")) {
            null, is JsonNull -> Unit
            is JsonArray -> if (references.isNotEmpty()) {
                throw UnpreparedModelResidueException(
                    path = "header.modelReferences",
                    residue = "model-reference",
                    message = "unresolved header.modelReferences remain in the model; workspace references must " +
                        "be prepared before loading",
                )
            }
            else -> throw InvalidPreparedModelSourceException(
                path = "header.modelReferences",
                message = "header.modelReferences must be an array or null",
            )
        }
    }

    private fun requireNoTypeDefinitionResidue(
        node: JsonElement,
        containingPath: String? = null,
    ) {
        when (node) {
            is JsonObject -> {
                val path = node.stringMember("id") ?: containingPath
                if (node.stringMember("type") == "TypeDefType") {
                    throw UnpreparedModelResidueException(
                        path = path ?: "fieldType",
                        residue = "type-definition",
                        message = "unresolved TypeDefType at '${path ?: "fieldType"}'; type definitions must be " +
                            "inlined before loading",
                    )
                }
                node.values.forEach { requireNoTypeDefinitionResidue(it, path) }
            }
            is JsonArray -> node.forEach { requireNoTypeDefinitionResidue(it, containingPath) }
            else -> Unit
        }
    }

    private fun requireNoElementRecordsOutsideTree(root: JsonObject) {
        root.forEach { (member, value) ->
            if (member == "content" && value is JsonObject) {
                value.forEach { (contentMember, contentValue) ->
                    if (contentMember == "modelRoot" && contentValue is JsonObject) {
                        contentValue.forEach { (rootMember, rootValue) ->
                            if (rootMember != "rootGroups") {
                                requireNoElementRecord(rootValue, "content.modelRoot.$rootMember")
                            }
                        }
                    } else {
                        requireNoElementRecord(contentValue, "content.$contentMember")
                    }
                }
            } else {
                requireNoElementRecord(value, member)
            }
        }
    }

    private fun requireNoElementRecord(node: JsonElement, path: String) {
        when (node) {
            is JsonObject -> {
                val elementType = PreparedElementType.fromWireName(node.stringMember("type"))
                val elementBody = node.keys.any { PreparedElementType.fromWireName(it) != null }
                if (elementType != null || elementBody && ("id" in node || "name" in node)) {
                    throw invalidElement(path, "document-model elements are valid only inside the model element tree")
                }
                node.forEach { (member, value) ->
                    requireNoElementRecord(value, "$path.$member")
                }
            }
            is JsonArray -> node.forEachIndexed { index, value ->
                requireNoElementRecord(value, "$path[$index]")
            }
            else -> Unit
        }
    }

    private fun JsonObject.optionalObject(member: String): JsonObject? {
        val value = this[member] ?: return null
        return value as? JsonObject
            ?: throw invalidElement(member, "$member must be a JSON object")
    }

    private fun JsonObject.requiredObject(member: String): JsonObject =
        this[member] as? JsonObject
            ?: throw invalidElement(member, "$member must be a JSON object")

    private fun JsonObject.optionalArray(member: String): JsonArray? {
        val value = this[member] ?: return null
        return value as? JsonArray
            ?: throw invalidElement(member, "$member must be a JSON array")
    }

    private fun JsonObject.stringMember(member: String): String? =
        (this[member] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content

    private fun invalidElement(path: String, message: String): InvalidPreparedModelSourceException =
        InvalidPreparedModelSourceException(path, message)

    /** A recorded element's `id` must be a name-path (`/Group/Field`), never an opaque (un-normalized) kernel id. */
    private fun requireNamePath(id: String) {
        if (!id.startsWith("/")) {
            throw UnpreparedModelResidueException(
                path = id,
                residue = "opaque-id",
                message = "non-name-path id '$id'; opaque model ids must be normalized to name paths before loading",
            )
        }
    }

    private fun walk(
        element: JsonElement,
        sink: Sink,
        parentPath: String,
        rootElement: Boolean,
    ) {
        val structure = requirePreparedElementStructure(
            element = element,
            parentPath = parentPath,
            position = if (rootElement) PreparedElementPosition.MODEL_ROOT else PreparedElementPosition.NESTED,
            invalid = { path, reason -> throw invalidElement(path, reason) },
        )
        val node = structure.node
        val id = structure.id
        requireNoNestedElementRecordsOutsideGroupChildren(structure)
        requireNamePath(id)
        when (structure.type) {
            PreparedElementType.FIELD -> {
                if (acceptedByVariantFilter(structure.body)) {
                    sink.fields.add(fieldDef(node, id))
                }
            }
            PreparedElementType.GROUP -> {
                sink.groupPaths.add(id)
                val includeMember = structure.body.keys.firstOrNull {
                    it in PREPARED_INCLUDE_DIRECTIVE_MEMBERS
                }
                if (includeMember != null) {
                    throw UnpreparedModelResidueException(
                        path = id,
                        residue = "include",
                        message = "unexpanded include directive '$includeMember' remains at '$id'; includes must " +
                            "be mounted before loading",
                    )
                }
                if (structure.body.path("repeatability").asInt(0) > 1) {
                    sink.repeatable.add(id)
                    sink.declaredReps[id] = structure.body.path("repeatability").asInt(0)
                }
                val indexField = structure.body.path("indexFieldName").asTextOrNull()
                if (!indexField.isNullOrEmpty()) {
                    sink.indexFields.add("$id/$indexField")
                }
                requirePreparedGroupChildren(
                    body = structure.body,
                    path = id,
                    invalid = { path, reason -> throw invalidElement(path, reason) },
                )?.forEach { walk(it, sink, parentPath = id, rootElement = false) }
            }
            PreparedElementType.RULE -> {
                val body = structure.body
                if (acceptedByVariantFilter(body)) {
                    sink.rules.add(
                        RuleDef(
                            path = id,
                            group = parent(id),
                            conditionText = body.path("errorCondition").asText(),
                            errorCode = body.path("errorCode").asText(),
                            errorEntityRelPath = body.path("errorEntityRelPath").asText(),
                            severity = Severity.valueOf(body.path("severity").asText("ERROR")),
                            messages = localizedTexts(body.path("errorMessage")),
                        ),
                    )
                }
            }
            PreparedElementType.COMPUTATION -> {
                val body = structure.body
                val target = resolveRel(id, body.path("computedFieldRelPath").asText())
                val common = if (body.hasNonNull("commonPrecondition")) {
                    body.path("commonPrecondition").asText()
                } else {
                    null
                }
                val alternatives = body.path("computationAlternatives").elements().map { alternative ->
                    val precondition = if (alternative.hasNonNull("precondition")) {
                        alternative.path("precondition").asText()
                    } else {
                        null
                    }
                    ComputationAlternative(
                        precondition,
                        alternative.path("operation").asText(),
                        toleranceBandOf(alternative),
                    )
                }
                if (acceptedByVariantFilter(body)) {
                    sink.comps.add(
                        ComputationDef(target, parent(id), alternatives, common, structure.name),
                    )
                }
            }
        }
    }

    private fun requireNoNestedElementRecordsOutsideGroupChildren(
        structure: PreparedElementStructure,
    ) {
        structure.node.forEach { (member, value) ->
            if (member == structure.type.wireName && value is JsonObject) {
                value.forEach { (bodyMember, bodyValue) ->
                    if (structure.type != PreparedElementType.GROUP || bodyMember != "elements") {
                        requireNoElementRecord(bodyValue, "${structure.id}.$member.$bodyMember")
                    }
                }
            } else {
                requireNoElementRecord(value, "${structure.id}.$member")
            }
        }
    }

    private fun fieldDef(node: JsonElement, id: String): FieldDef {
        requireConsistentEnumSValue(node, id)
        return FieldDef(
        path = id,
        kind = kindOf(node, id),
        labels = localizedTexts(node.path("Field").path("label")),
        scale = scaleOf(node),
        signed = signed(node),
        required = requirednessOf(node),
        format = formatOf(node),
        rangeSeparator = rangeSeparatorOf(node),
        datePrecision = datePrecisionOf(node, id),
        enumValues = enumValuesOf(node),
        enumCategories = enumCategoriesOf(node),
        enumSValue = enumSValueOf(node),
        customType = customFieldTypeOf(node),
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
        require(sValue in values) {
            "Field '$id': the enum S-value '$sValue' is not among the declared enumeration values $values " +
                "(kernel MVK_S_VALUE_INVALID). An out-of-domain s-value makes the model kernel-inconsistent and " +
                "ungeneratable; repair the model upstream before loading."
        }
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

    /** A DATE_RANGE field's two-date separator (`DateRangeType.rangeSeparator`), else null. */
    private fun rangeSeparatorOf(fieldNode: JsonElement?): String? {
        val type = fieldNode.path("Field").path("fieldType")
        if (type.path("type").asText() != "DateRangeType") return null
        return type.path("DateRangeType").path("rangeSeparator").asText().ifEmpty { null }
    }

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

    /** A `CustomFieldType` field's validator name and optional length-bound inputs; else null. */
    private fun customFieldTypeOf(fieldNode: JsonElement?): CustomFieldTypeDeclaration? {
        val type = typed(fieldNode, "CustomFieldType")
        val name = type.path("name")
        if (!name.isTextual()) return null
        return CustomFieldTypeDeclaration(
            name.asText(),
            type.path("minLength").takeIf { it.isIntNode() }?.asInt(0),
            type.path("maxLength").takeIf { it.isIntNode() }?.asInt(0),
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

    /** Missing/null means strict `!=`; any present non-string or string outside the closed set fails loud. */
    private fun toleranceBandOf(alternative: JsonElement): ToleranceBand? {
        val value = (alternative as? JsonObject)?.get("toleranceRangeOp") ?: return null
        if (value is JsonNull) return null
        require(value.isTextual()) {
            "toleranceRangeOp must be a string equal to one of ${ToleranceBand.entries.map { it.wire }}"
        }
        return ToleranceBand.ofWire(value.asText())
    }

    /** Resolve a relpath against the NODE's own id (`../F` pops the node name, then appends). */
    private fun resolveRel(nodeId: String, rel: String): String {
        var base = nodeId
        var r = rel
        while (r.startsWith("../")) {
            val boundary = base.lastIndexOf('/')
            if (boundary < 0) {
                throw InvalidPreparedModelSourceException(
                    nodeId,
                    "computedFieldRelPath '$rel' at '$nodeId' walks above the model root",
                )
            }
            base = base.substring(0, boundary)
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
