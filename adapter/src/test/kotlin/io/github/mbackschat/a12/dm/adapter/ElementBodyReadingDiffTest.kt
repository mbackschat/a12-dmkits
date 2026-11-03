package io.github.mbackschat.a12.dm.adapter

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.consistency.CK_MODEL_NOT_BUILDABLE
import io.github.mbackschat.a12.dm.consistency.CheckOutcome
import io.github.mbackschat.a12.dm.consistency.ConsistencyWorkspace
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.outcome
import io.github.mbackschat.a12.dm.core.ModelResolver
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationResult
import io.github.mbackschat.a12.dm.interpreter.ModelWorkspace
import io.github.mbackschat.a12.dm.interpreter.WorkspaceModelSource
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.modelkit.MountOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * How A12 reads an element's body, and that the portable checker and the interpreter's workspace read it alike
 * (KERNEL-FINDINGS KF3, corrected 2026-11-02). Kernel 31.1.1 reads a missing or `null` Group body as an empty group,
 * and builds no model from any other non-object Group body or from a Field body that is not an object, a missing one
 * included. Each holds on every carrier: the entry's root group, a nested group and a field, and the same three in an
 * included model under either include level.
 *
 * The Field rows are the discriminator against the rival reading "a body with no value is an empty element": a
 * Field has no empty reading, so A12 refuses it. Each carrier's control is the workspace as written (a Group with
 * the body `{}`), which must itself build, check and prepare, or every row would hold whatever the three readers
 * share. Own domain (a permit and the component it includes).
 */
class ElementBodyReadingDiffTest {

    @Test
    fun aNoValueBodyIsAnEmptyGroupAndAnyOtherNonObjectLeavesNoModel() {
        val expected = linkedMapOf<String, String>()
        val observed = linkedMapOf<String, String>()
        for (carrier in Carrier.entries) {
            val control = observe(carrier.workspace(Control))
            assertTrue(BUILT.matches(control), "$carrier: the control must build, check and prepare: $control")
            for ((spelling, body) in SPELLINGS) {
                val emptyGroup = carrier.kind == "Group" && (body == null || body.isNull)
                expected["$carrier $spelling"] = when {
                    emptyGroup -> control
                    carrier.discardedRoot -> NOT_BUILT_SUSPENDED
                    else -> NOT_BUILT
                }
                observed["$carrier $spelling"] = observe(carrier.workspace(Body(body)))
            }
        }
        assertEquals(expected, observed)
    }

    /**
     * A provider with no root group mounts nothing under `MODEL_ROOT`, which kernel 31.1.1 builds and checks, while
     * `SINGLE_RG`, which needs exactly one root group to replace, still leaves no model (KERNEL-FINDINGS KF3).
     */
    @Test
    fun aProviderWithoutRootGroupsMountsNothingOnlyUnderModelRoot() {
        val empty = (MAPPER.readTree(component().toString()) as ObjectNode).also {
            (it.at("/content/modelRoot/rootGroups") as com.fasterxml.jackson.databind.node.ArrayNode).removeAll()
        }.toString()
        val observed = Level.entries.associateWith { level -> observe(host(level == Level.MODEL_ROOT) to empty) }
        assertTrue(BUILT.matches(observed.getValue(Level.MODEL_ROOT)), observed.toString())
        assertEquals(NOT_BUILT_SUSPENDED, observed.getValue(Level.SINGLE_RG))
    }

    /** A12's build and consistency verdict, the checker's refusal and the interpreter's preparation of the entry. */
    private fun observe(workspace: Pair<String, String?>): String {
        val (entry, provider) = workspace
        val resolve = ModelResolver { reference -> if (reference == PROVIDER_ID) provider else null }
        val built = runCatching { KernelModelOracle.of(entry, resolve) }.isSuccess
        val checks = built && runCatching { KernelConsistencyOracle().checkConsistency(entry, resolve) }.isSuccess
        val sources = listOfNotNull(entry, provider)
        val report = ConsistencyWorkspace.fromSources(sources.map(::ModelSource)).check(ENTRY_ID)
        val refusals = (report.findings.filter { it.justifiesRejection }.map { it.code } +
            report.diagnostics.map { it.code.toString() }).toSortedSet()
        val gaps = report.gaps.map { it.reason }.filter { "Group" in it }
        val prepared = ModelWorkspace.fromSources(sources.map { WorkspaceModelSource.builder().json(it).build() })
            .tryPrepare(ENTRY_ID) is ModelPreparationResult.Success
        // A rejection that states A12's own build failure is the whole answer, and so is a mount the checker leaves
        // unexpanded; anything else is shown in full.
        val checker = when {
            report.outcome == CheckOutcome.REJECTED && CK_MODEL_NOT_BUILDABLE in refusals -> "REJECTED"
            report.outcome == CheckOutcome.NOT_ESTABLISHED && report.gaps.any { "is not expanded here" in it.reason } ->
                "SUSPENDED"
            else -> "${report.outcome} $refusals$gaps"
        }
        return "A12 ${if (built) "built" else "not built"}${if (checks) ", checks" else ""}; checker $checker; " +
            "interpreter ${if (prepared) "prepares" else "refuses"}"
    }

