package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * §11 / SPEC-2026-07-26-05 — a Number computation's changed subset compares the source and computed V2
 * objects, not their parsed amounts or plain renderings. The dynamic-Groovy and generated-Java routes share
 * the same result projection: an original BigDecimal is unchanged only when coefficient and scale agree;
 * a source String and a negative-scale BigDecimal remain changed even when their plain text and amount match.
 */
class NumberResultSourceIdentityLawsTest {

    private val model = run {
        val base = BoundModel.ofJson(
            DocumentModelDraft.named("number-result-source-identity").locale("en_US")
                .rootGroup("Invoice") { invoice ->
                    invoice.numberField(EXACT) { it.fractionDigits(2, 2) }
                        .numberField(DIFFERENT_SCALE) { it.fractionDigits(2, 2) }
                        .numberField(SOURCE_TEXT) { it.fractionDigits(2, 2) }
                        .numberField(NEGATIVE_SCALE) { it.fractionDigits(0, 0) }
                }
                .build().toJson(),
        )
        val computations = listOf(
            computation(base, EXACT, "7.00"),
            computation(base, DIFFERENT_SCALE, "7.00"),
            computation(base, SOURCE_TEXT, "7.00"),
            computation(base, NEGATIVE_SCALE, "70"),
        )
        base.addAll(emptyList(), computations).toJson()
    }

    @Test
    fun bothKernelStrategiesCompareNumberSourcesByV2ObjectIdentity() {
        val typedSource = RuntimeLaws.documentV2(
            model,
            """{"Invoice":{"ExactScale":7.00,"DifferentScale":7,"NegativeScale":7e1}}""",
        )
        val rawSource = RuntimeLaws.doc().group("/Invoice", 1)
            .field("/Invoice/$SOURCE_TEXT", "7.00", 1, 1)
        val dynamic = RuntimeLaws.kernelEngine(model)
        val java = RuntimeLaws.kernelJavaEngine(model)

        assertProfile(
            "dynamic-Groovy",
            dynamic.resultChannels(typedSource),
            dynamic.resultChannels(dynamic.instance(rawSource)),
        )
        assertProfile(
            "static-Java",
            java.resultChannels(typedSource),
            java.resultChannels(java.instance(rawSource)),
        )
    }

    private fun assertProfile(
        strategy: String,
        typed: RuntimeLaws.ResultChannels,
        raw: RuntimeLaws.ResultChannels,
    ) {
        assertEquals(
            mapOf(
                EXACT to false,
                DIFFERENT_SCALE to true,
                NEGATIVE_SCALE to true,
                SOURCE_TEXT to true,
            ),
            mapOf(
                EXACT to typed.changed(EXACT),
                DIFFERENT_SCALE to typed.changed(DIFFERENT_SCALE),
                NEGATIVE_SCALE to typed.changed(NEGATIVE_SCALE),
                SOURCE_TEXT to raw.changed(SOURCE_TEXT),
            ),
            "$strategy must classify Number changes by source/computed V2 object identity",
        )
    }

    private fun RuntimeLaws.ResultChannels.changed(field: String): Boolean =
        withChanges().any { it.endsWith("/$field") }

    private fun computation(model: BoundModel, field: String, value: String) =
        model.computation(model.num("/Invoice/$field"))
            .name("${field}Computation")
            .calc(NumberLiteral.of(value))
            .message(Dsl.en("$field is derived."))
            .build()

    private companion object {
        const val EXACT = "ExactScale"
        const val DIFFERENT_SCALE = "DifferentScale"
        const val SOURCE_TEXT = "SourceText"
        const val NEGATIVE_SCALE = "NegativeScale"
    }
}
