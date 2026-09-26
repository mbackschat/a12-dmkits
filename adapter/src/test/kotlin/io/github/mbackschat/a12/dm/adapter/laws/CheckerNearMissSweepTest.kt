package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.NearMissPopulation.Arm
import io.github.mbackschat.a12.dm.adapter.laws.NearMissPopulation.Edit
import io.github.mbackschat.a12.dm.adapter.laws.NearMissPopulation.Family
import io.github.mbackschat.a12.dm.adapter.laws.NearMissPopulation.Seed
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.core.Severity
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every seed rule, and single edits of it, through A12 and the checker, with every disagreement counted under a
 * cluster key in a committed ledger: milestone M1 of [CONFORMANCE-PLAN](../../../../../../../../../../../docs/CONFORMANCE-PLAN.md).
 *
 * **This replaces discovery by hand.** Each hand grid measured one construct and uncovered its neighbours, so a
 * fix never closed a class for long. Here the neighbours are generated, by [NearMissPopulation]. A seed that A12
 * does not admit as written is dropped and listed in the report with the classes it drew, since its edits are near
 * misses of nothing. That includes a seed whose host needs a resolver for a referenced model, which the
 * single-file probe does not supply.
 *
 * **The ledger holds cluster keys and their COUNTS, never mutants.** A key is `verdict · A12's classes · the
 * checker's classes`, the verdict from [checkerVerdict], and the edit is left out because one mechanism shows
 * under many edits. The count is locked with the key, so a change that grows a cluster fails the run as surely as
 * one that opens a new key, and a fix that shrinks one fails it until the row is updated. The live run writes
 * every key with its count and three example mutants to `build/checker-near-miss/report.tsv`, so a batch starts
 * from the largest cluster and a discriminating example of it.
 *
 * **Floors per arm and on refusals**, because an empty ledger is the goal and must not be reachable by a sweep
 * that stopped comparing: each arm must admit its seeds and reach the message edits, and A12 must refuse inputs
 * that the checker then agrees on.
 */
class CheckerNearMissSweepTest {

    private val validator = RuleValidator()

    @Test
    fun theCheckerDisagreesWithA12OnlyWhereTheLedgerSays() {
        val tally = Tally()
        for (seed in NearMissPopulation.seeds()) {
            val asWritten = a12Classes(seed.host, seed.rule)
            if (asWritten.isNotEmpty()) {
                tally.dropped += "${seed.label}\t${asWritten.sorted().joinToString(",")}"
                continue
            }
            tally.admitted[seed.arm] = tally.admitted.getValue(seed.arm) + 1
            tally.compare(seed, Edit("as written", seed.rule, Family.AS_WRITTEN), asWritten)
            for (edit in NearMissPopulation.edits(seed)) {
                tally.compare(seed, edit, a12Classes(seed.host, edit.rule))
            }
        }
        REPORT.apply { parentFile.mkdirs() }.writeText(tally.report())
        DIVERGENT.writeText(tally.divergent())

        val live = tally.counts()
        val ledger = ledger()
        if (live == ledger) ACTUAL.delete() else ACTUAL.apply { parentFile.mkdirs() }.writeText(render(live))

        // Floors, measured on this class's run of 2026-09-26 and set below it. Each answers whether one part of the
        // sweep ran at all, so a lost arm, a probe A12 stopped refusing, or a checker that stopped reading cannot
        // pass as agreement.
        assertTrue(tally.admitted.getValue(Arm.COMMITTED) >= 30, "committed seeds admitted: ${tally.admitted}")
        assertTrue(tally.admitted.getValue(Arm.CATALOG) >= 100, "catalog seeds admitted: ${tally.admitted}")
        assertTrue(tally.compared >= 4000, "the sweep compared only ${tally.compared} inputs")
        assertTrue(tally.compared(Family.MESSAGE) >= 150, "only ${tally.compared(Family.MESSAGE)} message edits were compared")
        assertTrue(tally.compared(Family.TRUNCATION) >= 1000, "only ${tally.compared(Family.TRUNCATION)} truncations were compared")
        assertTrue(tally.compared(Family.SEPARATOR) >= 2000, "only ${tally.compared(Family.SEPARATOR)} separator edits were compared")
        assertTrue(tally.a12Refused >= 4000, "A12 refused only ${tally.a12Refused} inputs")
        assertTrue(tally.agreedRefusals >= 4000, "the checker agreed on only ${tally.agreedRefusals} refusals")

        assertEquals(render(ledger), render(live),
            "cluster counts differ from the ledger (live counts in ${ACTUAL.path}, examples in ${REPORT.path}); " +
                "a fix updates or deletes its rows, a new key is a new defect")
    }

