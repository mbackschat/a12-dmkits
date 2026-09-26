package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.core.ModelIndex
import io.github.mbackschat.a12.dm.core.Severity
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.readText
import kotlin.io.path.relativeTo

/**
 * Every seed rule, and single edits of it, through A12 and the checker, with every disagreement counted under a
 * cluster key in a committed ledger: milestone M1 of [CONFORMANCE-PLAN](../../../../../../../../../../../docs/CONFORMANCE-PLAN.md).
 *
 * **This replaces discovery by hand.** Each hand grid measured one construct and uncovered its neighbours, so a
 * fix never closed a class for long. Here the neighbours are generated. The seeds come from two ARMS:
 *
 * - every rule of the committed single-file models ([CorpusFiles.committedModels]), copied as a probe beside
 *   the rule it copies. The probe carries the rule's target, condition, severity and messages; a variant filter
 *   is not copied, so the probe is checked in every variant;
 * - every catalog `validExample` but those of the three consumer-SPI operators, whose conditions name host code
 *   rather than the model ([CatalogHosting.SPECIALISTS]), hosted on the fixture and error field
 *   [CatalogHosting] chooses.
 *
 * Each seed is edited one token at a time: a token deleted, doubled or swapped with the next; an operator or a
 * model name substituted; a connective, bracket, comma, star or sign inserted. On a rule with a message, a dollar,
 * an unresolvable parameter, `$$`, a tab or a supplementary character is appended to its first message. A seed
 * that A12 does not admit as written is dropped and listed in the report with the classes it drew, since its edits
 * are near misses of nothing. That includes a seed whose host needs a resolver for a referenced model, which the
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
 *
 * **Deterministic.** A seed's edits are sampled with `java.util.Random`, whose sequence for a seed is specified by
 * its documentation, keyed by the seed's own label, so adding a model elsewhere does not reshuffle the edits of
 * every seed after it.
 */
class CheckerNearMissSweepTest {

    private val validator = RuleValidator()

