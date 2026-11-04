package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.EffectiveRelevanceIndex
import io.github.mbackschat.a12.dm.interpreter.eval.GroupCountGate
import io.github.mbackschat.a12.dm.interpreter.eval.GroupPresenceState
import io.github.mbackschat.a12.dm.interpreter.eval.GroupRelevance
import io.github.mbackschat.a12.dm.interpreter.model.CustomConditionFieldPointer
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity

/** A `Long` holds a wildcard mask for a path this deep; beyond it `reducedByPatternScan` takes over. */
private const val MASKABLE_DEPTH = 63

/**
 * The seams a PARTIAL validation call derives from its relevant set: which cells, stars and groups it may judge,
 * and what a `CustomCondition` sees. One instance per model, each seam built fresh per call by [DmInterpreter.validatePart].
 */
internal class PartialRelevance(private val model: ModelDef, private val paths: ModelPathIndex) {

    /**
     * Whether a STARRED operation has complete extent in PARTIAL mode (IF42, IF262). The kernel evaluates an
     * all-rows aggregate (`Sum`/`Max`/`Min`/`NumberOfDifferentValues`, but NOT `SumOfProducts`) and the direct
     * filled-field/group counts only when its reduced relevant identifiers wildcard every repeatable level
     * traversed by the star. Relevant ancestor groups first
     * expand to the operand path, and a broader wildcard identifier removes every narrower identifier it encompasses.
     * After that reduction, ANY remaining concrete coordinate at a starred level makes the extent unknown.
     * `FirstFilled` does not use this path because it is order-aware. Starred value-list quantifiers and
     * `NumberOfValueInFields` use the declared-coverage [starExtentKnownOf] instead.
     */
    fun starRelevantOf(relevant: Set<RelevantEntity>): (String, Int, List<Int>) -> Boolean {
        val candidates = wellFormed(relevant)
        val reducedByPath = HashMap<String, RowAddressIndex>()
        val decided = HashMap<Triple<String, Int, List<Int>>, Boolean>()
        return { starPath, bindDepth, rowReps ->
            val scope = starScope(starPath, bindDepth, rowReps)
            decided.getOrPut(Triple(starPath, bindDepth, scope.bound)) {
                wildcardsEveryStarredLevel(
                    reducedByPath.getOrPut(starPath) { RowAddressIndex(reducedStarRelevantCandidates(candidates, starPath)) },
                    scope,
                )
            }
        }
    }

    /**
     * Whether the reduced identifiers that address [scope]'s row establish the extent: at least one does, and
     * none is concrete at a level the star iterates.
     */
    private fun wildcardsEveryStarredLevel(reduced: RowAddressIndex, scope: StarScope): Boolean {
        val scoped = reduced.addressing(scope.bound)
        return scoped.isNotEmpty() && scope.levels.none { level -> scoped.any { it.indices[level] != RelevantEntity.ALL } }
    }

