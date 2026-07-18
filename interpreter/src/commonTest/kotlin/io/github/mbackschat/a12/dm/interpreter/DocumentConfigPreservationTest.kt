package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.DocumentConfig
import io.github.mbackschat.a12.dm.interpreter.model.DatePrecision
import io.github.mbackschat.a12.dm.interpreter.model.FieldConstraints
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The IG75/IF131 lock: a [Document] reconstruction ([Document.withComputed] via `emptyCopy`, the compute→apply
 * path) must preserve its [DocumentConfig] **wholesale** — every slot, not 13 of 14. The defect was a positional
 * 14-argument copy that silently dropped the final `timeZone` (→ UTC). The fix collapses the config into one
 * immutable value copied as a whole, so partial reconstruction is impossible; this test is the standing guard
 * against a future positional regression, keyed on data-class equality so a NEW slot is covered automatically.
 */
class DocumentConfigPreservationTest {

    // Every slot set to a NON-default value, so equality after a copy proves no slot is dropped.
    private val config = DocumentConfig(
        kinds = mapOf("/G/N" to Kind.NUMBER, "/G/D" to Kind.DATE, "/G/DT" to Kind.DATE_TIME,
            "/G/T" to Kind.TIME, "/G/R" to Kind.DATE_RANGE, "/G/E" to Kind.STRING),
        declaredReps = mapOf("/G" to 5),
        unsignedFields = setOf("/G/N"),
        rangeConfig = mapOf("/G/R" to ("dd.MM.yyyy" to "-")),
        dateFormats = mapOf("/G/D" to "dd.MM.yyyy"),
        scales = mapOf("/G/N" to 2),
        dateTimeFormats = mapOf("/G/DT" to "yyyy-MM-dd'T'HH:mm:ss"),
        datePrecisions = mapOf("/G/D" to DatePrecision.DAY_OPTIONAL),
        enumValues = mapOf("/G/E" to listOf("A", "B")),
        enumCategories = mapOf("/G/E" to mapOf("cat" to listOf("A"))),
        timeFormats = mapOf("/G/T" to "HH:mm:ss"),
        baseYear = 2021,
        fieldConstraints = mapOf("/G/N" to FieldConstraints(minFractionalDigits = 2)),
        timeZone = ModelTimeZone.EUROPE_BERLIN,
    )

    @Test
    fun withComputedPreservesEveryConfigSlot() {
        val copied = Document(config).field("/G/N", "1.00", 1, 1).withComputed(emptyMap())
        assertEquals(config, copied.config,
            "withComputed/applyComputations must preserve EVERY DocumentConfig slot wholesale (IG75/IF131)")
    }

    @Test
    fun theHistoricallyDroppedTimeZoneSlotSurvives() {
        // The exact IG75 regression: the positional copy dropped the 14th arg, so a Europe/Berlin document
        // became UTC after compute→apply. Pin that one slot explicitly.
        val copied = Document(config).withComputed(emptyMap())
        assertEquals(ModelTimeZone.EUROPE_BERLIN, copied.config.timeZone,
            "a Europe/Berlin document must stay Europe/Berlin across withComputed (not silently UTC)")
    }
}
