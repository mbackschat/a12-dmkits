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
 * **A12 keeps a NAME, not a position.** Its converter joins the spelling to the group's path and keeps only the
 * last segment, and the group must then carry a field of that short name, otherwise the member's own code. Where
 * the spelling lands does not matter on kernel 31.1.1, with three exceptions that leave no model: an absolute
 * spelling and one climbing past the root, which the reader cannot resolve, and one landing on a GROUP, which the
 * reader binds where a field is expected. Landing on nothing, or on a rule, is only a name; the reader's map holds
 * fields and groups alone (KERNEL-FINDINGS KF653, `GroupIndexAndSortReferenceLawsTest`). On 30.8.1 a spelling that
 * did not land on a field left no model.
 *
 * **Why "judge where the path landed" is wrong, and two rows separate them.** With the short name `Sku` living at
 * two levels, `../Sku` written in `/Order/Items` lands on `/Order/Sku`, outside the group, and A12 admits the
 * model because `/Order/Items` carries a `Sku` of its own. And `../../Sku` lands on nothing at all and is admitted
 * the same way.
 *
 * **A THIRD gate reads the selected local field's TYPE**, and it lives here rather than in a file of its own
 * because it is the same lookup: `checkIndexField` returns at the first gate that fires, so a type verdict is
 * only ever reached by a reference that already resolved, and separating them would let a resolution fix read
 * as a type approval. The report still tells them apart — `CheckFamily.GROUP_ORDERING_FIELD_TYPE` is its own
 * constant. KF295's remote/local controls distinguish the referent the spelling lands on from the group's own
 * field of that short name, the one whose type is judged. A `noValueValidation` string draws `MVK_INDEX_FIELD_NO_VALUE_VALIDATION` /
 * `MVK_SORT_FIELD_NO_VALUE_VALIDATION` even as a direct child (`GroupOrderingFieldTypeLawsTest`).
 *
 * **The index member has a FOURTH gate in source that no stored A12 model can reach**, so it is excluded on a
 * cited dead path rather than carried as silence: `MVK_INDEX_FIELD_NO_TABLE_ENUM` needs
 * `IEnumerationType.getTableName()` to be present, and the only implementor of that interface returns
 * `Optional.empty()` unconditionally, is built by a constructor taking no table argument, and binds from a
 * DM-JSON shape with no table member at all — measured: kernel 31.1.1's reader passes over a `tableName`, leaving an
 * enumeration with no values when it stands alone (KERNEL-FINDINGS KF653). The sort
 * member has no such gate to begin with, so the pair above is the whole family.
 *
 * **An ABSENT member is legal; an EMPTY one is not absent.** An empty index is joined to the group's own path, so
 * the short name it keeps is the group's name, and A12 adds its basic sentence ([basicSentenceFindings]). An empty
 * or missing sort `name` binds no reference and leaves no model. A blank sort name is an unfilled
 * entry of A12's metamodel, so it draws `MVK_SORT_INFO_MISSING` beside the member's code and no name sentence. The
 * check runs on every group, not only a repeatable one.
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
    return orderingFindings(
        group, declared, "indexFieldName",
        "MVK_INVALID_INDEX_FIELD", "MVK_INDEX_FIELD_NO_VALUE_VALIDATION", statesName = true,
    )
}

private fun TrackedFactBase.sortFindings(group: TrackedDeclaration, body: JsonObject?): List<Finding> {
    val entries = body?.get("sortFields") as? JsonArray ?: return emptyList()
    // Each NAME is judged once, however often the list repeats it, and wherever it stands — beyond the
    // list's capacity too ([KERNEL-FINDINGS KF345]).
    val judged = mutableSetOf<String>()
    val refusedNames = mutableSetOf<String>()
    val perEntry = entries.withIndex().flatMap { (position, entry) ->
        // A non-string primitive is COERCED to the String member, as a rule's `errorCode` is, so `5` is the
        // name "5" and resolves like any other — measured, because reporting it as a reader refusal was
        // this family's one emission with no basis. Only a MISSING `name`, or an entry that is not an
        // object, is the refusal.
        val name = (entry as? JsonObject)?.get("name")?.let { (it as? JsonPrimitive)?.contentOrNull }
        when {
            name.isNullOrEmpty() -> listOf(unbuildable(group, "a `sortFields` entry here carries no `name`, or an empty one"))
            !judged.add(name) -> emptyList()
            // The name sentence is A12's metamodel reading of the list, which holds three entries; the
            // gates below read every entry ([KERNEL-FINDINGS KF589]).
            else -> orderingFindings(
                group, name, "sortFields[].name",
                "MVK_INVALID_SORT_FIELD", "MVK_SORT_FIELD_NO_VALUE_VALIDATION",
                statesName = position < SORT_CAPACITY,
            ).also { found -> if (found.any { it.code == RK_FIELD_NAME_ALPHABET }) refusedNames += name }
        }
    }
    return group.unsortableCarrier(entries) + group.repeatedSortField(entries, refusedNames) +
        group.sortCapacityFindings(entries) + group.unfilledSortEntries(entries) + perEntry
}

