package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A category read in a comparison: A12 types `[Cover -> Tier]` as the Enumeration it hangs off, carrying the
 * category, so the comparison walk treats it as an Enumeration whose value domain is the CATEGORY's values
 * (`CheckVergleichsBedingungImpl.getValidEnumValues`; KERNEL-FINDINGS KF610). A computation assigns through the
 * same walk.
 *
 * [RULES] and [COMPUTATIONS] pin A12's answer on every row, and the checker must name exactly the same classes.
 * The plain-field rows are the twins that ask whether a class is the category's or the comparison's. Own domain:
 * an insurance policy whose cover carries a tier.
 */
class CheckerCategoryComparisonAgreementTest {

    private val policy = DocumentModelDraft.named("policy-category-comparison").locale("en_US")
        .rootGroup("Policy") { p -> p
            .stringField("Title") { }
            .numberField("Premium") { n -> n.fractionDigits(2) }
            .booleanField("Active") { }
            .dateField("StartsOn") { d -> d.format("yyyy-MM-dd") }
            .enumField("Cover") { e -> e.value("BASIC").value("PLUS").category("Tier", "Gold", "Silver") }
            .enumField("Plan") { e -> e.value("Gold").value("Silver") }
            .group("Claims") { c -> c.repeatable(9)
                .stringField("Note") { }
                .numberField("Amount") { n -> n.fractionDigits(2) }
                .enumField("ClaimCover") { e -> e.value("BASIC").value("PLUS").category("Tier", "Gold", "Silver") } } }
        .build().toJson()

    private val validator = RuleValidator()

    @Test
    fun a12AnswersEveryRowAsPinned() {
        assertEquals(RULES.mapValues { it.value }, RULES.mapValues { rule(it.key).first })
        assertEquals(COMPUTATIONS.mapValues { it.value.second }, COMPUTATIONS.mapValues { computation(it.key, it.value.first).first })
    }

    @Test
    fun theCheckerNamesWhatA12Names() {
        assertEquals(RULES.mapValues { rule(it.key).first }, RULES.mapValues { rule(it.key).second })
        assertEquals(COMPUTATIONS.mapValues { computation(it.key, it.value.first).first },
            COMPUTATIONS.mapValues { computation(it.key, it.value.first).second })
    }

    /** A12's classes beside the checker's, for one rule reporting on the title. */
    private fun rule(condition: String): Pair<List<String>, List<String>> {
        val draft = DraftRule("/Policy", "TierProbe", "../Title", "FieldFilled(Title) And $condition", "PROBE", "ERROR")
        val result = validator.check(policy, draft)
        val a12 = if (result.ok()) emptyList() else result.diagnostics().map { it.code().orElse("(no code)") }
        return a12.sorted() to ours(validator.inject(policy, draft))
    }

    /** A12's classes beside the checker's, for one computation of [target] declared in the target's group. */
    private fun computation(target: String, operation: String): Pair<List<String>, List<String>> {
        val draft = DraftComputation(target.substringBeforeLast('/'), "TierProbe", "../${target.substringAfterLast('/')}",
            null, listOf(DraftComputation.Alternative(null, operation)))
        val result = validator.check(policy, draft)
        val a12 = if (result.ok()) emptyList() else result.diagnostics().map { it.code().orElse("(no code)") }
        return a12.sorted() to ours(validator.inject(policy, draft))
    }

    private fun ours(model: String) =
        rejectingCodes(model).sorted()

    private companion object {
        const val ENUM_OR_STRING = "MVK_INVALID_COMPARE_TO_ENUM_OR_STRING"
        const val NOT_IN_DOMAIN = "MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON"
        val Q = listOf("?")

        val RULES: Map<String, List<String>> = linkedMapOf(
            "[Cover -> Tier] == 5" to listOf(ENUM_OR_STRING),
            "[Cover -> Tier] == [Premium]" to listOf(ENUM_OR_STRING),
            "[Premium] == [Cover -> Tier]" to listOf(ENUM_OR_STRING),
            "[Cover -> Tier] > [Premium]" to listOf("MVK_INVALID_TYPE_FOR_COMPARISON"),
            "[Cover -> Tier] == [Active]" to listOf("MVK_INVALID_COMPARE_TO_YESNO"),
            "[Cover -> Tier] == True" to listOf(ENUM_OR_STRING),
            "[Cover -> Tier] == [StartsOn]" to listOf("MVK_INVALID_COMPARE_TO_DATE"),
            "[Cover -> Tier] == [Title]" to emptyList(),
            "[Title] == [Cover -> Tier]" to emptyList(),
            "[Cover -> Tier] == \"Gold\"" to emptyList(),
            "\"Silver\" == [Cover -> Tier]" to emptyList(),
            "[Cover -> Tier] == \"Bronze\"" to listOf(NOT_IN_DOMAIN),
            "[Cover -> Tier] == \"BASIC\"" to listOf(NOT_IN_DOMAIN),
            "\"BASIC\" == [Cover -> Tier]" to listOf(NOT_IN_DOMAIN),
            "[Cover -> Tier] != \"PLUS\"" to listOf(NOT_IN_DOMAIN),
            "[Cover -> Tier] == [Cover]" to emptyList(),
            "[Cover -> Tier] == [Plan]" to emptyList(),
            "[Cover -> Tier] == [Cover -> Tier]" to emptyList(),
            "[Title] == True" to listOf(ENUM_OR_STRING),
            "[Cover] == True" to listOf(ENUM_OR_STRING),
            "[Plan] == True" to listOf(ENUM_OR_STRING),
            "[Title] == False" to listOf(ENUM_OR_STRING),
        )

        /** Computed target → (operation, A12's classes). */
        val COMPUTATIONS: Map<String, Pair<String, List<String>>> = linkedMapOf(
            "/Policy/Premium" to ("[Cover -> Tier]" to listOf(ENUM_OR_STRING)),
            "/Policy/Active" to ("[Cover -> Tier]" to listOf("MVK_INVALID_COMPARE_TO_YESNO")),
            "/Policy/StartsOn" to ("[Cover -> Tier]" to listOf("MVK_INVALID_COMPARE_TO_DATE")),
            "/Policy/Title" to ("[Cover -> Tier]" to emptyList()),
            "/Policy/Plan" to ("[Cover -> Tier]" to emptyList()),
            "/Policy/Claims/Amount" to ("[ClaimCover -> Tier]" to listOf(ENUM_OR_STRING)),
            "/Policy/Claims/Note" to ("True" to listOf(ENUM_OR_STRING)),
        )
    }
}
