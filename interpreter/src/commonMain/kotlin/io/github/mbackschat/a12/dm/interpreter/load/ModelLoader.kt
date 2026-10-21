package io.github.mbackschat.a12.dm.interpreter.load

import io.github.mbackschat.a12.dm.interpreter.parseA12Json
import io.github.mbackschat.a12.dm.interpreter.bindJavaInt
import io.github.mbackschat.a12.dm.interpreter.JavaIntBinding
import io.github.mbackschat.a12.dm.interpreter.UnsupportedConstructException
import io.github.mbackschat.a12.dm.interpreter.asciiIntOrNull
import io.github.mbackschat.a12.dm.interpreter.javaIntOrNull
import io.github.mbackschat.a12.dm.interpreter.javaTrimmed
import io.github.mbackschat.a12.dm.interpreter.configFlag
import io.github.mbackschat.a12.dm.interpreter.orRefuse
import kotlin.jvm.JvmStatic
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import io.github.mbackschat.a12.dm.interpreter.model.ComputationAlternative
import io.github.mbackschat.a12.dm.interpreter.model.ConditionLanguage
import io.github.mbackschat.a12.dm.interpreter.model.RangeYearInterpretation
import io.github.mbackschat.a12.dm.interpreter.model.RoundingMode
import io.github.mbackschat.a12.dm.interpreter.model.ToleranceBand
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldTypeDeclaration
import io.github.mbackschat.a12.dm.interpreter.model.DatePrecision
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.isA12DecimalText
import io.github.mbackschat.a12.dm.interpreter.model.printedNumberBound
import io.github.mbackschat.a12.dm.interpreter.model.resolveFractionalPair
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
 * The mapper reads via kotlinx-serialization-json's **tree API** (`parseA12Json` — the shared
 * A12-shape parse configuration, so a commented model source loads as it does in A12 — plus a hand-written
 * [JsonElement] → [ModelDef] walk) — no `@Serializable`, no compiler plugin (proposal §11 fork F1). A number's
 * **raw literal is read verbatim** ([JsonPrimitive.content], e.g. `100.50` stays `"100.50"`), never through a parser's
 * own `Double`. The one deliberate exception is a Number field's `minValue`/`maxValue`: A12 compares those as the
 * text printed from their nearest double, so the loader prints them the same way ([printedNumberBound], KF564).
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
            parseA12Json(dmJson)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("not valid DM-JSON", e)
        }
        val rootGroups = requirePrepared(root)
        val sink = Sink()
        rootGroups.forEach { walk(it, sink, parentPath = "", rootElement = true) }
        // The model's base year, the value of the BaseYear constant: an `Integer`, so blank text is no base year.
        val baseYear = boundJavaInt(root.path("content").path("modelInfo").path("baseYear"), "modelInfo.baseYear")
        // content.modelConfig.fieldRefByShortNameAllowed (default false) — enables the bare cross-group
        // short-name resolution the condition parser applies (IF119).
        val modelConfig = root.path("content").path("modelConfig")
        // Through the kernel's own POJO binding rather than `asBoolean`: the two disagree on a capitalized
        // `"True"` (kernel ON, asBoolean OFF, so every model-wide short name would go unresolved) and on a
        // fractional number (kernel refuses the model, asBoolean truncates and answers). KF289 has the table.
        val shortNameRefs = configFlag(modelConfig.path("fieldRefByShortNameAllowed"))
            .orRefuse { throw unbindableFlag("content.modelConfig.fieldRefByShortNameAllowed") }
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
                // A primitive `int`: blank text and a JSON null are 0, which the consistency check refuses.
                val repeatability = boundJavaInt(structure.body.path("repeatability"), "$id: repeatability") ?: 0
                if (repeatability > 1) {
                    sink.repeatable.add(id)
                    sink.declaredReps[id] = repeatability
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
                        ComputationDef(
                            target, parent(id), alternatives, common, structure.name,
                            localizedTexts(body.path("errorMessage")), roundingModeOf(body),
                        ),
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
        val declaredMinimumFraction = typedInt(node, "NumberType", "minFractionalDigits")
        val declaredMaximumFraction = scaleOf(node)
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
        customType = customFieldTypeOf(node),
        minLength = typedInt(node, "StringType", "minLength"),
        maxLength = typedInt(node, "StringType", "maxLength"),
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
        maxIntegerDigits = typedInt(node, "NumberType", "maxIntegerDigits"),
        amountTrait = "amount" == numberTypeText(node, "trait"),
        numberMinLength = typedInt(node, "NumberType", "minLength"),
        numberMaxLength = typedInt(node, "NumberType", "maxLength"),
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
     *  **An EMPTY declared separator is a VALUE, not "unset"** — `MM`+`""` is one of the eight allowlisted
     *  `(format, separator)` pairs (KERNEL-FINDINGS KF81) and the split-in-the-middle branch `splitDateRange`
     *  exists for exactly it. Folding `""` into null dropped the field out of [ModelDef.rangeConfig], which
     *  disables the range's whole config: the formal check short-circuits to "nothing to check" and a stored
     *  value never resolves as a range (IF306). Only an ABSENT key — which the kernel's required pair never
     *  produces — is null. */
    private fun rangeSeparatorOf(fieldNode: JsonElement?): String? {
        val type = fieldNode.path("Field").path("fieldType")
        if (type.path("type").asText() != "DateRangeType") return null
        return type.path("DateRangeType").path("rangeSeparator").asTextOrNull()
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

    /** A NUMBER field's DECLARED scale (`NumberType.maxFractionalDigits`, `-1` included — [resolveFractionalPair]
     *  reads it), or null for a non-number field. The kernel's
     *  `NumberType.maxFractionalDigits` is a **primitive int defaulting to 0** (kernel-md-a12internal), so a
     *  NumberType that omits it is scale-0 — an INTEGER: any decimal separator is `zahlHatDezimalTrenner`. A
     *  null return here means "not a number field", NOT "no scale constraint" (a number is never unconstrained). */
    private fun scaleOf(fieldNode: JsonElement?): Int? {
        val type = fieldNode.path("Field").path("fieldType")
        if (type.path("type").asText() != "NumberType") return null
        val max = type.path("NumberType").path("maxFractionalDigits")
        return max.intNodeOrNull() ?: 0
    }

    /** A `CustomFieldType` field's validator name and optional length-bound inputs; else null. */
    private fun customFieldTypeOf(fieldNode: JsonElement?): CustomFieldTypeDeclaration? {
        val type = typed(fieldNode, "CustomFieldType")
        val name = type.path("name")
        if (!name.isTextual()) return null
        return CustomFieldTypeDeclaration(
            name.asText(),
            type.path("minLength").intNodeOrNull(),
            type.path("maxLength").intNodeOrNull(),
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
        return v.intNodeOrNull()
    }

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

    /** Missing/null means [RoundingMode.EXACT]; any present non-string or string outside the closed set fails loud. */
    private fun roundingModeOf(body: JsonElement): RoundingMode {
        val value = (body as? JsonObject)?.get("roundingMode") ?: return RoundingMode.EXACT
        if (value is JsonNull) return RoundingMode.EXACT
        require(value.isTextual()) { "roundingMode must be a string equal to one of ${RoundingMode.entries.map { it.wire }}" }
        return RoundingMode.ofWire(value.asText())
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

    /**
     * A model member A12's reader binds to a Java `int` or `Integer` ([JavaIntBinding]); null where it binds no value,
     * and a refusal naming [member] where A12's reader leaves no model.
     */
    private fun boundJavaInt(value: JsonElement?, member: String): Int? = when (val binding = value.bindJavaInt()) {
        is JavaIntBinding.Bound -> binding.value
        JavaIntBinding.NoValue -> null
        JavaIntBinding.Unbindable -> throw invalidElement(
            member, "$member ${(value as? JsonPrimitive)?.content} is not a whole number A12 can bind, so A12 does not load the model")
    }

    /**
     * A NUMBER field's `minValue` or `maxValue` text, which A12's reader binds to a `BigDecimal` (KF591): after Java's
     * `trim` it must spell an ASCII decimal, so a no-break space, a non-ASCII digit or a word leaves A12 with no model
     * and this refuses it. The text `null` is the null value to that reader, so it declares no bound (KF593). An
     * unquoted number is a lexeme the strict parse has already held to JSON's grammar.
     */
    private fun numberBoundText(fieldNode: JsonElement?, id: String, prop: String): String? {
        val text = numberTypeText(fieldNode, prop) ?: return null
        if (!typed(fieldNode, "NumberType").path(prop).isTextual()) return text
        val trimmed = text.javaTrimmed()
        if (trimmed == "null") return null
        if (trimmed.isEmpty() || isA12DecimalText(trimmed)) return text
        throw invalidElement("$id: NumberType.$prop", "$id: NumberType.$prop '$text' is not a decimal A12 can bind, so A12 does not load the model")
    }

    /** Jackson `isInt()`'s value — a plain integer literal that fits an `Int`; null for a string, a decimal or an oversized one. */
    private fun JsonElement?.intNodeOrNull(): Int? {
        val p = this as? JsonPrimitive ?: return null
        if (p is JsonNull || p.isString) return null
        return p.content.asciiIntOrNull()
    }

    /** Jackson `isNumber()` — a numeric literal: an unquoted primitive that is neither null nor a boolean. */
    private fun JsonElement?.isNumberNode(): Boolean {
        val p = this as? JsonPrimitive ?: return false
        if (p is JsonNull || p.isString) return false
        return p.content != "true" && p.content != "false"
    }

    /** Jackson `isTextual()` — a JSON string. */
    private fun JsonElement?.isTextual(): Boolean {
        val p = this as? JsonPrimitive ?: return false
        return p !is JsonNull && p.isString
    }
}