/**
 * One `MVK_SORT_INFO_MISSING` for a group whose list holds, within capacity, a name A12's metamodel reads as
 * unfilled: present, not empty, and blank as Java's `isBlank` reads it, so an em space counts and trimming would
 * not. The metamodel requires a sort entry's field, order and priority filled together; two such entries still
 * draw one. On 30.8.1 such a name left no model first (KERNEL-FINDINGS KF653).
 */
private fun TrackedDeclaration.unfilledSortEntries(entries: JsonArray): List<Finding> =
    entries.take(SORT_CAPACITY).firstNotNullOfOrNull { entry ->
        ((entry as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotEmpty() && it.isA12Blank() }
            ?.let {
                Finding(
                    FindingCategory.A12_CONSTRAINT,
                    FindingSeverity.ERROR,
                    "MVK_SORT_INFO_MISSING",
                    "a `sortFields` entry here names only blanks, which A12's metamodel reads as no field — a sort " +
                        "entry names its field and its order together",
                    path,
                )
            }
    }.let(::listOfNotNull)

/**
 * One finding per `sortFields` entry beyond the three A12's metamodel holds, which it refuses with no class:
 * "The index for group '/MVK/Model/Groups/SortInfo/SortFields' was specified with 4, but at most 3 is allowed."
 * The entries beyond it are still judged for their names ([KERNEL-FINDINGS KF345]).
 */
private fun TrackedDeclaration.sortCapacityFindings(entries: JsonArray): List<Finding> =
    (SORT_CAPACITY + 1..entries.size).map { position ->
        Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            "RK_SORT_LIST_ABOVE_CAPACITY",
            "`sortFields` entry $position is beyond the $SORT_CAPACITY a group may sort by, which A12 refuses " +
                "with no class: \"The index for group '/MVK/Model/Groups/SortInfo/SortFields' was specified " +
                "with $position, but at most $SORT_CAPACITY is allowed.\"",
            path,
        )
    }

/** The most `sortFields` entries A12's metamodel holds. */
private const val SORT_CAPACITY = 3

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
 * A12's own multiplicity, measured with the notification list at the two cells that could have made it
 * larger: a name appearing three times draws one, and two names each appearing twice draw one
 * ([KERNEL-FINDINGS KF345], `adapter.laws.CheckerDeclarationWithholdingAgreementTest`). So it is a verdict on
 * the LIST rather than on an entry, which is why it is emitted here and not inside the per-entry walk below.
 * It reads only the entries within the list's capacity: a name repeated at the fourth entry draws nothing.
 *
 * It rides beside whatever the entries themselves draw — the duplicate does not withhold the type class, and
 * the type class does not withhold it. A name the field-name sentence refuses is no value it compares, so a
 * refused name repeated draws no duplicate while a sound one repeated beside it still does ([KERNEL-FINDINGS
 * KF589]).
 */
