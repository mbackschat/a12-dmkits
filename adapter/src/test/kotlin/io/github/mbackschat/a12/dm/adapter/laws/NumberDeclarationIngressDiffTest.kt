package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.NumberTrait
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A Number declaration as the runtime reads it, and a quoted Number as A12's Document JSON ingress converts it,
 * each against the Kernel through the same ingress route (KERNEL-FINDINGS KF564, SPEC-2026-10-13-13 and -15).
 *
 * Every row is one document holding one field, so a code names its field. [KERNEL] pins what the Kernel answered
 * when this matrix was measured, and the interpreter must answer the same; a drift on either side fails. The
 * three mechanisms the matrix separates:
 *
 * - the `-1` fractional sentinel resolves into an ordinary pair, except a `-1` minimum beside a zero maximum,
 *   which arms neither the separator nor the fractional-count test (`loose` rows);
 * - a declared bound is compared as the text printed from its nearest double: fixed places round the double's
 *   shortest decimal half up when both fractional members equal `n ≥ 0` (`5.555` admits `5.56`);
 * - a quoted Number converts by the host decimal grammar, non-ASCII decimal digits included, and a converted value
 *   never meets the 99,999-unit technical guard, which only text the converter kept can reach; it still meets
 *   the charset gate.
 */
class NumberDeclarationIngressDiffTest {

    private val model = DocumentModelDraft.named("premium-number-declarations").locale("en_US")
        .rootGroup("Premium") { p ->
            p.numberField("OpenFloor") { it.fractionDigits(-1, 2) }
                .numberField("FeeTwoPlaces") { it.fractionDigits(2, -1) }
                .numberField("FeeOnePlace") { it.fractionDigits(1, -1) }
                .numberField("UnitsBare") { it.fractionDigits(-1) }
                .numberField("Loose") { it.fractionDigits(-1, 0) }
                .numberField("CapRounded") { it.fractionDigits(2, 2).max("5.555") }
                .numberField("CapHalf") { it.fractionDigits(2, 2).max("0.125") }
                .numberField("CapWhole") { it.fractionDigits(0, 0).max("12.5") }
                .numberField("FloorShort") { it.fractionDigits(2).min("1.0") }
                .numberField("Units") { it.fractionDigits(0) }
                .numberField("Rate") { it.fractionDigits(2) }
                .numberField("ZeroLed") { it.fractionDigits(0).leadingZerosAllowed() }
                .numberField("Amount") { it.fractionDigits(2, 2).unit(NumberTrait.AMOUNT) }
        }.build().toJson()

    private val prepared by lazy { PreparedModel.loadExpandedJson(model) }
    private val interpreter by lazy { prepared.createInterpreter() }

    @Test
    fun theKernelAnswersThePinnedCodesAndTheInterpreterAgrees() {
        val kernel = KERNEL.associate { (field, json, _) -> label(field, json) to kernelCodes(field, json) }
        assertEquals(KERNEL.associate { (field, json, codes) -> label(field, json) to codes }, kernel,
            "the Kernel moved off the measured matrix")
        val interpreted = KERNEL.associate { (field, json, _) -> label(field, json) to interpreterCodes(field, json) }
        assertEquals(kernel, interpreted, "the interpreter's Number declaration or ingress drifted from the Kernel")
    }

    /** A row's name in a failure report: a 100,000-character value is shown by its length, not spelled out. */
    private fun label(field: String, json: String) = "$field=" + if (json.length > 40) "${json.take(12)}…(${json.length})" else json

    /**
     * The other input gate a converted value could meet: a model whose supported characters exclude the digits.
     * Unlike the technical guard, the charset gate DOES reach a converted Number: `5` draws the charset class and is
     * unavailable to the rule that reads it, exactly as the same text on a String field and Number text the converter
     * keeps. Predicted the other way before it was run; this row is what corrected the account.
     */
    @Test
    fun aConvertedNumberStillMeetsTheCharsetGate() {
        val letters = DocumentModelDraft.named("premium-letter-charset").locale("en_US").supportedCharacters("A-Z")
            .rootGroup("Premium") { p -> p.numberField("Units") { it.fractionDigits(0) }.stringField("Code") { } }
            .build().toJson()
        val bound = BoundModel.ofJson(letters)
        val reading = TypedRules.inject(letters, bound.rule(bound.group("/Premium")).name("UnitsAboveThreeRule")
            .errorField(bound.field("/Premium/Units")).`when`(bound.num("/Premium/Units").gt(3))
            .code("UNITS_ABOVE_THREE").severity(Severity.ERROR).message(Dsl.en("Too many units.")).build())
        val documents = PreparedModel.loadExpandedJson(reading)
        val engine = documents.createInterpreter()
        val rows = listOf(
            """{"Premium":{"Units":"5"}}""" to listOf("ZeichenNichtImZeichensatz"),
            """{"Premium":{"Units":"5X"}}""" to listOf("ZeichenNichtImZeichensatz"),
            """{"Premium":{"Code":"5"}}""" to listOf("ZeichenNichtImZeichensatz"),
        )
        val kernel = rows.associate { (json, _) -> json to RuntimeLaws.jsonIngressCodes(reading, json) }
        assertEquals(rows.toMap(), kernel, "the Kernel moved off the measured charset rows")
        val interpreted = rows.associate { (json, _) ->
            json to engine.validateFull(documents.documents.readDocument(json)).findings.map { it.code }.sorted()
        }
        assertEquals(kernel, interpreted, "the interpreter's charset gate on a converted Number drifted")
    }