    /**
     * Partial group predicates and counts share the missing-position coverage check (SPEC-2026-10-10-20/21).
     * The stricter [GroupCountGate] for `NumberOfFilledGroups` is decided on the counted group
     * itself rather than on its fields (KF464, IF339). An operand is countable when three things hold:
     *
     * 1. **The group's fully-relevant identifiers establish the extent.** Kernel 31.1.1 asks the identifiers
     *    of the group instances that are WHOLLY relevant ([fullyRelevantIdentifiers]) rather than of those with
     *    any relevant content, as 30.8.1 did; none may stay concrete at a starred level (KERNEL-FINDINGS KF667).
     * 2. **The first declared instance is fully relevant and not erroneous** — the one instance A12 inspects
     *    before it counts anything, held or not, so a partly covered or erroneous first row leaves the count
     *    undecidable even where every other row is decided.
     * 3. **Every declared instance the document lacks is fully relevant.** A12 walks the declared rows, not the
     *    held ones, and an absent row it cannot call empty has no count.
     *
     * Rule 3 is decided on one representative per equivalence class rather than on every declared coordinate:
     * group relevance at a level depends only on whether an identifier names that index, so the indices some
     * identifier or held instance names are checked each, and all the rest by the smallest of them, which is
     * absent by construction. That keeps the check proportional to the named indices when a capacity is large.
     * A group without a declared capacity, which only a hand-built model can express since `ModelLoader` gives
     * every A12 repeatable group one, is read as having such a row beyond every named index.
     */
    inner class PartialGroupExtents(relevant: Set<RelevantEntity>) {
        private val candidates = wellFormed(relevant)
        private val reducedByPath = HashMap<String, RowAddressIndex>()
        private val namedByLevel = HashMap<String, Set<Int>>()
        private val absentCoverage = HashMap<Triple<String, Int, List<Int>>, Boolean>()

        fun gate(presence: (String, List<Int>) -> GroupPresenceState): GroupCountGate {
            val decided = HashMap<Triple<String, Int, List<Int>>, Boolean>()
            return object : GroupCountGate {
                override fun admits(groupPath: String, bindDepth: Int, rowReps: List<Int>, present: Set<List<Int>>): Boolean {
                    val scope = starScope(groupPath, bindDepth, rowReps)
                    return decided.getOrPut(Triple(groupPath, bindDepth, scope.bound)) {
                        wildcardsEveryStarredLevel(reducedByPath.getOrPut(groupPath) {
                            RowAddressIndex(fullyRelevantIdentifiers(groupPath).map { RelevantEntity(groupPath, it) })
                        }, scope) &&
                            presence(groupPath, firstInstance(groupPath, scope))
                                .let { it.relevance == GroupRelevance.FULL && !it.erroneous } &&
                            coversAbsent(groupPath, bindDepth, rowReps, present, presence)
                    }
                }

                override fun decidingPrefix(groupPath: String, bindDepth: Int, rowReps: List<Int>): List<Int> =
                    starScope(groupPath, bindDepth, rowReps).bound
            }
        }

        /** Missing positions have the same coverage requirement for predicates and counts; predicates do not
         * inherit the count's own-identifier wildcard or first-instance gates (SPEC-2026-10-10-20/21). */
        fun coversAbsent(groupPath: String, bindDepth: Int, rowReps: List<Int>, present: Set<List<Int>>,
                presence: (String, List<Int>) -> GroupPresenceState): Boolean {
            val scope = starScope(groupPath, bindDepth, rowReps)
            return absentCoverage.getOrPut(Triple(groupPath, bindDepth, scope.bound)) {
                absentRepresentatives(groupPath, scope, present)
                    .all { presence(groupPath, it).relevance == GroupRelevance.FULL }
            }
        }

        /**
         * The index tuples of [groupPath]'s wholly relevant instances, one index per path segment and
         * [RelevantEntity.ALL] for a wildcard. An instance is wholly relevant when every direct child is: a field
         * through its own relevant identifiers, a sub-group only through a tuple that wildcards the sub-group's
         * own level (or names a non-repeatable one), so the tuples are the compatible intersections of one tuple
         * per child. A repeatable group's concrete tuples under one upper prefix that name every declared slot
         * then fold into that prefix's wildcard. The fold applies at the group's own level only, which is why a
         * non-repeatable group covered row by row under a starred parent stays concrete at the parent's level
         * (KERNEL-FINDINGS KF667, `adapter.laws.PartialGroupCountRelevanceDiffTest`).
         */
        private fun fullyRelevantIdentifiers(groupPath: String): Set<List<Int>> = fullyByGroup.getOrPut(groupPath) {
            val depth = pathParts(groupPath).size
            val childTuples: List<Set<List<Int>>> = model.fields.filter { parentOf(it.path) == groupPath }.map { field ->
                reducedStarRelevantCandidates(candidates, field.path).mapTo(HashSet()) { it.indices.take(depth) }
            } + groupsBelow(groupPath).map { child ->
                val repeatable = child in model.repeatable
                fullyRelevantIdentifiers(child).filter { !repeatable || it[depth] == RelevantEntity.ALL }
                    .mapTo(HashSet()) { it.take(depth) }
            }
            if (childTuples.isEmpty()) return@getOrPut emptySet()
            val intersected = childTuples.reduce { acc, next -> intersections(acc, next, depth) }
            folded(groupPath, withoutEncompassed(intersected, depth))
        }

        private val fullyByGroup = HashMap<String, Set<List<Int>>>()

        /**
         * [groupPath]'s direct sub-groups as DECLARED, a field-less one included: A12 asks every contained element
         * to be covered, so an empty sub-group, which nothing can cover, keeps its parent from ever being wholly
         * relevant (KERNEL-FINDINGS KF667, `adapter.laws.PartialWhollyRelevantBoundaryDiffTest`). Field prefixes
         * stand in where a hand-built model leaves [ModelDef.groupPaths] empty.
         */
        private fun groupsBelow(groupPath: String): Set<String> {
            val depth = pathParts(groupPath).size
            val viaFields = model.fields.mapNotNullTo(HashSet()) { field ->
                val parts = pathParts(field.path)
                if (parts.size > depth + 1 && parts.take(depth) == pathParts(groupPath))
                    "/" + parts.take(depth + 1).joinToString("/") else null
            }
            val prefix = "$groupPath/"
            return (model.groupPaths + model.repeatable).filterTo(viaFields) {
                it.startsWith(prefix) && '/' !in it.substring(prefix.length)
            }
        }

        private fun parentOf(path: String): String = path.substringBeforeLast('/')

        /** The tuple both [a] and [b] address, or null when they name different rows at some level. */
        private fun intersection(a: List<Int>, b: List<Int>): List<Int>? =
            a.indices.map { level ->
                when {
                    a[level] == RelevantEntity.ALL -> b[level]
                    b[level] == RelevantEntity.ALL || b[level] == a[level] -> a[level]
                    else -> return null
                }
            }

        /**
         * Every [intersection] of a [left] tuple with a [right] tuple, each [depth] entries long. Two tuples are
         * compatible exactly when they agree on the levels both name concretely, so each pair of wildcard masks
         * finds its partners by a hash lookup on those levels rather than by a scan of [right]: one relevant
         * identifier per row makes both sets grow with the document, and the pairwise scan this replaces cost
         * O(rows²) (`GrowingRelevancePartialPerfTest`).
         */
        private fun intersections(left: Set<List<Int>>, right: Set<List<Int>>, depth: Int): Set<List<Int>> {
            if (depth > MASKABLE_DEPTH) return left.flatMapTo(HashSet()) { a -> right.mapNotNull { b -> intersection(a, b) } }
            val rightByMask = right.groupBy(::wildcardMask)
            val result = HashSet<List<Int>>()
            left.groupBy(::wildcardMask).forEach { (leftMask, lefts) ->
                rightByMask.forEach { (rightMask, rights) ->
                    val shared = (0 until depth).filter { (leftMask or rightMask) shr it and 1L == 0L }
                    val byShared = rights.groupBy { b -> shared.map(b::get) }
                    lefts.forEach { a -> byShared[shared.map(a::get)]?.forEach { b -> intersection(a, b)?.let(result::add) } }
                }
            }
            return result
        }

        private fun folded(groupPath: String, tuples: Set<List<Int>>): Set<List<Int>> {
            val capacity = model.declaredReps[groupPath] ?: return tuples
            val own = pathParts(groupPath).lastIndex
            val result = HashSet(tuples)
            tuples.filter { it[own] != RelevantEntity.ALL }.groupBy { it.take(own) }.forEach { (prefix, rows) ->
                if (rows.mapTo(HashSet()) { it[own] }.count { it in 1..capacity } >= capacity) {
                    // One removal per row, not `removeAll`, whose cost depends on which side a platform iterates:
                    // walking `result` once per folded prefix would be quadratic in the parent rows.
                    rows.forEach(result::remove)
                    result.add(prefix + RelevantEntity.ALL)
                }
            }
            return withoutEncompassed(result, own + 1)
        }

        private fun firstInstance(groupPath: String, scope: StarScope): List<Int> =
            scope.bound + List(pathParts(groupPath).size - scope.bound.size) { 1 }

        private fun absentRepresentatives(groupPath: String, scope: StarScope, present: Set<List<Int>>): List<List<Int>> {
            val parts = pathParts(groupPath)
            var tuples = listOf(scope.bound)
            for (level in scope.bound.size until parts.size) {
                val prefix = "/" + parts.take(level + 1).joinToString("/")
                val axis = if (prefix !in model.repeatable) listOf(1) else {
                    val named = HashSet(namedIndices(prefix, level))
                    present.mapTo(named) { it[level] }
                    val capacity = model.declaredReps[prefix]
                    (named + generateSequence(1) { it + 1 }.first { it !in named })
                        .filter { capacity == null || it <= capacity }
                }
                tuples = tuples.flatMap { tuple -> axis.map { tuple + it } }
            }
            return tuples.filter { it !in present }
        }

        /** The concrete indices the identifiers on [prefix]'s branch name at its [level], whatever row asks. */
        private fun namedIndices(prefix: String, level: Int): Set<Int> = namedByLevel.getOrPut(prefix) {
            val prefixParts = pathParts(prefix)
            candidates.mapNotNullTo(HashSet()) { e ->
                e.indices.getOrNull(level)?.takeIf { index ->
                    index != RelevantEntity.ALL && pathParts(e.path).take(level + 1) == prefixParts
                }
            }
        }
    }

