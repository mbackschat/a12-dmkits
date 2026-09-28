package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import java.util.regex.Pattern

/** DG102-C tests Java Pattern as a predictive model of the kernel's indexed String gate. */
internal object JavaPatternModelPopulation {
    private data class Case(val group: String, val pattern: String, val match: String, val miss: String)

    private val cases = listOf(
        Case("NamedB", "\\N{LATIN CAPITAL LETTER B}", "B", "A"),
        Case("ClassHex", "[\\x{E9}]", "é", "A"),
        Case("ClassUnion", "[\\s@]", "@", "\u00A0"),
        Case("PropertyComplement", "\\P{L}", "2", "a"),
        Case("Grapheme", "\\X", "a", "ab"),
        Case("WordBoundary", "\\bA\\b.", "A!", "A_"),
        Case("GlobalUnicodeCase", "(?iu)é", "É", "e"),
        Case("Intersection", "[a-z&&b-d]", "c", "a"),
    )

    private val host = DocumentModelDraft.named("policy-index-pattern-model").locale("en_US")
        .rootGroup("Policy") { policy ->
            policy.stringField("Reference") { }
            for (case in cases) {
                policy.group(case.group) { group ->
                    group.repeatable(5).indexField("K")
                        .stringField("K") { it.pattern(case.pattern, "policy code") }
                        .numberField("Amount") { it.fractionDigits(2) }
                }
            }
        }.build().toJson()

    private val validator = RuleValidator()
    private val baseRule = BoundModel.ofJson(host).let { model ->
        val reference = model.str("/Policy/Reference")
        model.rule(model.group("/Policy")).name("IndexModelProbe").errorField(reference)
            .`when`(reference.filled()).code("INDEX_MODEL_PROBE")
            .message(Dsl.en("Check policy code")).build().draft()
    }

    fun rows(): List<ConditionCase> {
        for (case in cases) {
            check(Pattern.matches(case.pattern, case.match)) { "direct Java prediction failed: ${case.group} match" }
            check(!Pattern.matches(case.pattern, case.miss)) { "direct Java prediction failed: ${case.group} miss" }
        }
        return listOf(ConditionCase("admitted-control", validator.inject(host, baseRule), null)) +
            cases.flatMap { case ->
                listOf(row(case.group, case.match, true), row(case.group, case.miss, false))
            }
    }

    private fun row(group: String, constant: String, accepted: Boolean): ConditionCase {
        val condition = baseRule.errorCondition() + " And Sum($group/Amount For \"$constant\") > 0"
        return ConditionCase("$group-${if (accepted) "match" else "miss"}",
            validator.inject(host, baseRule.withErrorCondition(condition)),
            if (accepted) "MVK_PARAMSIZE_INVALIDN" else "MVK_INDEX_VALUE_INVALID")
    }
}
