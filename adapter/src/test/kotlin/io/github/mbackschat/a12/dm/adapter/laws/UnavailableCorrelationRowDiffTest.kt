package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A `$` correlation whose filtered rows include unavailable empty cells (KERNEL-FINDINGS KF552), on the three shapes
 * the indexed tallies must answer exactly once they no longer hand such a scope to the nested tally (IF423): the
 * iterated row itself unavailable under a self-exclusion, an unavailable join field beside an empty outer key, and
 * the ordered plan over an unavailable starred column. Every rule reads the tally through more than one operator, so
 * a row counted as empty where A12 leaves it undecided changes at least one verdict. `PEER_NONE` joins on the optional
 * `Alias`, whose empty cell is an ordinary one, so it separates unavailability from how an empty key matches.
 *
 * Own domain (a claims register: each claim's reference, the reference it duplicates, its amount and premium).
 */
class UnavailableCorrelationRowDiffTest {

    private val skeleton = DocumentModelDraft.named("claims-correlation").locale("en_US")
        .rootGroup("Register") { r -> r.group("Claims") { c -> c.repeatable(8)
            .numberField("Reference") { it.fractionDigits(0).required() }
            .numberField("Alias") { it.fractionDigits(0) }
            .numberField("Amount") { it.fractionDigits(0) }
            .numberField("Premium") { it.fractionDigits(0).required() } } }
        .build().toJson()

    private val bound = BoundModel.ofJson(skeleton)
    private val claims = bound.group(CLAIMS)
    private val reference = bound.num("$CLAIMS/Reference")
    private val alias = bound.num("$CLAIMS/Alias")
    private val amount = bound.num("$CLAIMS/Amount")
    private val otherRow = Dsl.currentRepetition(claims).ne(Dsl.currentRepetition(claims, true))

    private fun rule(code: String, condition: Condition) = bound.rule(claims).name("${code.lowercase()}_rule")
        .errorField(bound.field("$CLAIMS/Amount")).`when`(condition)
        .code(code).severity(Severity.ERROR).message(Dsl.en("Check this claim against the register.")).build()

    private fun sameReference() = bound.flatten("$CLAIMS*/Reference").having(otherRow.and(reference.eq(reference.outer())))
    private fun amountsUnderAlias() = bound.flatten("$CLAIMS*/Amount").having(reference.eq(alias.outer()))
    private fun lowerPremiums() = bound.flatten("$CLAIMS*/Premium").having(otherRow.and(reference.lt(reference.outer())))

    private val model = bound.addAll(listOf(
        // The iterated row's own reference may be the unavailable cell.
        rule("SELF_ANY", amount.filled().and(Dsl.atLeastOneFieldFilled(sameReference()))),
        rule("SELF_NONE", amount.filled().and(Dsl.noFieldFilled(sameReference()))),
        rule("SELF_COUNT", amount.filled().and(Dsl.numberOfFilledFields(sameReference()).gt(0))),
        // An empty alias is an empty outer key, and an unavailable reference an empty inner one.
        rule("ALIAS_ANY", amount.filled().and(Dsl.atLeastOneFieldFilled(amountsUnderAlias()))),
        rule("ALIAS_NONE", amount.filled().and(Dsl.noFieldFilled(amountsUnderAlias()))),
        rule("ALIAS_COUNT", amount.filled().and(Dsl.numberOfFilledFields(amountsUnderAlias()).gt(0))),
        // The ordered plan, its starred column required.
        rule("LOWER_ANY", amount.filled().and(Dsl.atLeastOneFieldFilled(lowerPremiums()))),
        rule("LOWER_NONE", amount.filled().and(Dsl.noFieldFilled(lowerPremiums()))),
        // An optional join field: its empty cell is an ordinary empty, the twin of the unavailable reference.
        rule("PEER_NONE", amount.filled().and(Dsl.noFieldFilled(bound.flatten("$CLAIMS*/Amount")
            .having(otherRow.and(alias.eq(alias.outer())))))),
        rule("LOWER_COUNT", amount.filled().and(Dsl.numberOfFilledFields(lowerPremiums()).gt(1))),
    ), emptyList()).toJson()

    private fun claim(reference: Int?, alias: Int? = null, amount: Int? = 1, premium: Int? = 10) = "{" + listOfNotNull(
        reference?.let { "\"Reference\":$it" }, alias?.let { "\"Alias\":$it" },
        amount?.let { "\"Amount\":$it" }, premium?.let { "\"Premium\":$it" }).joinToString(",") + "}"

    private fun register(vararg claims: String) = """{"Register":{"Claims":[${claims.joinToString(",")}]}}"""

    private val documents = linkedMapOf(
        "one claim without a reference" to register(claim(1), claim(null), claim(1), claim(2, premium = null)),
        "two claims without a reference, one with a premium" to register(claim(null), claim(null, premium = null),
            claim(3), claim(3, alias = 3), claim(4, premium = null)),
        "an empty alias beside an unavailable reference" to register(claim(null, amount = 5), claim(2, amount = null),
            claim(2, alias = 2), claim(5)),
        "an alias naming an unavailable row's neighbour" to register(claim(7, alias = 7, amount = null), claim(null),
            claim(8, alias = 7), claim(7, premium = null)),
        "every cell available, the control" to register(claim(1), claim(1, alias = 1), claim(2, premium = 5)),
    )

    @Test
    fun theModelIsKernelConsistent() {
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model))
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        DocumentDifferential.assertAgree(model, documents)
    }

    private companion object {
        const val CLAIMS = "/Register/Claims"
    }
}
