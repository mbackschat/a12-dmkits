package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The checker's fact base measured against the element set the real Kernel produces for the same workspace.
 *
 * **Why this is an oracle and `WorkspaceMountTest` is not.** Those rows are shapes I authored to exercise the
 * mounting I wrote, so they can only disagree with my own reading of KF3 — and once already did not, when
 * every row shared a constant that hid a wrong denominator. This matrix is authored in `:adapter` through the
 * typed kits, and the projection compared here is the one `WorkspacePreparationParityTest` asserts equal
 * between the Kernel's own `expand` and the JVM reader, over includes, imported typedefs, a repeated mount
 * and a seven-source typedef diamond. Eight of the sources a comparing row reads carry OPAQUE element ids
 * (counted 2026-09-12) while the locked facts are keyed by path, so a checker that keyed anything on `id`
 * fails here and passes every fixture we wrote ourselves. Its freshness is `:adapter`'s: only that lane re-derives the matrix from
 * its typed author, so a green `:consistency:check` alone does not mean the oracle is current.
 *
 * **What the matrix does NOT reach, so the hand-written rows stay.** No case spells `includeLevel` as
 * `SINGLE_RG` explicitly, none carries `modelAlias` beside non-empty `elements`, and its only multi-root
 * provider sits behind a mount that suspends first — the already-expanded branch, the level spelling and the
 * multi-root refusal are `WorkspaceMountTest`'s, and this file is not evidence about them.
 *
 * **What is compared, and what deliberately is not.** Paths and element kinds: the expanded set of
 * declarations and where each one landed. Not the rules' condition TEXT, which the Kernel rewrites during
 * expansion while this checker leaves every declaration exactly as its author wrote it and rehosts only when
 * it resolves a reference ([DeclarationOrigin]) — comparing the text would measure a rewrite this module
 * deliberately does not perform.
 *
 * **A suspended case is an expectation, not a hole.** Where the checker does not implement a mount directive
 * it names the mount and reports no facts for it, so the row records which cases that is. Moving a case from
 * suspended to agreeing is exactly what implementing a directive looks like, and this table is where that
 * shows up.
 */
class WorkspaceMatrixReconciliationTest {

    private val agrees = "agrees with the Kernel"

    @Test
    fun everyMountedCaseEitherAgreesWithTheKernelOrNamesWhatItSuspended() {
        val expected = mapOf(
            "self-contained" to agrees,
            "local-typedef" to agrees,
            "include-contributed-typedef" to agrees,
            "imported-typedef-nested-single" to agrees,
            "imported-typedef-nested-model-root" to "suspended at [/Permit/Review (MODEL_ROOT)]",
            "alias-with-exclusions" to "suspended at [/Permit/Inspection (excludeRules)]",
            "rules-excluded-computation-retained" to "suspended at [/Permit/Inspection (excludeRules)]",
            "computations-excluded-rule-retained" to
                "suspended at [/Permit/Inspection (excludeComputations)]",
            "model-root-multiple-groups" to "suspended at [/Permit/Sections (MODEL_ROOT)]",
            "same-alias-mounted-twice" to agrees,
            "nested-rules-excluded" to "suspended at [/Permit/Review (excludeRules)]",
            // The two attachment cases differ in the directive that stops the checker first, not only in the
            // path: this one mounts the template as MODEL_ROOT, its sibling as SINGLE_RG with an exclusion.
            "attachment-rules-survive-outer-exclusion" to
                "suspended at [/Permit/Documents (MODEL_ROOT)]",
            "single-attachment-rules-survive-outer-exclusion" to
                "suspended at [/Permit/Documents (excludeRules)]",
            "single-provider-root-include" to agrees,
            "reference-family-single" to agrees,
            "reference-family-model-root" to "suspended at [/Policy/Cover (MODEL_ROOT)]",
            "reference-family-german-single" to agrees,
            "reference-family-mixed-language-neutral" to agrees,
            "reference-family-mixed-language-neutral-reverse" to agrees,
            "collection-diamond-unmounted" to agrees,
        )

        val measured = WorkspaceMatrixJson.caseIds().associateWith { outcome(it) }

        assertEquals(expected.keys, measured.keys, "the shared matrix gained or lost a case")
        assertEquals(expected, measured)
    }

