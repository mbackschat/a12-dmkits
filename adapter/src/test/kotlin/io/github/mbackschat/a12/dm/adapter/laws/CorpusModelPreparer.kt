package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.KernelExpansionFixture
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.mgmtp.a12.kernel.md.facade.DocumentModelServiceFactory
import com.mgmtp.a12.model.notification.RankedNotification
import com.mgmtp.a12.model.notification.Severity
import io.github.mbackschat.a12.dm.core.ModelResolver
import java.io.StringReader
import java.io.StringWriter

/**
 * **Real-corpus pre-expansion** (WI-7, SEMANTICS-FORMALIZATION-PROPOSAL §6.7 / S2) — the kernel-side stage that
 * turns a raw production DM-JSON model into a self-contained form each engine can evaluate, so the real-corpus
 * sweep ([RealCorpusSweepTest]) compares interpreter vs kernel on the SAME model.
 *
 * Two outputs, because the two sides of the differential accept different forms:
 *
 * - **[prepareKernelJson]** (kernel legs) = deserialize → `expand(resolver)` → serialize. `expand` folds
 *   `include`s into the group tree and embeds every imported `_TDM` type-definition into the base model's
 *   `content.typeDefinitions` list (the kernel's own resolution — nothing kernel-flavoured leaks). The result is
 *   self-contained DM-JSON with the model's **opaque ids preserved** and `TypeDefType` references intact — the
 *   kernel resolves both natively.
 * - **[toInterpreterJson]** (interpreter leg + [io.github.mbackschat.a12.dm.interpreter.gen.SeedGenerator]) =
 *   the kernel form, then two **pure-JSON** transforms the kernel-free loader needs:
 *   1. [inlineTypeDefs] — replace each field's `TypeDefType` fieldType with the referenced definition's concrete
 *      fieldType (transitively). Without this the loader flattens a typedef field to a plain STRING (S2), which
 *      seeds illegal values and suppresses the very rules under test.
 *   2. [normalizeIdsToPaths] — rewrite every group/field/rule/computation `id` to its name-path
 *      (`/Root/Group/Field`). Real kernel models carry **opaque hash ids**, but the interpreter's `ModelLoader`
 *      treats `id` as the full path (INTERPRETER-GAPS); pre-expansion supplies the name-path form it expects.
 *
 * Both engines still address instances and render pointers by **name-path**, so the same placement list drives
 * the kernel (opaque-id model) and the interpreter (name-path model), and their signatures line up. The kernel is
 * id-agnostic and treats an inlined typedef identically to the reference — both facts are locked by
 * [CorpusModelPreparerTest] on own-domain fixtures (so the CI guard needs no real corpus).
 *
 * Kernel-free except [prepareKernelJson] (which must run `expand`), keeping the interpreter side's transforms
 * pure JSON.
 */
object CorpusModelPreparer {

    private val MAPPER = ObjectMapper()
    private val FACTORY = DocumentModelServiceFactory()

    /** Kernel legs: deserialize → `expand(resolver)` → serialize to self-contained DM-JSON (opaque ids +
     *  `TypeDefType` refs preserved; includes folded, imported typedefs embedded). */
    fun prepareKernelJson(rawJson: String, resolver: ModelResolver): String {
        val serializer = FACTORY.createDocumentModelSerializer()
        // The backend's own expansion port (KernelResolvers.expand, through the test fixture), so the corpus
        // kernel legs expand exactly as production does.
        val model = KernelExpansionFixture.expanded(rawJson, resolver)
        val sw = StringWriter()
        val errors = mutableListOf<RankedNotification>()
        serializer.serialize(model, sw) { n -> if (n.severity == Severity.ERROR) errors.add(n) }
        if (errors.isNotEmpty()) {
            throw IllegalStateException("model failed serialization consistency: " +
                errors.joinToString("; ") { it.toString() })
        }
        // Post-expand the includes are folded into the tree and imported typedefs embedded, but `header`
        // still lists the (now vestigial) references as `transitiveInclude`/`typeDefinitions`. Strip them so
        // the self-contained re-expand a downstream kernel leg (RuntimeLaws) runs finds nothing to resolve.
        return stripModelReferences(sw.toString())
    }

