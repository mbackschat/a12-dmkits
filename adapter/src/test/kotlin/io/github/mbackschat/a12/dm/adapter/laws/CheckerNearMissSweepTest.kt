package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
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
 * **P4 also replays the syntax tests' helper inputs**, including negative rows that cannot be admitted seeds.
 * Their original partial models are kept beside separately completed hosts; neither class-set comparison
 * replaces the syntax-only acceptance, selected-family or positioned-report assertions R3a must preserve.
 *
 * **Floors per arm and on refusals**, because an empty ledger is the goal and must not be reachable by a sweep
 * that stopped comparing: each arm must admit its seeds and reach the message edits, and A12 must refuse inputs
 * that the checker then agrees on.
 */
class CheckerNearMissSweepTest {

    private val validator = RuleValidator()

    @Test
    fun classComparisonDoesNotExemptSpecificCodes() {
        for ((a, b) in listOf("MVK_POINT_IN_A12_MODEL" to "MVK_INVALID_WILDCARD", "A" to "B")) {
            assertEquals(listOf(null, null, "EXTRA", "WRONG-CLASS", "WRONG-CLASS", "WRONG-CLASS", "OVER-REJECT", "SILENT"),
                listOf(
                    checkerVerdict(emptySet(), emptySet()),
                    checkerVerdict(setOf(a), setOf(a)),
                    checkerVerdict(setOf(a), setOf(a, b)),
                    checkerVerdict(setOf(a, b), setOf(a)),
                    checkerVerdict(setOf(a), setOf(b)),
                    checkerVerdict(setOf(a), setOf(b, "C")),
                    checkerVerdict(emptySet(), setOf(a)),
                    checkerVerdict(setOf(a), emptySet()),
                ), "class-set relations for $a and $b")
        }
    }

    @Test
    fun pathGatesHaveOnlyLedgeredDifferences() {
        assertModelLedger(PathGatePopulation.rows(), 18, "path-gates")
    }

    @Test
    fun aggregatePathGatesHaveOnlyLedgeredDifferences() {
        assertModelLedger(PathGatePopulation.aggregateRows(), 18, "aggregate-path-gates")
    }

    @Test
    fun harvestedOperationsPreserveTheirKernelComparisonPopulation() {
        val clusters = sortedMapOf<String, Int>()
        val examples = mutableListOf<String>()
        var compared = 0
        fun compare(id: String, model: String, expected: Set<String>) {
            val a12 = kernelModelClasses(model)
            assertEquals(expected, a12, id)
            val ours = check(ModelSource(model)).findings.filter { it.justifiesRejection }.map { it.code }.toSet()
            compared++
            checkerVerdict(a12, ours)?.let { verdict ->
                val signature = key(verdict, a12, ours)
                clusters[signature] = clusters.getOrDefault(signature, 0) + 1
                examples += "$signature\t$id"
            }
        }
        for (row in OperationHarvest.checks()) {
            val id = row.get("id").asText()
            val original = row.get("model").asText()
            compare("original:$id", original, row.get("originalKernelClasses").map { it.asText() }.toSet())
            val completed = HarvestedCheckerPopulation.completed(original,
                row.get("hostClasses").map { it.asText() }.toSet())
            compare("completed:$id", completed, row.get("completedKernelClasses").map { it.asText() }.toSet())
        }
        for (row in OperationHarvest.laws()) {
            val expected = row.get("observedClasses").map { it.asText() }
            val assertion = row.get("assertion").asText()
            val claimed = row.get("classes").map { it.asText() }
            when (assertion) {
                "admitted" -> assertEquals(emptyList<String>(), expected)
                "contains" -> assertTrue(expected.containsAll(claimed) && claimed.isNotEmpty())
                "ordered" -> assertEquals(claimed, expected)
                else -> error("unknown harvested assertion: $assertion")
            }
            compare("law:${row.get("id").asText()}", row.get("model").asText(), expected.toSet())
        }
        assertEquals(419, compared, "183 original + 183 completed + 53 laws")
        val directory = File("build/checker-near-miss").apply { mkdirs() }
        File(directory, "operation-harvest.tsv").writeText(render(clusters))
        File(directory, "operation-harvest-examples.tsv").writeText(examples.joinToString("\n"))
        assertEquals(checkNotNull(javaClass.getResource("/checker-near-miss/operation-harvest.tsv")).readText(),
            render(clusters))
    }

    @Test
    fun numericNamesHaveOnlyLedgeredDifferences() {
        assertModelLedger(NumericNamePopulation.rows(), 27, "numeric-names")
    }

    @Test
    fun operationTypesHaveOnlyLedgeredDifferences() {
        assertModelLedger(OperationTypingPopulation.rows(), 48, "operation-types")
    }

    @Test
    fun fieldReadActionsHaveOnlyLedgeredDifferences() {
        assertFieldReadLedger(FieldReadPopulation.rows(), 22, "field-reads")
    }