    /**
     * The matrix is only an oracle while it still carries the shapes the claims above rest on, so each one a
     * future fixture edit could quietly remove is asserted rather than assumed. The provider-root mount is
     * the load-bearing one: flatten it and the case still agrees, before and after the fix it exists to hold.
     */
    @Test
    fun theMatrixStillCarriesTheShapesThisTestRestsOn() {
        val provider = WorkspaceMatrixJson.sources("single-provider-root-include")
            .getValue("inspection-root-mount")
        val root = Json.parseToJsonElement(provider).jsonObject
            .getValue("content").jsonObject.getValue("modelRoot").jsonObject
            .getValue("rootGroups").jsonArray.single().jsonObject.getValue("Group").jsonObject
        assertTrue("modelAlias" in root && root["elements"]?.jsonArray.isNullOrEmpty(),
            "this provider's own ROOT group must still be an unexpanded mount, which is the shape that " +
                "separates expanding a provider fully from reading its root's elements")

        assertEquals(7, WorkspaceMatrixJson.sources("collection-diamond-unmounted").size,
            "the collection case is the widest source closure the checker is measured on")
        assertTrue(
            WorkspaceMatrixJson.sources("reference-family-single").values
                .any { "\"opaque-1\"" in it },
            "the opaque-id sources are what separate a path-keyed fact base from an id-keyed one")
    }

    private fun outcome(caseId: String): String {
        val admitted = WorkspaceMatrixJson.sources(caseId)
            .map { (modelId, json) -> admit(ModelSource(json, diagnosticLabel = modelId)) }
            .filterIsInstance<Admission.Admitted>()
            .associateBy { it.modelId }
        val entryId = WorkspaceMatrixJson.entryModelId(caseId)
        val entry = admitted[entryId]
            ?: return "the entry source `$entryId` was not admitted"
        val prepared = prepare(entry, admitted - entryId)
        if (prepared.diagnostics.isNotEmpty()) {
            return "unreadable: " + prepared.diagnostics.joinToString { "${it.code} at ${it.at}" }
        }
        if (prepared.unexpandedMounts.isNotEmpty()) {
            return "suspended at " +
                prepared.unexpandedMounts.map { "${it.at} (${directive(it.reason)})" }.sorted()
        }
        return compare(expectedElements(caseId), prepared.elements.associate { it.path to it.kind })
    }

    /**
     * Which mount directive suspended the analysis. The full sentence is `WorkspaceMountTest`'s to hold; here
     * a class is what keeps the table readable while still failing when two directives swap — the path alone
     * cannot tell `excludeRules` from `excludeComputations` on the same mount.
     */
    private fun directive(reason: String): String = when {
        "MODEL_ROOT" in reason -> "MODEL_ROOT"
        "`excludeRules`" in reason -> "excludeRules"
        "`excludeComputations`" in reason -> "excludeComputations"
        "`includeLevel`" in reason -> "includeLevel"
        else -> reason
    }

    private fun expectedElements(caseId: String): Map<String, String> =
        Json.parseToJsonElement(WorkspaceMatrixJson.preparedStructure(caseId)).jsonObject
            .mapValues { (_, facts) -> facts.jsonObject.getValue("elementType").jsonPrimitive.content }

    private fun compare(expected: Map<String, String>, actual: Map<String, String>): String {
        // An empty oracle would agree with an empty fact base and read as a pass, so the denominator is
        // refused rather than trusted — the same rule the compiled-purity guard applies to its class count.
        if (expected.isEmpty()) {
            return "the matrix carries no expected elements for this case"
        }
        if (expected == actual) {
            return agrees
        }
        val misKinded = expected.keys.intersect(actual.keys)
            .filter { expected[it] != actual[it] }
            .map { "$it is ${actual[it]} and not ${expected[it]}" }
        return listOf(
            "absent ${(expected.keys - actual.keys).sorted()}",
            "unexpected ${(actual.keys - expected.keys).sorted()}",
            "mis-kinded ${misKinded.sorted()}",
        ).filterNot { it.endsWith("[]") }.joinToString("; ")
    }
}
