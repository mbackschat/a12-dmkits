package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File

internal fun assertModelLedger(rows: List<ConditionCase>, count: Int, name: String) {
    assertEquals(count, rows.size)
    assertEquals(count, rows.map { it.id }.distinct().size)
    assertTrue(rows.any { it.expected == null }, "population retains admitted controls")
    val predictions = mutableListOf<String>()
    val clusters = sortedMapOf<String, Int>()
    val examples = mutableListOf<String>()
    rows.forEach { row ->
        val a12 = kernelModelClasses(row.model)
        if (a12 != setOfNotNull(row.expected)) predictions += "${row.id}: $a12"
        val ours = check(ModelSource(row.model)).findings.filter { it.justifiesRejection }.map { it.code }.toSet()
        val verdict = checkerVerdict(a12, ours) ?: return@forEach
        val signature = key(verdict, a12, ours)
        clusters[signature] = clusters.getOrDefault(signature, 0) + 1
        examples += "$signature\t${row.id}"
    }
    val directory = File("build/checker-near-miss").apply { mkdirs() }
    File(directory, "$name.tsv").writeText(render(clusters))
    File(directory, "$name-examples.tsv").writeText(examples.joinToString("\n"))
    assertEquals(emptyList<String>(), predictions, "kernel predictions differed")
    assertEquals(checkNotNull(CheckerNearMissSweepTest::class.java.getResource("/checker-near-miss/$name.tsv")).readText(), render(clusters))
}


private fun key(verdict: String, a12: Set<String>, ours: Set<String>) =
    listOf(verdict, a12.column(), ours.column()).joinToString("\t")

private fun Set<String>.column() = if (isEmpty()) "(none)" else sorted().joinToString(",")

private fun render(counts: Map<String, Int>): String =
    counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .joinToString("") { "${it.value}\t${it.key}\n" }
