package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.KernelConsistencyOracle
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Whether a date constant before the Gregorian cutover names a real day, which A12 decides on the Julian–Gregorian
 * hybrid calendar (KERNEL-FINDINGS KF588): `DateUtils` parses the shape with a non-lenient `SimpleDateFormat`, so the
 * ten labels 1582-10-05 to 1582-10-14 are no date and a Julian leap day such as 29.02.1500 is one. Each row is A12
 * against the portable checker.
 *
 * The rival reading is the proleptic Gregorian calendar, which answers the opposite on both; 04.10.1582 and
 * 15.10.1582 (real in both) and 29.02.1700 (unreal in both) are the rows the two calendars agree on. The date-time
 * stamp shape is judged by the same day rule. The conditions are written out because
 * the spelling is the subject. Own domain (an order's shipping date).
 */
class CheckerHybridDateConstantAgreementTest {

    @Test
    fun aConstantIsRealOnTheHybridCalendar() {
        val observed = CONDITIONS.associateWith { condition -> model(condition).let { kernel(it) to ours(it) } }
        assertEquals(observed.mapValues { it.value.first to it.value.first }, observed, "each condition as (A12, ours)")
    }

    private val oracle = KernelConsistencyOracle()

    private val base: String = DocumentModelDraft.named("order-digits").locale("en_US")
        .rootGroup("Order") { o -> o.stringField("Reference") { }.numberField("Quantity") { it.fractionDigits(0) }
            .dateField("ShippedOn") { it.format("dd.MM.yyyy") }.dateTimeField("PackedAt") { it.format("yyyy-MM-dd'T'HH:mm:ss") } }
        .build().toJson()

    private fun model(condition: String): String =
        RuleValidator().inject(base, DraftRule("/Order", "Probe", "../Reference", "FieldFilled(Reference) And $condition", "C1", "ERROR"))

    private fun kernel(json: String): List<String> = oracle.checkConsistency(json, ReferenceResolvers.selfContained())
        .map { notification ->
            val message = notification.message().trim()
            Regex("\\[(\\w+)]$").find(message)?.groupValues?.get(1)
                ?: codelessClass(message).let { if (it == UNKEYED_A12_CLASS) "codeless:$message" else it }
        }.sorted()

    private fun ours(json: String): List<String> =
        check(ModelSource(json)).findings.filter { it.justifiesRejection }.map { it.code }.sorted()

    private companion object {
        val CONDITIONS = listOf("04.10.1582", "05.10.1582", "14.10.1582", "15.10.1582", "29.02.1500", "29.02.1300", "29.02.1700")
            .flatMap { listOf("[ShippedOn] > AddDays(\"$it\", 7)", "[ShippedOn] > \"$it\"") } + listOf(
            "[ShippedOn] > AddDays(\"29.02.\", 7)",
        ) + listOf("04.10.1582", "05.10.1582", "14.10.1582", "15.10.1582", "29.02.1500", "29.02.1700")
            .map { "[PackedAt] > \"${it}T00:00:00\"" }
    }
}
