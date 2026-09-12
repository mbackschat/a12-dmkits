package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The two relative field references A12 declares on a GROUP — `indexFieldName` and `sortFields[].name`.
 *
 * Both were named in every report as members this checker does not resolve, which is the gap this closes.
 * They could not be closed on the existing reference machinery: what a rule's `errorEntityRelPath` does when
 * it names nothing is a law about THAT member, these two have codes of their own, and their resolution has
 * no short-name fallback.
 *
 * **One mechanism explains every row: A12 resolves the spelling positionally from the group, then judges
 * what it landed on.** A direct child FIELD is the only thing that is legal. Something else that exists — a
 * field one level down, a field of the parent reached with `..` — draws the member's own code. Anything
 * that resolves to nothing, blank and whitespace included, stops A12 from BUILDING the model, so no verdict
 * about it exists at all (`GroupIndexAndSortReferenceLawsTest`, kernel 30.8.1, 2026-09-12).
 *
 * That mechanism is discriminated rather than fitted: `Items/Sku` read from inside `/Order/Items` names
 * nothing and stops the build, while the same spelling read from `/Order` resolves to a real field outside
 * the group and draws the code. A reading where `..` is the special ingredient gets that pair wrong.
 *
 * **An ABSENT member is legal and a BLANK one is fatal** — the sibling slot families' "blank counts as
 * absent" law inverted as far as it goes. And the check runs on every group, not only a repeatable one, so a
 * non-repeatable group carrying an unresolvable index is refused just the same.
 *
 * **An ABSOLUTE spelling escapes every typed channel**: `ElementPathUtils.splitRelPath` raises "illegal
 * relPath" during conversion. The model is unusable, which is what this checker reports; that A12 reports it
 * as an untyped raise is [KF259]'s class, not something a consumer can act on differently.
 */
fun PreparedModel.groupOrderingFindings(): List<Finding> =
    elements.filter { it.kind == GROUP_KIND }.flatMap { group ->
        val body = group.declaration[GROUP_KIND] as? JsonObject
        indexFindings(group, body) + sortFindings(group, body)
    }

private const val GROUP_KIND = "Group"
private const val FIELD_KIND = "Field"

private fun PreparedModel.indexFindings(group: DeclaredElement, body: JsonObject?): List<Finding> {
    val declared = (body?.get("indexFieldName") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        ?: return emptyList()
    return listOfNotNull(orderingFinding(group, declared, "indexFieldName", "MVK_INVALID_INDEX_FIELD"))
}

private fun PreparedModel.sortFindings(group: DeclaredElement, body: JsonObject?): List<Finding> {
    val entries = body?.get("sortFields") as? JsonArray ?: return emptyList()
    return entries.flatMap { entry ->
        val name = (entry as? JsonObject)?.get("name")
            ?.let { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.contentOrNull }
        if (name == null) {
            // A12's reader binds each entry to a typed record and refuses one without a string `name`
            // outright — a tier above any verdict, so it may not draw a sort-field code.
            listOf(unbuildable(group, "a `sortFields` entry here carries no string `name`"))
        } else {
            listOfNotNull(orderingFinding(group, name, "sortFields[].name", "MVK_INVALID_SORT_FIELD"))
        }
    }
}

/**
 * One ordering reference, judged by what it lands on.
 *
 * @param outsideCode the member's own code for a spelling that resolves to something other than one of this
 *     group's own fields. The two members do NOT share one — `MVK_INVALID_INDEX_FIELD` is named for the
 *     index and `MVK_INVALID_SORT_FIELD` for the sort, and each was measured on its own member rather than
 *     transferred from the other.
 */
private fun PreparedModel.orderingFinding(
    group: DeclaredElement,
    written: String,
    member: String,
    outsideCode: String,
): Finding? {
    if (written.startsWith("/")) {
        return unbuildable(group, "`$member` is `$written`, which A12 refuses as an illegal relative path")
    }
    val landed = resolvePositionally(group.path, written)
    val target = elements.firstOrNull { it.path == landed }
    return when {
        // DIRECT child, tested on where it LANDED rather than on how it was spelled: `Items/Sku` written in
        // `/Order` lands on a real field whose own parent is `/Order/Items`, so it is outside — and comparing
        // the spelling would have called it a direct child.
        target != null && target.kind == FIELD_KIND && landed.substringBeforeLast('/') == group.path -> null
        target != null && target.kind == FIELD_KIND -> Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            outsideCode,
            "`$member` is `$written`, which names `$landed` — a field outside this group, where A12 accepts " +
                "only one of the group's own",
            group.path,
        )
        else -> unbuildable(
            group,
            "`$member` is `$written`, which names no field of this group — a blank or unresolvable one " +
                "included, and a child GROUP of that name does not count",
        )
    }
}

/**
 * Resolve [written] from [from] with no short-name fallback — `..` climbs, anything else descends.
 *
 * The condition resolver's model-wide fallback deliberately does not apply: a bare name here is looked up
 * among the group's own fields alone, so a field of the parent group is not reachable however the model
 * configures `fieldRefByShortNameAllowed`.
 */
private fun resolvePositionally(from: String, written: String): String {
    val segments = from.split('/').filter { it.isNotEmpty() }.toMutableList()
    written.split('/').filter { it.isNotEmpty() }.forEach { step ->
        when (step) {
            ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.size - 1)
            else -> segments += step
        }
    }
    return "/" + segments.joinToString("/")
}

private fun unbuildable(group: DeclaredElement, explain: String) = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    CK_MODEL_NOT_BUILDABLE,
    "$explain — so A12 never builds the model and no consistency verdict about it exists",
    group.path,
)
