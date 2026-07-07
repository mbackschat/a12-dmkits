package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.interpreter.conformance.Op
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CI guard for [CorpusModelPreparer] (WI-7 / S2 pre-expansion) — proves the two facts the real-corpus sweep
 * relies on, on **own-domain** fixtures so no external corpus is needed:
 *
 * 1. The kernel is **id-agnostic and typedef-transparent**: evaluating a model whose ids are opaque hashes and
 *    whose field types are `TypeDefType` references yields the SAME signatures as the plain path-id/inlined form.
 * 2. The interpreter, fed [CorpusModelPreparer.toInterpreterJson] (typedef-inlined + id-normalized), **matches
 *    the kernel** — so the sweep's interpreter and kernel legs compare like for like.
 *
 * The real-model-like form (opaque ids + a `TypeDefType` field) is DERIVED from a rulekit fixture by two
 * test-only transforms ([demoteFieldToTypedef], [hashifyIds]) — the inverse of the preparer's work — rather than
 * hand-writing raw DM-JSON.
 */
class CorpusModelPreparerTest {

    private val mapper = ObjectMapper()
    private val selfContained = ReferenceResolvers.selfContained()

    /** The full chain end-to-end: opaque-id + typedef model → prepare → interpreter matches the kernel, and both
     *  match the plain original. Exercises inline (`Priority` enum) + id normalization + rule resolution. */
    @Test
    fun preparedRealLikeModelAgreesWithKernelAndOriginal() {
        // Original own-domain model with a rule on the (soon-to-be-typedef'd) enum field — the signature is
        // non-empty and depends on the field resolving as a real enum with members.
        val original = RuntimeLaws.withRule(
            RuntimeLaws.model("models/order.dm.json"),
            "/Order", "PriorityFilled", "../Priority", "PRIORITY_FILLED", "FieldFilled(Priority)",
        )
        // The real-model-like form: Priority's concrete EnumerationType demoted to a content typedef reference,
        // then every element id replaced with an opaque hash (names preserved) — what a kernel export looks like.
        val realLike = hashifyIds(demoteFieldToTypedef(original, "Priority", "PRIORITY_TYPE_1"))

        val kernelJson = CorpusModelPreparer.prepareKernelJson(realLike, selfContained)
        val interpJson = CorpusModelPreparer.toInterpreterJson(kernelJson)
        val kernelOriginalJson = CorpusModelPreparer.prepareKernelJson(original, selfContained)

        // Placements from the prepared interpreter model (name-paths) drive all three — the kernel resolves them
        // by name-path regardless of its own opaque ids.
        val model = ModelLoader.load(interpJson)
        val placements = CorpusSeeding.seedPlacementsFixed(model, seed = 1L, rows = 1)

        val interpSigs = CorpusEngines.interpret(interpJson, placements, Op.ValidateFull)
        val kernelSigs = CorpusEngines.groovyDynamic(kernelJson, placements, Op.ValidateFull)
        val kernelOrigSigs = CorpusEngines.groovyDynamic(kernelOriginalJson, placements, Op.ValidateFull)

        assertTrue(interpSigs.any { it.startsWith("PRIORITY_FILLED|") }) {
            "the rule must fire (else the comparison is vacuous) — signatures: $interpSigs"
        }
        assertEquals(kernelOrigSigs, kernelSigs, "kernel must be id-agnostic + typedef-transparent")
        assertEquals(kernelSigs, interpSigs, "interpreter (prepared) must match the kernel anchor")
    }

    /** Structural: after inlining, the demoted enum field resolves to a STRING-shaped enum WITH its members
     *  (not the empty-STRING flatten the loader produces on an un-inlined `TypeDefType`). */
    @Test
    fun inliningRestoresEnumMembers() {
        val base = RuntimeLaws.model("models/order.dm.json")
        val demoted = demoteFieldToTypedef(base, "Priority", "PRIORITY_TYPE_1")
        val model = ModelLoader.load(CorpusModelPreparer.toInterpreterJson(
            CorpusModelPreparer.prepareKernelJson(demoted, selfContained)))

        val priority = model.fields.first { it.path == "/Order/Priority" }
        assertTrue(priority.enumValues.isNotEmpty()) {
            "an inlined enum typedef must carry its members; got ${priority.enumValues}"
        }
    }

    /** Structural: normalization rewrites opaque hash ids back to name-paths (what the loader consumes). */
    @Test
    fun normalizationRewritesOpaqueIdsToNamePaths() {
        val hashed = hashifyIds(RuntimeLaws.model("models/order.dm.json"))
        val model = ModelLoader.load(CorpusModelPreparer.toInterpreterJson(
            CorpusModelPreparer.prepareKernelJson(hashed, selfContained)))

        val paths = model.fields.map { it.path }
        assertTrue("/Order/Priority" in paths && "/Order/Items/Sku" in paths) {
            "field ids must be name-paths after normalization; got $paths"
        }
    }

    // ── test-only transforms — the inverse of the preparer, to derive a real-model-like fixture ─────────────

    /** Move [fieldName]'s concrete fieldType into `content.typeDefinitions` under [typedefId] and replace the
     *  field's fieldType with a `TypeDefType` reference (the kernel's imported-typedef shape). */
    private fun demoteFieldToTypedef(json: String, fieldName: String, typedefId: String): String {
        val root = mapper.readTree(json) as ObjectNode
        val content = root.path("content") as ObjectNode
        val field = findByTypeAndName(root, "Field", fieldName)
            ?: error("field '$fieldName' not found")
        val fieldObj = field.path("Field") as ObjectNode
        val concrete = fieldObj.path("fieldType").deepCopy<JsonNode>()

        val defs = (content.get("typeDefinitions") as? ArrayNode) ?: content.putArray("typeDefinitions")
        defs.add(mapper.createObjectNode().apply {
            put("id", typedefId)
            put("name", typedefId)
            set<JsonNode>("fieldType", concrete)
            put("revision", "1")
        })
        fieldObj.set<JsonNode>("fieldType", mapper.createObjectNode().apply {
            put("type", "TypeDefType")
            set<JsonNode>("TypeDefType", mapper.createObjectNode().put("typeDefinitionId", typedefId))
        })
        return mapper.writeValueAsString(root)
    }

    /** Replace every group/field/rule/computation `id` with an opaque hash (names + typeDefinitions untouched) —
     *  simulating a kernel export, whose ids are not name-paths. */
    private fun hashifyIds(json: String): String {
        val root = mapper.readTree(json) as ObjectNode
        var counter = 0
        fun hash(node: JsonNode) {
            when {
                node.isObject -> {
                    val obj = node as ObjectNode
                    if (obj.path("type").asText("") in setOf("Group", "Field", "Rule", "Computation")) {
                        obj.put("id", "h%08x".format(counter++ * 2654435761L.toInt()))
                    }
                    obj.forEach { hash(it) }
                }
                node.isArray -> node.forEach { hash(it) }
            }
        }
        hash(root.path("content").path("modelRoot"))
        return mapper.writeValueAsString(root)
    }

    private fun findByTypeAndName(node: JsonNode, type: String, name: String): JsonNode? {
        if (node.isObject) {
            if (node.path("type").asText("") == type && node.path("name").asText("") == name) return node
            for (child in node) findByTypeAndName(child, type, name)?.let { return it }
        } else if (node.isArray) {
            for (child in node) findByTypeAndName(child, type, name)?.let { return it }
        }
        return null
    }
}
