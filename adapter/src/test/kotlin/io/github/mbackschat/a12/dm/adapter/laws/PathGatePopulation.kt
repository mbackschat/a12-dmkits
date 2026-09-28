package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator

/** R5-PE crosses segment resolution, suffix and landing gates before the leaf's resolution. */
internal object PathGatePopulation {
    private val host = DocumentModelDraft.named("policy-path-gates").locale("en_US")
        .rootGroup("Policy") { root -> root
            .numberField("Total") { it.fractionDigits(0) }
            .group("Fixed") { it.numberField("Amount") { field -> field.fractionDigits(0) } }
            .group("Repeating") { it.repeatable(9)
                .numberField("Amount") { field -> field.fractionDigits(0) } } }
        .build().toJson()

    private enum class Segment { Missing, Fixed, Repeating }
    private enum class Suffix(val text: String) { NONE(""), STAR("*"), H_MODEL(".*") }

    fun rows(): List<ConditionCase> = rows(aggregate = false)

    fun aggregateRows(): List<ConditionCase> = rows(aggregate = true)

    fun repetitionRows(): List<ConditionCase> {
        val model = BoundModel.ofJson(host)
        val total = model.num("/Policy/Total")
        val rule = model.rule(model.group("/Policy")).name("RepetitionProbe").errorField(total)
            .`when`(total.filled()).code("REPETITION_PROBE").message(Dsl.en("Check policy repetitions")).build().draft()
        val validator = RuleValidator()
        return listOf(false, true).flatMap { filter -> Segment.entries.flatMap { segment ->
            listOf(false, true).flatMap { starred -> listOf(false, true).map { field ->
                val path = segment.name + (if (starred) "*" else "") + (if (field) "/Amount" else "")
                val expected = when {
                    segment == Segment.Missing -> "MVK_INVALID_ENTITY"
                    starred && segment == Segment.Fixed -> "MVK_INVALID_WILDCARD"
                    segment == Segment.Repeating && !starred && !filter -> "MVK_NO_WILDCARD"
                    field -> "MVK_NO_FIELDS_ALLOWED"
                    starred -> "MVK_NO_WILDCARDS_ALLOWED"
                    filter && segment == Segment.Fixed -> "MVK_NO_ITERATION_FOR_WILDCARD"
                    else -> null
                }
                val comparison = "CurrentRepetition($path) > 0"
                val probe = if (filter) "Sum(Repeating*/Amount Having $comparison) > 0" else comparison
                ConditionCase("filter=$filter:$path", validator.inject(host,
                    rule.withErrorCondition(rule.errorCondition() + " And $probe")), expected)
            } }
        } }
    }

    private fun rows(aggregate: Boolean): List<ConditionCase> {
        val model = BoundModel.ofJson(host)
        val total = model.num("/Policy/Total")
        val rule = model.rule(model.group("/Policy")).name("PathProbe").errorField(total)
            .`when`(total.filled()).code("PATH_PROBE").message(Dsl.en("Check policy amounts")).build().draft()
        val validator = RuleValidator()
        return Segment.entries.flatMap { segment -> Suffix.entries.flatMap { suffix ->
            listOf("Amount", "NoSuchField").map { leaf ->
                val path = "${segment.name}${suffix.text}/$leaf"
                val expected = when {
                    segment == Segment.Missing -> "MVK_INVALID_ENTITY"
                    suffix == Suffix.H_MODEL -> "MVK_POINT_IN_A12_MODEL"
                    suffix == Suffix.STAR && segment == Segment.Fixed -> "MVK_INVALID_WILDCARD"
                    leaf == "NoSuchField" -> "MVK_INVALID_ENTITY"
                    segment == Segment.Fixed -> if (aggregate) "MVK_PARAMSIZE_INVALIDN" else null
                    suffix == Suffix.NONE -> "MVK_NO_WILDCARD"
                    else -> if (aggregate) null else "MVK_NO_WILDCARDS_ALLOWED"
                }
                val probe = if (aggregate) "Sum($path) > 0" else "FieldFilled($path)"
                ConditionCase(path, validator.inject(host,
                    rule.withErrorCondition(rule.errorCondition() + " And $probe")), expected)
            }
        } }
    }
}
