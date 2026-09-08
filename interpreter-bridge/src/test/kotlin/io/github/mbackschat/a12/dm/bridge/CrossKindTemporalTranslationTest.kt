package io.github.mbackschat.a12.dm.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import io.github.mbackschat.a12.dm.core.ModelIndex
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.interpreter.model.ConditionLanguage
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer

/**
 * Conditions over fields whose temporal KIND and declared FORMAT disagree — the class the kernel decides by
 * the declaration (KF271) and every layer of ours used to decide by the kind.
 *
 * The oracle is a ROUND TRIP: the kernel admits each condition below (the accept rows are
 * `rulekit.validate.laws.TemporalConstructorDeclaredFormatGateLawsTest` and
 * `TemporalAggregateComponentSetLawsTest`; the adapter read is
 * `adapter.CrossKindTemporalOperandReadTest`), so translating it and rendering the result must give the text
 * back unchanged. A kind-keyed dispatch fails visibly here — it either throws while narrowing to the wrong
 * typed ref or finds no comparison for the pair it built.
 */
class CrossKindTemporalTranslationTest {

    private lateinit var bridge: ConditionBridge

    @BeforeEach
    fun bind() {
        val json = BridgeModels.crossKindTemporalJson
        bridge = ConditionBridge(BoundModel.ofJson(json), ModelIndex.of(json, ReferenceResolvers.selfContained()))
    }

    /** Translate, then render — the identity that proves the typing survived both directions. */
    private fun roundTrip(condition: String): String =
        DslRenderer().render(bridge.translate(condition, "/Cover", ConditionLanguage.EN))

    @Test
    fun aCrossKindTemporalComparisonRoundTrips() {
        val actual = linkedMapOf(
            "DATE == DATE — control" to roundTrip("[/Cover/StartsOn] == [/Cover/EndsOn]"),
            "TIME-declared yyyy-MM-dd == DATE" to roundTrip("[/Cover/AuditedOn] == [/Cover/StartsOn]"),
            "DATE_TIME-declared yyyy-MM-dd == DATE" to roundTrip("[/Cover/FiledOn] == [/Cover/StartsOn]"),
            "DATE-declared HH:mm:ss == TIME — the diagonal"
                to roundTrip("[/Cover/ShiftAt] == [/Cover/ShiftFrom]"),
        )
        val expected = linkedMapOf(
            "DATE == DATE — control" to "[/Cover/StartsOn] == [/Cover/EndsOn]",
            "TIME-declared yyyy-MM-dd == DATE" to "[/Cover/AuditedOn] == [/Cover/StartsOn]",
            "DATE_TIME-declared yyyy-MM-dd == DATE" to "[/Cover/FiledOn] == [/Cover/StartsOn]",
            "DATE-declared HH:mm:ss == TIME — the diagonal" to "[/Cover/ShiftAt] == [/Cover/ShiftFrom]",
        )
        assertEquals(expected, actual, "the comparison dispatch must read the declaration, not the kind")
    }

    @Test
    fun aCrossKindDateRangeEndpointRoundTrips() {
        val actual = linkedMapOf(
            "DATE + DATE — control"
                to roundTrip("DateRange(/Cover/StartsOn, /Cover/EndsOn) == [/Cover/CoverWindow]"),
            "TIME-declared yyyy-MM-dd as the START"
                to roundTrip("DateRange(/Cover/AuditedOn, /Cover/EndsOn) == [/Cover/CoverWindow]"),
            "DATE_TIME-declared yyyy-MM-dd as the FINISH"
                to roundTrip("DateRange(/Cover/StartsOn, /Cover/FiledOn) == [/Cover/CoverWindow]"),
        )
        val expected = linkedMapOf(
            "DATE + DATE — control" to "DateRange(/Cover/StartsOn, /Cover/EndsOn) == [/Cover/CoverWindow]",
            "TIME-declared yyyy-MM-dd as the START"
                to "DateRange(/Cover/AuditedOn, /Cover/EndsOn) == [/Cover/CoverWindow]",
            "DATE_TIME-declared yyyy-MM-dd as the FINISH"
                to "DateRange(/Cover/StartsOn, /Cover/FiledOn) == [/Cover/CoverWindow]",
        )
        assertEquals(expected, actual, "the endpoint dispatch must read the declaration, not the kind")
    }

    @Test
    fun aCrossKindTemporalExtremumRoundTrips() {
        val actual = linkedMapOf(
            "DATE + DATE — control"
                to roundTrip("MaxValue(/Cover/StartsOn, /Cover/EndsOn) == [/Cover/StartsOn]"),
            "MaxValue over a TIME-declared yyyy-MM-dd beside a DATE"
                to roundTrip("MaxValue(/Cover/AuditedOn, /Cover/StartsOn) == [/Cover/StartsOn]"),
            "MinValue over a DATE_TIME-declared yyyy-MM-dd beside a DATE"
                to roundTrip("MinValue(/Cover/FiledOn, /Cover/StartsOn) == [/Cover/StartsOn]"),
        )
        val expected = linkedMapOf(
            "DATE + DATE — control" to "MaxValue(/Cover/StartsOn, /Cover/EndsOn) == [/Cover/StartsOn]",
            "MaxValue over a TIME-declared yyyy-MM-dd beside a DATE"
                to "MaxValue(/Cover/AuditedOn, /Cover/StartsOn) == [/Cover/StartsOn]",
            "MinValue over a DATE_TIME-declared yyyy-MM-dd beside a DATE"
                to "MinValue(/Cover/FiledOn, /Cover/StartsOn) == [/Cover/StartsOn]",
        )
        assertEquals(expected, actual, "the extremum dispatch must read the declaration, not the kind")
    }
}