    @Test
    fun theCheckerDisagreesWithA12OnlyWhereTheLedgerSays() {
        val tally = Tally()
        for (seed in seeds()) {
            val asWritten = a12Classes(seed.host, seed.rule)
            if (asWritten.isNotEmpty()) {
                tally.dropped += "${seed.label}\t${asWritten.sorted().joinToString(",")}"
                continue
            }
            tally.admitted[seed.arm] = tally.admitted.getValue(seed.arm) + 1
            tally.compare(seed, Edit("as written", seed.rule, isMessageEdit = false), asWritten)
            for (edit in edits(seed)) {
                tally.compare(seed, edit, a12Classes(seed.host, edit.rule))
            }
        }
        REPORT.apply { parentFile.mkdirs() }.writeText(tally.report())

        val live = tally.counts()
        val ledger = ledger()
        if (live == ledger) ACTUAL.delete() else ACTUAL.apply { parentFile.mkdirs() }.writeText(render(live))

        // Floors, measured on this class's run of 2026-09-26 and set below it. Each answers whether one part of the
        // sweep ran at all, so a lost arm, a probe A12 stopped refusing, or a checker that stopped reading cannot
        // pass as agreement.
        assertTrue(tally.admitted.getValue(Arm.COMMITTED) >= 30, "committed seeds admitted: ${tally.admitted}")
        assertTrue(tally.admitted.getValue(Arm.CATALOG) >= 100, "catalog seeds admitted: ${tally.admitted}")
        assertTrue(tally.compared >= 4000, "the sweep compared only ${tally.compared} inputs")
        assertTrue(tally.messageInputs >= 150, "only ${tally.messageInputs} message edits were compared")
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
        var messageInputs = 0
        var a12Refused = 0
        var agreedRefusals = 0

        fun compare(seed: Seed, edit: Edit, a12: Set<String>) {
            val report = check(ModelSource(validator.inject(seed.host, edit.rule)))
            check(report.modelId != null) { "the checker did not read ${seed.label} · ${edit.label}: ${report.diagnostics}" }
            val ours = report.findings.filter { it.justifiesRejection }.map { it.code }.toSet()
            compared++
            if (edit.isMessageEdit) messageInputs++
            if (a12.isNotEmpty()) a12Refused++
            val verdict = checkerVerdict(a12, ours)
            if (verdict == null) {
                if (a12.isNotEmpty()) agreedRefusals++
                return
            }
            clusters.getOrPut(key(verdict, a12, ours)) { mutableListOf() } +=
                "${seed.label} · ${edit.label} · ${edit.rule.errorCondition()}"
        }

        fun counts(): Map<String, Int> = clusters.mapValues { it.value.size }

        fun report(): String =
            "# seeds admitted $admitted, dropped ${dropped.size}; inputs compared $compared, message edits " +
                "$messageInputs, A12 refused $a12Refused, the checker agreed on $agreedRefusals; clusters " +
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

    private fun seeds(): List<Seed> = committedRuleSeeds() + catalogSeeds()

    private fun committedRuleSeeds(): List<Seed> {
        val root = CorpusFiles.repoRoot()
        return CorpusFiles.committedModels().flatMap { file ->
            val host = file.readText()
            rulesWithGroupPaths(MAPPER.readTree(host)).map { (groupPath, rule) ->
                val body = rule.path("Rule")
                Seed(
                    Arm.COMMITTED,
                    "${file.relativeTo(root)}#$groupPath/${rule.path("name").asText()}",
                    host,
                    DraftRule.builder().groupPath(groupPath).name(PROBE)
                        .errorEntityRelPath(body.path("errorEntityRelPath").asText())
                        .errorCondition(body.path("errorCondition").asText()).errorCode(PROBE_CODE)
                        .severity(body.path("severity").asText("ERROR"))
                        .messages(body.path("errorMessage").map {
                            LocalizedText(it.path("locale").asText(), it.path("text").asText())
                        })
                        .build(),
                )
            }
        }
    }

    private fun catalogSeeds(): List<Seed> = OperatorCatalog.load().entries()
        .filter { it.validExample() != null && it.id() !in CatalogHosting.SPECIALISTS }
        .map { entry ->
            val fixture = CatalogHosting.fixtureOf(entry)
            val condition = entry.validExample().dsl()
            Seed(
                Arm.CATALOG,
                "catalog#${entry.id()}",
                fixture.json,
                DraftRule.builder().groupPath(fixture.ruleGroup).name(PROBE)
                    .errorEntityRelPath(fixture.errorEntityRelPathOf(condition)).errorCondition(condition)
                    .errorCode(PROBE_CODE).severity("ERROR").build(),
            )
        }

    /** A deterministic sample of [seed]'s single edits, distinct from each other and from the seed itself. */
    private fun edits(seed: Seed): List<Edit> {
        val condition = seed.rule.errorCondition()
        val candidates = LinkedHashMap<String, String>()
        conditionEdits(condition, ModelNames.of(seed.host)).forEach { (label, text) ->
            if (text != condition) candidates.putIfAbsent(text, label)
        }
        val conditionEdits = candidates.entries.shuffled(java.util.Random(seed.label.hashCode().toLong()))
            .take(CONDITION_EDITS)
            .map { (text, label) -> Edit(label, seed.rule.withErrorCondition(text), isMessageEdit = false) }
        val first = seed.rule.messages().firstOrNull() ?: return conditionEdits
        val messageEdits = MESSAGE_APPENDIXES.map { (label, appended) ->
            val messages = listOf(LocalizedText(first.locale(), first.text() + appended)) + seed.rule.messages().drop(1)
            Edit(label, seed.rule.withMessages(messages), isMessageEdit = true)
        }
        return conditionEdits + messageEdits
    }

    private fun conditionEdits(text: String, names: ModelNames): List<Pair<String, String>> {
        val tokens = TOKEN.findAll(text).filterNot { it.value.startsWith(";;") }.toList()
        val out = mutableListOf<Pair<String, String>>()
        tokens.forEachIndexed { i, token ->
            val at = token.range
            out += "delete `${token.value}`" to text.removeRange(at)
            out += "double `${token.value}`" to text.substring(0, at.last + 1) + " " + token.value + text.substring(at.last + 1)
            tokens.getOrNull(i + 1)?.let { next ->
                out += "swap `${token.value}` `${next.value}`" to text.substring(0, at.first) + next.value +
                    text.substring(at.last + 1, next.range.first) + token.value + text.substring(next.range.last + 1)
            }
            (SUBSTITUTES[token.value].orEmpty() + names.substitutesFor(token.value)).forEach { other ->
                out += "`${token.value}` → `$other`" to text.replaceRange(at, other)
            }
            INSERTS.forEach { inserted ->
                out += "insert `$inserted` before `${token.value}`" to
                    text.substring(0, at.first) + inserted + " " + text.substring(at.first)
            }
        }
        INSERTS.forEach { inserted -> out += "append `$inserted`" to "$text $inserted" }
        return out
    }

    private enum class Arm { COMMITTED, CATALOG }

    /** One seed: its arm, a label unique across both arms, its host model, and the probe rule. */
    private class Seed(val arm: Arm, val label: String, val host: String, val rule: DraftRule)

    private class Edit(val label: String, val rule: DraftRule, val isMessageEdit: Boolean)

    /** The host's field and group names, so a name edit can swap a field for a group or for an unresolvable name. */
    private class ModelNames(val fields: Set<String>, val groups: Set<String>) {
        fun substitutesFor(token: String): List<String> = when (token) {
            in fields -> listOfNotNull(UNRESOLVABLE, groups.firstOrNull())
            in groups -> listOfNotNull(UNRESOLVABLE, fields.firstOrNull())
            else -> emptyList()
        }

        companion object {
            fun of(host: String): ModelNames {
                val index = ModelIndex.ofSelfContained(host)
                return ModelNames(
                    index.fieldPaths().map(index::nameOf).toList().toSortedSet(),
                    index.groupPaths().map(index::nameOf).toList().toSortedSet(),
                )
            }
        }
    }

    /**
     * Each rule element of the model with the absolute path of the group that declares it. Read off the DM-JSON
     * because no kit enumerates a model's rules yet (DEV-API-GAPS DG116).
     */
    private fun rulesWithGroupPaths(root: JsonNode): List<Pair<String, JsonNode>> {
        val found = mutableListOf<Pair<String, JsonNode>>()
        fun walk(group: JsonNode, path: String) {
            group.path("Group").path("elements").forEach { element ->
                when (element.path("type").asText()) {
                    "Rule" -> found += path to element
                    "Group" -> walk(element, "$path/${element.path("name").asText()}")
                }
            }
        }
        root.path("content").path("modelRoot").path("rootGroups").forEach { walk(it, "/${it.path("name").asText()}") }
        return found
    }

    /** The committed ledger: each row a count, then its cluster key. */
    private fun ledger(): Map<String, Int> =
        checkNotNull(javaClass.getResource(LEDGER)) { "missing $LEDGER" }.readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { row -> row.substringAfter('\t') to row.substringBefore('\t').toInt() }

    private companion object {
        const val LEDGER = "/checker-near-miss/clusters.tsv"
        val ACTUAL = File("build/checker-near-miss/actual.tsv")
        val REPORT = File("build/checker-near-miss/report.tsv")
        const val PROBE = "NearMissProbe"
        const val PROBE_CODE = "NEAR_MISS"
        const val UNRESOLVABLE = "Nope"
        const val NONE = "(none)"

        /** How many condition edits each seed contributes; its message edits come on top. */
        const val CONDITION_EDITS = 30

        val MAPPER = ObjectMapper()

        /** A string or a comment whole, a number, a name, a two-character operator, or any other one character. */
        val TOKEN = Regex("\"[^\"]*\"|;;[^\\n]*|\\d+(?:\\.\\d+)?|[\\p{L}_][\\p{L}\\p{N}_]*|\\.\\.|<=|>=|<>|!=|==|->|\\S")

        val SUBSTITUTES: Map<String, List<String>> = mapOf(
            "And" to listOf("Or"), "Or" to listOf("And"), "Und" to listOf("Oder"), "Oder" to listOf("Und"),
            "==" to listOf("!="), "!=" to listOf("=="), "=" to listOf("<>"), "<>" to listOf("="),
            ">" to listOf(">=", "<"), "<" to listOf("<=", ">"), ">=" to listOf(">"), "<=" to listOf("<"),
            "+" to listOf("-", "*"), "-" to listOf("+"), "*" to listOf("/"), "/" to listOf("*"),
        )

        val INSERTS = listOf("And", "(", ")", ",", "*", "-")

        val MESSAGE_APPENDIXES = listOf(
            "append a lone `$`" to " $",
            "append an unresolvable parameter" to " \$Nope\$",
            "append `$$`" to " $$",
            "append a tab" to "\t",
            "append a supplementary character" to " 😀",
        )
    }
}
