package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.DatePrecision
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for SPEC-2026-07-27-06's runtime half — a Time constant is built on a
 * **1970-01-01 model-zone carrier**, and that carrier stays transport-only: a Time consumer observes the
 * decoded clock, never the hidden instant.
 *
 * The separator makes the literal meet a Time derived from a **zone-parsed** DateTime, the only place the two
 * accounts diverge. `2024-06-01T10:30:45` is CEST (+2) under `Europe/Berlin`, so its instant decodes to
 * `08:30:45` in UTC — which makes the `08:30:45` case the actual failure mode rather than padding. Both
 * outcomes must be identical under both model zones; a retained model-zone instant on either side would make
 * them disagree. Dual-kernel agreement is `adapter.laws.TimeLiteralCarrierDiffTest`; the static syntax gate is
 * `rulekit.validate.laws.TimeLiteralLawsTest`.
 */
class TimeLiteralCarrierTest {

    private fun fires(condition: String, zone: ModelTimeZone, stamp: String): Boolean {
        val def = ModelDef(
            fields = listOf(
                FieldDef(STAMP_FIELD, Kind.DATE_TIME, format = "yyyy-MM-dd'T'HH:mm:ss"),
                FieldDef(CLOCK_FIELD, Kind.TIME, format = "HH:mm:ss"),
                FieldDef(APPROX_FIELD, Kind.DATE, format = "dd.MM.yyyy",
                    datePrecision = DatePrecision.DAY_OPTIONAL),
            ),
            rules = listOf(RuleDef("/Order/R", "/Order", condition, "TC", "../ScheduledAt")),
            timeZone = zone,
        )
        val doc = def.newDocument()
            .field(STAMP_FIELD, stamp, 1, 1)
            .field(CLOCK_FIELD, "10:30:45", 1, 1)
            .field(APPROX_FIELD, "00.06.2024", 1, 1)
        return DmInterpreter(def).validate(doc).messages.any { it.code == "TC" }
    }

    private fun bothZones(condition: String, expected: Boolean, msg: String, stamp: String = STAMP) {
        assertEquals(expected, fires(condition, ModelTimeZone.EUROPE_BERLIN, stamp), "$msg [Europe/Berlin]")
        assertEquals(expected, fires(condition, ModelTimeZone.UTC, stamp), "$msg [UTC]")
    }

    private fun wallClock(literal: String) =
        "FieldFilled($STAMP_FIELD) And TimeFromDateTime($STAMP_FIELD) == \"$literal\""

    @Test fun theWallClockIsWhatAConsumerCompares() =
        bothZones(wallClock("10:30:45"), true, "the DateTime's wall clock equals the literal")

    @Test fun theSourceInstantIsNotWhatAConsumerCompares() =
        bothZones(wallClock("08:30:45"), false, "the UTC decoding of the same instant must NOT match")

    @Test fun aStoredClockComparesToItsLiteralInEitherZone() {
        bothZones("FieldFilled($CLOCK_FIELD) And [$CLOCK_FIELD] == \"10:30:45\"", true, "stored clock matches")
        bothZones("FieldFilled($CLOCK_FIELD) And [$CLOCK_FIELD] == \"08:30:45\"", false, "and a shifted one does not")
    }

    /**
     * The entry's named composition: a Time LITERAL as the time argument beside a resolved partial date. It
     * exercises the literal's decoding and the carrier's irrelevance in one shape — and the negative control
     * uses the OTHER day boundary, so a composition that silently dropped the resolution would fail.
     */
    @Test fun aTimeLiteralComposesWithAResolvedPartialDate() {
        val composed = { day: String ->
            "FieldFilled($APPROX_FIELD) And [$STAMP_FIELD] == " +
                "DateTime(ValueAsDate($APPROX_FIELD, $day), \"09:30:00\")"
        }
        bothZones(composed("LastDay"), true, "LastDay of June at 09:30 is the stored stamp",
            stamp = "2024-06-30T09:30:00")
        bothZones(composed("FirstDay"), false, "FirstDay does not compose to the 30th",
            stamp = "2024-06-30T09:30:00")
    }

    private companion object {
        const val STAMP_FIELD = "/Order/ScheduledAt"
        const val CLOCK_FIELD = "/Order/PickupTime"
        const val APPROX_FIELD = "/Order/ApproxDate"

        /** 2024-06-01 is CEST (+2) under Europe/Berlin, so this wall time's instant is 08:30:45Z. */
        const val STAMP = "2024-06-01T10:30:45"
    }
}
