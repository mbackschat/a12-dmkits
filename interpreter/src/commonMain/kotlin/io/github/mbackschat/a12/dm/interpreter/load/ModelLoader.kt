package io.github.mbackschat.a12.dm.interpreter.load

import io.github.mbackschat.a12.dm.interpreter.conditionLanguageCode
import io.github.mbackschat.a12.dm.interpreter.resolveRelative
import io.github.mbackschat.a12.dm.interpreter.parseA12Json
import io.github.mbackschat.a12.dm.interpreter.bindJavaInt
import io.github.mbackschat.a12.dm.interpreter.JavaIntBinding
import io.github.mbackschat.a12.dm.interpreter.ConfigFlag
import io.github.mbackschat.a12.dm.interpreter.configFlag
import io.github.mbackschat.a12.dm.interpreter.modelMemberState
import io.github.mbackschat.a12.dm.interpreter.unbindableModelMember
import io.github.mbackschat.a12.dm.interpreter.runtimeUnevaluableFrameMember
import kotlin.jvm.JvmStatic
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import io.github.mbackschat.a12.dm.interpreter.model.ComputationAlternative
import io.github.mbackschat.a12.dm.interpreter.model.ConditionLanguage
import io.github.mbackschat.a12.dm.interpreter.model.RoundingMode
import io.github.mbackschat.a12.dm.interpreter.model.ToleranceBand
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.printedNumberBound
import io.github.mbackschat.a12.dm.interpreter.model.LegalCharset
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
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
    fun load(dmJson: String): ModelDef = load(dmJson, forEvaluation = false)

    /**
     * As [load], and also refuse a model A12 reads but its runtime cannot evaluate ([runtimeUnevaluableFrameMember]):
     * the runtime preparation gate's load, so reading or expanding such a model stays possible.
     */
    internal fun loadForEvaluation(dmJson: String): ModelDef = load(dmJson, forEvaluation = true)

    private fun load(dmJson: String, forEvaluation: Boolean): ModelDef {
        val root = try {
            parseA12Json(dmJson)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("not valid DM-JSON", e)
        }
        val rootGroups = requirePrepared(root)
        // A12 binds the model-level members whole, so one it cannot bind refuses the model before any is read.
        (root as? JsonObject)?.let { model ->
            model.unbindableModelMember()?.let { member ->
                throw invalidElement(member,
                    "$member ${model.modelMemberState(member)}, which A12 cannot bind, so A12 does not load the model")
            }
            if (forEvaluation) model.runtimeUnevaluableFrameMember()?.let { member ->
                throw invalidElement(member,
                    "$member ${model.modelMemberState(member)}, which A12's runtime cannot evaluate a model with, " +
                        "so A12 runs none of its rules or computations")
            }
        }
        val sink = Sink()
        rootGroups.forEach { walk(it, sink, parentPath = "", rootElement = true) }
        // The model's base year, the value of the BaseYear constant: an `Integer`, so blank text is no base year.
        val baseYear = (root.path("content").path("modelInfo").path("baseYear").bindJavaInt() as? JavaIntBinding.Bound)?.value
        // content.modelConfig.fieldRefByShortNameAllowed (default false) — enables the bare cross-group
        // short-name resolution the condition parser applies (IF119).
        val modelConfig = root.path("content").path("modelConfig")
        // Through the kernel's own POJO binding rather than `asBoolean`: the two disagree on a capitalized
        // `"True"` (kernel ON, asBoolean OFF, so every model-wide short name would go unresolved) and on a
        // fractional number (kernel refuses the model, asBoolean truncates and answers). KF289 has the table.
        val shortNameRefs = configFlag(modelConfig.path("fieldRefByShortNameAllowed")) == ConfigFlag.ON
        // content.modelConfig.supportedCharacters (a list of single chars / "X-Y" ranges) — the model-declared
        // legal charset the kernel enforces at runtime (ZeichenNichtImZeichensatz); empty/absent → no restriction (IF120).
        val legalCharset = LegalCharset.ofSupportedCharacters(
            modelConfig.path("supportedCharacters").elements().mapNotNull { it.asTextOrNull() })
        // Only an ABSENT timeZone slot defaults to UTC. An explicit null/blank is an invalid config value;
        // outside-set ids reach ModelTimeZone's neutral common refusal. The JVM consumer classifies those ids
        // against java.util.TimeZone before this kernel-free JVM+JS boundary.
        val timeZoneNode = (modelConfig as? JsonObject)?.get("timeZone")
        val timeZone = if (timeZoneNode == null) ModelTimeZone.UTC else ModelTimeZone.of(timeZoneNode.asTextOrNull())
        val conditionLanguage = ConditionLanguage.fromCode((root as? JsonObject)?.conditionLanguageCode())
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
    private fun resolveRel(nodeId: String, rel: String): String =
        resolveRelative(nodeId, rel) ?: throw InvalidPreparedModelSourceException(
            nodeId,
            "computedFieldRelPath '$rel' at '$nodeId' walks above the model root",
        )
}
