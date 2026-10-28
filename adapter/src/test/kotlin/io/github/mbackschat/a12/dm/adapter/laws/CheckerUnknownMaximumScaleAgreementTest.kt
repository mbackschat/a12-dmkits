package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The checker against A12 — a Number whose `maxFractionalDigits` is `-1` carries an UNKNOWN scale, not a scale of
 * minus one (KERNEL-FINDINGS KF625, a12-kernel-lean SPEC-2026-10-26-25).
 *
 * `-1` is A12's `IFieldType.UNDEFINED`. The scale gate behind `==` / `!=` admits a pair only when both derived
 * scales are known, so every equality that such a field reaches is refused — against itself, against a second
 * unknown field, through a sum or a product with a constant, and as the computation that writes it or the one it
 * writes. Ordering is not scale-gated, a rounding names its own scale, and an open MINIMUM (`-1, 2`) leaves the
 * maximum known, so those rows are admitted. An exponent needs a known whole scale, so it is refused too.
 *
 * The checker once read `-1` as a known scale: equal to itself, absorbed by `max` in a sum, made "expandable" by
 * a constant factor. So `R1`, `R2`, `R6`, `R9`, `R10`, `R12` and `C3` passed while A12 refused them, and `R3`,
 * `C1`, `C2` agreed only because `-1 ≠ 2`. The `I` and `D` rows lock the two sibling readers of the same member,
 * which already read `-1` as A12 does: an index key on it admits a whole constant only, and a date component on
 * it is scale-free.
 *
 * Own domain (an insurance premium with rates, fees and keyed amounts).
 */
class CheckerUnknownMaximumScaleAgreementTest {

    private val premium = DocumentModelDraft.named("premium-unknown-scale").locale("en_US")
        .rootGroup("Premium") { p -> p
            .numberField("Rate") { it.fractionDigits(2, -1) }
            .numberField("Rate2") { it.fractionDigits(2, -1) }
            .numberField("Fee") { it.fractionDigits(2, 2) }
            .numberField("Count") { it.fractionDigits(0) }
            .numberField("MinOpen") { it.fractionDigits(-1, 2) }
            .numberField("ZeroOpen") { it.fractionDigits(0, -1) }
            .stringField("Code") { }
            .numberField("HourOpen") { it.fractionDigits(0, -1).min("0").max("23") }
            .numberField("Hour") { it.fractionDigits(0).min("0").max("23") }
            .timeField("Clock") { it.format("HH:mm:ss") }
            .group("Lines") { l -> l.repeatable(3).numberField("LineRate") { it.fractionDigits(2, -1) }
                .numberField("LineFee") { it.fractionDigits(2, 2) }.stringField("LineNote") { } }
            .group("Keys") { k -> k.repeatable(3).indexField("K").numberField("K") { it.fractionDigits(0, -1) }
                .numberField("Amount") { it.fractionDigits(2) } } }
        .build().toJson()

    private val validator = RuleValidator()

    @Test
    fun anUnknownMaximumScaleIsJudgedAsA12JudgesIt() {
        val actual = ROWS.mapValues { (_, row) ->
            val (target, text) = row
            if (target.startsWith("=")) computed(target.removePrefix("="), text) else judged(target, text)
        }
        assertEquals(ROWS.mapValues { (row, _) -> A12.getValue(row).let { it to it } }, actual, "as (A12, ours)")
        assertEquals(emptySet<String>(), rejections(premium), "the model alone is admitted")
    }

    private fun rejections(json: String) =
        check(ModelSource(json)).findings.filter { it.justifiesRejection }.mapTo(HashSet()) { it.code }

    private fun judged(errorField: String, condition: String): Pair<Set<String>, Set<String>> {
        val draft = DraftRule("/Premium", "ScaleProbe", "../$errorField", condition, "PROBE", "ERROR")
        val a12 = validator.check(premium, draft)
            .let { r -> if (r.ok()) emptySet() else r.diagnostics().mapTo(HashSet()) { it.ledgerClass() } }
        return a12 to rejections(validator.inject(premium, draft))
    }

    private fun computed(target: String, operation: String): Pair<Set<String>, Set<String>> {
        val json = RuntimeLaws.withComputation(premium, "/Premium", "ScaleCalc", "../$target", operation)
        val a12 = RuntimeLaws.consistencyMessages(json).filter { it.startsWith("ERROR") }
            .mapTo(HashSet()) { it.substringAfterLast('[').trimEnd(']') }
        return a12 to rejections(json)
    }

