package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
 * **A THIRD gate reads the selected local field's TYPE**, and it lives here rather than in a file of its own
 * because it is the same lookup: `checkIndexField` returns at the first gate that fires, so a type verdict is
 * only ever reached by a reference that already resolved, and separating them would let a resolution fix read
 * as a type approval. The report still tells them apart — `CheckFamily.GROUP_ORDERING_FIELD_TYPE` is its own
 * constant. KF295's remote/local controls (2026-09-27, `FieldVariantStageLawsTest`) distinguish the original
 * referent from the surviving local field. A `noValueValidation` string draws `MVK_INDEX_FIELD_NO_VALUE_VALIDATION` /
 * `MVK_SORT_FIELD_NO_VALUE_VALIDATION` even as a direct child (`GroupOrderingFieldTypeLawsTest`).
 *
 * **The index member has a FOURTH gate in source that no stored A12 model can reach**, so it is excluded on a
 * cited dead path rather than carried as silence: `MVK_INDEX_FIELD_NO_TABLE_ENUM` needs
 * `IEnumerationType.getTableName()` to be present, and the only implementor of that interface returns
 * `Optional.empty()` unconditionally, is built by a constructor taking no table argument, and binds from a
 * DM-JSON shape with no table member at all — measured: the reader REFUSES a `tableName` outright. The sort
 * member has no such gate to begin with, so the pair above is the whole family.
 *
 * **An ABSENT member is legal and a BLANK one is fatal** — the sibling slot families' "blank counts as
 * absent" law inverted as far as it goes. And the check runs on every group, not only a repeatable one, so a
 * non-repeatable group carrying an unresolvable index is refused just the same.
 *
 * **An ABSOLUTE spelling escapes every typed channel**: `ElementPathUtils.splitRelPath` raises "illegal
 * relPath" during conversion. The model is unusable, which is what this checker reports; that A12 reports it
 * as an untyped raise is [KF259]'s class, not something a consumer can act on differently.
 */
internal fun TrackedFactBase.groupOrderingFindings(): List<Finding> =
    declarations().filter { it.declaredKind == DeclaredKind.GROUP }.flatMap { group ->
        val body = group.body
        indexFindings(group, body) + sortFindings(group, body)
    }

private fun TrackedFactBase.indexFindings(group: TrackedDeclaration, body: JsonObject?): List<Finding> {
    val declared = (body?.get("indexFieldName") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        ?: return emptyList()
    return listOfNotNull(
        orderingFinding(
            group, declared, "indexFieldName",
            "MVK_INVALID_INDEX_FIELD", "MVK_INDEX_FIELD_NO_VALUE_VALIDATION",
        ),
    )
}

private fun TrackedFactBase.sortFindings(group: TrackedDeclaration, body: JsonObject?): List<Finding> {
    val entries = body?.get("sortFields") as? JsonArray ?: return emptyList()
    return group.unsortableCarrier(entries) + group.repeatedSortField(entries) + entries.flatMap { entry ->
        // A non-string primitive is COERCED to the String member, as a rule's `errorCode` is, so `5` is the
        // name "5" and resolves like any other — measured, because reporting it as a reader refusal was
        // this family's one emission with no basis. Only a MISSING `name`, or an entry that is not an
        // object, is the refusal.
        val name = (entry as? JsonObject)?.get("name")?.let { (it as? JsonPrimitive)?.contentOrNull }
        if (name == null) {
            listOf(unbuildable(group, "a `sortFields` entry here carries no readable `name`"))
        } else {
            listOfNotNull(
                orderingFinding(
                    group, name, "sortFields[].name",
                    "MVK_INVALID_SORT_FIELD", "MVK_SORT_FIELD_NO_VALUE_VALIDATION",
                ),
            )
        }
    }
}

/**
 * The finding a NON-repeatable group carrying sort entries draws: a group with at most one row has no order.
 *
 * **The member's PRESENCE is not the gate, its CONTENT is** — an EMPTY `sortFields` on the same carrier is
 * ADMITTED, measured (`GroupIndexAndSortReferenceLawsTest`). Reading the key rather than the entries would
 * refuse every non-repeatable group that merely carries it.
 *
 * **The four sibling PRIORITY laws the ledger lists beside this one are unreachable by SHAPE**, so they are
 * not silence here: A12's DM-JSON `Group.SortField` declares `field` and `order` and nothing else, the
 * position being derived from the list INDEX by `GroupWrapper.SortFieldWrapper` — a stored model has no
 * priority to duplicate, to overrun, or to leave a hole in.
 */
private fun TrackedDeclaration.unsortableCarrier(entries: JsonArray): List<Finding> {
    if (entries.isEmpty() || isRepeatable) {
        return emptyList()
    }
    return listOf(
        Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            "MVK_INVALID_SORTINFO",
            "`$path` declares `sortFields` and does not repeat — a group holding at most one row has no " +
                "order to declare, so either make it repeatable or drop the entries",
            path,
        ),
    )
}

