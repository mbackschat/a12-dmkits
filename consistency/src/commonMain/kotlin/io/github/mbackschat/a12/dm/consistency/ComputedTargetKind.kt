package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * That a computation computes into a FIELD.
 *
 * A rule's error target and a computation's computed field are both a relative path to a declared element,
 * they are read by the same reader and they resolve the same way — so an implementation naturally judges them
 * alike, and A12 does not. A rule may report on a group, fixed or repeatable; a computation may not compute
 * into one ([KERNEL-FINDINGS KF329], `rulekit.validate.laws.ComputedFieldTargetKindLawsTest`). Both rows are
 * measured, because either alone licenses the wrong rule for the other slot.
 *
 * **It is reported as `CK_MODEL_NOT_BUILDABLE` rather than under A12's own key, and the measurement is what
 * decides that.** `MVK_GROUP_AS_CALCULATED_FIELD` exists in A12's vocabulary, but the Kernel's BUILDER refuses
 * a group-valued computed field before any consistency check runs, so that key is never what a consumer would
 * have seen — the same tier, and for the same reason, as a computed field that resolves to nothing. Reporting
 * the `MVK_*` key here would claim a verdict A12 never reaches.
 *
 * **The shape exists only in stored DM-JSON**, which is why the kits carry no such family: their computed
 * field slot is a typed field handle, so `MVK_GROUP_AS_CALCULATED_FIELD` is `erased` there. The member this
 * reads is an ordinary string, and a group path is one edit away.
 */
internal fun TrackedFactBase.computedTargetKindFindings(): List<Finding> = computedTargets()
    .mapNotNull { declared ->
        val path = (declared.target as? Reference.Resolved)?.path ?: return@mapNotNull null
        val element = declarationAt(path) ?: return@mapNotNull null
        if (element.declaredKind != DeclaredKind.GROUP) {
            return@mapNotNull null
        }
        Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            CK_MODEL_NOT_BUILDABLE,
            "in `computedFieldRelPath`: `$path` is a group, and a computation writes its result into a " +
                "FIELD — the Kernel cannot build a model whose computed field names a group",
            declared.declaredAt,
        )
    }

/**
 * …and that it does not compute into the field a repeatable group is INDEXED by.
 *
 * A12 answers this one with a key of its own — `MVK_ERROR_INDEX_AS_CALCULATED_FIELD` — rather than refusing
 * to build, so it is a verdict a consumer receives and this family reports it verbatim
 * ([KERNEL-FINDINGS KF330], `rulekit.validate.laws.ComputedFieldTargetKindLawsTest`). The index field
 * identifies a row so two lists can be iterated in lockstep; a computed one would be decided by the very
 * evaluation the identity has to survive.
 *
 * **The SORT field is what makes this about the index and not about "a field the group names".** Both are
 * direct children named by a group member, read by the same code and resolved the same way, and a
 * computation writes into a sort field freely — measured, because a family reading `indexFieldName` and
 * `sortFields[].name` through one helper is one edit away from judging them alike.
 *
 * **The law follows the TARGET, not the computation's own group.** A computation declared outside the
 * repeatable group draws the same class, so keying on the resolved field alone is correct — measured rather
 * than transferred from the inside-the-group row, which is where this family would otherwise have inherited
 * a scope it never tested.
 */
internal fun TrackedFactBase.computedTargetIndexFieldFindings(): List<Finding> = computedTargets()
    .mapNotNull { declared ->
        val path = (declared.target as? Reference.Resolved)?.path ?: return@mapNotNull null
        val group = path.substringBeforeLast('/', missingDelimiterValue = "")
        if (indexFieldOf(group) != path.substringAfterLast('/')) {
            return@mapNotNull null
        }
        Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            "MVK_ERROR_INDEX_AS_CALCULATED_FIELD",
            "in `computedFieldRelPath`: `$path` is the field `$group` declares as its `indexFieldName`, " +
                "which identifies a row so two repetitions can be read in lockstep — A12 refuses to let a " +
                "computation decide it. A `sortFields` entry carries no such restriction",
            declared.declaredAt,
        )
    }

/** The short name [group] declares as its index field, when it declares one. */
private fun TrackedFactBase.indexFieldOf(group: String): String? = declarationAt(group)
    ?.takeIf { it.declaredKind == DeclaredKind.GROUP }
    ?.body
    ?.let { (it["indexFieldName"] as? JsonPrimitive)?.contentOrNull }

/**
 * Every computation's computed-field reference, charged per declaration rather than model-wide.
 *
 * Both families above want the same subset, and going through the cursor is what keeps the charge
 * per-declaration: the whole-model `declaredReferences()` would make each of them depend on every rule's and
 * computation's body ([TrackedDeclaration.declaredReference]).
 */
private fun TrackedFactBase.computedTargets(): List<DeclaredReference> = declarations()
    .mapNotNull { it.declaredReference() }
    .filter { it.role == ReferenceRole.COMPUTED_TARGET }
