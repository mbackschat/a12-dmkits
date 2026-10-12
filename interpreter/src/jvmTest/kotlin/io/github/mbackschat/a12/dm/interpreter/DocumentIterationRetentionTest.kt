package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.FilterWalkRowProvider
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DocumentIterationRetentionTest {

    @Test
    fun presenceAndDisplayReadsLeaveTheResolvedValueMemoAbsentUntilTheFirstValueRead() {
        val document = model(40).newDocument().field(AMOUNT, "1", 1, 1, 1)
        val context = document.computeContexts(CLAIMS).single()

        assertTrue(context.filled(AMOUNT))
        assertEquals("1", context.displayValue(AMOUNT))
        assertNull(context.retained("valCache"))

        val value = context.field(AMOUNT)
        assertSame(value, context.field(AMOUNT))
        assertEquals(setOf(AMOUNT), (context.retained("valCache") as Map<*, *>).keys)
    }

    @Test
    fun denseAndSparseFullValidationReuseStoredCoordinateTuples() {
        for (capacity in listOf(4, 40)) {
            val model = model(capacity)
            val document = model.newDocument().field(AMOUNT, "1", 1, 3, 1)
            val stored = document.instantiatedRows(CLAIMS).associateBy { it }

            val contexts = document.fullValidationContextsFor(CLAIMS) { model.declaredReps[it] ?: 1 }
            val coordinates = contexts.map { it.coordinates() }

            assertEquals((1..if (capacity == 4) 4 else 3).map { listOf(1, it) }, coordinates)
            for (coordinate in coordinates) stored[coordinate]?.let {
                assertSame(it, coordinate, "a real full-validation row must reuse its stored tuple")
            }
        }
    }

    @Test
    fun sparseValidationRetainsSortedPrimitiveOccupancy() {
        val model = model(40)
        val document = model.newDocument().field(AMOUNT, "1", 1, 3, 1)
        document.fullValidationContextsFor(CLAIMS) { model.declaredReps[it] ?: 1 }

        val occupancy = document.retained("cachedIndex")!!.retained("occupiedChildren") as Map<*, *>
        val children = (occupancy[2] as Map<*, *>)[listOf(1)]

        assertContentEquals(intArrayOf(1, 2, 3), assertIs<IntArray>(children))
    }

    @Test
    fun filterCandidatesReusePrivateCoordinatesWithoutPopulatingThePublicRepetitionMemo() {
        val document = model(40).newDocument().field(AMOUNT, "1", 1, 3, 1)
        val stored = document.instantiatedRows(CLAIMS).associateBy { it }
        val context = document.computeContexts(CLAIMS).first() as FilterWalkRowProvider

        val candidates = context.filterWalkRows(CLAIMS, 0, null)

        assertEquals(3, candidates.size)
        for (candidate in candidates) {
            val coordinate = candidate.coordinates()
            assertSame(stored.getValue(coordinate), coordinate)
            assertTrue((candidate.retained("repsCache") as Map<*, *>).isEmpty(),
                "building a candidate map must not memoize a copy of the row's own tuple")
        }
    }

    @Test
    fun freshEquivalentFilterPoliciesReuseOneGraphAcrossEveryRawCache() {
        val document = filterPolicyDocument()
        val context = document.computeContexts(CLAIMS).first() as FilterWalkRowProvider
        var first: Any? = null

        repeat(4) {
            val policy = policy(CLAIMS)
            val candidates = context.filterWalkRows(CLAIMS, 0, policy)
            assertEquals(101, candidates.size)
            assertTrue(context.sparseFilterPrefixes(CLAIMS, 0, policy)!!.isNotEmpty())
            first?.let { assertSame(it, candidates, "equivalent selection must reuse the candidate graph") }
            first = candidates
            assertEquals(listOf(1, 1, 1), document.filterCacheSizes())
        }
    }

    @Test
    fun changedFilterPoliciesReplaceEveryGraphAndMutationInvalidatesTheirBounds() {
        val document = filterPolicyDocument()
        val context = document.computeContexts(CLAIMS).first() as FilterWalkRowProvider

        for (group in listOf(CLAIMS, FOREIGN, CLAIMS)) {
            val policy = policy(group)
            assertEquals(if (group == CLAIMS) 101 else 129, context.filterWalkRows(CLAIMS, 0, policy).size)
            context.sparseFilterPrefixes(CLAIMS, 0, policy)
            assertEquals(listOf(1, 1, 1), document.filterCacheSizes())
        }

        val foreign = policy(FOREIGN)
        assertEquals(129, context.filterWalkRows(CLAIMS, 0, foreign).size)
        context.sparseFilterPrefixes(CLAIMS, 0, foreign)
        document.field(FOREIGN_AMOUNT, "1", 1, 140, 1)

        assertEquals(140, context.filterWalkRows(CLAIMS, 0, foreign).size)
        context.sparseFilterPrefixes(CLAIMS, 0, foreign)
        assertEquals(listOf(1, 1, 1), document.filterCacheSizes())
    }

    @Test
    fun nullAndExplicitAllGroupPoliciesShareTheSameEffectiveSelection() {
        val document = filterPolicyDocument()
        val context = document.computeContexts(CLAIMS).first() as FilterWalkRowProvider
        val all = context.filterWalkRows(CLAIMS, 0, null)
        context.sparseFilterPrefixes(CLAIMS, 0, null)

        val explicit = policy("/Register", CLAIMS, FOREIGN)
        assertSame(all, context.filterWalkRows(CLAIMS, 0, explicit))
        context.sparseFilterPrefixes(CLAIMS, 0, explicit)
        assertEquals(listOf(1, 1, 1), document.filterCacheSizes())
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun observerCountsEveryFilterCacheMapEntry() {
        val document = filterPolicyDocument()
        document.computeContexts(CLAIMS)
        val index = document.retained("cachedIndex")!!
        for ((owner, name, value) in listOf(
            Triple(document, "filterWalkCache", emptyList<Any>()),
            Triple(index, "highestRepetitionsByCount", intArrayOf()),
            Triple(index, "filterPrefixesByCount", emptySet<Any>()),
        )) {
            var before = 0
            document.observeCacheEntries { _, _, entries -> before = entries }
            val cache = owner.retained(name) as MutableMap<Any?, Any?>
            cache[Any()] = value
            var after = 0
            document.observeCacheEntries { _, _, entries -> after = entries }
            assertEquals(before + 1, after, name)
            cache.clear()
        }
    }

    private fun model(capacity: Int) = ModelDef(
        fields = listOf(FieldDef(AMOUNT, Kind.NUMBER, scale = 0)),
        rules = emptyList(),
        repeatableGroups = setOf(CLAIMS),
        declaredReps = mapOf(CLAIMS to capacity),
    )

    private fun filterPolicyDocument(): Document = ModelDef(
        fields = listOf(FieldDef(AMOUNT, Kind.NUMBER, scale = 0), FieldDef(FOREIGN_AMOUNT, Kind.NUMBER, scale = 0)),
        rules = emptyList(),
        repeatableGroups = setOf(CLAIMS, FOREIGN),
        declaredReps = mapOf(CLAIMS to 200, FOREIGN to 200),
    ).newDocument().field(AMOUNT, "1", 1, 101, 1).field(FOREIGN_AMOUNT, "1", 1, 129, 1)

    private fun policy(vararg groups: String): (String) -> Boolean {
        val admitted = groups.toSet()
        return { it in admitted }
    }

    private fun Document.filterCacheSizes(): List<Int> {
        val index = retained("cachedIndex")!!
        return listOf(
            (retained("filterWalkCache") as Map<*, *>).size,
            (index.retained("highestRepetitionsByCount") as Map<*, *>).size,
            (index.retained("filterPrefixesByCount") as Map<*, *>).size,
        )
    }

    private fun Any.retained(name: String): Any? =
        javaClass.getDeclaredField(name).also { it.isAccessible = true }.get(this)

    @Suppress("UNCHECKED_CAST")
    private fun Any.coordinates(): List<Int> = retained("rowReps") as List<Int>

    private companion object {
        const val CLAIMS = "/Register/Claims"
        const val AMOUNT = "$CLAIMS/Amount"
        const val FOREIGN = "/Register/Foreign"
        const val FOREIGN_AMOUNT = "$FOREIGN/Amount"
    }
}
