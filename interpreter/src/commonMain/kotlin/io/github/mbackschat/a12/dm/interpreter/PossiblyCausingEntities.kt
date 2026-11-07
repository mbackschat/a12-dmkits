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
import io.github.mbackschat.a12.dm.interpreter.workspace.ImmutableProjection

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
     * Whether [instances] rows of this aggregated group leave some of its declared ones absent, which A12 counts as not
     * given: its question walks every declared repetition (`EntityIterator`), where the group-list quantifiers walk
     * the instantiated rows (KERNEL-FINDINGS KF683, `adapter.laws.SumOfTermsRuntimeLawsTest`).
     */
    fun leavesDeclaredRowsAbsent(instances: Int): Boolean = instances < declaredInstances

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
}

/**
 * The key cells of one `RepetitionNotUnique` duplicate cluster, split by presence: A12 names each cell on every
 * message the cluster raises, an empty key as not given (KERNEL-FINDINGS KF683). Built once per cluster and
 * shared by all its messages, because a per-message copy of N cells is O(N²) allocation on a dense cluster
 * (INTERPRETER-FINDINGS IF256; `CausingGroupClusterSharingTest`).
 */
internal class ClusterCauses(given: Collection<A12PartiallyKnownMultiPointer>, empty: Collection<A12PartiallyKnownMultiPointer>) {
    val given: Set<A12PartiallyKnownMultiPointer> = PointerSet(given)
    val empty: Set<A12PartiallyKnownMultiPointer> = PointerSet(empty)
    val all: Set<A12PartiallyKnownMultiPointer> = when {
        this.empty.isEmpty() -> this.given
        this.given.isEmpty() -> this.empty
        else -> PointerSet(given + empty)
    }

    companion object {
        val NONE = ClusterCauses(emptyList(), emptyList())
    }
}

/** An immutable, hash-backed pointer set that may be shared and published as-is. */
internal class PointerSet<T>(members: Collection<T>) : AbstractSet<T>(), ImmutableProjection {
    private val members = LinkedHashSet(members)
    override val size: Int get() = members.size
    override fun contains(element: T): Boolean = element in members
    override fun iterator(): Iterator<T> = members.iterator().let { source -> object : Iterator<T> by source {} }
}

/** A shared [cluster] set joined by a few [extra] members it lacks: one message's view, never a copy. */
internal class CausingUnion<T>(val cluster: Set<T>, private val extra: List<T>) : AbstractSet<T>(), ImmutableProjection {
    override val size: Int get() = cluster.size + extra.size
    override fun contains(element: T): Boolean = element in cluster || element in extra
    override fun iterator(): Iterator<T> = (cluster.asSequence() + extra.asSequence()).iterator()
}

private fun <T> joined(cluster: Set<T>, members: Set<T>): Set<T> {
    val extra = members.filterNot(cluster::contains)
    return when {
        extra.isEmpty() -> cluster
        cluster.isEmpty() -> PointerSet(extra)
        else -> CausingUnion(cluster, extra)
    }
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
    // A12 applies a filter only when either list names an entity (`IRule.hasCustomRefEntities`, source-read); with
    // both empty it derives the causes as for an unfiltered rule (`PossiblyCausingEntitiesLawsTest`).
    val filter = rule.possibleCauses?.takeIf { it.ifFilled.isNotEmpty() || it.ifNotFilled.isNotEmpty() }
    val given = if (filter == null) referenced else named(referenced, rule.group, filter.ifFilled)
    val omitted = if (filter == null) omission else named(referenced, rule.group, filter.ifNotFilled)
    // Prepared for every rule of a model, so a reference both lists name shares one entity, and equal lists one list.
    val entities = HashMap<MessageReference, CausingEntity>()
    fun entitiesOf(references: List<MessageReference>): List<CausingEntity> = if (references.isEmpty()) emptyList()
        else references.map { reference ->
            entities.getOrPut(reference) {
                CausingEntity(reference, reference.path !in model.kinds, declaredInstances(reference, model),
                    reference.path in model.customTypes)
            }
        }
    val givenEntities = entitiesOf(given)
    return CausingEntityCandidates(givenEntities, if (omitted == given) givenEntities else entitiesOf(omitted))
}

/**
 * A12's groups for a message of [type] fired at one row: an OMISSION names its not-given candidates, then the given
 * ones not already named; a VALUE error names both in one group. [presence] answers for an entity at that row, and
 * [pointerOf] places it there, an aggregated level as the wildcard. [cluster] holds the key cells of the
 * `RepetitionNotUnique` rows the message reports, joined to the row's own entities without being copied.
 */
internal fun possiblyCausingEntityGroups(
    candidates: CausingEntityCandidates,
    type: MsgType,
    presence: EntityPresence,
    pointerOf: (MessageReference) -> A12PartiallyKnownMultiPointer,
    cluster: ClusterCauses,
): List<Set<A12PartiallyKnownMultiPointer>> {
    val empty = candidates.omission.filter(presence::notGiven).mapTo(LinkedHashSet()) { pointerOf(it.reference) }
    val given = candidates.given.filter(presence::given).mapTo(LinkedHashSet()) { pointerOf(it.reference) }
    return when (type) {
        MsgType.OMISSION -> {
            // The cluster's given and empty cells are disjoint, so only a row entity can move a given cell out.
            val givenCells = if (empty.none(cluster.given::contains)) cluster.given else PointerSet(cluster.given - empty)
            val givenEntities = given.filterNotTo(LinkedHashSet()) { it in empty || it in cluster.empty }
            listOf(joined(cluster.empty, empty), joined(givenCells, givenEntities))
        }
        MsgType.VALUE -> listOf(joined(cluster.all, given + empty))
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
        ask(entity, notGiven)
    } catch (outage: UnsupportedConstructException) {
        if (!entity.customTyped) throw outage
        false
    }

    // A selection whose key field is not check-relevant reads as neither given nor not given, so both questions
    // answer false (`adapter.laws.SelectedErrorReanchorEligibilityDiffTest`, ShortKey). A key that selects no row
    // leaves the entity with no instance, which is not given. A question the entity leaves null is answered TRUE. An entity the row
    // names in its own terms is asked at the row; any other one at the place its pointer names, a level the pointer
    // leaves open being aggregated there.
    private fun ask(entity: CausingEntity, notGiven: Boolean): Boolean {
        fun question(operand: Expr): Cond.Pred? = if (notGiven) entity.notGiven(operand) else entity.given(operand)
        val reference = entity.reference
        if (reference is MessageReference.Ordinary) {
            return question(Expr.Field(reference.path))?.let { interpreter.evalPred(it) == Tri.TRUE } ?: true
        }
        val key = (reference as? MessageReference.Selected)?.key
        if (key is SelectedKey.Field && !pass.readsAsKey(ctx, key.path)) return false
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
        if (notGiven && open >= 0 && entity.group &&
            entity.leavesDeclaredRowsAbsent(local.rowContexts(reference.path, open).size)) return true
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

