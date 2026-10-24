package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** SPEC-2026-10-03-02 separates complete operands from the producer graph used for cycle admission. */
internal class ComputationDependencyProfiles(private val facts: TrackedFactBase, private val computations: List<TrackedDeclaration>) {
    private val targets = computations.mapNotNull { element -> computedTargetOf(element)?.let { element.path to it } }.toMap()
    private val written = targets.values.toSet()
    private val groups = facts.declarations().filter { it.declaredKind == DeclaredKind.GROUP }.map { it.path }.toSet()
    private val repeatable = facts.repeatableGroups().map { it.path }
    private val keywords = CONDITION_OPERATOR_KEYWORDS.getValue(facts.conditionLanguage)

    private val profiles = computations.groupBy { targets[it.path] }.mapValues { (target, writers) ->
        if (target == null) emptySet() else writers.flatMap { writer ->
            val body = writer.body ?: return@flatMap emptyList()
            val common = (body["commonPrecondition"] as? JsonPrimitive)?.contentOrNull
            val implicit = repeatable.filter { target.startsWith("$it/") }.maxByOrNull { it.length }
            (body["computationAlternatives"] as? JsonArray).orEmpty().mapNotNull { alternative ->
                val own = ((alternative as? JsonObject)?.get("precondition") as? JsonPrimitive)?.contentOrNull
                val present = listOfNotNull(common, own).filter { !it.isA12Blank() }
                if (present.isEmpty() && implicit == null) null
                else present.flatMap { profile(it, writer, target) }.toSet() + ancestors(implicit)
            }
        }.reduceOrNull { left, right -> left intersect right }.orEmpty()
    }

    fun edges(): Map<String, Set<String>> {
        val edges = mutableMapOf<String, MutableSet<String>>()
        for (writer in computations) {
            val target = targets[writer.path] ?: continue
            for ((member, text) in conditionTexts(writer.kind, writer.body ?: continue)) {
                val calls = callsIn(text, facts.conditionLanguage, syntaxEntryOf(member))
                val special = calls.mapNotNull { call ->
                    when (call.operator) {
                        keywords["CurrentRepetition"] -> call to emptyList()
                        keywords["GroupFilled"] -> containingGroup(call, writer, target)?.let { group ->
                            call to written.filter { it != target && it.startsWith("$group/") && group !in profiles[it].orEmpty() }
                        }
                        else -> null
                    }
                }
                val reads = special.flatMap { it.second } + referenceSpansIn(text, facts.conditionLanguage)
                    .filter { ref -> special.none { (call, _) -> ref.start >= call.at && ref.end <= call.end } }
                    .mapNotNull { resolve(it.written, writer) }
                    .flatMap { path -> if (path in groups) written.filter { it.startsWith("$path/") } else listOf(path) }
                edges.getOrPut(target) { mutableSetOf() }.addAll(reads.filter { it != target && it in written })
            }
        }
        return edges
    }

    private fun profile(text: String, writer: TrackedDeclaration, target: String): Set<String> {
        val tokens = tokenize(text).filter { it.kind != TokenKind.COMMENT }
        val calls = callsIn(text, facts.conditionLanguage, SyntaxEntry.CONDITION).associateBy { it.at }
        fun walk(start: Int, end: Int): Set<String> {
            if (start >= end) return emptySet()
            var depth = 0
            val splits = mutableListOf<Int>()
            for (i in start until end) {
                val token = tokens[i]
                if (token.kind == TokenKind.PUNCTUATION) when (token.text) {
                    "(", "[", "{" -> depth++
                    ")", "]", "}" -> depth--
                }
                if (depth == 0 && token.kind == TokenKind.NAME && token.text in
                    CONDITION_KEYWORDS.getValue(facts.conditionLanguage) && token.text.asciiLowercase() in setOf("and", "und", "or", "oder")) splits += i
            }
            if (splits.isNotEmpty()) {
                val boundaries = listOf(start - 1) + splits + end
                val parts = boundaries.zipWithNext { left, right -> walk(left + 1, right) }
                return if (tokens[splits.first()].text.asciiLowercase() in setOf("or", "oder"))
                    parts.reduce { left, right -> left intersect right } else parts.flatten().toSet()
            }
            if (tokens[start].text == "(" && tokens[end - 1].text == ")") return walk(start + 1, end - 1)
            val call = calls[tokens[start].start]?.takeIf { it.end == tokens[end - 1].end } ?: return emptySet()
            return ancestors(containingGroup(call, writer, target))
        }
        return walk(0, tokens.size)
    }

    private fun containingGroup(call: ConditionCall, writer: TrackedDeclaration, target: String): String? {
        if (call.operator != keywords["GroupFilled"]) return null
        val operand = call.operands.singleOrNull() ?: return null
        // A selection guards as the group it selects: A12 admits two computations a keyed `GroupFilled` over their
        // group guards, as it does the plain form (`adapter.laws.CheckerKeyedGuardCycleAgreementTest`).
        val path = facts.operandReferenceIn(operand.text, writer.path.substringBeforeLast('/'), writer, call.inAFilterBody) {
            operand.path?.let(::OperandReference)
        }?.reference ?: return null
        val group = if (isRuleContext(path, facts.conditionLanguage)) writer.path.substringBeforeLast('/') else resolve(path, writer)
        return group?.takeIf { it in groups && target.startsWith("$it/") }
    }

    private fun resolve(path: String, writer: TrackedDeclaration): String? =
        (facts.resolveEntity(writer.path.substringBeforeLast('/'), path.replace("*", ""), writer.origin) as? Reference.Resolved)?.path

    private fun ancestors(group: String?): Set<String> = if (group == null) emptySet()
        else groups.filterTo(mutableSetOf()) { it == group || group.startsWith("$it/") }
}
