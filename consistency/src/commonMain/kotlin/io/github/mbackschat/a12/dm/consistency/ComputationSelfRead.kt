package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CallOperand
import io.github.mbackschat.a12.dm.consistency.syntax.OPERATOR_SPELLINGS
import io.github.mbackschat.a12.dm.consistency.syntax.OperatorId
import io.github.mbackschat.a12.dm.consistency.syntax.callsIn
import io.github.mbackschat.a12.dm.consistency.syntax.categoryAccessesIn
import io.github.mbackschat.a12.dm.consistency.syntax.referenceSpansIn
import io.github.mbackschat.a12.dm.consistency.syntax.semanticIndexUsesIn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
 * **Three shapes are carved out of that walk and none is a rule about group predicates**, which is what
 * the measured `GroupNotFilled` row over the same group establishes: `GroupFilled` — A12's single-entity
 * `AllGroupsFilled`, its "context specified" escape — a `CurrentRepetition` operand, which the walk does
 * not traverse at all, and `AtLeastOneGroupFilled` over a sole root group ([KERNEL-FINDINGS KF521]). Missing
 * any would refuse a model A12 admits.
 *
 * **A PROJECTED self-read is A12's other class and is reported as such.** An enum category or a semantic
 * index over the computed field draws `MVK_ERROR_SEMANTIC_INDEX_OR_CATEGORY_FOR_ERRORFIELD`, and it
 * pre-empts across the whole computation rather than per member ([KERNEL-FINDINGS KF182]) — so one projected
 * read anywhere decides the class everywhere in it, which is why the search runs over every member before
 * the plain one does.
 *
 * **On a NUMBER target, only where the scale gate is known to stand aside.** The implicit target-scale
 * comparison is decided AHEAD of both classes ([KERNEL-FINDINGS KF181], [KERNEL-FINDINGS KF182]), so the first
 * rejection depends on the operation's DERIVED scale. Two cases settle it: the model suppresses
 * `MVK_INVALID_COMPARE_DEC_PLACES`, measured separately for each class; or the operation reader types every
 * operation's lowered comparison and it passes the gate, which is the comparison family's own judgement read
 * back. A number target whose operations the reader cannot type is under-reported, deliberately.
 *
 * **An unguarded alternative's own reads go unjudged once there are several alternatives.** From two
 * alternatives on, A12 answers an alternative without a precondition with `MVK_ERROR_EMPTY_PRECONDITION` and
 * never walks its operation for this class, whether or not another alternative is guarded; a lone one is
 * walked ([KERNEL-FINDINGS KF181], `adapter.laws.ComputationGateOrderAgreementTest` `B04`, `S07`, `S09`, `S10`).
 * Only the plain walk is narrowed: a projected self-read in such an alternative is still reported, and it
 * pre-empts the precondition class too (measured 2026-10-02 on the routing model's category read).
 *
 * **One finding per judged read, in the order A12 walks them.** `[Total] + [Total]` is two, a self-reading
 * precondition beside a self-reading operation two. The common precondition's reads are reported once at
 * the first judged alternative, or not at all when none is judged (SPEC-2026-10-02-07); the projected class
 * alone answers once for the computation ([KERNEL-FINDINGS KF181], `adapter.laws.SelfReadMultiplicityAgreementTest`).
 */
internal fun TrackedFactBase.computationSelfReadFindings(): List<Finding> =
    declarations().flatMap { computationSelfReadContributions(it).map { contribution -> contribution.finding } }

internal fun TrackedFactBase.computationSelfReadContributions(element: TrackedDeclaration): List<DeclarationFinding> =
    when (val reads = selfReadsOf(element)) {
        null -> emptyList()
        is SelfReads.Projected -> listOf(DeclarationFinding(element.key, DeclarationStage.CALCULATION_STRUCTURE,
            projectedFinding(element, reads.member, reads.projection)))
        is SelfReads.Plain -> reads.reads.map {
            DeclarationFinding(element.key, DeclarationStage.CALCULATION_ALTERNATIVES,
                selfReadFinding(element, reads.target, it.member, it.written), it.walkPosition)
        }
    }

/**
 * The operations whose own reads draw the plain self-read class. A12 returns on that class before any other
 * gate of the same operation, so a family judging an operation stands down on these ([KERNEL-FINDINGS KF616]).
 */
internal fun TrackedFactBase.operationsReadingTheirTarget(element: TrackedDeclaration): Set<String> =
    (selfReadsOf(element) as? SelfReads.Plain)?.reads.orEmpty()
        .map { it.member }.filter(::isOperationMember).toSet()

/** What the self-read walk finds in one computation, or null where it does not judge the computation. */
private sealed interface SelfReads {
    /** A projected read, which answers for the whole computation (KF182). */
    data class Projected(val member: String, val projection: String) : SelfReads

    /** The plain reads A12 judges, in the members it walks. */
    data class Plain(val target: String, val reads: List<PlainSelfRead>) : SelfReads
}

private data class PlainSelfRead(val member: String, val written: String, val walkPosition: Int)

private fun TrackedFactBase.selfReadsOf(element: TrackedDeclaration): SelfReads? {
    if (element.declaredKind != DeclaredKind.COMPUTATION) return null
    val operators = OPERATOR_SPELLINGS[conditionLanguage] ?: return null
    val target = computedTargetOf(element) ?: return null
    val body = element.body ?: return null
    val texts = conditionTexts(element.kind, body)
    val group = element.path.substringBeforeLast('/')
    // The scale gate answers ahead of both classes, but it can only take a verdict away, so it is asked once a
    // read is found rather than of every computation: its probe is a whole operation analysis.
    texts.firstNotNullOfOrNull { (member, text) ->
        projectedSelfReadIn(text, target, group, element, operators[OperatorId.SEMANTIC_INDEX])
            ?.let { SelfReads.Projected(member, it) }
    }?.let { return it.takeIf { scaleGateStandsAside(target, element, body, texts) } }
    val unwalked = unguardedAmongSeveralAlternatives(body)
    val walked = texts.filter { (member, _) -> member !in unwalked }
    // A12 appends the common precondition to the first judged alternative's own precondition parts.
    val commonPosition = walked.firstNotNullOfOrNull { (member, _) -> alternativeIndexOf(member) }
        ?.let { alternativeWalkPosition(it, AlternativeStep.PRECONDITION) }
    val reads = walked.filter { (member, _) -> member != "commonPrecondition" || commonPosition != null }
        .flatMap { (member, text) ->
            val exempt = exemptOperandsIn(text, member, operators[OperatorId.GROUP_FILLED],
                operators[OperatorId.CURRENT_REPETITION], operators[OperatorId.AT_LEAST_ONE_GROUP_FILLED])
            referenceSpansIn(text, conditionLanguage)
                .filter { span -> exempt.none { it.at <= span.start && span.end <= it.end } }
                .map { it.written }
                .filter { readsTheTarget(it, target, group, element) }
                .map { PlainSelfRead(member, it,
                    if (member == "commonPrecondition") checkNotNull(commonPosition) else walkPositionOf(member)) }
        }
    return if (reads.isNotEmpty() && scaleGateStandsAside(target, element, body, texts)) SelfReads.Plain(target, reads) else null
}

/** The operation members of the unguarded alternatives, when the computation has more than one alternative. */
internal fun unguardedAmongSeveralAlternatives(body: JsonObject): Set<String> {
    val alternatives = (body["computationAlternatives"] as? JsonArray).orEmpty()
    if (alternatives.size < 2) return emptySet()
    return alternatives.withIndex()
        .filter { (_, alternative) -> (alternative as? JsonObject).lacksAPrecondition() }
        .map { "computationAlternatives[${it.index}].operation" }.toSet()
}

/**
 * Whether the implicit target-scale comparison cannot answer ahead of this class on [target].
 *
 * The declared type is read rather than inferred: a target this reader cannot type is left to the gate, the
 * same direction every other silence here takes. On a NUMBER target the gate is out of the way when the model
 * suppresses it, or when every operation's lowered comparison is typed and passes it — A12 parses every
 * alternative before its calculation module walks any, so one undecided operation leaves the whole
 * computation to the gate ([KERNEL-FINDINGS KF181]).
 */
private fun TrackedFactBase.scaleGateStandsAside(
    target: String,
    element: TrackedDeclaration,
    body: JsonObject,
    texts: List<Pair<String, String>>,
): Boolean {
    val field = declarationAt(target, DeclaredKind.FIELD)
        ?: return false
    val declaredType = effectiveFieldType(field) ?: return false
    if (declaredType != FieldType.NUMBER) {
        return true
    }
    val suppressed = (body["errorCodesToSuppress"] as? JsonArray).orEmpty()
    if (suppressed.any { (it as? JsonPrimitive)?.content == SUPPRESSIBLE }) return true
    val operations = texts.map { it.first }.filter(::isOperationMember)
    return operations.isNotEmpty() && operationsPassingTheScaleGate(element).containsAll(operations)
}

/** Whether [written] names the computed field or a group that contains it. */
private fun TrackedFactBase.readsTheTarget(
    written: String,
    target: String,
    declaringGroup: String,
    element: TrackedDeclaration,
): Boolean {
    val path = resolvedPathOf(written, declaringGroup, element) ?: return false
    return target == path || target.startsWith("$path/")
}

/**
 * The PROJECTED read of the computed field [text] carries, as authored, or null when it carries none.
 *
 * Both halves of the class are asked, because A12 matches the computed field against a semantic index's KEY
 * as well as against its indexed value — a computation whose operation reads another field keyed by its own
 * target is this class even though the target is not the field being read ([KERNEL-FINDINGS KF182]).
 *
 * Either half must name the computed field ITSELF. A key selecting the group that holds it projects the group,
 * which A12 admits (`GroupFilled(Lines For "a")`, [KERNEL-FINDINGS KF605]), and no measured row shows a keyed
 * group drawing this class.
 */
private fun TrackedFactBase.projectedSelfReadIn(
    text: String,
    target: String,
    declaringGroup: String,
    element: TrackedDeclaration,
    indexKeyword: String?,
): String? {
    categoryAccessesIn(text, conditionLanguage)
        .firstOrNull { (path, _) -> resolvedPathOf(path, declaringGroup, element) == target }
        ?.let { (path, category) -> return "$path -> $category" }
    val keyword = indexKeyword ?: return null
    return semanticIndexUsesIn(text, conditionLanguage, keyword)
        .firstOrNull { use ->
            listOfNotNull(use.valuePath, use.keyPath)
                .any { resolvedPathOf(it, declaringGroup, element) == target }
        }
        ?.let { use -> "${use.valuePath} $keyword ${use.keyPath ?: use.keyLiteral?.let { key -> "\"$key\"" }}" }
}

