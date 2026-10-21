package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The relevance indexes answer exactly what a scan of every entity answers. The oracle is each question's
 * definition, written here as the scan it replaces, over random entities of mixed lengths with wildcards at every
 * level; a fixed seed keeps a failure reproducible.
 */
class RelevanceIndexesTest {

    private val random = Random(20261021)
    private fun index() = if (random.nextInt(3) == 0) RelevantEntity.ALL else 1 + random.nextInt(3)
    private fun indices(maxLength: Int) = List(random.nextInt(maxLength + 1)) { index() }
    private fun reps(length: Int) = List(length) { 1 + random.nextInt(3) }
    private val entities = List(60) { RelevantEntity("/A/B/C/D", indices(4)) }

    @Test
    fun aRowIsAddressedByExactlyTheEntitiesAScanFinds() {
        val index = RowAddressIndex(entities)
        repeat(500) {
            val bound = reps(random.nextInt(5))
            val scanned = entities.filter { e ->
                bound.indices.all { i -> i >= e.indices.size || e.indices[i] == RelevantEntity.ALL || e.indices[i] == bound[i] }
            }
            assertEquals(scanned, index.addressing(bound), "bound $bound")
        }
    }

    @Test
    fun aTrieAgreesAndSelectsExactlyAsAScanDoes() {
        val lists = List(40) { indices(4) }
        val trie = IndexTrie().also { t -> lists.forEach(t::add) }
        repeat(500) {
            val reps = reps(random.nextInt(5))
            val agrees = lists.any { list ->
                (0 until minOf(list.size, reps.size)).all { l -> list[l] == RelevantEntity.ALL || list[l] == reps[l] }
            }
            assertEquals(agrees, trie.agreesWithin(reps), "agrees $reps")
            for (depth in 0..reps.size) {
                val selects = lists.any { list ->
                    list.size == depth && (0 until depth).all { l -> list[l] == RelevantEntity.ALL || list[l] == reps[l] }
                }
                assertEquals(selects, trie.selects(reps, depth), "selects $reps at $depth")
            }
        }
    }
}