    private fun document(field: String, json: String) = """{"Premium":{"$field":$json}}"""

    private fun kernelCodes(field: String, json: String) = RuntimeLaws.jsonIngressCodes(model, document(field, json))

    private fun interpreterCodes(field: String, json: String) =
        interpreter.validateFull(prepared.documents.readDocument(document(field, json))).findings.map { it.code }.sorted()

    private companion object {
        private fun quoted(text: String) = "\"$text\""

        /** (field, JSON value, Kernel codes), measured 2026-10-14 at Kernel 30.8.1 through `RuntimeLaws.jsonIngress`. */
        val KERNEL: List<Triple<String, String, List<String>>> = buildList {
            // The sentinel pairs: (-1, 2) reads as (0, 2), (n, -1) as fixed n places, a bare -1 maximum as an integer.
            for (value in listOf("0", "1", "1.00", "1.5", "1.50", "-1.5")) {
                add(Triple("OpenFloor", quoted(value), emptyList()))
                add(Triple("FeeTwoPlaces", quoted(value), emptyList()))
                add(Triple("FeeOnePlace", quoted(value), emptyList()))
                add(Triple("Loose", quoted(value), emptyList()))
            }
            add(Triple("OpenFloor", quoted("1.555"), listOf("zahlFalscheAnzahlNachkommastellen")))
            add(Triple("FeeTwoPlaces", quoted("1.555"), listOf("zahlFalscheAnzahlNachkommastellen")))
            add(Triple("FeeOnePlace", quoted("1.55"), listOf("zahlFalscheAnzahlNachkommastellen")))
            add(Triple("UnitsBare", quoted("1"), emptyList()))
            add(Triple("UnitsBare", quoted("1.5"), listOf("zahlHatDezimalTrenner")))
            // The exception: neither the separator nor the fractional-count test is armed.
            add(Triple("Loose", quoted("1.555"), emptyList()))
            // Printed bounds: the comparison reads the printed text, not the authored decimal.
            add(Triple("CapRounded", quoted("5.56"), emptyList()))
            add(Triple("CapRounded", quoted("5.57"), listOf("zahlZuGross")))
            add(Triple("CapHalf", quoted("0.13"), emptyList()))
            add(Triple("CapHalf", quoted("0.14"), listOf("zahlZuGross")))
            add(Triple("CapWhole", quoted("13"), emptyList()))
            add(Triple("CapWhole", quoted("14"), listOf("zahlZuGross")))
            add(Triple("FloorShort", quoted("1"), emptyList()))
            add(Triple("FloorShort", quoted("0.99"), listOf("zahlZuKlein")))
            // Non-ASCII decimal digits convert; a leading ARABIC-INDIC zero is not the ASCII zero the keep test reads.
            add(Triple("Units", quoted("٣"), emptyList()))
            add(Triple("Rate", quoted("٣.٥"), emptyList()))
            add(Triple("ZeroLed", quoted("٠٣"), emptyList()))
            add(Triple("ZeroLed", quoted("0٣"), listOf("zahlHatUngueltigeZeichen")))
            add(Triple("Amount", quoted("５"), emptyList()))
            // A converted value never meets the technical guard, however long any rendering of it is…
            add(Triple("Units", quoted("0".repeat(100_000) + "1"), emptyList()))
            add(Triple("Rate", quoted("1." + "0".repeat(99_999)), emptyList()))
            add(Triple("Units", quoted("1e100000"), listOf("stelligkeitZuLang")))
            add(Triple("Units", "1e100000", listOf("stelligkeitZuLang")))
            add(Triple("Units", quoted("1" + "0".repeat(100_000)), listOf("stelligkeitZuLang")))
            // …while text the converter keeps does.
            add(Triple("ZeroLed", quoted("0".repeat(100_000) + "1"), listOf("wertZuLang")))
            add(Triple("Units", quoted("x".repeat(100_000)), listOf("wertZuLang")))
        }
    }
}