/**
 * The operands A12's walk steps over — its carved exemptions, read as authored.
 *
 * The exemption is the operand's own, so it is returned by position: the same group spelled by another
 * operand of the same text is still a read (`rulekit.validate.laws.ComputationSelfReadScopeLawsTest
 * .anExemptOperandExemptsOnlyItself`).
 *
 * The kernel exempts its `AllGroupsFilled` only at arity ONE, and no arity branch is needed to honour that:
 * this catalog spells the single-entity form as its own operator, and a second operand draws
 * `MVK_PARAMSIZE_INVALID1` from a gate that answers ahead of the calculation walk — measured 2026-09-15 in
 * the laws twin, after a mutant dropping the branch survived and the row written to kill it showed the
 * branch could not be reached.
 *
 * `AtLeastOneGroupFilled` over a sole unstarred ROOT group is the list family's one exemption, whatever lies
 * below the root; over a sole non-root group, beside a second group or starred, it reads the field like any
 * other group operand (a12-kernel-lean SPEC-2026-10-03-05, `rulekit.validate.laws.GroupListSelfReadLawsTest`).
 */
private fun TrackedFactBase.exemptOperandsIn(
    text: String,
    member: String,
    contextEscape: String?,
    repetitionOf: String?,
    anyGroupFilled: String?,
): List<CallOperand> = callsIn(text, conditionLanguage, syntaxEntryOf(member)).flatMap { call ->
    when (call.operator) {
        contextEscape, repetitionOf -> call.operands
        anyGroupFilled -> listOfNotNull(call.operands.singleOrNull()?.takeIf { namesARootGroup(it.path ?: it.base) })
        else -> emptyList()
    }
}

/** Whether an operand spelling names a root group itself: absolute, one segment, no repetition marker. */
private fun namesARootGroup(written: String?): Boolean =
    written != null && written.startsWith("/") && '*' !in written && written.trim('/').split('/').size == 1

/**
 * @param projection the authored projection, which is what the repair has to remove — dropping only the
 *     reference's decoration leaves the plain self-read behind, so the message names the operand instead.
 */
private fun projectedFinding(element: TrackedDeclaration, member: String, projection: String): Finding = Finding(
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
    element: TrackedDeclaration,
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