/**
 * The one finding a `sortFields` list repeating a name draws — ONCE, whatever repeats and however often.
 *
 * A12's own multiplicity, measured at the two cells that could have made it larger: a name appearing three
 * times draws one, and two names each appearing twice draw one ([KERNEL-FINDINGS KF345],
 * `rulekit.validate.laws.GroupOrderingMultipleDefectLawsTest`). So it is a verdict on the LIST rather than on
 * an entry, which is why it is emitted here and not inside the per-entry walk below.
 *
 * It rides beside whatever the entries themselves draw — the duplicate does not withhold the type class, and
 * the type class does not withhold it.
 */
private fun TrackedDeclaration.repeatedSortField(entries: JsonArray): List<Finding> {
    val names = entries.mapNotNull { entry ->
        ((entry as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull
    }
    if (names.size == names.distinct().size) {
        return emptyList()
    }
    return listOf(
        Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            "MVK_SORT_FIELD_DUPLICATE",
            "this group's `sortFields` names the same field more than once, which A12 refuses — a sort " +
                "order lists each field once, and the repeat says nothing a single entry does not",
            path,
        ),
    )
}

/**
 * Bind one ordering reference, then judge its surviving local field by short name (KF295).
 *
 * @param outsideCode the member's own code when the landed short name selects no surviving local field.
 *     The two members do NOT share one — `MVK_INVALID_INDEX_FIELD` is named for the
 *     index and `MVK_INVALID_SORT_FIELD` for the sort, and each was measured on its own member rather than
 *     transferred from the other.
 * @param noValueValidationCode the member's own code for the third gate, likewise unshared.
 */
private fun TrackedFactBase.orderingFinding(
    group: TrackedDeclaration,
    written: String,
    member: String,
    outsideCode: String,
    noValueValidationCode: String,
): Finding? {
    if (written.startsWith("/")) {
        return unbuildable(group, "`$member` is `$written`, which A12 refuses as an illegal relative path")
    }
    // GATE ONE — the spelling must land on a field at all. A climb past the root cannot reach one either:
    // the model root holds groups, so every clamped path names a group path, never a field.
    val landed = resolvePositionally(group.path, written)
    val target = declarationAt(landed)
    if (target == null || target.declaredKind != DeclaredKind.FIELD) {
        return unbuildable(
            group,
            "`$member` is `$written`, which names no field at all — a blank or unresolvable one included, " +
                "and a GROUP of that name does not count",
        )
    }
    // KF295's filter and twin controls require a surviving local cursor, after the original binding above.
    val shortName = landed.substringAfterLast('/')
    val own = defaultVariantFieldAt("${group.path}/$shortName")
    if (own != null) {
        // GATE THREE — the field A12 ends up with is this group's OWN field of that short name, which is
        // also the one it reports the type error against, so the finding is attached there and not at the
        // group that named it.
        return noValueValidationFinding(own, member, noValueValidationCode)
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
 * Whether this value SETS the flag, the way A12 binds it rather than the way JSON types it.
 *
 * A12 does not require a JSON boolean here, and a checker demanding one admits fields A12 refuses. Measured
 * (`rulekit.validate.laws.GroupOrderingFieldTypeLawsTest.theShapesAroundTheRefusedTypeAreAdmitted`): `true`
 * and the string `"true"` set it; `false`, the string `"false"` and the number `0` clear it; the numbers `1`
 * and `2` set it, so any non-zero number does. A string that is neither boolean literal — `"yes"` — is not
 * coerced at all but refuses A12's reader outright, which is a binding verdict this family does not own, so
 * it is left to whoever reports unbindable members rather than silently read as false here.
 */
private fun JsonPrimitive.setsTheFlag(): Boolean {
    booleanOrNull?.let { return it }
    if (isString) {
        return false
    }
    return content.toDoubleOrNull()?.let { it != 0.0 } ?: false
}

/**
 * Whether [field] is the one type A12 refuses as an ordering key: a string declared `noValueValidation`.
 *
 * The predicate keys on the STRING body carrying the flag, which is `FieldUtils.isNoValueValidation`'s own
 * shape — it reads the flag only after `isString`, so the same flag on any other type is not this law. A
 * field whose `fieldType` A12 cannot BIND at all is not judged here either: that model never loads, and
 * saying so is [fieldTypeBindingFindings]'s business rather than a second opinion from this family.
 */
private fun noValueValidationFinding(field: TrackedDeclaration, member: String, code: String): Finding? {
    val string = FieldType.STRING.bodyIn(field.body?.get("fieldType") as? JsonObject)
        ?: return null
    if ((string["noValueValidation"] as? JsonPrimitive)?.setsTheFlag() != true) {
        return null
    }
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        code,
        "`$member` names `${field.path}`, a string declared `noValueValidation` — A12 refuses a field it " +
            "never validates as an ordering key",
        field.path,
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

private fun unbuildable(group: TrackedDeclaration, explain: String) = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    CK_MODEL_NOT_BUILDABLE,
    "$explain — so A12 never builds the model and no consistency verdict about it exists",
    group.path,
)
