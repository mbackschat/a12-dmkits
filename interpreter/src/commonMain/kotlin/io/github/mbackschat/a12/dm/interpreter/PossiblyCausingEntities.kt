package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.ast.Tri
import io.github.mbackschat.a12.dm.interpreter.eval.EvalContext
import io.github.mbackschat.a12.dm.interpreter.eval.Interpreter
import io.github.mbackschat.a12.dm.interpreter.eval.PredicateOp
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef

/**
 * One entity a fired rule may name as possibly causing it, and the two questions A12 asks of it at the firing row:
 * is it given — at least one of its instances filled — and is it not given — not every one of them filled, an
 * aggregated level counting up to its capacity (`mindestensEineEntityAngegeben`, `nichtAlleOderKeineEntityAngegeben`,
 * source-read). Both are the fill quantifiers' own semantics over the entity alone, so they are asked through those
 * quantifiers: [given] and [notGiven] build the question over an operand. An aggregated ROOT group is always not
 * given (KERNEL-FINDINGS KF683).
 */
internal class CausingEntity(
    val reference: MessageReference,
    val group: Boolean,
    private val declaredInstances: Int,
    /** A field of a custom type, whose registered validator the presence question may find unavailable. */
    val customTyped: Boolean,
) {
    private val aggregatedRoot = group && reference is MessageReference.Wildcard && pathParts(reference.path).size == 1

    /**
     * Whether [instances] rows of an aggregated group leave some of its declared ones absent, which A12 counts as not
     * given: its question walks every declared repetition (`EntityIterator`), where the group-list quantifiers walk
     * the instantiated rows (KERNEL-FINDINGS KF683, `adapter.laws.SumOfTermsRuntimeLawsTest`).
     */
    fun leavesDeclaredRowsAbsent(instances: Int): Boolean = group && instances < declaredInstances

    fun given(operand: Expr): Cond.Pred =
        quantifier(if (group) PredicateOp.AT_LEAST_ONE_GROUP_FILLED else PredicateOp.AT_LEAST_ONE_FIELD_FILLED, operand)

    /** Null when the answer does not depend on the Document: an aggregated root group is never all given. */
    fun notGiven(operand: Expr): Cond.Pred? = if (aggregatedRoot) null
        else quantifier(if (group) PredicateOp.NOT_ALL_GROUPS_FILLED else PredicateOp.NOT_ALL_FIELDS_FILLED, operand)

    private fun quantifier(op: PredicateOp, operand: Expr) = Cond.Pred(op.kernelName, listOf(operand))
}

/**
 * How a message's row answers for one entity: whether it is given, and whether it is not given. [EntityPresence]
 * is supplied by the engine, which can place a selected entity at the row its key selects.
 */
internal interface EntityPresence {
    fun given(entity: CausingEntity): Boolean
    fun notGiven(entity: CausingEntity): Boolean
    fun cellFilled(pointer: A12PartiallyKnownMultiPointer): Boolean
}

/**
 * A rule's candidates, fixed per rule: [given] are named when given, [omission] when not given. Without a filter they
 * are every entity the condition references and the subset that can cause an omission; a filter replaces each with
 * the referenced entities its own list names (KERNEL-FINDINGS KF683).
 */
internal class CausingEntityCandidates(val given: List<CausingEntity>, val omission: List<CausingEntity>)

/** The candidates of [rule] from its [referenced] entities and the [omission] subset, both prepared by the caller. */
internal fun causingEntityCandidates(
    rule: RuleDef,
    model: ModelDef,
    referenced: List<MessageReference>,
    omission: List<MessageReference>,
): CausingEntityCandidates {
    val filter = rule.possibleCauses
    val given = if (filter == null) referenced else named(referenced, rule.group, filter.ifFilled)
    val omitted = if (filter == null) omission else named(referenced, rule.group, filter.ifNotFilled)
    fun entity(reference: MessageReference) =
        CausingEntity(reference, reference.path !in model.kinds, declaredInstances(reference, model),
            reference.path in model.customTypes)
    return CausingEntityCandidates(given.map(::entity), omitted.map(::entity))
}

/**
 * A12's groups for a message of [type] fired at one row: an OMISSION names its not-given candidates, then the given
 * ones not already named; a VALUE error names both in one group. [presence] answers for an entity at that row, and
 * [pointerOf] places it there, an aggregated level as the wildcard. [duplicateKeys] are the key cells of the
 * `RepetitionNotUnique` rows the message reports, which A12 names cell by cell, an empty key as not given.
 */
internal fun possiblyCausingEntityGroups(
    candidates: CausingEntityCandidates,
    type: MsgType,
    presence: EntityPresence,
    pointerOf: (MessageReference) -> A12PartiallyKnownMultiPointer,
    duplicateKeys: List<A12PartiallyKnownMultiPointer>,
): List<Set<A12PartiallyKnownMultiPointer>> {
    val empty = candidates.omission.filter(presence::notGiven).mapTo(LinkedHashSet()) { pointerOf(it.reference) }
    val given = candidates.given.filter(presence::given).mapTo(LinkedHashSet()) { pointerOf(it.reference) }
    duplicateKeys.forEach { key -> if (presence.cellFilled(key)) given += key else empty += key }
    return when (type) {
        MsgType.OMISSION -> listOf(empty, given - empty)
        MsgType.VALUE -> listOf(given + empty)
    }
}