    /** The body a row writes: [Control] keeps a Field's as written and gives a Group `{}`; a null [Body] removes it. */
    private sealed interface Edit
    private data object Control : Edit
    private data class Body(val node: JsonNode?) : Edit

    /**
     * Where the body is written: the [kind] element [target] picks out of the entry, or of the included [level].
     * A `SINGLE_RG` provider root is [discardedRoot]: it disappears into its mount, and the checker states a mount A12
     * refuses as an unexpanded mount rather than as a rejection, which under-reports by design (`PreparationCollector`).
     */
    private enum class Carrier(val kind: String, val level: Level?, val target: (ObjectNode) -> ObjectNode) {
        ENTRY_ROOT_GROUP("Group", null, { it.rootGroup() }),
        ENTRY_NESTED_GROUP("Group", null, { it.rootGroup().child("Address") }),
        ENTRY_FIELD("Field", null, { it.rootGroup().child("Note") }),
        SINGLE_RG_PROVIDER_ROOT_GROUP("Group", Level.SINGLE_RG, { it.rootGroup() }),
        SINGLE_RG_PROVIDER_NESTED_GROUP("Group", Level.SINGLE_RG, { it.rootGroup().child("Sub") }),
        SINGLE_RG_PROVIDER_FIELD("Field", Level.SINGLE_RG, { it.rootGroup().child("Description") }),
        MODEL_ROOT_PROVIDER_ROOT_GROUP("Group", Level.MODEL_ROOT, { it.rootGroup() }),
        MODEL_ROOT_PROVIDER_NESTED_GROUP("Group", Level.MODEL_ROOT, { it.rootGroup().child("Sub") }),
        MODEL_ROOT_PROVIDER_FIELD("Field", Level.MODEL_ROOT, { it.rootGroup().child("Description") }),
        ;

        val discardedRoot get() = this == SINGLE_RG_PROVIDER_ROOT_GROUP

        fun workspace(edit: Edit): Pair<String, String?> {
            fun apply(model: ObjectNode) = model.also { root ->
                val element = target(root)
                when (edit) {
                    Control -> if (kind == "Group") element.set<ObjectNode>(kind, MAPPER.createObjectNode())
                    is Body -> if (edit.node == null) element.remove(kind) else element.set<ObjectNode>(kind, edit.node)
                }
            }.toString()
            return when (level) {
                null -> apply(entry()) to null
                else -> host(level == Level.MODEL_ROOT) to apply(component())
            }
        }
    }

    private enum class Level { SINGLE_RG, MODEL_ROOT }

    private companion object {
        val MAPPER = ObjectMapper()
        const val ENTRY_ID = "permit"
        const val PROVIDER_ID = "component"
        /** A12 builds and checks it, the checker refuses nothing and leaves no Group gap, the interpreter prepares. */
        val BUILT = Regex("A12 built, checks; checker (ALL_CHECKS_PASSED|NO_REJECTION) \\[]\\[]; interpreter prepares")
        const val NOT_BUILT = "A12 not built; checker REJECTED; interpreter refuses"
        const val NOT_BUILT_SUSPENDED = "A12 not built; checker SUSPENDED; interpreter refuses"

        /** Each spelling as JSON; `null` is a missing body. */
        val SPELLINGS: Map<String, JsonNode?> = linkedMapOf(
            "missing" to null, "null" to MAPPER.nullNode(), "true" to MAPPER.readTree("true"),
            "7" to MAPPER.readTree("7"), "\"x\"" to MAPPER.readTree("\"x\""), "[]" to MAPPER.createArrayNode(),
        )

        fun ObjectMapper.nullNode(): JsonNode = nodeFactory.nullNode()

        fun entry() = MAPPER.readTree(DocumentModelDraft.named(ENTRY_ID).locale("en_US")
            .rootGroup("Permit") { p -> p.field("Note", FieldKind.STRING).group("Address") { a -> a.field("Street", FieldKind.STRING) } }
            .build().toJson()) as ObjectNode

        fun host(modelRoot: Boolean): String = DocumentModelDraft.named(ENTRY_ID).locale("en_US")
            .rootGroup("Permit") { p ->
                val options = MountOptions.single()
                p.mount("Details", PROVIDER_ID, if (modelRoot) options.asModelRoot() else options)
            }
            .build().toJson()

        fun component() = MAPPER.readTree(DocumentModelDraft.named(PROVIDER_ID).locale("en_US")
            .rootGroup("Component") { c -> c.field("Description", FieldKind.STRING).group("Sub") { s -> s.field("Code", FieldKind.STRING) } }
            .build().toJson()) as ObjectNode

        fun ObjectNode.rootGroup() = at("/content/modelRoot/rootGroups/0") as ObjectNode

        fun ObjectNode.child(name: String) = path("Group").path("elements").first { it.path("name").asText() == name } as ObjectNode

    }
}