    /**
     * The complete-extent gate for value lists, value counts and descendant group coverage uses the same star anchor and
     * per-iteration-row scope as [starRelevantOf]. A wildcard covering every reopened level or a union covering
     * every declared slot establishes extent; concrete siblings do not suppress either (SPEC-2026-10-10-15,
     * `PartialDeclaredListExtentDiffTest`). Descendant group coverage shares this union (SPEC-2026-10-10-22,
     * `PartialParentGroupCoverageDiffTest`). Wildcards still establish extent for unbounded declarations.
     *
     * The two gates were briefly one predicate, on the reasoning that they answer the same structural question.
     * They do not. On `{field wildcard} + {concrete group rows}` the kernel FIRES a starred `No` and is SILENT
     * on `Sum` over the same rows, on both codegen strategies — so folding the value-list site into [IF262]'s
     * reduction made the interpreter suppress where the kernel fires. Seven relevant-set shapes measured side by
     * side in `PartialExtentGateShapeDiffTest`; the split is IF264.
     */
    fun starExtentKnownOf(relevant: Set<RelevantEntity>): (String, Int, List<Int>) -> Boolean {
        val covering = coveringIndexes(relevant)
        val decided = HashMap<Triple<String, Int, List<Int>>, Boolean>()
        return { starPath, bindDepth, rowReps ->
            val scope = starScope(starPath, bindDepth, rowReps)
            decided.getOrPut(Triple(starPath, bindDepth, scope.bound)) {
                val addressing = covering(starPath).addressing(scope.bound)
                addressing.any { e ->
                    scope.levels.all { i -> i >= e.indices.size || e.indices[i] == RelevantEntity.ALL }
                } || coversEveryRepetition(addressing, starPath, scope)
            }
        }
    }

