package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.random.Random

/**
 * Every seed rule, and single edits of it, through A12 and the checker, with every disagreement filed under a
 * cluster key in a committed ledger: milestone M1 of [CONFORMANCE-PLAN](../../../../../../../../../../../docs/CONFORMANCE-PLAN.md).
 *
 * **This replaces discovery by hand.** Each hand grid measured one construct and uncovered its neighbours, so a
 * fix never closed a class for long. Here the neighbours are generated. The seeds are every rule in the three
 * committed model trees and every catalog `validExample`, hosted as the N7 guard hosts it ([CatalogHosting]).
 * Each seed is injected as a probe rule beside its host's own rules, and edited one token at a time: a token
 * deleted, doubled or swapped with the next; an operator or a model name substituted; a connective, bracket,
 * comma, star or sign inserted; and on a rule with a message, a parameter, dollar, tab or supplementary
 * character appended to it. A seed A12 refuses as written is dropped, since its edits are near misses of
 * nothing.
 *
 * **The ledger holds cluster keys, never mutants.** A key is `verdict · A12's classes · the checker's classes`,
 * the verdict from [checkerVerdict]. The edit is left out of the key because one mechanism shows under many
 * edits. A new key fails the run as a new defect, and a vanished key fails it until its row is deleted, which
 * is the path grid's mechanism ([CheckerPathGridAgreementTest]). The live run writes every key with its count
 * and three example mutants to `build/checker-near-miss/report.tsv`, so a batch starts from the largest cluster
 * and a discriminating example of it.
 *
 * **Deterministic by construction.** A seed's edits are sampled with a random source keyed by the seed's own
 * label, not by its position, so adding a model elsewhere does not reshuffle the edits of every seed after it.
 */
class CheckerNearMissSweepTest {

    private val validator = RuleValidator()

    @Test
    fun theCheckerDisagreesWithA12OnlyWhereTheLedgerSays() {
        val clusters = sortedMapOf<String, MutableList<String>>()
        var admittedSeeds = 0
        var compared = 0
        for (seed in seeds()) {
            if (a12Classes(seed.host, seed.rule).isNotEmpty()) {
                continue
            }
            admittedSeeds++
            for (edit in listOf(Edit("as written", seed.rule)) + edits(seed)) {
                val a12 = a12Classes(seed.host, edit.rule)
                val ours = check(ModelSource(validator.inject(seed.host, edit.rule))).findings
                    .filter { it.justifiesRejection }.map { it.code }.toSet()
                compared++
                val verdict = checkerVerdict(a12, ours) ?: continue
                val key = listOf(verdict, a12.sorted().joinToString(","), ours.sorted().joinToString(","))
                    .joinToString("\t")
                clusters.getOrPut(key) { mutableListOf() } +=
                    "${seed.label} · ${edit.label} · ${edit.rule.errorCondition()}"
            }
        }
        // Floors, not counts: they answer whether the sweep reached both engines at all (measured 2026-09-26 in
        // this class's first run, recorded in CONFORMANCE-PLAN's results table).
        assertTrue(admittedSeeds >= 100, "only $admittedSeeds seeds were admitted as written")
        assertTrue(compared >= 2000, "the sweep compared only $compared inputs")

        val live = clusters.keys.toCollection(LinkedHashSet())
        val ledger = ledger()
        if (live != ledger) {
            ACTUAL.apply { parentFile.mkdirs() }.writeText(live.joinToString("\n", postfix = "\n"))
        }
        REPORT.apply { parentFile.mkdirs() }.writeText(
            "# seeds admitted $admittedSeeds, inputs compared $compared, clusters ${clusters.size}\n" +
                clusters.entries.sortedByDescending { it.value.size }.joinToString("\n", postfix = "\n") {
                    "${it.value.size}\t${it.key}\t${it.value.take(3).joinToString(" || ")}"
                },
        )
        assertEquals(emptyList<String>(), (live - ledger).toList(),
            "clusters the ledger does not list (live keys in ${ACTUAL.path}, counts and examples in ${REPORT.path})")
        assertEquals(emptyList<String>(), (ledger - live).toList(),
            "ledger rows no cluster fills any more: delete them")
    }

    /** A12's classes for the host with [rule] injected; empty where A12 admits it. */
    private fun a12Classes(host: String, rule: DraftRule): Set<String> = validator.check(host, rule).let { result ->
        if (result.ok()) emptySet() else result.diagnostics().map { it.ledgerClass() }.toSet()
    }

    private fun seeds(): List<Seed> = committedRuleSeeds() + catalogSeeds()