/**
 * [EntityPresence] at one fired row. An entity named in the row's own terms is asked there; a selected one is asked
 * at the row its key selects, a level its pointer leaves open being aggregated, and a key that selects no row leaves
 * it with no instance — not given (KERNEL-FINDINGS KF683).
 */
internal class RowEntityPresence(
    private val pass: ValidationPass,
    private val ctx: EvalContext,
    private val interpreter: Interpreter,
    private val pointers: MessagePointers,
    private val errorPath: String,
) : EntityPresence {

    override fun given(entity: CausingEntity): Boolean = answer(entity, notGiven = false)

    override fun notGiven(entity: CausingEntity): Boolean = answer(entity, notGiven = true)

    // A registered custom field type that is unavailable leaves its cell undecided rather than unmessaged: the
    // outage is reported on the cell already, and the message the rule fired is A12's to keep
    // (`CustomFieldTypeMultiplatformTest.unsupportedIndexValuesRemainLenientButStillObeyCollisionPolicy`).
    private fun answer(entity: CausingEntity, notGiven: Boolean): Boolean = try {
        ask(entity, notGiven) == true
    } catch (outage: UnsupportedConstructException) {
        if (!entity.customTyped) throw outage
        false
    }

    override fun cellFilled(pointer: A12PartiallyKnownMultiPointer): Boolean {
        val repetitions = pointer.repetitionIndexes
        val group = parentGroup(pointer.fullName)
        return pass.doc.contextFor(group, repetitions.dropLast(1)).filled(pointer.fullName)
    }

    // Null when A12 cannot tell: a selection whose key field is not check-relevant, which reads as neither given nor
    // not given (`adapter.laws.SelectedErrorReanchorEligibilityDiffTest`, ShortKey). A key that selects no row leaves
    // the entity with no instance, which is not given. A question that is null is answered TRUE. An entity the row
    // names in its own terms is asked at the row; any other one at the place its pointer names, a level the pointer
    // leaves open being aggregated there.
    private fun ask(entity: CausingEntity, notGiven: Boolean): Boolean? {
        fun question(operand: Expr): Cond.Pred? = if (notGiven) entity.notGiven(operand) else entity.given(operand)
        val reference = entity.reference
        if (reference is MessageReference.Ordinary) {
            return question(Expr.Field(reference.path))?.let { interpreter.evalPred(it) == Tri.TRUE } ?: true
        }
        val key = (reference as? MessageReference.Selected)?.key
        if (key is SelectedKey.Field && !pass.readsAsKey(ctx, key.path)) return null
        val repetitions = pointers.of(reference, pass, ctx).repetitionIndexes
        if (A12PartiallyKnownMultiPointer.UNKNOWN in repetitions) return notGiven
        val parts = pathParts(reference.path)
        val open = repetitions.indexOf(A12PartiallyKnownMultiPointer.WILDCARD)
        val bound = when {
            open >= 0 -> open
            entity.group -> parts.size
            else -> parts.size - 1
        }
        val local = if (bound == 0) ctx else pass.doc.contextFor("/" + parts.take(bound).joinToString("/"), repetitions.take(bound))
        val operand = if (open >= 0) Expr.Star(reference.path, bindDepth = open) else Expr.Field(reference.path)
        val pred = question(operand) ?: return true
        if (notGiven && open >= 0 && entity.leavesDeclaredRowsAbsent(local.rowContexts(reference.path, open).size)) return true
        return pass.interpreterFor(local, errorPath).evalPred(pred) == Tri.TRUE
    }
}

/** A formal check's groups: the one cell it reports on, an omission included (KERNEL-FINDINGS KF683). */
internal fun <P> ownCellGroups(pointer: P): List<Set<P>> = listOf(setOf(pointer))

// The rows an aggregated reference ranges over at its open levels, each repeatable group counting its capacity.
private fun declaredInstances(reference: MessageReference, model: ModelDef): Int {
    if (reference !is MessageReference.Wildcard) return 1
    val parts = pathParts(reference.path)
    return (maxOf(reference.bindDepth, 0) until parts.size).fold(1) { product, level ->
        product * (model.declaredReps["/" + parts.take(level + 1).joinToString("/")] ?: 1)
    }
}

// An entry is matched as A12 checks it, by text against the shortest relative path from the rule's group after one
// spelling map (KF682), so a reference named twice — once plain and once aggregated — is selected twice.
private fun named(referenced: List<MessageReference>, group: String, entries: List<String>): List<MessageReference> {
    val wanted = entries.mapTo(HashSet(), ::canonicalEntry)
    return referenced.filter { canonicalEntry(relativePath(group, it.path)) in wanted }
}

private fun canonicalEntry(entry: String): String = when {
    entry.isEmpty() -> "./"
    entry.endsWith("..") -> "$entry/"
    else -> entry
}

