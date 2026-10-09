package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * A `$`-marked typed reference reads the RULE's row in every slot its own methods build, on all three engines:
 * a presence, a value list of literals or of fields, a pattern, a length, a concatenation, a text comparison,
 * the equal-or-both-empty pair, and the number and enumeration forms. Each rule asks whether ANOTHER claim
 * matches this one through one slot, and the documents are built so that a lost marker moves a firing onto the
 * other claim. Admission is `rulekit.validate.laws.CorrelatedTypedRefSlotLawsTest`'s.
 *
 * Own domain (an insurance claims register).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CorrelatedTypedRefSlotDiffTest {

    val model: String = run {
        val draft = DocumentModelDraft.named("claims-register-marked-refs").locale("en_US")
            .rootGroup("Register") { r ->
                r.group("Claims") { g -> g.repeatable(5)
                    .stringField("Code") { it.maxLength(6) }
                    .stringField("Note") { }
                    .numberField("Units") { it.fractionDigits(0) }
                    .enumField("Plan") { it.value("BASIC").value("GOLD") }
                    .dateField("FiledOn") { it.format("yyyy-MM-dd") } }
            }.build().toJson()
        val session = EditSession.read(draft)
        val register = session.group("/Register")
        val claims = session.group("/Register/Claims")
        val code = claims.str("Code")
        val note = claims.str("Note")
        val units = claims.num("Units")
        val plan = claims.enumOf("Plan")
        val filedOn = claims.date("FiledOn")
        val another = Dsl.currentRepetition(claims.ref()).ne(Dsl.currentRepetition(claims.ref(), true))
        val matches: Map<String, Condition> = linkedMapOf(
            "NoteGap" to note.outer().notFilled().and(note.filled()),
            "SameCode" to code.outer().`in`(code),
            "CodeListed" to code.outer().`in`("AB12"),
            "SamePattern" to code.outer().matches("AB.*").and(code.matches("AB.*")),
            "SameLength" to code.outer().length().eq(code.length()),
            "SameTagged" to code.outer().asString().plus("-").eq(code.asString().plus("-")),
            "SameText" to code.outer().eq(code.asString()),
            "Agrees" to code.outer().agreesWith(code),
            "UnitsListed" to units.outer().`in`(1),
            "PlanListed" to plan.outer().`in`("GOLD"),
            "PlanTagged" to plan.outer().plus("-").eq(plan.plus("-")),
        )
        var json = session.toJson()
        for ((name, match) in matches) {
            json = TypedRules.inject(json, claims.rule {
                val anotherMatches = Dsl.atLeastOneFieldFilled(
                    register.flatten("Claims*/FiledOn").having(filedOn.filled().and(another).and(match)))
                it.name(name).code(name).errorField(filedOn.field()).`when`(filedOn.filled().and(anotherMatches))
                    .message(Dsl.en("Check the claim.")).build()
            })
        }
        json
    }

    private data class Claim(val code: String, val note: String?, val units: Int, val plan: String)

    private fun register(vararg claims: Claim) = """{"Register":{"Claims":[""" + claims.joinToString(",") {
        val note = it.note?.let { n -> ""","Note":"$n"""" } ?: ""
        """{"Code":"${it.code}"$note,"Units":${it.units},"Plan":"${it.plan}","FiledOn":"2024-03-10"}"""
    } + "]}}"

    private val documents = linkedMapOf(
        "two agreeing claims, the first without a note" to register(
            Claim("AB12", null, 1, "GOLD"), Claim("AB12", "seen", 1, "GOLD")),
        "two differing claims, the second without a note" to register(
            Claim("AB12", "seen", 1, "GOLD"), Claim("CD345", null, 2, "BASIC")),
    )

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        DocumentDifferential.assertAgree(model, documents)
    }

    /**
     * The rules each document fires on the kernel's dynamic strategy, sorted. Agreeing claims fire every rule for
     * both claims except the note gap, which only the claim without a note has. On differing claims the listed
     * rules fire for the claim holding the listed value and the gap for the claim lacking the note; a lost marker
     * would read the candidate instead and fire them for the other claim.
     */
    @Test
    fun theKernelReadsTheRulesOwnRow() {
        val observed = documents.mapValues { (_, doc) ->
            DocumentDifferential.kernel(model, doc, dynamic = true).messages
                .map { it.code + "@" + it.pointer.substringAfter("Claims[").substringBefore("]") }
                .sorted().joinToString()
        }
        val both = listOf(
            "Agrees", "CodeListed", "PlanListed", "PlanTagged", "SameCode", "SameLength", "SamePattern",
            "SameTagged", "SameText", "UnitsListed").flatMap { listOf("$it@1", "$it@2") }
        assertEquals(linkedMapOf(
            "two agreeing claims, the first without a note" to (both + "NoteGap@1").sorted().joinToString(),
            "two differing claims, the second without a note" to
                listOf("CodeListed@1", "NoteGap@2", "PlanListed@1", "UnitsListed@1").joinToString(),
        ), observed)
    }

    @Test
    fun theCheckerIsSilentOnTheModelA12Admits() {
        val rejecting = check(ModelSource(model)).findings.filter { it.justifiesRejection }.map { it.code }
        assertEquals(emptyList<String>(), rejecting)
    }
}