    /**
     * The kernel expands each relevant ancestor group into an identifier for every contained field, then removes
     * identifiers encompassed by a broader wildcard identifier of the same field name. Its `EntityIterator`
     * rejects an aggregate extent when ANY identifier left for that operand is concrete at a starred level.
     * Therefore "any wildcard wins" is wrong: `[1,*,1]` beside an expanded `[0,1,*]` stays mixed and is UNKNOWN,
     * while `[*,*,*]` encompasses and removes the concrete sibling before the iterator asks.
     *
     * Reduction happens before the per-row subtree filter, matching `ValidationCache.reduceRedundancies` followed
     * by `EntityIterator.certainlyNotInSameSubtree`. This preserves IF257's row-local answer when one outer row is
     * wildcarded and another is concrete. SumOfProducts does not use this combiner iterator and keeps the separate
     * [starFullyEnumeratedOf] path. See IF262 for the source trace and discriminating cases.
     */
    private fun reducedStarRelevantCandidates(
        relevant: List<RelevantEntity>,
        starPath: String,
    ): List<RelevantEntity> {
        val depth = pathParts(starPath).size
        val expanded = relevant.asSequence()
            .filter { covers(it, starPath) }
            .map { entity -> entity.indices + List(depth - entity.indices.size) { RelevantEntity.ALL } }
            .distinct()
            .toList()
        return withoutEncompassed(expanded, depth).map { RelevantEntity(starPath, it) }
    }

