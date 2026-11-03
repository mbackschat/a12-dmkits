package io.github.mbackschat.a12.dm.adapter

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws
import io.github.mbackschat.a12.dm.core.ModelConsistencyException
import io.github.mbackschat.a12.dm.core.ModelResolver
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.modelkit.MountOptions
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Where a mount group's `usageType` comes from on kernel 31.1.1, and which `attachment` marker shields which
 * exclusion (KERNEL-FINDINGS KF3, corrected 2026-11-02).
 *
 * Kernel 30.8.1 replaced the mount's slot with the provider root's. On 31.1.1 the mount KEEPS its own marker and
 * the provider root's never transfers, while the two shields stay where they were: the provider root's marker
 * still shields its mount's OWN exclusion, and an OUTER exclusion is shielded by a nested group's final marker,
 * which is now that group's own. Each grid is asserted whole, so a row that moves cannot hide behind a neighbour,
 * and the rows that tell the rival readings apart are the ones with exactly one of the two markers set.
 *
 * The leaf is an attachment template (its mandated fields and rules) plus one rule of its own, so every row but
 * one is a model A12 admits; the refused row is the one whose exclusion removed the mandated rules from a group
 * that is still an attachment. A mount group marked `attachment` is written into the tree directly, because that
 * raw marker is the input being measured and no authoring surface writes it.
 */
class MountUsageTypeKernelTest {

    @Test
    fun aMountKeepsItsOwnUsageTypeAndTheProviderRootShieldsItsOwnExclusion() {
        val observed = linkedMapOf<String, String>()
        for (rootMarked in listOf(true, false)) for (mountMarked in listOf(false, true)) for (exclude in listOf(false, true)) {
            val leaf = leaf(rootMarked)
            val entry = entry(MountOptions.single().let { if (exclude) it.excludingRules() else it })
            if (mountMarked) mountBody(entry).put("usageType", "attachment")
            observed[row("root", rootMarked, "mount", mountMarked, "excludeRules", exclude)] =
                observe(entry.toString(), "/Permit/Details") { reference -> if (reference == "leaf") leaf else null }
        }
        assertEquals(linkedMapOf(
            row("root", true, "mount", false, "excludeRules", false) to "none $ALL",
            row("root", true, "mount", false, "excludeRules", true) to "none $ALL",
            row("root", true, "mount", true, "excludeRules", false) to "attachment $ALL",
            row("root", true, "mount", true, "excludeRules", true) to "attachment $ALL",
            row("root", false, "mount", false, "excludeRules", false) to "none $ALL",
            row("root", false, "mount", false, "excludeRules", true) to "none []",
            row("root", false, "mount", true, "excludeRules", false) to "attachment $ALL",
            row("root", false, "mount", true, "excludeRules", true) to "attachment REFUSED",
        ), observed)
    }

    @Test
    fun anOuterExclusionIsShieldedByTheNestedMountsOwnMarker() {
        val observed = linkedMapOf<String, String>()
        for (rootMarked in listOf(true, false)) for (innerMarked in listOf(false, true)) {
            val leaf = leaf(rootMarked)
            val section = tree(DocumentModelDraft.named("section").locale("en_US")
                .rootGroup("Section") { it.mount("Inspection", "leaf") }.build().toJson())
            if (innerMarked) mountBody(section).put("usageType", "attachment")
            val entry = DocumentModelDraft.named("permit").locale("en_US")
                .rootGroup("Permit") { it.mount("Review", "section", MountOptions.single().excludingRules()) }
                .build().toJson()
            observed[row("root", rootMarked, "inner", innerMarked)] = observe(entry, "/Permit/Review/Inspection") {
                when (it) {
                    "leaf" -> leaf
                    "section" -> section.toString()
                    else -> null
                }
            }
        }
        assertEquals(linkedMapOf(
            row("root", true, "inner", false) to "none []",
            row("root", true, "inner", true) to "attachment $ALL",
            row("root", false, "inner", false) to "none []",
            row("root", false, "inner", true) to "attachment $ALL",
        ), observed)
    }

    /** MODEL_ROOT keeps the mount's own marker too, where 30.8.1 cleared it; only expansion is observable, because
     *  a marked MODEL_ROOT mount holds groups and so can never carry the mandated attachment fields itself. */
    @Test
    fun aModelRootMountKeepsItsOwnUsageType() {
        val component = DocumentModelDraft.named("component").locale("en_US")
            .rootGroup("Component") { it.field("Description", FieldKind.STRING) }.build().toJson()
        val resolver = ModelResolver { reference -> if (reference == "component") component else null }
        val observed = listOf(false, true).associate { marked ->
            val entry = entry(MountOptions.single().asModelRoot(), "component")
            if (marked) mountBody(entry).put("usageType", "attachment")
            val observation = PreparationParityHarness.observeElements(entry.toString(), resolver)
            PreparationParityHarness.assertElementsEquivalent(observation)
            row("mount", marked) to usage(KernelModelOracle.of(entry.toString(), resolver), "/Permit/Details")
        }
        assertEquals(mapOf(row("mount", false) to "none", row("mount", true) to "attachment"), observed)
    }

    /** The kernel's usage type and rule set at [groupPath], checked against the JVM reader and the interpreter on
     *  every row A12 admits. */
    private fun observe(entry: String, groupPath: String, resolver: ModelResolver): String {
        val usage = usage(KernelModelOracle.of(entry, resolver), groupPath)
        val rules = try {
            KernelAdapter.ofJson(entry, resolver).ruleFullNames().map { it.substringAfterLast('/') }.sorted().toString()
        } catch (refused: ModelConsistencyException) {
            return "$usage REFUSED"
        }
        PreparationParityHarness.assertEquivalent(PreparationParityHarness.observe(entry, resolver))
        return "$usage $rules"
    }

    private fun usage(kernel: KernelModelOracle, groupPath: String) =
        kernel.group(groupPath).usageType.map { it.toString() }.orElse("none")

    private fun leaf(rootMarked: Boolean): String {
        val template = EditSession.read(DocumentModelDraft.named("leaf").locale("en_US")
            .attachmentGroup("Inspection") {}.build().toJson())
        template.group("/Inspection").addField("Score", FieldKind.NUMBER)
        val json = template.toJson()
        val ruled = RuntimeLaws.withRule(json, "/Inspection", "NegativeScore", "../Score", "N1",
            BoundModel.ofJson(json).field("/Inspection/Score").filled())
        return if (rootMarked) ruled else tree(ruled).also { rootBody(it).remove("usageType") }.toString()
    }

    private fun entry(options: MountOptions, reference: String = "leaf"): ObjectNode =
        tree(DocumentModelDraft.named("permit").locale("en_US")
            .rootGroup("Permit") { it.mount("Details", reference, options) }.build().toJson())

    private fun rootBody(model: ObjectNode) = model.at("/content/modelRoot/rootGroups/0/Group") as ObjectNode

    /** The body of the first group under the root: the mount in every entry and section built here. */
    private fun mountBody(model: ObjectNode) =
        (rootBody(model).path("elements") as ArrayNode).first { it.path("type").asText() == "Group" }.path("Group") as ObjectNode

    private fun tree(json: String) = MAPPER.readTree(json) as ObjectNode

    private fun row(vararg parts: Any) = parts.toList().chunked(2).joinToString(" ") { (k, v) -> "$k=$v" }

    private companion object {
        val MAPPER = ObjectMapper()
        const val ALL = "[AttachmentExactlyOneSource, AttachmentInternalFilenameRequired, AttachmentMimeTypeRequired, " +
            "AttachmentSizeRequired, NegativeScore]"
    }
}
