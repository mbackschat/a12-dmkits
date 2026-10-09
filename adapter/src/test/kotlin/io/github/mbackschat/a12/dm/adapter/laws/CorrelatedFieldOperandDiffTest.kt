package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DatePrecision
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * A `$`-marked operand in a plain-field slot reads the RULE's row inside a filter, on all three engines. Each rule
 * asks whether ANOTHER claim matches this one through one slot — `RangeAsString`, `RangeAsNumber`,
 * `FieldValueAsString`, a field rounding, `ValueAsDate` — so a lost marker would compare every candidate with
 * itself and fire on every claim. The portable checker is silent on the model A12 admits.
 *
 * Own domain (an insurance claims register).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CorrelatedFieldOperandDiffTest {

    private val model: String = run {
        val draft = DocumentModelDraft.named("claims-register-correlated").locale("en_US")
            .rootGroup("Register") { r ->
                r.group("Claims") { g -> g.repeatable(5)
                    .stringField("Code") { it.maxLength(4) }
                    .numberField("Amount") { it.fractionDigits(2) }
                    .dateField("FiledOn") { it.format("yyyy-MM-dd") }
                    .dateField("ReportedAbout") { it.format("yyyy-MM-dd").datePrecision(DatePrecision.MONTH_OPTIONAL) } }
            }.build().toJson()
        val session = EditSession.read(draft)
        val register = session.group("/Register")
        val claims = session.group("/Register/Claims")
        val code = claims.field("Code")
        val amount = claims.num("Amount")
        val filedOn = claims.date("FiledOn")
        val reported = claims.field("ReportedAbout")
        val another = Dsl.currentRepetition(claims.ref()).ne(Dsl.currentRepetition(claims.ref(), true))
        val matches: Map<String, Condition> = linkedMapOf(
            "SamePrefix" to Dsl.rangeAsString(code.outer(), 1, 2).eq(Dsl.rangeAsString(code, 1, 2)),
            "SameSuffix" to Dsl.rangeAsNumber(code.outer(), 3, 4).eq(Dsl.rangeAsNumber(code, 3, 4)),
            "SameAmountText" to Dsl.fieldValueAsString(amount.field().outer()).eq(Dsl.fieldValueAsString(amount.field())),
            "SameRoundedAmount" to Dsl.roundUpValue(amount.outer()).eq(Dsl.roundUpValue(amount)),
            "FiledAfterReport" to filedOn.after(Dsl.valueAsDateLastDay(reported.outer())),
        )
        var json = session.toJson()
        for ((name, match) in matches) {
            json = TypedRules.inject(json, claims.rule {
                it.name(name).code(name).errorField(filedOn.field()).`when`(filedOn.filled().and(Dsl.atLeastOneFieldFilled(
                    register.flatten("Claims*/FiledOn").having(filedOn.filled().and(another).and(match)))))
                    .message(Dsl.en("Check the claim.")).build()
            })
        }
        json
    }

    private data class Claim(val code: String, val amount: String, val filedOn: String, val reported: String)

    private fun register(vararg claims: Claim) = """{"Register":{"Claims":[""" + claims.joinToString(",") {
        """{"Code":"${it.code}","Amount":${it.amount},"FiledOn":"${it.filedOn}","ReportedAbout":"${it.reported}"}"""
    } + "]}}"

    private val documents = linkedMapOf(
        "two claims agreeing on every slot" to register(
            Claim("AB12", "10.20", "2024-03-10", "2024-01-00"), Claim("AB12", "10.20", "2024-03-15", "2024-01-00")),
        "two claims differing on every slot" to register(
            Claim("AB12", "10.20", "2024-01-10", "2024-05-00"), Claim("CD34", "11.50", "2024-01-15", "2024-06-00")),
        "a third claim matching only the first" to register(
            Claim("AB12", "10.20", "2024-03-10", "2024-05-00"), Claim("CD34", "11.50", "2024-01-15", "2024-06-00"),
            Claim("AB12", "10.20", "2024-06-01", "2024-01-00")),
    )

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        DocumentDifferential.assertAgree(model, documents)
    }

    /**
     * The rules each document fires on the kernel's dynamic strategy, sorted. Agreeing claims fire every rule
     * once per claim and differing ones none, which a lost marker could not give, since a candidate compared with
     * itself always matches. With a third claim matching only the first, the matching rules fire for those two
     * claims alone, and the report rule for the claims filed after another claim's reported month.
     */
    @Test
    fun theKernelReadsTheRulesOwnRow() {
        val observed = documents.mapValues { (_, doc) ->
            DocumentDifferential.kernel(model, doc, dynamic = true).messages.map { it.code }.sorted().joinToString()
        }
        val all = "FiledAfterReport, FiledAfterReport, SameAmountText, SameAmountText, SamePrefix, SamePrefix, " +
            "SameRoundedAmount, SameRoundedAmount, SameSuffix, SameSuffix"
        assertEquals(linkedMapOf(
            "two claims agreeing on every slot" to all,
            "two claims differing on every slot" to "",
            "a third claim matching only the first" to all,
        ), observed)
    }

    @Test
    fun theCheckerIsSilentOnTheModelA12Admits() {
        assertEquals(emptyList<String>(), check(ModelSource(model)).findings.filter { it.justifiesRejection }.map { it.code })
    }
}
