package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.callsIn
import io.github.mbackschat.a12.dm.consistency.syntax.categoryAccessesIn
import io.github.mbackschat.a12.dm.consistency.syntax.referencesIn
import io.github.mbackschat.a12.dm.consistency.syntax.semanticIndexUsesIn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * A computation that reads the field it writes — including through the GROUP that field sits in.
 *
 * A12 walks every parsed member of the generated calculation rule and refuses a reference whose target
 * CONTAINS the computed field, not merely one equal to it: `feldReferenziertOhneKontextAngegeben` asks
 * `isDescendantOrEqual(calculatedField, reference)`, so the field's own group — and the root group above it
 * — are reads of the field ([KERNEL-FINDINGS KF416],
 * `rulekit.validate.laws.ComputationSelfReadScopeLawsTest`). A family keyed on path equality would admit
 * every one of those.
 *
 * **Two shapes are carved out of that walk and neither is a rule about group predicates**, which is what
 * the measured `GroupNotFilled` row over the same group establishes: `GroupFilled` — A12's single-entity
 * `AllGroupsFilled`, its "context specified" escape — and a `CurrentRepetition` operand, which the walk does
 * not traverse at all. Missing either would refuse a model A12 admits.
 *
 * **A PROJECTED self-read is A12's other class and is reported as such.** An enum category or a semantic
 * index over the computed field draws `MVK_ERROR_SEMANTIC_INDEX_OR_CATEGORY_FOR_ERRORFIELD`, and it
 * pre-empts across the whole computation rather than per member ([KERNEL-FINDINGS KF182]) — so one projected
 * read anywhere decides the class everywhere in it, which is why the search runs over every member before
 * the plain one does.
 *
 * **Silent on a NUMBER target unless the model suppresses the scale gate.** The implicit target-scale
 * comparison is decided AHEAD of both classes ([KERNEL-FINDINGS KF181], [KERNEL-FINDINGS KF182]), so on a
 * number target the first rejection depends on the operation's DERIVED scale, which no family here infers.
 * The safe number case is the model's own escape hatch: with `MVK_INVALID_COMPARE_DEC_PLACES` suppressed the
 * gate cannot answer and A12 reports the self-read class whatever the scale — measured separately for each
 * of the two classes rather than transferred from one to the other. Every other number target is
 * under-reported, deliberately.
 *
 * **One finding per computation, not per offending reference.** A12 answers a computation with one class
 * however many of its reads are self-reads, measured across both operand orders of a two-entry list.
 */
fun PreparedModel.computationSelfReadFindings(): List<Finding> {
    val operators = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val contextEscape = operators[GROUP_FILLED]
    val repetitionOf = operators[CURRENT_REPETITION]
    val indexKeyword = operators[SEMANTIC_INDEX]
    return elements
        .filter { it.declaredKind == DeclaredKind.COMPUTATION }
        .mapNotNull { element ->
            val target = computedTargetOf(element) ?: return@mapNotNull null
            if (!scaleGateStandsAside(target, element)) {
                return@mapNotNull null
            }
            val texts = conditionTexts(element.kind, element.body ?: return@mapNotNull null)
            val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
            texts.firstNotNullOfOrNull { (member, text) ->
                projectedSelfReadIn(text, target, group, element, indexKeyword)
                    ?.let { projectedFinding(element, member, it) }
            } ?: texts.firstNotNullOfOrNull { (member, text) ->
                val exempt = exemptOperandsIn(text, member, contextEscape, repetitionOf)
                referencesIn(text, conditionLanguage)
                    .filter { it !in exempt }
                    .firstOrNull { readsTheTarget(it, target, group, element) }
                    ?.let { selfReadFinding(element, target, member, it) }
            }
        }
}

private const val GROUP_FILLED = "GroupFilled"

private const val CURRENT_REPETITION = "CurrentRepetition"

private const val SEMANTIC_INDEX = "SemanticIndex"

/** The absolute path this computation writes, absent when the member does not resolve. */
private fun PreparedModel.computedTargetOf(element: DeclaredElement): String? = declaredReferences()
    .firstOrNull { it.role == ReferenceRole.COMPUTED_TARGET && it.declaredAt == element.path }
    ?.let { (it.target as? Reference.Resolved)?.path }

/**
 * Whether the implicit target-scale comparison cannot answer ahead of this class on [target].
 *
 * The declared type is read rather than inferred: a target this reader cannot type is left to the gate, the
 * same direction every other silence here takes.
 */