    /** A12's classes for the host with [rule] injected: its ERROR findings, as the checker's side reads its own. */
    private fun a12Classes(host: String, rule: DraftRule): Set<String> =
        validator.check(host, rule).diagnostics().filter { it.severity() == Severity.ERROR }
            .map { it.ledgerClass() }.toSet()

    /** What one run found: the admitted seeds per arm, the comparisons, the refusals and the clusters. */
    private inner class Tally {
        val admitted = Arm.entries.associateWith { 0 }.toMutableMap()
        val dropped = mutableListOf<String>()
        val clusters = sortedMapOf<String, MutableList<String>>()
        var compared = 0
        val comparedByFamily = Family.entries.associateWith { 0 }.toMutableMap()
        var a12Refused = 0
        var agreedRefusals = 0

        fun compare(seed: Seed, edit: Edit, a12: Set<String>) {
            val report = check(ModelSource(validator.inject(seed.host, edit.rule)))
            check(report.modelId != null) { "the checker did not read ${seed.label} · ${edit.label}: ${report.diagnostics}" }
            val ours = report.findings.filter { it.justifiesRejection }.map { it.code }.toSet()
            compared++
            comparedByFamily[edit.family] = comparedByFamily.getValue(edit.family) + 1
            if (a12.isNotEmpty()) a12Refused++
            val verdict = checkerVerdict(a12, ours)
            if (verdict == null) {
                if (a12.isNotEmpty()) agreedRefusals++
                return
            }
            clusters.getOrPut(key(verdict, a12, ours)) { mutableListOf() } +=
                "${edit.family} · ${seed.label} · ${edit.label} · ${edit.rule.errorCondition()}"
        }

        fun counts(): Map<String, Int> = clusters.mapValues { it.value.size }

        fun compared(family: Family): Int = comparedByFamily.getValue(family)

        /** Every divergent input under its cluster key, for a batch that needs more than the report's three. */
        fun divergent(): String = clusters.entries.joinToString("") { (key, inputs) ->
            inputs.joinToString("") { "$key\t$it\n" }
        }

        fun report(): String =
            "# seeds admitted $admitted, dropped ${dropped.size}; inputs compared $compared $comparedByFamily, " +
                "A12 refused $a12Refused, the checker agreed on $agreedRefusals; clusters " +
                "${clusters.size}, divergent ${clusters.values.sumOf { it.size }}\n" +
                clusters.entries.sortedByDescending { it.value.size }.joinToString("") {
                    "${it.value.size}\t${it.key}\t${it.value.take(3).joinToString(" || ")}\n"
                } +
                dropped.joinToString("") { "# dropped\t$it\n" }
    }

    private fun key(verdict: String, a12: Set<String>, ours: Set<String>) =
        listOf(verdict, a12.column(), ours.column()).joinToString("\t")

    /** A class set as a ledger column; the empty set is spelled, so no row ends in a tab an editor could eat. */
    private fun Set<String>.column() = if (isEmpty()) NONE else sorted().joinToString(",")

    private fun render(counts: Map<String, Int>): String =
        counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .joinToString("") { "${it.value}\t${it.key}\n" }

    /** The committed ledger: each row a count, then its cluster key. */
    private fun ledger(): Map<String, Int> =
        checkNotNull(javaClass.getResource(LEDGER)) { "missing $LEDGER" }.readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { row -> row.substringAfter('\t') to row.substringBefore('\t').toInt() }

    private companion object {
        const val LEDGER = "/checker-near-miss/clusters.tsv"
        val ACTUAL = File("build/checker-near-miss/actual.tsv")
        val REPORT = File("build/checker-near-miss/report.tsv")
        val DIVERGENT = File("build/checker-near-miss/divergent.tsv")
        const val NONE = "(none)"
    }
}
