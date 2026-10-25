package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.MessageParameterProbes.kernel
import io.github.mbackschat.a12.dm.adapter.laws.MessageParameterProbes.portable
import io.github.mbackschat.a12.dm.adapter.laws.PremiumProbes.Alt
import io.github.mbackschat.a12.dm.adapter.laws.PremiumProbes.computation
import io.github.mbackschat.a12.dm.adapter.laws.PremiumProbes.premium
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * SPEC-2026-10-01-07 on our own premium carrier: where a computation's read of its own target is asked among
 * its other static gates, across expression shapes and across its alternatives.
 *
 * The 144 rows are a12-kernel-lean's `static.tsv` (the rows into the scale-0 target, ids `J…`), `settle.tsv` and
 * `settle2.tsv` (ids kept), re-expressed on `Total`, scale 0, beside the scale-0 `Base`, `Fee`, `Levy`, the
 * scale-2 `Ratio`, the String gates `Agent` and `Broker`, and the groups `Riders` and `Extras`. Each row's
 * classes are the complete list in report order, so a co-firing second gate cannot hide behind the first.
 * The discriminating pairs are `Max([Total], [Ratio])` against `Max([Total], [Base])`, `[Total] / 2` against
 * `[Total] ^ 2`, and the two-alternative rows, which separate an order from a shape list and from a
 * computation-wide names stage.
 *
 * The portable checker is held to the same rows and must name exactly the kernel's classes on every one, the
 * suppressed `S…` rows included.
 */
class ComputationGateOrderAgreementTest {
    data class Row(val id: String, val model: String, val classes: List<String>)

    private val base = premium()

