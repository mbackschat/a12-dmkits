package io.github.mbackschat.a12.dm.interpreter.eval

/**
 * A `Having` filter's walk over one group's rows below one scope, as A12 visits it: every coordinate of the grid
 * the walk's widths span, in coordinate order, the stored rows where they exist and a missing row elsewhere
 * (KERNEL-FINDINGS KF528). The grid spans each starred level up to the highest repetition stored at that depth
 * in ANY row, so one wide row widens every sibling's grid; a missing row is therefore built only when the walk
 * reaches it, and never kept, or the walks of N sibling rows would retain N² of them (INTERPRETER-FINDINGS IF413,
 * `SkewedFilterWalkPerfTest`).
 */
internal class FilterWalkView private constructor(
    private val scope: List<Int>,
    private val widths: IntArray,
    private val storedIndices: IntArray,
    private val storedRows: List<EvalContext>,
    private val missing: (List<Int>) -> EvalContext,
) : AbstractList<EvalContext>() {

    /** [stored] holds the rows on the grid in coordinate order, with their coordinates; reading one is then a
     *  search of an index array rather than a coordinate list built and hashed per access, which the per-row
     *  re-walks of a correlated filter pay for every candidate. */
    constructor(
        scope: List<Int>,
        widths: IntArray,
        stored: List<Pair<List<Int>, EvalContext>>,
        missing: (List<Int>) -> EvalContext,
    ) : this(scope, widths, IntArray(stored.size) { i -> indexOf(widths, scope.size, stored[i].first) },
        stored.map { it.second }, missing)

    override val size: Int = widths.fold(1) { cells, width -> cells * width }

    init {
        // [storedSlotOf] binary-searches the stored rows, so each must lie on the grid once and in coordinate order.
        for (i in storedIndices.indices) {
            require(storedIndices[i] in 0 until size && (i == 0 || storedIndices[i - 1] < storedIndices[i])) {
                "stored row $i is off the grid or out of coordinate order"
            }
        }
    }

    override fun get(index: Int): EvalContext {
        if (index !in 0 until size) throw IndexOutOfBoundsException("index $index, size $size")
        val slot = storedSlotOf(index)
        return if (slot >= 0) storedRows[slot] else missing(coordinatesAt(index))
    }

    // Hand-written because `IntArray.binarySearch` is JVM-only, and this view is common code.
    private fun storedSlotOf(index: Int): Int {
        var low = 0
        var high = storedIndices.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val at = storedIndices[mid]
            when {
                at < index -> low = mid + 1
                at > index -> high = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    fun coordinatesAt(index: Int): List<Int> = scope + digitsAt(index).toList()

    /** The same walk with every row passed through [transform]: the stored ones once, a missing one when reached. */
    fun mapRows(transform: (EvalContext) -> EvalContext): FilterWalkView =
        FilterWalkView(scope, widths, storedIndices, storedRows.map(transform)) { transform(missing(it)) }

    /**
     * The first index at or after [from] that A12's sparse walk does not skip while [kept] is the pending kept row:
     * a candidate is visited only when its coordinates, cut after the first level where they differ from [kept],
     * are [occupied] (`ExprEval.filteredRows`, SPEC-2026-10-10-02). Every coordinate sharing that cut shares the
     * verdict, so an unoccupied cut skips its whole subtree, and the next sibling worth visiting is read off the
     * occupied children rather than stepped to. [size] when nothing remains. [from] lies after [kept]'s own index,
     * as it does in a walk that resumes past the row it just kept.
     */
    fun nextUnskipped(from: Int, kept: List<Int>, occupied: OccupiedPrefixes): Int {
        if (from >= size) return size
        val digits = digitsAt(from)
        val base = scope.size
        while (true) {
            val level = digits.indices.firstOrNull { digits[it] != kept[base + it] } ?: return indexOf(digits)
            val parent = scope + digits.take(level)
            if (parent + digits[level] in occupied) return indexOf(digits)
            val sibling = occupied.nextChild(parent, digits[level])
            if (sibling != null && sibling <= widths[level]) {
                digits[level] = sibling
                digits.fill(1, level + 1)
                continue
            }
            var carry = level - 1
            while (carry >= 0 && digits[carry] == widths[carry]) carry--
            if (carry < 0) return size
            digits[carry]++
            digits.fill(1, carry + 1)
        }
    }

    private fun digitsAt(index: Int): IntArray {
        val digits = IntArray(widths.size)
        var rest = index
        for (level in widths.indices.reversed()) {
            digits[level] = rest % widths[level] + 1
            rest /= widths[level]
        }
        return digits
    }

    private fun indexOf(digits: IntArray): Int = digits.indices.fold(0) { index, level -> index * widths[level] + digits[level] - 1 }

    private companion object {
        fun indexOf(widths: IntArray, base: Int, coordinates: List<Int>): Int =
            widths.indices.fold(0) { index, level -> index * widths[level] + coordinates[base + level] - 1 }
    }
}

/** The coordinate prefixes some counted row occupies (`Document.sparseFilterPrefixes`), with each prefix's occupied
 *  children in order, so a sparse walk jumps to the next occupied sibling instead of stepping to it. */
internal class OccupiedPrefixes(private val prefixes: Set<List<Int>>) : Set<List<Int>> by prefixes {
    private val children: Map<List<Int>, IntArray> by lazy(LazyThreadSafetyMode.NONE) {
        val byParent = HashMap<List<Int>, MutableList<Int>>()
        for (prefix in prefixes) if (prefix.isNotEmpty()) byParent.getOrPut(prefix.dropLast(1)) { ArrayList() } += prefix.last()
        byParent.mapValues { (_, values) -> values.toIntArray().apply { sort() } }
    }

    /** The smallest occupied child of [parent] above [after], or null. */
    fun nextChild(parent: List<Int>, after: Int): Int? {
        val values = children[parent] ?: return null
        var low = 0
        var high = values.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (values[mid] <= after) low = mid + 1 else high = mid
        }
        return values.getOrNull(low)
    }
}
