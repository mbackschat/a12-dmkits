package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.core.ModelIndex
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import kotlin.io.path.readText
import kotlin.io.path.relativeTo

/**
 * The near-miss population the conformance oracles share: seed rules, and single edits of each
 * ([CONFORMANCE-PLAN](../../../../../../../../../../../docs/CONFORMANCE-PLAN.md), P1 and P2). The seeds come from
 * two ARMS:
 *
 * - every rule [ModelIndex.rules] lists for a committed model ([CorpusFiles.committedModels]), a mounted one
 *   included, copied as a probe beside the rule it copies. The probe carries the rule's target, condition,
 *   severity and messages; a variant filter is not copied, so the probe is checked in every variant;
 * - every catalog `validExample` but those of the three consumer-SPI operators, whose conditions name host code
 *   rather than the model ([CatalogHosting.SPECIALISTS]), hosted on the fixture and error field
 *   [CatalogHosting] chooses.
 *
 * Each seed is edited one token at a time: a token deleted, doubled or swapped with the next; an operator or a
 * model name substituted; a connective, bracket, comma, star or sign inserted. On a rule with a message, a dollar,
 * an unresolvable parameter, `$$`, a tab or a supplementary character is appended to its first message. On top
 * of that sample, every truncation after a token and a `/` or `.` written straight after every token.
 *
 * **Deterministic.** A seed's edits are sampled with `java.util.Random`, whose sequence for a seed is specified by
 * its documentation, keyed by the seed's own label, so adding a model elsewhere does not reshuffle the edits of
 * every seed after it.
 */
internal object NearMissPopulation {

    /** Every seed of both arms, committed rules first. */
    fun seeds(): List<Seed> = committedRuleSeeds() + catalogSeeds()

    private fun committedRuleSeeds(): List<Seed> {
        val root = CorpusFiles.repoRoot()
        return CorpusFiles.committedModels().flatMap { file ->
            val host = file.readText()
            ModelIndex.of(host, ReferenceResolvers.directory(file.parent)).rules().map { rule ->
                Seed(
                    Arm.COMMITTED,
                    "${file.relativeTo(root)}#${rule.path()}",
                    host,
                    DraftRule.builder().groupPath(rule.groupPath()).name(PROBE)
                        .errorEntityRelPath(rule.errorEntityRelPath())
                        .errorCondition(rule.errorCondition()).errorCode(PROBE_CODE)
                        .severity(rule.severity())
                        .messages(rule.messages().map { (locale, text) -> LocalizedText(locale, text) })
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

    /**
     * [seed]'s single edits, distinct from each other and from the seed itself: a deterministic sample of the
     * token edits, then every truncation and every separator insertion, which are exhaustive because each is one
     * edit per token, then the message edits. The sample is drawn before the exhaustive families and from the
     * token edits alone, so widening those families never reshuffles it.
     */
    fun edits(seed: Seed): List<Edit> {
        val condition = seed.rule.errorCondition()
        val candidates = LinkedHashMap<String, String>()
        conditionEdits(condition, ModelNames.of(seed.host)).forEach { (label, text) ->
            if (text != condition) candidates.putIfAbsent(text, label)
        }
        val sampled = candidates.entries.shuffled(java.util.Random(seed.label.hashCode().toLong()))
            .take(CONDITION_EDITS)
            .map { (text, label) -> Edit(label, seed.rule.withErrorCondition(text), Family.SAMPLED) }
        val seen = sampled.mapTo(mutableSetOf(condition)) { it.rule.errorCondition() }
        val exhaustive = exhaustiveEdits(condition).filter { (_, text, _) -> seen.add(text) }
            .map { (label, text, family) -> Edit(label, seed.rule.withErrorCondition(text), family) }
        val first = seed.rule.messages().firstOrNull() ?: return sampled + exhaustive
        val messageEdits = MESSAGE_APPENDIXES.map { (label, appended) ->
            val messages = listOf(LocalizedText(first.locale(), first.text() + appended)) + seed.rule.messages().drop(1)
            Edit(label, seed.rule.withMessages(messages), Family.MESSAGE)
        }
        return sampled + exhaustive + messageEdits
    }

    /**
     * The text cut after each token but the last, where A12's parser meets the end of input inside a production;
     * and a path separator, `/` or `.`, written straight after each token, where a path stops at a separator the
     * production does not continue from.
     */
    private fun exhaustiveEdits(text: String): List<Triple<String, String, Family>> {
        val tokens = TOKEN.findAll(text).filterNot { it.value.startsWith(";;") }.toList()
        val cuts = tokens.dropLast(1).map { token ->
            Triple("cut after `${token.value}`", text.substring(0, token.range.last + 1), Family.TRUNCATION)
        }
        val separators = tokens.flatMap { token ->
            SEPARATORS.map { separator ->
                Triple("`$separator` after `${token.value}`",
                    text.substring(0, token.range.last + 1) + separator + text.substring(token.range.last + 1),
                    Family.SEPARATOR)
            }
        }
        return cuts + separators
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

    enum class Arm { COMMITTED, CATALOG }

    /** One seed: its arm, a label unique across both arms, its host model, and the probe rule. */
    class Seed(val arm: Arm, val label: String, val host: String, val rule: DraftRule)

    class Edit(val label: String, val rule: DraftRule, val family: Family)

    /** Where an input came from, so a widening is counted apart from the sample it widens. */
    enum class Family { AS_WRITTEN, SAMPLED, TRUNCATION, SEPARATOR, MESSAGE }

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

    const val PROBE = "NearMissProbe"
    const val PROBE_CODE = "NEAR_MISS"
    private const val UNRESOLVABLE = "Nope"

    /** How many condition edits each seed contributes; its message edits come on top. */
    private const val CONDITION_EDITS = 30

    /** A string or a comment whole, a number, a name, a two-character operator, or any other one character. */
    private val TOKEN = Regex("\"[^\"]*\"|;;[^\\n]*|\\d+(?:\\.\\d+)?|[\\p{L}_][\\p{L}\\p{N}_]*|\\.\\.|<=|>=|<>|!=|==|->|\\S")

    private val SUBSTITUTES: Map<String, List<String>> = mapOf(
        "And" to listOf("Or"), "Or" to listOf("And"), "Und" to listOf("Oder"), "Oder" to listOf("Und"),
        "==" to listOf("!="), "!=" to listOf("=="), "=" to listOf("<>"), "<>" to listOf("="),
        ">" to listOf(">=", "<"), "<" to listOf("<=", ">"), ">=" to listOf(">"), "<=" to listOf("<"),
        "+" to listOf("-", "*"), "-" to listOf("+"), "*" to listOf("/"), "/" to listOf("*"),
    )

    private val INSERTS = listOf("And", "(", ")", ",", "*", "-")

    private val SEPARATORS = listOf("/", ".")

    private val MESSAGE_APPENDIXES = listOf(
        "append a lone `$`" to " $",
        "append an unresolvable parameter" to " \$Nope\$",
        "append `$$`" to " $$",
        "append a tab" to "\t",
        "append a supplementary character" to " 😀",
    )
}
