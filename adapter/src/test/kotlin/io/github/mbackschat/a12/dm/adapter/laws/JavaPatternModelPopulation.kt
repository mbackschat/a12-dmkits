package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import java.util.regex.Pattern

/** DG102-C tests Java Pattern as a predictive model of the kernel's indexed String gate. */
internal object JavaPatternModelPopulation {
    private data class Case(val group: String, val pattern: String, val match: String?, val miss: String, val kernelMatch: Boolean = true)

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

    fun boundaries(): List<ConditionCase> = population(listOf(
        Case("NonSpaceUnion", "[\\S@]", "\u00A0", " "),
        Case("NegatedUnion", "[^\\S ]", "\t", "\u00A0"),
        Case("NonDigitUnion", "[\\D0]", "0", "2"),
        Case("NonHorizontalUnion", "[\\H@]", "@", " "),
        Case("NegatedVertical", "[^\\Vx]", "\n", "x"),
        Case("WholeFlagScope", "(?iu:è|é)", "È", "e"),
        Case("FinalCRLF", "a$\\r\\n", "a\r\n", "a\n", kernelMatch = false),
        Case("FinalNEL", "a$\\u0085", "a\u0085", "a\n"),
        Case("SplitCRLF", "a\\r$\\n", null, "a\r\n"),
        Case("MultipleLines", "a$\\n\\n", null, "a\n\n"),
        Case("UnanchoredCRLF", "a\\r\\n", "a\r\n", "a\n", kernelMatch = false),
    ))

    fun compositions(): List<ConditionCase> = population(listOf(
        Case("NegatedPlus", "[^ab]+", "cc", "ca"),
        Case("NegatedCount", "[^ab]{2}", "cc", "ca"),
        Case("NegatedOptional", "[^ab]?a", "a", "ba"),
        Case("NegatedNonSpacePlus", "[^\\S]+", "  ", " a"),
    ))


    fun caseClosure(): List<ConditionCase> = population(listOf(
        Case("HexUpper", "(?i)\\x41", "a", "b"),
        Case("UnicodeLower", "(?i)\\u0062", "B", "A"),
        Case("BracedUpper", "(?i)\\x{43}", "c", "d"),
        Case("ScopedHex", "(?i:\\x44)e", "de", "dE"),
        Case("ResetHex", "(?i)\\x45(?-i)f", "ef", "eF"),
        Case("MixedRange", "(?i)[Z-a]", "z", "b"),
        Case("LowerRange", "(?i)[@-B]", "a", "c"),
        Case("UpperRange", "(?i)[y-~]", "Y", "X"),
        Case("NegatedRange", "(?i)[^Z-a]", "b", "z"),
        Case("EmptyIntersection", "[a&&b]", null, "a"),
        Case("NegatedEmpty", "[^a&&b]", "a", "aa"),
        Case("NegatedIntersection", "[^a-c&&b-d]", "a", "b"),
        Case("FoldedIntersection", "(?i)[A-C&&b-d]", "B", "a"),
    ))


    fun intersections(): List<ConditionCase> = population(listOf(
        Case("OmittedRight", "[a&&]", "a", "b"),
        Case("OmittedLeft", "[&&a]", "a", "b"),
        Case("CaretIntersection", "[a^&&^]", "^", "a"),
        Case("TripleIntersection", "[a-z&&b-f&&d-h]", "e", "b"),
        Case("ComplementIntersection", "[\\S&&\\D]", "@", "2"),
        Case("OuterIntersection", "[^a-z&&b-d]", "a", "b"),
    ))

    fun classTokens(): List<ConditionCase> = population(listOf(
        Case("EscapedLeftAmpersand", "[\\&&]", "&", "\\"),
        Case("EscapedRightAmpersand", "[&\\&]", "&", "\\"),
        Case("CaretRangeIntersection", "[z&&^-z]", "z", "a"),
        Case("CaretRangeUnion", "[\\D^-z]", "z", "2"),
    ))

    fun rows(): List<ConditionCase> = population(cases)

    private fun population(cases: List<Case>): List<ConditionCase> {
        val host = DocumentModelDraft.named("policy-index-pattern-model").locale("en_US")
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

        val validator = RuleValidator()
        val baseRule = BoundModel.ofJson(host).let { model ->
            val reference = model.str("/Policy/Reference")
            model.rule(model.group("/Policy")).name("IndexModelProbe").errorField(reference)
                .`when`(reference.filled()).code("INDEX_MODEL_PROBE")
                .message(Dsl.en("Check policy code")).build().draft()
        }

        for (case in cases) {
            case.match?.let { check(Pattern.matches(case.pattern, it)) { "direct Java prediction failed: ${case.group} match" } }
            check(!Pattern.matches(case.pattern, case.miss)) { "direct Java prediction failed: ${case.group} miss" }
        }
        fun row(group: String, constant: String, accepted: Boolean, kernelAccepts: Boolean = accepted): ConditionCase {
            val condition = baseRule.errorCondition() + " And Sum($group/Amount For \"$constant\") > 0"
            return ConditionCase("$group-${if (accepted) "match" else "miss"}",
                validator.inject(host, baseRule.withErrorCondition(condition)),
                if (kernelAccepts) "MVK_PARAMSIZE_INVALIDN" else "MVK_INDEX_VALUE_INVALID")
        }
        return listOf(ConditionCase("admitted-control", validator.inject(host, baseRule), null)) +
            cases.flatMap { case ->
                listOfNotNull(case.match?.let { row(case.group, it, true, case.kernelMatch) }, row(case.group, case.miss, false))
            }
    }
}
