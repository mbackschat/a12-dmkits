package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.LexicalFailureKind
import io.github.mbackschat.a12.dm.consistency.syntax.lexicalFailure
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
        unlexableTargetFinding(declared)?.let { return@mapNotNull it }
        val path = (declared.target as? Reference.Resolved)?.path ?: return@mapNotNull null
        val group = path.substringBeforeLast('/', missingDelimiterValue = "")
        if (indexFieldOf(group) == path.substringAfterLast('/')) {
            Finding(
                FindingCategory.A12_CONSTRAINT,
                FindingSeverity.ERROR,
                "MVK_ERROR_INDEX_AS_CALCULATED_FIELD",
                "in `computedFieldRelPath`: `$path` is the field `$group` declares as its `indexFieldName`, " +
                    "which identifies a row so two repetitions can be read in lockstep — A12 refuses to let a " +
                    "computation decide it. A `sortFields` entry carries no such restriction",
                declared.declaredAt,
            )
        } else {
            dottedTargetFinding(path, declared)
        }
    }

/**
 * …and, ahead of both, that A12's condition lexer can read the computed field's relative path at all.
 *
 * A12 lexes `computedFieldRelPath` as it lexes a condition operand, so a name carrying a character no terminal
 * begins — a no-break or figure space, an Arabic-Indic or full-width digit, `é` — draws `MVK_LEXER_STANDARD_ERROR` at
 * the computation, and that refusal withholds the index and dot gates and the operation's own class
 * ([KERNEL-FINDINGS KF586], `adapter.laws.CheckerComputedPathLexerAgreementTest`). A rule's `errorEntityRelPath` is
 * measured NOT lexed, so this belongs to the computed target alone.
 */
private fun unlexableTargetFinding(declared: DeclaredReference): Finding? =
    // Only the measured class: a quote A12 cannot pair was never put to it in this slot.
    lexicalFailure(declared.written)?.takeIf { it.kind == LexicalFailureKind.ILLEGAL_CHARACTER }?.let { failure ->
        Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            "MVK_LEXER_STANDARD_ERROR",
            "in `computedFieldRelPath`: `${declared.written}` carries `${failure.text}`, which A12's condition " +
                "lexer has no terminal for — A12 reads the computed field's path as it reads a condition operand",
            declared.declaredAt,
        )
    }

/**
 * …and, A12's next gate in the same order, that the computed field's PATH carries no dot or colon.
 *
 * A12 tests the field's full name, so a dot or colon on ANY segment refuses it: the field's own name, which its
 * name gate refuses too, or a group above it, which its path gate refuses ([KERNEL-FINDINGS KF583],
 * `adapter.laws.CheckerComputedPathAgreementTest`). That second carrier is why the class is reachable at all,
 * beside another refusal. The index gate comes first and pre-empts it, and a refused condition pre-empts both,
 * which the stage this family is asked in already gives.
 */
private fun dottedTargetFinding(path: String, declared: DeclaredReference): Finding? =
    path.takeIf { '.' in it || ':' in it }?.let {
        Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            "MVK_ERROR_INVALID_FIELDNAME",
            "in `computedFieldRelPath`: the computed field's path `$path` carries a dot or a colon, and A12 " +
                "refuses a computed field whose path does, whichever segment carries it",
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
private fun TrackedFactBase.computedTargets(): List<DeclaredReference> = declaredReferences()
    .filter { it.role == ReferenceRole.COMPUTED_TARGET }