private fun TrackedDeclaration.repeatedSortField(entries: JsonArray, refusedNames: Set<String>): List<Finding> {
    val names = entries.take(SORT_CAPACITY).mapNotNull { entry ->
        ((entry as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull
    }.filterNot { it in refusedNames }
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
 * Bind one ordering reference, then judge the group's own field of the short name it lands on (KF295).
 *
 * @param outsideCode the member's own code when the landed short name selects no field of the group's own.
 *     The two members do NOT share one — `MVK_INVALID_INDEX_FIELD` is named for the
 *     index and `MVK_INVALID_SORT_FIELD` for the sort, and each was measured on its own member rather than
 *     transferred from the other.
 * @param noValueValidationCode the member's own code for the third gate, likewise unshared.
 * @param statesName whether A12's metamodel reads this entry at all, and so states its name sentence.
 */
private fun TrackedFactBase.orderingFindings(
    group: TrackedDeclaration,
    written: String,
    member: String,
    outsideCode: String,
    noValueValidationCode: String,
    statesName: Boolean,
): List<Finding> {
    if (written.startsWith("/")) {
        return listOf(unbuildable(group, "`$member` is `$written`, which A12 refuses as an illegal relative path"))
    }
    val landed = resolvePositionally(group.path, written)
        ?: return listOf(unbuildable(group, "`$member` is `$written`, which climbs above the root group"))
    // An empty spelling binds nothing; any other one landing on a group is bound where a field is expected.
    if (written.isNotEmpty() && declarationAt(landed)?.declaredKind == DeclaredKind.GROUP) {
        return listOf(unbuildable(group, "`$member` is `$written`, which names the group `$landed` where a field belongs"))
    }
    // KF295's twin controls: the type gate reads the group's own field of this short name, not the landed one.
    val shortName = landed.substringAfterLast('/')
    // The short name A12 keeps is judged as a field name, in a sentence of its own that neither withholds the
    // later gates nor is withheld by them (KERNEL-FINDINGS KF589). The gates are A12's later check, which it
    // skips whenever an earlier one refused the model ([ORDERING_GATE_CODES]).
    // A blank name is an unfilled metamodel field, which no pattern law judges.
    val sentence = if (statesName && !shortName.isA12Blank() && shortName.refusedAsFieldName()) {
        Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            RK_FIELD_NAME_ALPHABET,
            "`$member` names `$landed`, and the name A12 keeps from it, `${shortName.javaTrimmed()}`, is " +
                FIELD_NAME_ALPHABET,
            group.path,
        )
    } else {
        null
    }
    return listOfNotNull(sentence, gateFinding(group, written, member, landed, shortName, outsideCode, noValueValidationCode))
}

private fun TrackedFactBase.gateFinding(
    group: TrackedDeclaration,
    written: String,
    member: String,
    landed: String,
    shortName: String,
    outsideCode: String,
    noValueValidationCode: String,
): Finding? {
    val own = fieldAt("${group.path}/$shortName")
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
 * Whether [field] is the one type A12 refuses as an ordering key: a string declared `noValueValidation`, the
 * declaration a type definition supplies included (KERNEL-FINDINGS KF602,
 * `adapter.laws.CheckerTypeDefinedRawFieldAgreementTest`).
 *
 * The predicate keys on the STRING body carrying the flag, which is `FieldUtils.isNoValueValidation`'s own
 * shape — it reads the flag only after `isString`, so the same flag on any other type is not this law. A
 * field whose `fieldType` A12 cannot BIND at all is not judged here either: that model never loads, and
 * saying so is [fieldTypeBindingFindings]'s business rather than a second opinion from this family. An
 * unbindable flag is not `true`, so it draws nothing here, and the string-parameter family reports it.
 */
private fun TrackedFactBase.noValueValidationFinding(field: TrackedDeclaration, member: String, code: String): Finding? {
    if (!waivesValueValidation(field)) {
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
 * Resolve [written] from [from] with no short-name fallback — `..` climbs, anything else descends — or `null`
 * when a `..` climbs above the root, which A12's reader cannot resolve.
 *
 * The condition resolver's model-wide fallback deliberately does not apply: a bare name here is looked up
 * among the group's own fields alone, so a field of the parent group is not reachable however the model
 * configures `fieldRefByShortNameAllowed`.
 */
private fun resolvePositionally(from: String, written: String): String? {
    val segments = from.split('/').filter { it.isNotEmpty() }.toMutableList()
    written.split('/').filter { it.isNotEmpty() }.forEach { step ->
        when (step) {
            ".." -> segments.removeLastOrNull() ?: return null
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

/**
 * The ordering gates, which A12's `NoMetaModelChecks` reaches only when every check it runs before them passed
 * ([KERNEL-FINDINGS KF589]): a model refused there is never asked about its ordering members, so these classes are
 * withheld whenever [refusesTheEntityWalk] holds of a reported finding. The duplicate verdict and the name sentence are read
 * elsewhere and are not withheld.
 */
internal val ORDERING_GATE_CODES = setOf(
    "MVK_INVALID_INDEX_FIELD", "MVK_INVALID_SORT_FIELD",
    "MVK_INDEX_FIELD_NO_VALUE_VALIDATION", "MVK_SORT_FIELD_NO_VALUE_VALIDATION",
)

/**
 * Whether [finding] is one of the classes `NoMetaModelChecks.check` reports before its ordering gates: the entity walk (paths,
 * repetition layers, a field's own message, an enumeration's characters) and the model-wide settings it reads
 * beside it. Measured on the path and repetition-layer carriers; the others share the one `return false`.
 */
internal fun TrackedFactBase.refusesTheEntityWalk(finding: Finding): Boolean = finding.justifiesRejection && (
    finding.code in PRE_ORDERING_CODES ||
        // A rule's message carries the same codes, and A12 judges it later, so only a FIELD's own message counts.
        finding.code in FIELD_MESSAGE_CODES && finding.at?.let { declarationAt(it)?.declaredKind } == DeclaredKind.FIELD
    )

private val PRE_ORDERING_CODES = setOf(
    "MVK_PATH_INVALID", "MVK_PATHS_INCONSISTENT", "MVK_FIELD_WITH_CONTAINED_ENTITIES", "MVK_PARENT_ENTITY_MISSING",
    "MVK_MAX_VALUES_INVALID", "MVK_MAX_VALUES_INCONSISTENT", "MVK_FIELD_NO_PARENT",
    "MVK_ENUM_VALUE_WITH_INVALID_CHARACTER", "MVK_DUPLICATE_DYNAMIC_ENUM",
    "MVK_NO_PROJECT_LANGUAGE_SETTINGS", "MVK_INVALID_NAME_4_GENERATED_CODE", "MVK_INVALID_PACKAGE_NAME_4_GENERATED_CODE",
    "MVK_INVALID_TIME_ZONE",
)

private val FIELD_MESSAGE_CODES =
    setOf("WRONG_ENCODING", "INVALID_LINEBREAK", "INVALID_CHARACTERS", "WRONG_NUMBER_OF_DOLLARS", "INVALID_PARAMETER")