    /**
     * [tuples], each [depth] entries long, in their given order without every one a broader tuple of the set
     * encompasses: a tuple that is [RelevantEntity.ALL] or equal at every level, and differs somewhere.
     *
     * **Cost.** A candidate is removed exactly when the set contains a tuple whose wildcard mask is a STRICT
     * SUPERSET of the candidate's and whose remaining coordinates match it — so only masks with a strictly
     * larger wildcard count can remove anything, which is what [wildcardMasksByBreadth] orders by. Testing a
     * mask first and materializing the broader tuple only on a hit is what keeps this off the quadratic path:
     * scanning every distinct mask and hashing a fresh list for each took **1306 ms** on 3000 relevant
     * identifiers whose masks form an antichain (equal wildcard counts, so nothing removes anything and every
     * probe was wasted), growing 3.86x when the set doubled — measured, not predicted, by the growth test whose
     * antichain lane is now `StarRelevanceReductionPerfTest`. The residual worst case is a set mixing many wildcard counts, bounded
     * by cheap `Long` comparisons rather than allocation. The group-count gate's wholly relevant identifiers
     * use it too, where a pairwise scan of their own cost O(rows²) (`GrowingRelevancePartialPerfTest`).
     */
    private fun withoutEncompassed(tuples: Collection<List<Int>>, depth: Int): Set<List<Int>> {
        if (depth > MASKABLE_DEPTH) return withoutEncompassedByPatternScan(tuples)
        val members = tuples.toHashSet()
        val byBreadth = wildcardMasksByBreadth(tuples)
        return tuples.filterTo(LinkedHashSet()) { candidate ->
            val own = wildcardMask(candidate)
            val ownBreadth = own.countOneBits()
            byBreadth.none { (breadth, masks) ->
                breadth > ownBreadth && masks.any { mask ->
                    mask and own == own && broaderTuple(candidate, mask) in members
                }
            }
        }
    }

    /**
     * The same reduction for an operand path deeper than a `Long` has bits for — the direct pairwise scan,
     * kept because it needs no mask and is provably equivalent. It is quadratic, but a path this deep does not
     * occur: reaching it needs more than [MASKABLE_DEPTH] segments in one field path.
     */
    private fun withoutEncompassedByPatternScan(tuples: Collection<List<Int>>): Set<List<Int>> {
        val members = tuples.toHashSet()
        val patterns = tuples.asSequence()
            .map { tuple -> tuple.map { it == RelevantEntity.ALL } }
            .filter { pattern -> pattern.any { it } }
            .distinct()
            .toList()
        return tuples.filterTo(LinkedHashSet()) { candidate ->
            patterns.none { pattern ->
                val broader = candidate.mapIndexed { level, index ->
                    if (pattern[level]) RelevantEntity.ALL else index
                }
                broader != candidate && broader in members
            }
        }
    }

    /**
     * The distinct wildcard masks present, grouped by how many levels each wildcards and ordered by that count.
     * Only a mask that wildcards strictly MORE levels than a candidate can encompass it, so a candidate whose
     * own breadth is maximal — the antichain case — skips every group without touching a single mask.
     */
    private fun wildcardMasksByBreadth(tuples: Collection<List<Int>>): List<Pair<Int, List<Long>>> =
        tuples.asSequence()
            .map { wildcardMask(it) }
            .filter { it != 0L }
            .distinct()
            .groupBy { it.countOneBits() }
            .toList()
            .sortedBy { it.first }

    private fun wildcardMask(indices: List<Int>): Long {
        var mask = 0L
        indices.forEachIndexed { level, index -> if (index == RelevantEntity.ALL) mask = mask or (1L shl level) }
        return mask
    }

    private fun broaderTuple(indices: List<Int>, mask: Long): List<Int> =
        indices.mapIndexed { level, index ->
            if (mask shr level and 1L == 1L) RelevantEntity.ALL else index
        }

