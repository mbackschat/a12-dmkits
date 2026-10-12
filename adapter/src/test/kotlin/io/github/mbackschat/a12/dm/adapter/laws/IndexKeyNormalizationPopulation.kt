package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator

/** DG102-E2 discriminates key preprocessing from regex matching and length validation. */
internal object IndexKeyNormalizationPopulation {
    private data class Case(val name: String, val pattern: String?, val min: Int, val max: Int, val admitted: List<Boolean>)
    private val cases = listOf(
        Case("LF", "a\\n", 0, 0, listOf(true, false, true)),
        Case("CR", "a\\r", 0, 0, listOf(false, true, false)),
        Case("CRLF", "a\\r\\n", 0, 0, listOf(false, false, false)),
        Case("Maximum", null, 0, 2, listOf(true, true, true)),
        Case("Minimum", null, 3, 0, listOf(false, false, false)),
    )

    fun rows(): List<ConditionCase> {
        val host = DocumentModelDraft.named("policy-index-key-normalization").locale("en_US")
            .rootGroup("Policy") { policy ->
                policy.stringField("Reference") { }
                for (case in cases) policy.group(case.name) { group ->
                    group.repeatable(5).indexField("K").stringField("K") { field ->
                        case.pattern?.let { field.pattern(it, "policy code") }
                        if (case.min > 0) field.minLength(case.min)
                        if (case.max > 0) field.maxLength(case.max)
                    }.numberField("Amount") { it.fractionDigits(2) }
                }
            }.build().toJson()
        val validator = RuleValidator()
        val model = BoundModel.ofJson(host)
        val reference = model.str("/Policy/Reference")
        val rule = model.rule(model.group("/Policy")).name("NormalizationProbe").errorField(reference)
            .`when`(reference.filled()).code("NORMALIZATION_PROBE")
            .message(Dsl.en("Check policy code")).build().draft()
        return listOf(ConditionCase("admitted-control", validator.inject(host, rule), null)) + cases.flatMap { case ->
            listOf("a\n", "a\r", "a\r\n").mapIndexed { index, key ->
                val condition = rule.errorCondition() + " And Sum(${case.name}/Amount For \"$key\") > 0"
                ConditionCase("${case.name}-$index", validator.inject(host, rule.withErrorCondition(condition)),
                    if (case.admitted[index]) "MVK_PARAMSIZE_INVALIDN" else "MVK_INDEX_VALUE_INVALID")
            }
        }
    }

    fun literalDecodingRows(custom: Boolean = false): List<ConditionCase> {
        val declarations = listOf(
            Triple("Unbounded", 0, 0), Triple("MinimumFour", 4, 0),
            Triple("MaximumThree", 0, 3), Triple("MaximumFour", 0, 4),
        )
        val keys = listOf(
            "plain" to "AB", "lone-cr" to "A\rB", "lf" to "A\nB",
            "crlf" to "A\r\nB", "repeated-cr" to "A\r\r\nB",
            "empty" to "", "quote" to "\"", "doubled-quote" to "A\"B", "backslash" to "A\\nB",
        )
        val admitted = listOf(
            listOf(true, true, true, true, true, false, true, true, true),
            listOf(false, false, false, false, true, false, false, false, true),
            listOf(true, true, true, true, false, false, true, true, false),
            listOf(true, true, true, true, true, false, true, true, true),
        )
        val host = DocumentModelDraft.named("policy-authored-index-literals").locale("en_US")
            .rootGroup("Policy") { policy ->
                policy.stringField("Reference") { }
                for ((name, min, max) in declarations) policy.group(name) { group ->
                    group.repeatable(5).indexField("Code")
                    if (custom) group.customField("Code") { field ->
                        field.type("PolicyCode")
                        if (min > 0) field.minLength(min)
                        if (max > 0) field.maxLength(max)
                    } else group.stringField("Code") { field ->
                        if (min > 0) field.minLength(min)
                        if (max > 0) field.maxLength(max)
                    }
                    group.numberField("Amount") { it.fractionDigits(2) }
                }
            }.build().toJson()
        val edit = EditSession.read(host)
        val policy = edit.group("/Policy")
        val reference = policy.str("Reference")
        return declarations.flatMapIndexed { declarationAt, (name, _, _) ->
            val amount = policy.num("$name/Amount")
            keys.mapIndexed { keyAt, (id, key) ->
                val rule = policy.rule { it.name("LiteralProbe").errorField(reference)
                    .`when`(reference.filled().and(amount.forIndex(key).gt(0)))
                    .code("LITERAL_PROBE").message(Dsl.en("Check the selected policy amount.")).build() }
                ConditionCase("$name-$id", RuleValidator().inject(host, rule.draft()),
                    if (admitted[declarationAt][keyAt]) null else "MVK_INDEX_VALUE_INVALID")
            }
        }
    }
}