    @Test
    fun fieldReadTokenBoundariesHaveOnlyLedgeredDifferences() {
        assertFieldReadLedger(FieldReadPopulation.boundaryRows(), 8, "field-read-boundaries")
    }

    @Test
    fun markedFieldReadsHaveOnlyLedgeredDifferences() {
        assertFieldReadLedger(FieldReadPopulation.markerRows(), 5, "field-read-markers")
    }

    @Test
    fun boundMarkedFieldReadsHaveOnlyLedgeredDifferences() {
        assertFieldReadLedger(FieldReadPopulation.boundMarkerRows(), 3, "field-read-bound-markers")
    }

    private fun assertFieldReadLedger(rows: List<FieldReadPopulation.Row>, count: Int, name: String) {
        assertEquals(count, rows.size)
        assertEquals(count, rows.map { it.rule.errorCondition() }.distinct().size)
        assertEquals(emptySet<String>(), kernelModelClasses(FieldReadPopulation.host))
        val clusters = sortedMapOf<String, Int>()
        val examples = mutableListOf<String>()
        val predictions = mutableListOf<String>()
        rows.forEach { row ->
            val json = validator.inject(FieldReadPopulation.host, row.rule)
            val a12 = kernelModelClasses(json)
            if (a12 != setOfNotNull(row.modelCode)) predictions += "${row.rule.errorCondition()}: $a12"
            val ours = check(ModelSource(json)).findings.filter { it.justifiesRejection }.map { it.code }.toSet()
            val verdict = checkerVerdict(a12, ours) ?: return@forEach
            val signature = key(verdict, a12, ours)
            clusters[signature] = clusters.getOrDefault(signature, 0) + 1
            examples += "$signature\t${row.rule.errorCondition()}"
        }
        val directory = File("build/checker-near-miss").apply { mkdirs() }
        File(directory, "$name.tsv").writeText(render(clusters))
        File(directory, "$name-examples.tsv").writeText(examples.joinToString("\n"))
        assertEquals(emptyList<String>(), predictions, "kernel predictions differed")
        assertEquals(checkNotNull(javaClass.getResource("/checker-near-miss/$name.tsv")).readText(), render(clusters))
    }

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

    @Test
    fun harvestedFieldReadsHaveOnlyLedgeredDifferences() {
        val sources = mapOf(
            "FilterWildcardTest.onlyAStarredRepeatableGroupInsideTheFilterIsReported" to 7,
            "FieldValueReadOperandTest.aStarredReadIsTheStarsClassWhereverTheBracketStands" to 13,
            "FieldValueReadOperandTest.theGatesAnswerInTheirOrderAfterTheReadersOwnClasses" to 19,
            "FieldValueReadOperandTest.theGatesReachEveryCarrierOfTheRead" to 11,
        )
        assertHarvestedLedger("harvested-field-reads", "field-read-harvest", sources)
    }

    @Test
    fun harvestedNumericKeysHaveOnlyLedgeredDifferences() {
        assertHarvestedLedger("harvested-numeric-keys", "numeric-key-harvest",
            mapOf("LiteralIndexKeyTest.aDigitSequenceKeyIsAnEntityName" to 18))
    }

    private fun assertHarvestedLedger(resource: String, ledgerName: String, sources: Map<String, Int>) {
        val rows = ObjectMapper().readTree(checkNotNull(javaClass.getResource(
            "/checker-near-miss/$resource.json")).readText())
        val count = sources.values.sum()
        assertEquals(sources.flatMap { (source, count) -> (1..count).map { "$source#$it" } }.toSet(),
            rows.map { it.get("id").asText() }.toSet())
        assertEquals(count, rows.size())
        val clusters = sortedMapOf<String, Int>()
        val predictions = mutableListOf<String>()
        var compared = 0
        fun compare(json: String): Set<String> {
            val a12 = kernelModelClasses(json)
            val ours = check(ModelSource(json)).findings.filter { it.justifiesRejection }.map { it.code }.toSet()
            compared++
            checkerVerdict(a12, ours)?.let { verdict ->
                val signature = key(verdict, a12, ours)
                clusters[signature] = clusters.getOrDefault(signature, 0) + 1
            }
            return a12
        }
        rows.forEach { row ->
            val id = row.get("id").asText()
            val original = row.get("model").asText()
            assertEquals(setOf("CK_MODEL_NOT_BUILDABLE"), compare(original), id)
            val a12 = compare(HarvestedCheckerPopulation.completed(original))
            val onlyCode = row.get("onlyCode")?.takeUnless { it.isNull }?.asText()
            val expected = row.get("classes").map { it.asText() }.toSet()
            if (a12.filter { onlyCode == null || it == onlyCode }.toSet() != expected)
                predictions += "$id: ${a12.sorted()}"
        }
        assertEquals(2 * count, compared, "both original and completed models reach O1")
        assertEquals(emptyList<String>(), predictions, "old expectations versus kernel")
        assertEquals(checkNotNull(javaClass.getResource("/checker-near-miss/$ledgerName.tsv")).readText(),
            render(clusters))
    }