    /** Remove the vestigial `header.modelReferences` left after `expand` folded everything in — so a
     *  self-contained re-expand has nothing to resolve. No-op for a model that declared no references. */
    private fun stripModelReferences(json: String): String {
        val root = MAPPER.readTree(json) as ObjectNode
        (root.get("header") as? ObjectNode)?.remove("modelReferences")
        return MAPPER.writeValueAsString(root)
    }

    /** Interpreter leg: the kernel form with `TypeDefType` inlined and ids normalized to name-paths (pure JSON). */
    fun toInterpreterJson(kernelJson: String): String {
        val root = MAPPER.readTree(kernelJson) as ObjectNode
        inlineTypeDefs(root)
        (root.path("content").path("modelRoot") as? ObjectNode)?.let { normalizeIdsToPaths(it, emptyList()) }
        return MAPPER.writeValueAsString(root)
    }

    // ── TypeDefType inlining ──────────────────────────────────────────────────────────────────────────────

    /** Replace every field's `TypeDefType` fieldType with the referenced `content.typeDefinitions` entry's
     *  concrete fieldType (transitively, cycle-guarded). A reference with no matching definition is left as-is. */
    private fun inlineTypeDefs(root: ObjectNode) {
        val defs = HashMap<String, JsonNode>()
        for (td in root.path("content").path("typeDefinitions")) {
            val id = td.path("id").asText("")
            val ft = td.path("fieldType")
            if (id.isNotEmpty() && !ft.isMissingNode) defs[id] = ft
        }
        if (defs.isEmpty()) return
        inlineFieldTypes(root.path("content").path("modelRoot"), defs)
    }

    private fun inlineFieldTypes(node: JsonNode, defs: Map<String, JsonNode>) {
        when {
            node.isObject -> {
                val obj = node as ObjectNode
                if (obj.path("type").asText("") == "Field") {
                    val field = obj.path("Field")
                    val ft = field.path("fieldType")
                    if (field is ObjectNode && ft.path("type").asText("") == "TypeDefType") {
                        field.set<JsonNode>("fieldType", resolveTypeDef(ft, defs, emptySet()))
                    }
                }
                obj.forEach { inlineFieldTypes(it, defs) }
            }
            node.isArray -> node.forEach { inlineFieldTypes(it, defs) }
        }
    }

    /** Resolve a (possibly `TypeDefType`) fieldType to its concrete form; returns a deep copy for the target. */
    private fun resolveTypeDef(fieldType: JsonNode, defs: Map<String, JsonNode>, seen: Set<String>): JsonNode {
        if (fieldType.path("type").asText("") != "TypeDefType") return fieldType
        val refId = fieldType.path("TypeDefType").path("typeDefinitionId").asText("")
        if (refId.isEmpty() || refId in seen) return fieldType   // unresolved / cyclic → leave as-is
        val target = defs[refId] ?: return fieldType
        return resolveTypeDef(target.deepCopy(), defs, seen + refId)
    }

    // ── id → name-path normalization ──────────────────────────────────────────────────────────────────────

    /** Rewrite the `id` of every group/field/rule/computation under [node] to its name-path. A group extends the
     *  ancestor-name [stack] for its subtree; a field/rule/computation is a leaf-name under the current stack. */
    private fun normalizeIdsToPaths(node: JsonNode, stack: List<String>) {
        when {
            node.isObject -> {
                val obj = node as ObjectNode
                when (obj.path("type").asText("")) {
                    "Group" -> {
                        val childStack = stack + obj.path("name").asText("")
                        obj.put("id", "/" + childStack.joinToString("/"))
                        obj.forEach { normalizeIdsToPaths(it, childStack) }
                    }
                    "Field", "Rule", "Computation" -> {
                        obj.put("id", "/" + (stack + obj.path("name").asText("")).joinToString("/"))
                        obj.forEach { normalizeIdsToPaths(it, stack) }
                    }
                    else -> obj.forEach { normalizeIdsToPaths(it, stack) }
                }
            }
            node.isArray -> node.forEach { normalizeIdsToPaths(it, stack) }
        }
    }
}