    /** The relevant entities carrying one index per path segment — the shape both gates can reason about. */
    private fun wellFormed(relevant: Set<RelevantEntity>): List<RelevantEntity> =
        relevant.filter { it.indices.size == pathParts(it.path).size }

    /** Whether [e] speaks about [starPath] at all: its path is an ancestor-or-equal prefix. */
    private fun covers(e: RelevantEntity, starPath: String): Boolean {
        val sParts = pathParts(starPath)
        val eParts = pathParts(e.path)
        return eParts.size <= sParts.size && eParts == sParts.take(eParts.size)
    }

    /** The repetition coordinates the current row BINDS for a starred operand, and the levels the star iterates. */
    private class StarScope(val bound: List<Int>, val levels: List<Int>)

    /**
     * Split [starPath]'s repeatable levels into the ones the current row fixes and the ones the star iterates.
     *
     * The split is [io.github.mbackschat.a12.dm.interpreter.ast.Expr.Star.bindDepth] — the segments strictly
     * above the FIRST `*`-marked group (IF94) — and **not** the path's deepest repeatable level. For a
     * single-star operand the two coincide, which is why deriving it from the path alone survived every
     * single-star probe. For `Outer* then Inner* then Qty` they differ by exactly one level, and that level is
     * one the row would claim to bind while the operand in fact spans every one of its rows: pinning `Outer` to
     * row 1 then read as "everything unbound is wildcarded", and the interpreter fired where the kernel is
     * silent (IF259). A hand-built node (−1) keeps the leaf-parent default, where the two coincide by definition.
     */
    private fun starScope(starPath: String, bindDepth: Int, rowReps: List<Int>): StarScope {
        val parts = pathParts(starPath)
        fun repeatableAt(i: Int) = "/" + parts.take(i + 1).joinToString("/") in model.repeatable
        val firstStar = if (bindDepth >= 0) minOf(bindDepth, parts.size)
            else parts.indices.lastOrNull { repeatableAt(it) } ?: 0
        val bound = rowReps.take(minOf(firstStar, rowReps.size))
        return StarScope(bound, parts.indices.filter { it >= bound.size && repeatableAt(it) })
    }

    /**
     * `SumOfProducts`' own, WEAKER completeness notion. It does not share [starRelevantOf]'s wildcard-only gate
     * (it runs a hand-written two-list loop with no three-value pre-survey), but it is not ungated either: the
     * kernel evaluates it under a CONCRETE enumeration only when that enumeration is COMPLETE. Measured against
     * the dynamic kernel on `/Cart/Lines[2]` (`PartialValidationAggregateDiffTest`):
     *
     * | relevant set | kernel |
     * |---|---|
     * | wildcard | fires |
     * | both rows enumerated | fires |
     * | row 1 only / row 2 only | **silent** |
     *
     * Exempting it from the gate ENTIRELY — reading "concrete enumeration evaluates" off the both-rows case
     * alone — made the interpreter fire where the kernel is silent, on 5 of the fuzz net's 180 (document,
     * relevant-subset) combinations. Completeness is measured against the DECLARED repetitions, which is what
     * the probe varied; a document with fewer rows than declared is not separately measured.
     *
     * **Completeness is a property of the TUPLES, not of each level independently** — the distinction only
     * appears once two repeatable levels are nested, which is why the one-level probe above could not see it.
     * Enumerating `Outer[1]/Inner[1]` and `Outer[2]/Inner[2]` covers `{1,2}` at both levels while covering only
     * the diagonal, half of the four rows; reading per-level unions called that complete and fired where the
     * kernel is silent. So a candidate must cover the full CROSS-PRODUCT of the declared repetitions of the
     * levels the star iterates.
     *
     * The cover is taken over the **union of every addressing entity**, not per wildcard pattern. Deciding it
     * per pattern suppressed a set whose union is complete but whose individual patterns are not — `[*,*,1]`
     * together with `[*,1,2]` and `[*,2,2]` covers all four pairs, and the kernel fires (IF260).
     */
    fun starFullyEnumeratedOf(relevant: Set<RelevantEntity>): (String, Int, List<Int>) -> Boolean {
        // Filtered ONCE per call. Deciding completeness by rescanning the relevant set for every declared
        // repetition is O(relevant² × reps), and enumerating every row makes the relevant set grow WITH the
        // document — measured 8.63× growth for 4× rows on `laws/PartialSumOfProductsPerfTest` (a growth
        // figure, so interpreter-only and unaffected by that guard's since-corrected kernel baseline).
        val covering = coveringIndexes(relevant)
        val decided = HashMap<Triple<String, Int, List<Int>>, Boolean>()
        return { starPath, bindDepth, rowReps ->
            val scope = starScope(starPath, bindDepth, rowReps)
            decided.getOrPut(Triple(starPath, bindDepth, scope.bound)) {
                coversEveryRepetition(covering(starPath).addressing(scope.bound), starPath, scope)
            }
        }
    }