    /** Every rule of the committed trees, re-declared as a probe beside the rule it copies. */
    private fun committedRuleSeeds(): List<Seed> = committedModels().flatMap { file ->
        val host = file.readText()
        val root = runCatching { MAPPER.readTree(host) }.getOrNull() ?: return@flatMap emptyList()
        val names = ModelNames.of(root)
        rulesWithGroupPaths(root).map { (groupPath, rule) ->
            Seed(
                "${file.name}#${rule.path("name").asText()}",
                host,
                DraftRule(groupPath, PROBE, rule.path("Rule").path("errorEntityRelPath").asText(),
                    rule.path("Rule").path("errorCondition").asText(), PROBE_CODE,
                    rule.path("Rule").path("severity").asText("ERROR"),
                    rule.path("Rule").path("errorMessage").map {
                        LocalizedText(it.path("locale").asText(), it.path("text").asText())
                    }),
                names,
            )
        }
    }

    /** Every ordinary catalog example, hosted where N7 proves it admitted. */
    private fun catalogSeeds(): List<Seed> = OperatorCatalog.load().entries()
        .filter { it.validExample() != null && it.id() !in CatalogHosting.SPECIALISTS }
        .map { entry ->
            val fixture = CatalogHosting.fixtureOf(entry)
            val condition = entry.validExample().dsl()
            Seed(
                "catalog#${entry.id()}",
                fixture.json,
                DraftRule(fixture.ruleGroup, PROBE, "../${fixture.errorFieldOf(condition)}", condition, PROBE_CODE,
                    "ERROR"),
                ModelNames.of(MAPPER.readTree(fixture.json)),
            )
        }

    /** A deterministic sample of [seed]'s single edits, distinct from each other and from the seed itself. */
    private fun edits(seed: Seed): List<Edit> {
        val condition = seed.rule.errorCondition()
        val candidates = LinkedHashMap<String, String>()
        conditionEdits(condition, seed.names).forEach { (label, text) ->
            if (text != condition) candidates.putIfAbsent(text, label)
        }
        val conditionEdits = candidates.entries.shuffled(Random(seed.label.hashCode())).take(CONDITION_EDITS)
            .map { (text, label) -> Edit(label, seed.rule.withCondition(text)) }
        val first = seed.rule.messages().firstOrNull() ?: return conditionEdits
        val messageEdits = MESSAGE_APPENDIXES.map { (label, appended) ->
            Edit(label, seed.rule.withMessages(listOf(LocalizedText(first.locale(), first.text() + appended)) +
                seed.rule.messages().drop(1)))
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

    /** One seed: a label, its host model, the probe rule, and the host's element names for substitution. */
    private class Seed(val label: String, val host: String, val rule: DraftRule, val names: ModelNames)

    private class Edit(val label: String, val rule: DraftRule)

    /** The host's field and group names, so a name edit can swap a field for a group or for nothing at all. */
    private class ModelNames(val fields: Set<String>, val groups: Set<String>) {
        fun substitutesFor(token: String): List<String> = when (token) {
            in fields -> listOfNotNull(UNRESOLVABLE, groups.firstOrNull())
            in groups -> listOfNotNull(UNRESOLVABLE, fields.firstOrNull())
            else -> emptyList()
        }

        companion object {
            fun of(root: JsonNode): ModelNames {
                val fields = sortedSetOf<String>()
                val groups = sortedSetOf<String>()
                fun walk(node: JsonNode) {
                    when (node.path("type").asText()) {
                        "Field" -> fields += node.path("name").asText()
                        "Group" -> groups += node.path("name").asText()
                    }
                    node.elements().forEach(::walk)
                }
                walk(root)
                return ModelNames(fields, groups)
            }
        }
    }

    private fun DraftRule.withCondition(condition: String) =
        DraftRule(groupPath(), name(), errorEntityRelPath(), condition, errorCode(), severity(), messages())

    private fun DraftRule.withMessages(messages: List<LocalizedText>) =
        DraftRule(groupPath(), name(), errorEntityRelPath(), errorCondition(), errorCode(), severity(), messages)

    /** Each rule element of the model with the absolute path of the group that declares it. */
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

    private fun ledger(): Set<String> =
        checkNotNull(javaClass.getResource(LEDGER)) { "missing $LEDGER" }.readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .toCollection(LinkedHashSet())

    private fun committedModels(): List<Path> {
        val root = CorpusFiles.repoRoot()
        return listOf(
            root.resolve("corpus/models"),
            root.resolve("cli/src/test/resources/models"),
            root.resolve("src/test/resources/models"),
        ).filter { Files.isDirectory(it) }.flatMap { dir ->
            Files.list(dir).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.name.endsWith(".json") }.sorted().toList()
            }
        }
    }

    private companion object {
        const val LEDGER = "/checker-near-miss/clusters.tsv"
        val ACTUAL = File("build/checker-near-miss/actual.tsv")
        val REPORT = File("build/checker-near-miss/report.tsv")
        const val PROBE = "NearMissProbe"
        const val PROBE_CODE = "NEAR_MISS"
        const val UNRESOLVABLE = "Nope"

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
