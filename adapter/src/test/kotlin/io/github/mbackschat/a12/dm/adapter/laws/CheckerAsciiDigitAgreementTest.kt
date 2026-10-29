package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.KernelConsistencyOracle
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The digits A12 reads in a condition are ASCII wherever it reads them (KERNEL-FINDINGS KF575, KF587): its lexer's
 * `DIGIT` and its date-constant patterns are `[0-9]`. A checker classifying through a platform's Unicode tables reads
 * an Arabic-Indic three as a digit, so a quoted date constant spelled with one is a date to it and a string to A12.
 * Each row is A12 against the portable checker, every class with the path it attaches to.
 *
 * The conditions are written out because the spelling is the subject: the typed builder refuses these literals.
 * Own domain (an order's shipping date and quantity).
 */
class CheckerAsciiDigitAgreementTest {

    @Test
    fun theCheckerReadsOnlyAsciiDigitsWhereA12Does() {
        val observed = CONDITIONS.associateWith { condition -> model(condition).let { kernel(it) to ours(it) } }
        assertEquals(observed.mapValues { it.value.first to it.value.first }, observed, "each condition as (A12, ours)")
    }

    private val oracle = KernelConsistencyOracle()

    private val base: String = DocumentModelDraft.named("order-digits").locale("en_US")
        .rootGroup("Order") { o -> o.stringField("Reference") { }.numberField("Quantity") { it.fractionDigits(0) }
            .dateField("ShippedOn") { it.format("dd.MM.yyyy") } }
        .build().toJson()

    private fun model(condition: String): String =
        RuleValidator().inject(base, DraftRule("/Order", "Probe", "../Reference", "FieldFilled(Reference) And $condition", "C1", "ERROR"))

    private fun kernel(json: String): List<String> = oracle.checkConsistency(json, ReferenceResolvers.selfContained())
        .map { notification ->
            val message = notification.message().trim()
            legibleNotificationClass(message)
        }.sorted()

    private fun ours(json: String): List<String> =
        rejectingCodes(json).sorted()

    private companion object {
        val THREE = Char(0x0663).toString()
        val CONDITIONS = listOf(
            "[ShippedOn] > AddDays(\"03.02.2024\", 7)",
            "[ShippedOn] > AddDays(\"0$THREE.02.2024\", 7)",
            "[ShippedOn] > \"03.02.2024\"",
            "[ShippedOn] > \"0$THREE.02.2024\"",
            "[ShippedOn] > AddDays(\"0$THREE.02.\", 7)",
            "RoundDownValue(Quantity, 2) > 1",
            "RoundDownValue(Quantity, $THREE) > 1",
        )
    }
}