    /** Per star path, the well-formed entities that speak about it ([covers]), indexed by their coordinates. */
    private fun coveringIndexes(relevant: Set<RelevantEntity>): (String) -> RowAddressIndex {
        val candidates = wellFormed(relevant)
        val byPath = HashMap<String, RowAddressIndex>()
        return { starPath -> byPath.getOrPut(starPath) { RowAddressIndex(candidates.filter { covers(it, starPath) }) } }
    }

    /**
     * Whether the relevant entities [addressing] this row cover every declared repetition TUPLE of the levels
     * [starPath]'s star iterates. Levels **deeper** than an entity's own path need no enumeration: a relevant
     * group makes every descendant relevant, which is the kernel's own rule, so such a level reads as wildcarded.
     */
    private fun coversEveryRepetition(
        addressing: List<RelevantEntity>,
        starPath: String,
        scope: StarScope,
    ): Boolean {
        if (scope.levels.isEmpty()) return addressing.isNotEmpty()

        val parts = pathParts(starPath)
        val declared = IntArray(scope.levels.size) { k ->
            model.declaredReps["/" + parts.take(scope.levels[k] + 1).joinToString("/")] ?: return false
        }
        // Each addressing entity becomes a BOX over the iterated levels — a concrete index pins that level,
        // ALL (and any level deeper than the entity's own path) spans it.
        val boxes = addressing.map { e ->
            IntArray(scope.levels.size) { k ->
                val i = scope.levels[k]
                if (i >= e.indices.size) RelevantEntity.ALL else e.indices[i]
            }
        }
        return boxesCoverEveryTuple(0, declared, boxes)
    }

    /**
     * Whether a union of boxes covers the full cross-product of [declared], from level [k] down.
     *
     * Recursing on the boxes that WILDCARD level `k` first is what keeps this linear in practice: if they alone
     * cover the remaining levels, every index of `k` is covered and no per-index descent is needed. Otherwise
     * each declared index must be covered by the wildcards plus the boxes pinning it — and an index no box
     * pins at all fails immediately, so the descent is bounded by the concrete indices actually present rather
     * than by the declared repetition counts.
     */
    private fun boxesCoverEveryTuple(k: Int, declared: IntArray, boxes: List<IntArray>): Boolean {
        if (k == declared.size) return boxes.isNotEmpty()
        val wild = boxes.filter { it[k] == RelevantEntity.ALL }
        if (boxesCoverEveryTuple(k + 1, declared, wild)) return true
        val pinned = boxes.filter { it[k] != RelevantEntity.ALL }.groupBy { it[k] }
        return (1..declared[k]).all { v ->
            val here = pinned[v] ?: return false
            boxesCoverEveryTuple(k + 1, declared, wild + here)
        }
    }

