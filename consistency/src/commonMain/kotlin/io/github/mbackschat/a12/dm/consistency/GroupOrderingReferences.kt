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
 * **TWO gates, and the second reads a NAME rather than a position.** A12 resolves the spelling positionally
 * from the group; one that does not land on a field stops the model from being BUILT, so no verdict about it
 * exists. One that does land keeps only that field's SHORT NAME, and the group must then carry a field of
 * that name — otherwise the member's own code (`GroupIndexAndSortReferenceLawsTest`, kernel 30.8.1,
 * 2026-09-12).
 *
 * **The second gate is why "judge where the path landed" is wrong, and exactly one row separates them.**
 * With the short name `Sku` living at two levels, `../Sku` written in `/Order/Items` lands on `/Order/Sku` —
 * outside the group — and A12 ADMITS the model, because `/Order/Items` carries a `Sku` of its own. A checker
 * judging the landing rejects a model A12 takes. Every other row has disjoint short names per level, where
 * the two accounts agree, which is why that row had to be built rather than inferred.
 *
 * A third reading, that `..` is the special ingredient, is refuted separately: `Items/Sku` read from inside
 * `/Order/Items` names nothing and stops the build, while the same spelling read from `/Order` lands on a
 * real field and reaches the second gate.
 *
 * **The landed field's own TYPE is gated too, and that is NOT checked here.** A `noValueValidation` string
 * draws `MVK_INDEX_FIELD_NO_VALUE_VALIDATION` / `MVK_SORT_FIELD_NO_VALUE_VALIDATION` even as a direct child
 * (measured in the same lock), and the table-enum gate beside it is unmeasured. So passing both gates above
 * is necessary and not sufficient, and every report says so rather than letting silence read as approval.
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
    elements.filter { it.declaredKind == DeclaredKind.GROUP }.flatMap { group ->
        val body = group.body
        indexFindings(group, body) + sortFindings(group, body)
    }

private fun PreparedModel.indexFindings(group: DeclaredElement, body: JsonObject?): List<Finding> {
    val declared = (body?.get("indexFieldName") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        ?: return emptyList()
    return listOfNotNull(orderingFinding(group, declared, "indexFieldName", "MVK_INVALID_INDEX_FIELD"))
}

private fun PreparedModel.sortFindings(group: DeclaredElement, body: JsonObject?): List<Finding> {
    val entries = body?.get("sortFields") as? JsonArray ?: return emptyList()
    return entries.flatMap { entry ->
        // A non-string primitive is COERCED to the String member, as a rule's `errorCode` is, so `5` is the
        // name "5" and resolves like any other — measured, because reporting it as a reader refusal was
        // this family's one emission with no basis. Only a MISSING `name`, or an entry that is not an
        // object, is the refusal.
        val name = (entry as? JsonObject)?.get("name")?.let { (it as? JsonPrimitive)?.contentOrNull }
        if (name == null) {
            listOf(unbuildable(group, "a `sortFields` entry here carries no readable `name`"))
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
    // GATE ONE — the spelling must land on a field at all. A climb past the root cannot reach one either:
    // the model root holds groups, so every clamped path names a group path, never a field.
    val landed = resolvePositionally(group.path, written)
    val target = elements.firstOrNull { it.path == landed }
    if (target == null || target.declaredKind != DeclaredKind.FIELD) {
        return unbuildable(
            group,
            "`$member` is `$written`, which names no field at all — a blank or unresolvable one included, " +
                "and a GROUP of that name does not count",
        )
    }
    // GATE TWO — A12 keeps only the landed field's SHORT NAME and looks it up under this group, so what
    // decides is whether the group has a field of that NAME, not whether the path stayed inside it.
    val shortName = landed.substringAfterLast('/')
    if (elements.any { it.path == "${group.path}/$shortName" && it.declaredKind == DeclaredKind.FIELD }) {
        return null
    }
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        outsideCode,
        "`$member` is `$written`, which names `$landed`; A12 keeps only the name `$shortName` and this " +
            "group has no field called that",
        group.path,
    )
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