    private companion object {
        /** Each row's error field (or, after `=`, its computation target) and its condition or operation. */
        val ROWS = linkedMapOf(
            "R1 against itself" to ("Rate" to "[Rate] == [Rate]"),
            "R2 two unknown fields" to ("Rate" to "[Rate] == [Rate2]"),
            "R3 unknown against two" to ("Rate" to "[Rate] != [Fee]"),
            "R4 ordering" to ("Rate" to "[Rate] > [Fee]"),
            "R5 ordering a sum" to ("Rate" to "[Rate] + 1 > 0"),
            "R6 exponent" to ("Count" to "[Count] ^ [Rate] > 1"),
            "R7 rounded value" to ("Rate" to "RoundAccountingValue(Rate, 2) == [Fee]"),
            "R8 rounded expression" to ("Rate" to "RoundAccounting([Rate] * 1, 2) == [Fee]"),
            "R9 aggregate" to ("Rate" to "Sum(Lines*/LineRate) == [Rate]"),
            "R10 constant product" to ("Rate" to "[Rate] * 1 == [Fee]"),
            "R11 open minimum" to ("MinOpen" to "[MinOpen] == [Fee]"),
            "R12 sum against two" to ("Rate" to "[Rate] + [Fee] == [Fee]"),
            "I1 whole key" to ("Rate" to "FieldFilled(Rate) And [Keys/Amount For \"5\"] > 0"),
            "I2 one-digit key" to ("Rate" to "FieldFilled(Rate) And [Keys/Amount For \"5.5\"] > 0"),
            "I3 three-digit key" to ("Rate" to "FieldFilled(Rate) And [Keys/Amount For \"5.555\"] > 0"),
            "D1 known-scale hour" to ("Hour" to "Time(Hour) > [Clock]"),
            "D2 unknown-scale hour" to ("HourOpen" to "Time(HourOpen) > [Clock]"),
            "C1 unknown into two" to ("=Fee" to "[Rate]"),
            "C2 two into unknown" to ("=Rate" to "[Fee]"),
            "C3 unknown into unknown" to ("=Rate" to "[Rate2]"),
            "C4 rounded into two" to ("=Fee" to "RoundAccounting([Rate], 2)"),
            "K1 open minimum zero" to ("ZeroOpen" to "[ZeroOpen] == 1"),
            "K2 a row the rule iterates" to ("Lines/LineNote" to "FieldFilled(Lines/LineNote) And [Lines/LineRate] == 1.00"),
            "K3 the same row at two" to ("Lines/LineNote" to "FieldFilled(Lines/LineNote) And [Lines/LineFee] == 1.00"),
            "K4 a filter body" to ("Rate" to "FieldFilled(Rate) And MaxValue(Lines*/LineFee Having [Lines/LineRate] == 1.00) > 0"),
            "K5 an extremum" to ("Rate" to "FieldFilled(Rate) And MaxValue(Lines*/LineRate) == 1.00"),
            "K6 an extremum at two" to ("Rate" to "FieldFilled(Rate) And MaxValue(Lines*/LineFee) == 1.00"),
            "K7 a sum at two" to ("Rate" to "FieldFilled(Rate) And Sum(Lines*/LineFee) == 1.00"),
            "C5 a constant into unknown" to ("=Rate" to "1.5"),
            "C6 a length into unknown" to ("=ZeroOpen" to "Length(Code)"),
            "C7 a length into whole" to ("=Count" to "Length(Code)"),
            "C8 first filled with unknown" to ("=Fee" to "FirstFilledValue(Rate, Fee)"),
            "C9 smaller of unknown" to ("=Fee" to "Min([Rate], [Fee])"),
        )

        private const val SCALE = "MVK_INVALID_COMPARE_DEC_PLACES"

        /** Measured through `RuleValidator` and `checkConsistency`, kernel 30.8.1, 2026-10-27; the `K` rows and `C5`
         *  to `C9` carry a12-kernel-lean SPEC-2026-10-28-01's carriers, 2026-10-28. */
        val A12 = mapOf(
            "R1 against itself" to setOf(SCALE),
            "R2 two unknown fields" to setOf(SCALE),
            "R3 unknown against two" to setOf(SCALE),
            "R4 ordering" to emptySet(),
            "R5 ordering a sum" to emptySet(),
            "R6 exponent" to setOf("MVK_INVALID_NUMBER_FOR_EXP"),
            "R7 rounded value" to emptySet(),
            "R8 rounded expression" to emptySet(),
            "R9 aggregate" to setOf(SCALE),
            "R10 constant product" to setOf(SCALE),
            "R11 open minimum" to emptySet(),
            "R12 sum against two" to setOf(SCALE),
            "I1 whole key" to emptySet(),
            "I2 one-digit key" to setOf("MVK_INDEX_VALUE_INVALID"),
            "I3 three-digit key" to setOf("MVK_INDEX_VALUE_INVALID"),
            "D1 known-scale hour" to emptySet(),
            "D2 unknown-scale hour" to emptySet(),
            "C1 unknown into two" to setOf(SCALE),
            "C2 two into unknown" to setOf(SCALE),
            "C3 unknown into unknown" to setOf(SCALE),
            "C4 rounded into two" to emptySet(),
            "K1 open minimum zero" to setOf(SCALE),
            "K2 a row the rule iterates" to setOf(SCALE),
            "K3 the same row at two" to emptySet(),
            "K4 a filter body" to setOf(SCALE),
            "K5 an extremum" to setOf(SCALE),
            "K6 an extremum at two" to emptySet(),
            "K7 a sum at two" to emptySet(),
            "C5 a constant into unknown" to setOf(SCALE),
            "C6 a length into unknown" to setOf(SCALE),
            "C7 a length into whole" to emptySet(),
            "C8 first filled with unknown" to setOf(SCALE),
            "C9 smaller of unknown" to setOf(SCALE),
        )
    }
}
