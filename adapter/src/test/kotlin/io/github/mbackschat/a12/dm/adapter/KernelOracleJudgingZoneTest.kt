package io.github.mbackschat.a12.dm.adapter

import io.github.mbackschat.a12.dm.adapter.laws.TypedRules
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import java.util.TimeZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * dmtool's kernel consistency verdict does not depend on the machine's zone. A12 judges a DateTime constant
 * against the JVM default rather than the model's declared zone (KERNEL-FINDINGS kf231), so on a Europe/Berlin
 * default it refused a spring-forward gap label that a UTC default admits. The oracle judges under UTC, as the
 * kernel probe does, and gives the caller's default back.
 *
 * Moving the JVM default is safe here: `:adapter` disables JUnit parallelism inside every fork, and each swap is
 * undone in a `finally`. Own domain (long-distance rail reservations).
 */
class KernelOracleJudgingZoneTest {

    private fun modelFor(literal: String): String {
        val base = DocumentModelDraft.named("rail-booking").locale("en_US")
            .rootGroup("Booking") { booking ->
                booking.stringField("Locator") { }
                booking.group("Segments") { segments ->
                    segments.repeatable(2).dateTimeField("BoardsAt") { it.format("yyyy-MM-dd'T'HH:mm:ss") }
                }
            }.build().toJson()
        val bound = BoundModel.ofJson(base)
        return TypedRules.inject(base, bound.computation(bound.dateTime("/Booking/Segments/BoardsAt").field())
            .name("BoardingComp").calc(Dsl.dateTimeValue(literal).expression())
            .message(Dsl.en("The scheduled boarding time for this segment.")).build())
    }

    /** The oracle's messages for [literal], run with the JVM default set to [zone]; the default is checked back. */
    private fun verdict(zone: String, literal: String): List<String> {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        try {
            val messages = KernelConsistencyOracle().checkConsistency(modelFor(literal), ReferenceResolvers.selfContained())
                .map { it.severity().name + " " + it.message() }
            assertEquals(zone, TimeZone.getDefault().id, "the oracle must give the caller's default back")
            return messages
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    /** `31.03.2024T02:30:00` is a wall time Europe/Berlin skips; `32.03.2024T12:30:00` is no date at all, the
     *  positive control that this route refuses a bad constant on every default. */
    @Test
    fun aGapLabelGetsTheSameVerdictOnABerlinAndAUtcMachine() {
        assertEquals(verdict("UTC", GAP), verdict("Europe/Berlin", GAP))
        assertEquals(emptyList<String>(), verdict("Europe/Berlin", GAP).filter { it.startsWith("ERROR") })
        for (zone in listOf("UTC", "Europe/Berlin")) {
            assertEquals(true, verdict(zone, "32.03.2024T12:30:00").any { it.startsWith("ERROR") }, zone)
        }
    }

    private companion object {
        const val GAP = "31.03.2024T02:30:00"
    }
}