private fun PreparedModel.scaleGateStandsAside(target: String, element: DeclaredElement): Boolean {
    val field = elements.firstOrNull { it.path == target && it.declaredKind == DeclaredKind.FIELD }
        ?: return false
    val declaredType = field.declaredFieldType() ?: return false
    if (declaredType != NUMBER_TYPE) {
        return true
    }
    val suppressed = element.body?.get("errorCodesToSuppress") as? JsonArray ?: return false
    return suppressed.any { (it as? JsonPrimitive)?.content == SUPPRESSIBLE }
}

private const val NUMBER_TYPE = "NumberType"

/** Whether [written] names the computed field or a group that contains it. */
private fun PreparedModel.readsTheTarget(
    written: String,
    target: String,
    declaringGroup: String,
    element: DeclaredElement,
): Boolean {
    val resolved = resolveEntity(declaringGroup, written.replace("*", ""), element.origin)
    val path = (resolved as? Reference.Resolved)?.path ?: return false
    return target == path || target.startsWith("$path/")
}

/**
 * The PROJECTED read of the computed field [text] carries, as authored, or null when it carries none.
 *
 * Both halves of the class are asked, because A12 matches the computed field against a semantic index's KEY
 * as well as against its indexed value — a computation whose operation reads another field keyed by its own
 * target is this class even though the target is not the field being read ([KERNEL-FINDINGS KF182]).
 */
private fun PreparedModel.projectedSelfReadIn(
    text: String,
    target: String,
    declaringGroup: String,
    element: DeclaredElement,
    indexKeyword: String?,
): String? {
    categoryAccessesIn(text, conditionLanguage)
        .firstOrNull { (path, _) -> readsTheTarget(path, target, declaringGroup, element) }
        ?.let { (path, category) -> return "$path -> $category" }
    val keyword = indexKeyword ?: return null
    return semanticIndexUsesIn(text, conditionLanguage, keyword)
        .firstOrNull { use ->
            listOfNotNull(use.valuePath, use.keyPath)
                .any { readsTheTarget(it, target, declaringGroup, element) }
        }
        ?.let { use -> "${use.valuePath} $keyword ${use.keyPath ?: use.keyLiteral?.let { key -> "\"$key\"" }}" }
}

/**
 * The operand spellings A12's walk steps over — its two carved exemptions, read as authored.
 *
 * The kernel exempts its `AllGroupsFilled` only at arity ONE, and no arity branch is needed to honour that:
 * this catalog spells the single-entity form as its own operator, and a second operand draws
 * `MVK_PARAMSIZE_INVALID1` from a gate that answers ahead of the calculation walk — measured 2026-09-15 in
 * the laws twin, after a mutant dropping the branch survived and the row written to kill it showed the
 * branch could not be reached.
 */
private fun PreparedModel.exemptOperandsIn(
    text: String,
    member: String,
    contextEscape: String?,
    repetitionOf: String?,
): Set<String> {
    val exempt = mutableSetOf<String>()
    callsIn(text, conditionLanguage, syntaxEntryOf(member)).forEach { call ->
        val operands = call.operands.mapNotNull { it.path ?: it.base }
        when (call.operator) {
            contextEscape -> exempt += operands
            repetitionOf -> exempt += operands
            else -> Unit
        }
    }
    return exempt
}

/**
 * @param projection the authored projection, which is what the repair has to remove — dropping only the
 *     reference's decoration leaves the plain self-read behind, so the message names the operand instead.
 */
private fun projectedFinding(element: DeclaredElement, member: String, projection: String): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    "MVK_ERROR_SEMANTIC_INDEX_OR_CATEGORY_FOR_ERRORFIELD",
    "in `$member`: this computation reads its own computed field through a projection, `$projection` — a " +
        "computation may read neither the field it writes nor a projection of it. Compute from a DIFFERENT " +
        "field's projection, or read the same projected value into another target; removing only the " +
        "projection leaves the plain self-read behind",
    element.path,
)

private fun selfReadFinding(
    element: DeclaredElement,
    target: String,
    member: String,
    written: String,
): Finding {
    val itself = written.trimEnd('*').substringAfterLast('/') == target.substringAfterLast('/')
    val because = if (itself) {
        "which is the field it writes"
    } else {
        "which CONTAINS `$target`, the field it writes — naming a group is naming everything under it"
    }
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD",
        "in `$member`: this computation reads `$written`, $because, so A12 cannot order the computation " +
            "against itself. Read the fields the value is computed FROM, and guard on them — or, to ask " +
            "only whether the target's own repetition exists, use `GroupFilled`, which A12 exempts",
        element.path,
    )
}