    /** The relevant set a [io.github.mbackschat.a12.dm.interpreter.model.CustomCondition] sees in PARTIAL mode —
     *  the kernel feeds it the caller's relevant entities (wildcards preserved, groups NOT expanded) **plus the
     *  auto-added global fields** (IF37/IF38, `MainValidatorController` → `getAllPruefungsrelevanteEntitiesExtern`).
     *  Full validation passes `null` ("all relevant"); here the caller's [RelevantEntity]s map straight to
     *  [CustomConditionFieldPointer]s and each global field rides as an all-reps-wildcard pointer. */
    fun customRelevantOf(relevant: Set<RelevantEntity>): Set<CustomConditionFieldPointer> =
        relevant.mapTo(HashSet()) { CustomConditionFieldPointer(it.path, it.indices) } +
            model.globalFields.map { CustomConditionFieldPointer(it, List(pathParts(it).size) { RelevantEntity.ALL }) }

    /** The production index answering a partial call's concrete-cell relevance. */
    fun relevanceQueriesOf(relevant: Set<RelevantEntity>): EffectiveRelevanceIndex =
        EffectiveRelevanceIndex.build(model.globalFields, relevant)

    /**
     * Partial validation's two group-relevance levels. Any selected descendant makes its ancestors PARTIAL,
     * which is enough to prove positive presence. Absence is known only when an ancestor selection covers the
     * whole group, or every declared descendant field is covered across every deeper repeatable level.
     */
    fun groupRelevanceOf(relevant: Set<RelevantEntity>): (String, List<Int>) -> GroupRelevance {
        val effective = relevant + model.globalFields.map(RelevantEntity::anyRep)
        // PartialParentGroupCoverageDiffTest: fixed-group coordinates above one cannot prove absence;
        // terminal field coordinates do not narrow that coverage, and partial presence stays independent.
        val coversField by lazy {
            val coverage = wellFormed(effective).filterTo(HashSet()) { entity ->
                entity.path in model.entityPaths && paths.groupsThrough(entity.path).withIndex().all { (level, path) ->
                    path in model.kinds || path in model.repeatable || entity.indices[level] in 0..1
                }
            }
            starExtentKnownOf(coverage)
        }
        val fieldsByAncestor by lazy {
            val fields = HashMap<String, MutableList<String>>()
            for (field in model.fields) {
                for (group in ancestorGroups(field.path)) fields.getOrPut(group) { ArrayList() } += field.path
            }
            fields
        }
        val cache = HashMap<String, HashMap<List<Int>, GroupRelevance>>()
        // Both questions are indexed, because a relevant set that names every row would otherwise be scanned once
        // per group instance (IF412, `GrowingRelevancePartialPerfTest`). An entity TOUCHES a group when one path
        // is a prefix of the other and its indices agree on the levels both carry; it COVERS one when its own path
        // is the group's or an ancestor's and every index it carries agrees.
        val touching = HashMap<String, IndexTrie>()
        fun touchesGroup(groupPath: String, groupReps: List<Int>): Boolean = touching.getOrPut(groupPath) {
            val groupParts = pathParts(groupPath)
            IndexTrie().also { trie ->
                for (entity in effective) {
                    val entityParts = pathParts(entity.path)
                    val common = minOf(entityParts.size, groupParts.size)
                    if (entityParts.take(common) == groupParts.take(common)) trie.add(entity.indices.take(common))
                }
            }
        }.agreesWithin(groupReps)
        val covering by lazy {
            val tries = HashMap<List<String>, IndexTrie>()
            for (entity in effective) {
                val entityParts = pathParts(entity.path)
                if (entity.indices.size == entityParts.size) tries.getOrPut(entityParts) { IndexTrie() }.add(entity.indices)
            }
            tries
        }
        fun coversGroup(groupPath: String, groupReps: List<Int>): Boolean {
            val groupParts = pathParts(groupPath)
            return (1..groupParts.size).any { depth -> covering[groupParts.take(depth)]?.selects(groupReps, depth) == true }
        }

        return { groupPath, groupReps ->
            cache.getOrPut(groupPath) { HashMap() }.getOrPut(groupReps) {
                if (!touchesGroup(groupPath, groupReps)) {
                    GroupRelevance.NONE
                } else if (coversGroup(groupPath, groupReps) ||
                    fieldsByAncestor[groupPath].orEmpty().all { field ->
                        coversField(field, pathParts(groupPath).size, groupReps)
                    }) {
                    GroupRelevance.FULL
                } else {
                    GroupRelevance.PARTIAL
                }
            }
        }
    }
}