    @Test
    fun scannerHostsHaveOnlyLedgeredDifferences() {
        val rows = HarvestedSyntaxPopulation.scannerRows()
        assertEquals(7, rows.size)
        rows.map { it.host.json }.distinct().forEach { assertEquals(emptySet<String>(), kernelModelClasses(it)) }
        val observations = rows.map { row ->
            val model = row.model()
            val a12 = kernelModelClasses(model)
            val ours = check(ModelSource(model)).findings.filter { it.justifiesRejection }.map { it.code }.toSet()
            "${row.id}\t${checkerVerdict(a12, ours) ?: "AGREE"}\t${a12.column()}\t${ours.column()}"
        }.joinToString("\n", postfix = "\n")
        File("build/checker-harvest/scanner.tsv").apply { parentFile.mkdirs(); writeText(observations) }
        assertEquals(checkNotNull(javaClass.getResource("/checker-near-miss/scanner.tsv")).readText(), observations)
    }

    @Test
    fun harvestedSyntaxFixturesHaveOnlyLedgeredDifferences() {
        val fixed = HarvestedSyntaxPopulation.fixedRows()
        val catalog = HarvestedSyntaxPopulation.catalogRows()
        assertEquals(77, fixed.size)
        assertEquals(324, catalog.size, "T1's live catalog denominator; re-harvest when its source changes")
        assertEquals(26, fixed.count { it.originalModel != null })
        val rows = fixed + catalog
        assertEquals(rows.size, rows.map { it.id }.distinct().size)
        val sourceCounts = mapOf(
            "ConditionShapeTest.theMeasuredRejectShapesAreRejectedWithTheCodeA12Gives" to 15,
            "ConditionShapeTest.theValueListInfixesParseInTheLanguageThatOwnsThemAndAsNamesInTheOther" to 18,
            "ConditionShapeTest.theRuleContextEntityParsesAsAReferenceInBothLanguages" to 3,
            "ConditionShapeTest.aSignStandsOnlyOnANumberLiteral" to 7,
            "ConditionShapeTest.onTheOperationCarrierASignStandsOnlyOnANumberLiteralToo" to 8,
            "ConditionCompletenessTest.textThatEndsOnAnInfixOperatorIsReportedIncomplete" to 10,
            "ConditionCompletenessTest.aTrailingConnectiveIsUnfinishedInEveryCaseTheLexerResolves" to 6,
            "ConditionCompletenessTest.textWithAnUnclosedDelimiterIsNotThisFamilysBusiness" to 7,
            "ConditionCompletenessTest.theOperatorIsRecognisedInTheModelsOwnConditionLanguage" to 2,
            "ConditionCompletenessTest.theFindingNamesTheMemberThatEndsEarly" to 1,
        )
        val expectedIdentities = sourceCounts.flatMap { (source, count) ->
            (1..count).map { ordinal -> "$source#$ordinal" }
        }.toSet()
        assertEquals(expectedIdentities, fixed.map { it.id }.toSet(), "every harvested source row survives")
        rows.map { it.host.json }.distinct().forEach { host ->
            assertEquals(emptySet<String>(), kernelModelClasses(host), "host control")
        }
        val clusters = sortedMapOf<String, Int>()
        val examples = mutableListOf<String>()
        var compared = 0
        fun compare(id: String, json: String) {
            val a12 = kernelModelClasses(json)
            val report = check(ModelSource(json))
            assertTrue(report.modelId != null, "checker did not read $id")
            val ours = report.findings.filter { it.justifiesRejection }.map { it.code }.toSet()
            compared++
            val verdict = checkerVerdict(a12, ours) ?: return
            val key = key(verdict, a12, ours)
            clusters[key] = clusters.getOrDefault(key, 0) + 1
            examples += "$key\t$id"
        }
        for (row in rows) {
            compare(row.id, row.model())
            row.originalModel?.let { compare("${row.id}:original", it) }
        }
        assertEquals(427, compared, "401 hosted rows and all 26 original models reach both oracles")
        val directory = File("build/checker-harvest").apply { mkdirs() }
        File(directory, "actual.tsv").writeText(render(clusters))
        File(directory, "examples.tsv").writeText(examples.joinToString("\n"))
        // Disposable enumeration checks the live catalog projection against the original helpers' harvest.
        ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(
            File(directory, "inputs.json"), rows.map { mapOf("id" to it.id, "text" to it.text,
                "language" to it.language.name, "entry" to it.entry.name) })
        assertEquals(checkNotNull(javaClass.getResource("/checker-near-miss/harvested.tsv")).readText(),
            render(clusters), "P4 baseline differs; actual keys and inputs are in ${directory.path}")
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