    fun rows(): List<Row> = buildList {
        fun row(id: String, group: String, target: String, alternatives: List<Alt>, suppress: List<String>,
            vararg classes: String) = add(Row(id, computation(base, group, target, alternatives, suppress), classes.toList()))
        // static.tsv, the rows into the scale-0 target
        row("J01", "/Premium", "Total", listOf(Alt("FieldFilled(Total)", "[Ratio]")), NONE, "MVK_INVALID_COMPARE_DEC_PLACES")
        row("J02", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Ratio]")), NONE, "MVK_INVALID_COMPARE_DEC_PLACES")
        row("J03", "/Premium", "Total", listOf(Alt(null, "[Total]")), NONE, "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD")
        row("J04", "/Premium", "Total", listOf(Alt(null, "Abs([Total])")), NONE, "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD")
        row("J05", "/Premium", "Total", listOf(Alt(null, "Max([Total], [Base])")), NONE, "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD")
        row("J06", "/Premium", "Total", listOf(Alt(null, "RoundAccounting([Total] / 2, 0)")), NONE, "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD")
        row("J07", "/Premium", "Total", listOf(Alt(null, "[Total] ^ 2")), NONE, "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD")
        row("J08", "/Premium", "Total", listOf(Alt(null, "[Total] + [Total]")), NONE, "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD", "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD")
        row("J09", "/Premium", "Total", listOf(Alt(null, "[Total] * 2")), NONE, "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD")
        row("J10", "/Premium", "Total", listOf(Alt(null, "{[Total] * 2}")), NONE, "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD")
        row("J11", "/Premium", "Total", listOf(Alt(null, "{[Total]}")), NONE, "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD")
        row("J12", "/Premium", "Total", listOf(Alt(null, "[Total] * {2 + 3}")), NONE, "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD")
        row("J13", "/Premium", "Total", listOf(Alt(null, "Max([Total], [Ratio])")), NONE, "MVK_INVALID_COMPARE_DEC_PLACES")
        row("J14", "/Premium", "Total", listOf(Alt(null, "Abs([Total]) * [Ratio]")), NONE, "MVK_INVALID_COMPARE_DEC_PLACES")
        row("J15", "/Premium", "Total", listOf(Alt(null, "Abs([Total] * [Ratio])")), NONE, "MVK_INVALID_COMPARE_DEC_PLACES")
        row("J16", "/Premium", "Total", listOf(Alt(null, "[Total] / [Base]")), NONE, "MVK_INVALID_COMPARE_DEC_PLACES")
        row("J17", "/Premium", "Total", listOf(Alt(null, "[Total] * [Total] * [Ratio]")), NONE, "MVK_INVALID_COMPARE_DEC_PLACES")
        row("J18", "/Premium", "Total", listOf(Alt(null, "[Total] * 1.5")), NONE, "MVK_INVALID_COMPARE_DEC_PLACES")
        row("J19", "/Premium", "Total", listOf(Alt(null, "[Total] / 2")), NONE, "MVK_INVALID_COMPARE_DEC_PLACES")
        row("J20", "/Premium", "Total", listOf(Alt(null, "[Total] / 2 * 4")), NONE, "MVK_INVALID_COMPARE_DEC_PLACES")
        row("J21", "/Premium", "Total", listOf(Alt(null, "Abs([Total] / 2)")), NONE, "MVK_INVALID_COMPARE_DEC_PLACES")
        row("J22", "/Premium", "Total", listOf(Alt(null, "Max([Total] / 2, [Base])")), NONE, "MVK_INVALID_COMPARE_DEC_PLACES")
        row("J23", "/Premium", "Total", listOf(Alt(null, "{[Total] * [Ratio]}")), NONE, "MVK_INVALID_COMPARE_DEC_PLACES")
        row("J24", "/Premium", "Total", listOf(Alt(null, "{[Total] / 2} * 4")), NONE, "MVK_INVALID_COMPARE_DEC_PLACES")
        row("J25", "/Premium", "Total", listOf(Alt(null, "{[Total] / 2} * {3 / 4}")), NONE, "MVK_INVALID_COMPARE_DEC_PLACES")
        row("J26", "/Premium", "Total", listOf(Alt(null, "[Total] / [Base] / [Fee]")), NONE, "MVK_TOO_MANY_DIV_FOR_CALC")
        row("J27", "/Premium", "Total", listOf(Alt(null, "RoundAccounting([Total] / [Base] / [Fee], 0)")), NONE, "MVK_TOO_MANY_DIV_FOR_CALC")
        row("J28", "/Premium", "Total", listOf(Alt(null, "[Base] / [Fee] / [Levy]")), NONE, "MVK_TOO_MANY_DIV_FOR_CALC")
        row("J29", "/Premium", "Total", listOf(Alt(null, "RoundAccounting([Base] / [Fee] / [Levy], 0)")), NONE, "MVK_TOO_MANY_DIV_FOR_CALC")
        row("J30", "/Premium", "Total", listOf(Alt(null, "[Base] / 2")), NONE, "MVK_INVALID_COMPARE_DEC_PLACES")
        row("J31", "/Premium", "Total", listOf(Alt(null, "{[Base] / 2} * {3 / 4}")), NONE, "MVK_INVALID_COMPARE_DEC_PLACES")
        row("J32", "/Premium", "Total", listOf(Alt(null, "{[Base] * 2}")), NONE)
        row("J33a", "/Premium", "Total", listOf(Alt(null, "([Total] * 2)")), NONE, "MVK_UNEXPECTED_TOKEN")
        row("J33b", "/Premium", "Total", listOf(Alt(null, "([Base] * 2)")), NONE, "MVK_UNEXPECTED_TOKEN")
        row("J33c", "/Premium", "Total", listOf(Alt(null, "[Total] * (2 + 3)")), NONE, "MVK_UNEXPECTED_TOKEN")
        // settle.tsv
        row("A01", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Total] * 2"), Alt("FieldFilled(Broker)", "[Base] * 1.5")), NONE, "MVK_INVALID_COMPARE_DEC_PLACES")
        row("A02", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Base] * 1.5"), Alt("FieldFilled(Broker)", "[Total] * 2")), NONE, "MVK_INVALID_COMPARE_DEC_PLACES")
        row("A03", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Total]"), Alt("FieldFilled(Broker)", "[Ratio]")), NONE, "MVK_INVALID_COMPARE_DEC_PLACES")
        row("A04", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Ratio]"), Alt("FieldFilled(Broker)", "[Total]")), NONE, "MVK_INVALID_COMPARE_DEC_PLACES")
        row("A05", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Total]"), Alt("FieldFilled(Broker)", "[Fee]")), NONE, "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD")
        row("A06", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Total]"), Alt("FieldFilled(Broker)", "[Base] / [Fee] / [Levy]")), NONE, "MVK_TOO_MANY_DIV_FOR_CALC")
        row("A07", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Base] / [Fee] / [Levy]"), Alt("FieldFilled(Broker)", "[Total]")), NONE, "MVK_TOO_MANY_DIV_FOR_CALC")
        row("A08", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Base]"), Alt("FieldFilled(Broker)", "[Ratio]")), NONE, "MVK_INVALID_COMPARE_DEC_PLACES")
        row("A09", "/Premium", "Total", listOf(Alt("FieldFilled(Total)", "[Base]"), Alt("FieldFilled(Broker)", "[Ratio]")), NONE, "MVK_INVALID_COMPARE_DEC_PLACES")
        row("A10", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Total]"), Alt("FieldFilled(Broker)", "[Nope]")), NONE, "MVK_INVALID_ENTITY")
        row("W01", "/Premium", "Total", listOf(Alt(null, "Sum(Total, Base)")), NONE, "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD")
        row("W02", "/Premium", "Total", listOf(Alt(null, "Sum(Total, Ratio)")), NONE, "MVK_INVALID_COMPARE_DEC_PLACES")
        row("W03", "/Premium", "Total", listOf(Alt(null, "Sum(Total, Base) * [Ratio]")), NONE, "MVK_INVALID_COMPARE_DEC_PLACES")
        row("W04", "/Premium", "Total", listOf(Alt(null, "FirstFilledValue(Total, Base)")), NONE, "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD")
        row("W05", "/Premium", "Total", listOf(Alt(null, "FirstFilledValue(Total, Ratio)")), NONE, "MVK_INVALID_COMPARE_DEC_PLACES")
        row("W06", "/Premium", "Total", listOf(Alt(null, "Sum(Base, Fee)")), NONE)
        row("W07", "/Premium/Riders", "Count", listOf(Alt(null, "NumberOfFilledGroups(/Premium/Riders, /Premium/Extras)")), NONE, "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD")
        row("W08", "/Premium/Riders", "Count", listOf(Alt(null, "NumberOfFilledGroups(/Premium/Extras, /Premium/Riders)")), NONE, "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD")
        row("W09", "/Premium/Riders", "Count", listOf(Alt(null, "NumberOfFilledGroups(/Premium/Riders, /Premium/Extras) * 1.5")), NONE, "MVK_INVALID_COMPARE_DEC_PLACES")
        row("W10", "/Premium", "Total", listOf(Alt(null, "NumberOfFilledGroups(/Premium/Riders, /Premium/Extras)")), NONE)
        row("P01", "/Premium", "Total", listOf(Alt(null, "[Total] ^ 2 ^ 2")), NONE, "MVK_TOO_MANY_POW_FOR_CALC")
        row("P02", "/Premium", "Total", listOf(Alt(null, "[Base] ^ 2 ^ 2")), NONE, "MVK_TOO_MANY_POW_FOR_CALC")
        row("P03", "/Premium", "Total", listOf(Alt(null, "Abs(2) + [Total]")), NONE, "MVK_NO_CONST")
        row("P04", "/Premium", "Total", listOf(Alt(null, "Abs(2) + [Base]")), NONE, "MVK_NO_CONST")
        row("P05", "/Premium", "Total", listOf(Alt(null, "[Total] ^ 0.5")), NONE, "MVK_INVALID_NUMBER_FOR_EXP")
        row("P06", "/Premium", "Total", listOf(Alt(null, "[Base] ^ 0.5")), NONE, "MVK_INVALID_NUMBER_FOR_EXP")
        row("P07", "/Premium", "Total", listOf(Alt(null, "[Total] + [Agent]")), NONE, "MVK_NO_STRING_OR_NUMBER_FOR_ADD")
        row("P08", "/Premium", "Total", listOf(Alt(null, "[Base] + [Agent]")), NONE, "MVK_NO_STRING_OR_NUMBER_FOR_ADD")
        row("P09", "/Premium", "Total", listOf(Alt(null, "[Total] * [Agent]")), NONE, "MVK_NO_NUMBER_FOR_CALC")
        // settle2.tsv
        row("B01", "/Premium", "Total", listOf(Alt(null, "[Total]"), Alt("FieldFilled(Broker)", "[Base]")), NONE, "MVK_ERROR_EMPTY_PRECONDITION")
        row("B02", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Base]"), Alt(null, "[Total]")), NONE, "MVK_ERROR_EMPTY_PRECONDITION")
        row("B03", "/Premium", "Total", listOf(Alt(null, "[Total] * 2"), Alt("FieldFilled(Broker)", "[Base]")), NONE, "MVK_ERROR_EMPTY_PRECONDITION")
        row("B04", "/Premium", "Total", listOf(Alt(null, "[Total]"), Alt(null, "[Base]")), NONE, "MVK_ERROR_EMPTY_PRECONDITION")
        row("B05", "/Premium", "Total", listOf(Alt(null, "[Base]"), Alt("FieldFilled(Broker)", "[Total]")), NONE, "MVK_ERROR_EMPTY_PRECONDITION", "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD")
        row("B06", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Total]"), Alt(null, "[Base]")), NONE, "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD", "MVK_ERROR_EMPTY_PRECONDITION")
        row("B07", "/Premium", "Total", listOf(Alt("FieldFilled(Total)", "[Base]"), Alt(null, "[Total]")), NONE, "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD", "MVK_ERROR_EMPTY_PRECONDITION")
        row("B08", "/Premium", "Total", listOf(Alt(null, "[Total]"), Alt("FieldFilled(Broker)", "[Total]")), NONE, "MVK_ERROR_EMPTY_PRECONDITION", "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD")
        row("B09", "/Premium", "Total", listOf(Alt(null, "[Base]"), Alt(null, "[Fee]")), NONE, "MVK_ERROR_EMPTY_PRECONDITION")
        row("B10", "/Premium", "Total", listOf(Alt(null, "[Base]"), Alt("FieldFilled(Broker)", "[Fee]")), NONE, "MVK_ERROR_EMPTY_PRECONDITION")
        row("B11", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Base]"), Alt(null, "[Fee]")), NONE, "MVK_ERROR_EMPTY_PRECONDITION")
        row("U01", "/Premium", "Total", listOf(Alt(null, "[Ratio]"), Alt("FieldFilled(Broker)", "[Base]")), NONE, "MVK_INVALID_COMPARE_DEC_PLACES")
        row("U02", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Base]"), Alt(null, "[Ratio]")), NONE, "MVK_INVALID_COMPARE_DEC_PLACES")
        row("U03", "/Premium", "Total", listOf(Alt(null, "[Base] / [Fee] / [Levy]"), Alt("FieldFilled(Broker)", "[Base]")), NONE, "MVK_TOO_MANY_DIV_FOR_CALC")
        row("U04", "/Premium", "Total", listOf(Alt(null, "[Base] ^ 2 ^ 2"), Alt("FieldFilled(Broker)", "[Base]")), NONE, "MVK_TOO_MANY_POW_FOR_CALC")
        row("U05", "/Premium", "Total", listOf(Alt(null, "[Nope]"), Alt("FieldFilled(Broker)", "[Base]")), NONE, "MVK_INVALID_ENTITY")
        row("U06", "/Premium", "Total", listOf(Alt(null, "[Base] + [Agent]"), Alt("FieldFilled(Broker)", "[Base]")), NONE, "MVK_NO_STRING_OR_NUMBER_FOR_ADD")
        row("U07", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Ratio]"), Alt(null, "[Total]")), NONE, "MVK_INVALID_COMPARE_DEC_PLACES")
        row("U08", "/Premium", "Total", listOf(Alt(null, "[Total]"), Alt("FieldFilled(Broker)", "[Ratio]")), NONE, "MVK_INVALID_COMPARE_DEC_PLACES")
        row("U09", "/Premium", "Total", listOf(Alt(null, "[Ratio]"), Alt(null, "[Total]")), NONE, "MVK_INVALID_COMPARE_DEC_PLACES")
        row("U10", "/Premium", "Total", listOf(Alt(null, "[Total]")), NONE, "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD")
        row("U11", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Base]"), Alt(null, "[Base] / [Fee] / [Levy]")), NONE, "MVK_TOO_MANY_DIV_FOR_CALC")
        row("U12", "/Premium", "Total", listOf(Alt("FieldFilled(Total)", "[Base]"), Alt(null, "[Fee]")), NONE, "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD", "MVK_ERROR_EMPTY_PRECONDITION")
        row("U13", "/Premium", "Total", listOf(Alt(null, "[Base]"), Alt("FieldFilled(Total)", "[Fee]")), NONE, "MVK_ERROR_EMPTY_PRECONDITION", "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD")
        row("G01", "/Premium", "Total", listOf(Alt("FieldFilled(Nope)", "[Base]"), Alt("FieldFilled(Broker)", "[Ratio]")), NONE, "MVK_INVALID_ENTITY")
        row("G02", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Ratio]"), Alt("FieldFilled(Nope)", "[Base]")), NONE, "MVK_INVALID_COMPARE_DEC_PLACES")
        row("G03", "/Premium", "Total", listOf(Alt("FieldFilled(Nope)", "[Base]"), Alt("FieldFilled(Broker)", "[Base] / [Fee] / [Levy]")), NONE, "MVK_INVALID_ENTITY")
        row("G04", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Base] / [Fee] / [Levy]"), Alt("FieldFilled(Nope)", "[Base]")), NONE, "MVK_TOO_MANY_DIV_FOR_CALC")
        row("G05", "/Premium", "Total", listOf(Alt("FieldFilled(Nope)", "[Base]"), Alt("FieldFilled(Broker)", "[Total]")), NONE, "MVK_INVALID_ENTITY")
        row("G06", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Total]"), Alt("FieldFilled(Nope)", "[Base]")), NONE, "MVK_INVALID_ENTITY")
        row("O01", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Ratio]"), Alt("FieldFilled(Broker)", "[Nope]")), NONE, "MVK_INVALID_COMPARE_DEC_PLACES")
        row("O02", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Nope]"), Alt("FieldFilled(Broker)", "[Ratio]")), NONE, "MVK_INVALID_ENTITY")
        row("O03", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Ratio]"), Alt("FieldFilled(Broker)", "[Base] / [Fee] / [Levy]")), NONE, "MVK_INVALID_COMPARE_DEC_PLACES")
        row("O04", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Base] / [Fee] / [Levy]"), Alt("FieldFilled(Broker)", "[Ratio]")), NONE, "MVK_TOO_MANY_DIV_FOR_CALC")
        row("O05", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Ratio]"), Alt("FieldFilled(Broker)", "[Base] ^ 2 ^ 2")), NONE, "MVK_INVALID_COMPARE_DEC_PLACES")
        row("O06", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Base] ^ 2 ^ 2"), Alt("FieldFilled(Broker)", "[Ratio]")), NONE, "MVK_TOO_MANY_POW_FOR_CALC")
        row("O07", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Base] / [Fee] / [Levy]"), Alt("FieldFilled(Broker)", "[Base] ^ 2 ^ 2")), NONE, "MVK_TOO_MANY_DIV_FOR_CALC")
        row("O08", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Base] ^ 2 ^ 2"), Alt("FieldFilled(Broker)", "[Base] / [Fee] / [Levy]")), NONE, "MVK_TOO_MANY_POW_FOR_CALC")
        row("O09", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Nope]"), Alt("FieldFilled(Broker)", "[Base] / [Fee] / [Levy]")), NONE, "MVK_INVALID_ENTITY")
        row("O10", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Base] / [Fee] / [Levy]"), Alt("FieldFilled(Broker)", "[Nope]")), NONE, "MVK_TOO_MANY_DIV_FOR_CALC")
        row("O11", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Ratio]"), Alt("FieldFilled(Broker)", "[Base] + [Agent]")), NONE, "MVK_INVALID_COMPARE_DEC_PLACES")
        row("O12", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Base] + [Agent]"), Alt("FieldFilled(Broker)", "[Ratio]")), NONE, "MVK_NO_STRING_OR_NUMBER_FOR_ADD")
        row("O13", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Nope]"), Alt("FieldFilled(Broker)", "[Nope2]")), NONE, "MVK_INVALID_ENTITY")
        row("O14", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Ratio]"), Alt("FieldFilled(Broker)", "[Ratio]")), NONE, "MVK_INVALID_COMPARE_DEC_PLACES")
        row("O15", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "Sum(Total, Base)"), Alt("FieldFilled(Broker)", "[Ratio]")), NONE, "MVK_INVALID_COMPARE_DEC_PLACES")
        row("D01", "/Premium", "Total", listOf(Alt("FieldFilled(Total)", "[Total]")), NONE, "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD", "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD")
        row("D02", "/Premium", "Total", listOf(Alt("FieldFilled(Total)", "[Base]"), Alt("FieldFilled(Broker)", "[Total]")), NONE, "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD", "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD")
        row("D03", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Total]"), Alt("FieldFilled(Total)", "[Base]")), NONE, "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD", "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD")
        row("D04", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Total]"), Alt("FieldFilled(Broker)", "[Total]")), NONE, "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD", "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD")
        row("D05", "/Premium", "Total", listOf(Alt("FieldFilled(Total)", "[Total]"), Alt("FieldFilled(Broker)", "[Base]")), NONE, "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD", "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD")
        row("D06", "/Premium", "Total", listOf(Alt("FieldFilled(Total)", "[Base]"), Alt("FieldFilled(Total)", "[Fee]")), NONE, "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD", "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD")
        row("D07", "/Premium", "Total", listOf(Alt("FieldFilled(Total)", "[Total]"), Alt("FieldFilled(Broker)", "[Ratio]")), NONE, "MVK_INVALID_COMPARE_DEC_PLACES")
        row("S01", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Total]"), Alt("FieldFilled(Broker)", "[Ratio]")), SUPPRESSED, "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD")
        row("S02", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Ratio]"), Alt("FieldFilled(Broker)", "[Total]")), SUPPRESSED, "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD")
        row("S03", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Ratio]"), Alt("FieldFilled(Broker)", "[Base] / [Fee] / [Levy]")), SUPPRESSED, "MVK_TOO_MANY_DIV_FOR_CALC")
        row("S04", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Ratio]"), Alt("FieldFilled(Broker)", "[Nope]")), SUPPRESSED, "MVK_INVALID_ENTITY")
        row("S05", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Total] * 1.5"), Alt("FieldFilled(Broker)", "[Base]")), SUPPRESSED, "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD")
        row("S06", "/Premium", "Total", listOf(Alt(null, "[Ratio]")), SUPPRESSED)
        row("S07", "/Premium", "Total", listOf(Alt(null, "[Total]"), Alt("FieldFilled(Broker)", "[Ratio]")), SUPPRESSED, "MVK_ERROR_EMPTY_PRECONDITION")
        row("S08", "/Premium", "Total", listOf(Alt(null, "[Ratio]"), Alt("FieldFilled(Broker)", "[Base]")), SUPPRESSED, "MVK_ERROR_EMPTY_PRECONDITION")
        row("T01", "/Premium", "Total", listOf(Alt(null, "[Base]"), Alt("FieldFilled(Broker)", "[Total]"), Alt(null, "[Fee]")), NONE, "MVK_ERROR_EMPTY_PRECONDITION", "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD")
        row("T02", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Total]"), Alt(null, "[Base]"), Alt("FieldFilled(Broker)", "[Total]")), NONE, "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD", "MVK_ERROR_EMPTY_PRECONDITION", "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD")
        row("T03", "/Premium", "Total", listOf(Alt(null, "[Base]"), Alt(null, "[Fee]"), Alt("FieldFilled(Broker)", "[Total]")), NONE, "MVK_ERROR_EMPTY_PRECONDITION", "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD")
        row("T04", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Total]"), Alt(null, "[Base]"), Alt(null, "[Fee]")), NONE, "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD", "MVK_ERROR_EMPTY_PRECONDITION")
        row("T05", "/Premium", "Total", listOf(Alt(null, "[Total]"), Alt(null, "[Total]"), Alt("FieldFilled(Broker)", "[Total]")), NONE, "MVK_ERROR_EMPTY_PRECONDITION", "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD")
        row("T06", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Base]"), Alt("FieldFilled(Broker)", "[Fee]"), Alt(null, "[Total]")), NONE, "MVK_ERROR_EMPTY_PRECONDITION")
        row("T07", "/Premium", "Total", listOf(Alt("FieldFilled(Nope)", "[Ratio]")), NONE, "MVK_INVALID_ENTITY")
        row("T08", "/Premium", "Total", listOf(Alt("FieldFilled(Nope)", "[Base] / [Fee] / [Levy]")), NONE, "MVK_INVALID_ENTITY")
        row("T09", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Ratio] + [Nope]")), NONE, "MVK_INVALID_ENTITY")
        row("T10", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Nope] + [Ratio]")), NONE, "MVK_INVALID_ENTITY")
        row("T11", "/Premium", "Total", listOf(Alt(null, "[Total] + [Total]"), Alt("FieldFilled(Broker)", "[Total] + [Total]")), NONE, "MVK_ERROR_EMPTY_PRECONDITION", "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD", "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD")
        row("T12", "/Premium", "Total", listOf(Alt("FieldFilled(Total)", "[Total] + [Total]")), NONE, "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD", "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD", "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD")
        row("E01", "/Premium", "Total", listOf(Alt("FieldFilled(Total)", "[Base]"), Alt("FieldFilled(Broker)", "[Fee]")), NONE, "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD")
        row("E02", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Base]"), Alt("FieldFilled(Broker)", "[Fee]")), NONE)
        row("C01", "/Premium", "Total", listOf(Alt(null, "[Base]"), Alt("FieldFilled(Nope)", "[Fee]")), NONE, "MVK_INVALID_ENTITY")
        row("C02", "/Premium", "Total", listOf(Alt("FieldFilled(Nope)", "[Fee]"), Alt(null, "[Base]")), NONE, "MVK_INVALID_ENTITY")
        row("C03", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Base] ^ 2 ^ 2"), Alt("FieldFilled(Broker)", "[Nope]")), NONE, "MVK_TOO_MANY_POW_FOR_CALC")
        row("C04", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Nope]"), Alt("FieldFilled(Broker)", "[Base] ^ 2 ^ 2")), NONE, "MVK_INVALID_ENTITY")
        row("C05", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Base] + [Agent]"), Alt("FieldFilled(Broker)", "[Nope]")), NONE, "MVK_NO_STRING_OR_NUMBER_FOR_ADD")
        row("C06", "/Premium", "Total", listOf(Alt("FieldFilled(Agent)", "[Nope]"), Alt("FieldFilled(Broker)", "[Base] + [Agent]")), NONE, "MVK_INVALID_ENTITY")

    }

    @Test
    fun theTargetReadIsAskedAfterTheAuthoringAndScaleGatesOneAlternativeAtATime() {
        val rows = rows()
        assertEquals(144, rows.size, "a12-kernel-lean's three verdict tables, every row into a computed target")
        assertEquals(rows.size, rows.map { it.id }.toSet().size, "observations retain independent identities")
        assertEquals(rows.map { it.id to it.classes }, rows.map { it.id to kernel(it.model) }, "kernel classes")
        assertEquals(rows.map { it.id to it.classes }, rows.map { it.id to portable(it.model) }, "portable classes")
    }

    private companion object {
        val NONE = emptyList<String>()
        val SUPPRESSED = listOf("MVK_INVALID_COMPARE_DEC_PLACES")
    }
}
