package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * An Enumeration declaring the empty stored token, against the Kernel (a12-kernel-lean SPEC-2026-10-14-04). The
 * declaration loads, but on kernel 31.1.1 the token is not one: A12's expand copy reads the empty `value` as an absent
 * one ([KERNEL-FINDINGS KF658]), so `""` is no condition literal even where it is declared, exactly as where it is not
 * (on 30.8.1 it was a literal where declared). A cell never holds it either: a stored `""` reads as an absent cell.
 * The control Enumeration declares no empty token.
 *
 * Own domain (a gift order's wrapping, where "no wrapping" is declared as the empty token, and its ribbon).
 */
class EmptyEnumerationTokenDiffTest {

    private val order = DocumentModelDraft.named("gift-order").locale("en_US")
        .rootGroup("Gift") { g -> g
            .enumField("Wrapping") { e -> e.value("").value("Gold") }
            .enumField("Ribbon") { e -> e.value("Red").value("Gold") }
            .stringField("Note") { } }
        .build().toJson()

    private val validator = RuleValidator()

    @Test
    fun theEmptyTokenIsNoLiteralEvenWhereDeclared() {
        val outsideDomain = setOf("MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION")
        val rows = linkedMapOf(
            "membership in the empty token" to ("FieldValueIncludedInValueList(Wrapping, \"\")" to outsideDomain),
            "non-membership in the empty token" to ("FieldValueNotIncludedInValueList(Wrapping, \"\")" to outsideDomain),
            "equality with the empty token" to
                ("[Wrapping] == \"\"" to setOf("MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON")),
            "membership in a declared token" to ("FieldValueIncludedInValueList(Wrapping, \"Gold\")" to emptySet()),
            "membership in the empty token where none is declared" to
                ("FieldValueIncludedInValueList(Ribbon, \"\")" to setOf("MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION")),
        )
        val observed = rows.mapValues { (_, row) ->
            val draft = DraftRule("/Gift", "ProbeRule", "../Wrapping", row.first, "PROBE", "ERROR")
            val result = validator.check(order, draft)
            val a12 = if (result.ok()) emptySet() else result.diagnostics().map { it.code().orElse("?") }.toSet()
            val ours = rejectingCodes(validator.inject(order, draft)).toSet()
            a12 to ours
        }
        assertEquals(rows.mapValues { (_, row) -> row.second to row.second }, observed,
            "(A12, ours) moved off the measured literal gate")
    }

    @Test
    fun aStoredEmptyTokenReadsAsAnAbsentCell() {
        val bound = BoundModel.ofJson(order)
        val wrapping = bound.enumOf("/Gift/Wrapping")
        val ribbon = bound.enumOf("/Gift/Ribbon")
        val rules = linkedMapOf<String, Condition>(
            "WRAPPING_FILLED" to bound.field("/Gift/Wrapping").filled(),
            "WRAPPING_EMPTY" to bound.field("/Gift/Wrapping").notFilled(),
            "RIBBON_FILLED" to bound.field("/Gift/Ribbon").filled(),
            "RIBBON_EMPTY" to bound.field("/Gift/Ribbon").notFilled(),
            "WRAPPING_GOLD" to wrapping.`in`("Gold"),
            "WRAPPING_NOT_GOLD" to wrapping.notIn("Gold"),
            "RIBBON_NOT_GOLD" to ribbon.notIn("Gold"),
        )
        val model = rules.entries.fold(order) { json, (code, condition) ->
            val target = if (code.startsWith("RIBBON")) "/Gift/Ribbon" else "/Gift/Wrapping"
            TypedRules.inject(json, bound.rule(bound.group("/Gift")).name("${code.lowercase()}_rule")
                .errorField(bound.field(target)).`when`(condition)
                .code(code).severity(Severity.ERROR).message(Dsl.en("Check the gift.")).build())
        }
        val documents = linkedMapOf(
            "stored empty" to """{"Gift":{"Wrapping":"","Ribbon":"","Note":"n"}}""",
            "absent" to """{"Gift":{"Note":"n"}}""",
            "filled" to """{"Gift":{"Wrapping":"Gold","Ribbon":"Gold","Note":"n"}}""",
            "undeclared" to """{"Gift":{"Wrapping":"Silver","Ribbon":"Silver","Note":"n"}}""",
        )
        val omissions = listOf("RIBBON_EMPTY", "WRAPPING_EMPTY")
        val expected = mapOf(
            "stored empty" to omissions,
            "absent" to omissions,
            "filled" to listOf("RIBBON_FILLED", "WRAPPING_FILLED", "WRAPPING_GOLD"),
            "undeclared" to listOf("stringFalschesMuster", "stringFalschesMuster"),
        )
        val kernel = documents.mapValues { (_, json) -> RuntimeLaws.jsonIngressCodes(model, json) }
        assertEquals(expected, kernel, "the Kernel moved off the measured empty-token documents")
        val prepared = PreparedModel.loadExpandedJson(model)
        val engine = prepared.createInterpreter()
        val interpreted = documents.mapValues { (_, json) ->
            engine.validateFull(prepared.documents.readDocument(json)).findings.map { it.code }.sorted()
        }
        assertEquals(kernel, interpreted, "the interpreter's reading of the empty token drifted from the Kernel")
    }
}
