package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * A key-selected `FirstFilledValue` operand that selects nothing is a not-given prefix, so a later filled fallback
 * makes the comparison fire as an OMISSION, while the same fallback read first fires as a VALUE (a12-kernel-lean
 * SPEC-2026-10-09-07). The controls: a match on a filled cell, a match on an empty one, and no key at all.
 *
 * Own domain (an insurance policy whose claims are keyed by code).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FirstFilledIndexNoMatchPolarityDiffTest {

    private val model: String = run {
        val draft = DocumentModelDraft.named("policy-first-filled-key").locale("en_US")
            .rootGroup("Policy") { p ->
                p.stringField("Key") { }
                p.numberField("Backup") { it.fractionDigits(0) }
                p.group("Claims") { g -> g.repeatable(3).indexField("Code")
                    .stringField("Code") { }
                    .numberField("Amount") { it.fractionDigits(0) } }
            }.build().toJson()
        val session = EditSession.read(draft)
        val p = session.group("/Policy")
        val backup = p.num("Backup")
        val keyed = p.num("Claims/Amount").semanticIndex(p.field("Key")).index()
        var json = session.toJson()
        val rules = linkedMapOf(
            "FirstRule" to Dsl.firstFilledNumber(keyed, backup.field()).gt(5),
            "PrefixRule" to Dsl.firstFilledNumber(backup.field(), keyed).gt(5),
        )
        for ((name, condition) in rules) {
            json = TypedRules.inject(json, p.rule {
                it.name(name).code(name).errorField(backup.field()).`when`(condition)
                    .message(Dsl.en("Check the claim amount.")).build()
            })
        }
        json
    }

    private fun policy(key: String?) = """{"Policy":{""" + (key?.let { "\"Key\":\"$it\"," } ?: "") +
        """"Backup":9,"Claims":[{"Code":"C1","Amount":7},{"Code":"C2"}]}}"""

    private val documents = linkedMapOf(
        "a key no row carries" to policy("C9"),
        "a key on a filled amount" to policy("C1"),
        "a key on an empty amount" to policy("C2"),
        "no key" to policy(null),
    )

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        DocumentDifferential.assertAgree(model, documents)
    }

    /** Each rule's message type on the kernel's dynamic strategy. */
    @Test
    fun aNoMatchPrefixKeepsItsOmissionProvenance() {
        val observed = documents.mapValues { (_, doc) ->
            DocumentDifferential.kernel(model, doc, dynamic = true).messages.map { it.code + "=" + it.type }.sorted()
        }
        val fallthrough = listOf("FirstRule=OMISSION_ERROR", "PrefixRule=VALUE_ERROR")
        assertEquals(linkedMapOf(
            "a key no row carries" to fallthrough,
            "a key on a filled amount" to listOf("FirstRule=VALUE_ERROR", "PrefixRule=VALUE_ERROR"),
            "a key on an empty amount" to fallthrough,
            "no key" to fallthrough,
        ), observed)
    }

    @Test
    fun theCheckerIsSilentOnTheModelA12Admits() {
        assertEquals(emptyList<String>(), check(ModelSource(model)).findings.filter { it.justifiesRejection }.map { it.code })
    }
}
