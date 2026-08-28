package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Kernel law — **the STORED-VALUE gate of a temporal cell reads the declared FORMAT and never the field's kind**,
 * on both codegen strategies. This is [KF206](../../../../../../../../../docs/KERNEL-FINDINGS.md)'s
 * kind-independent format vocabulary carried from the DECLARATION tier, where a12-kernel-lean measured it and
 * scoped storage out, to the VALUE tier.
 *
 * **The discriminator, and why it needs its own row.** A `Time` cell is documented — by us and by the peer's
 * `spec/05-dates-and-time.md` — to report only the format error and *never* the date error `datumFalsch`, with the
 * reason given as the KIND: a clock has no position in time to fall below the Gregorian floor. Once a TIME field
 * may legally declare `yyyy-MM-dd`, that reason is testable, and it is wrong: such a cell reports `datumFalsch` for
 * `1583-10-15` exactly as a DATE does. The deciding variable is the declaration, not the kind.
 *
 * **Two within-model controls, each moving one variable.** `TimeAsDate` versus `DateAsDate` versus `StampAsDate`
 * holds the declared format fixed and moves the KIND across all three date-bearing kinds — all agree, so the kind
 * is not read. `TimeAsDate` versus `TimeAsTime` holds the kind fixed and moves the FORMAT — they differ on every
 * value, so the declaration is what is read. One model, so nothing else can differ.
 *
 * **Deliberately kernel-only: no interpreter arm.** Our interpreter rejects every value of a date-declared TIME
 * cell (`FormalError.kt`'s `Kind.TIME -> parseTimeToIso`), which is the live gap `IG140` — see
 * [INTERPRETER-GAPS](../../../../../../../../../docs/INTERPRETER-GAPS.md). This class locks the kernel side that
 * gap has to be measured against; adding an interpreter comparison here would fail by design and would hide the
 * oracle behind a known divergence.
 */
class TemporalStoredValueGateFormalTest {

    private companion object {
        const val ISO = "yyyy-MM-dd"
        val VALUES = listOf(
            "2024-06-15",   // an ordinary date
            "1583-10-16",   // the Gregorian floor itself
            "1583-10-15",   // one day below it
            "1582-01-01",   // well below it
            "2024-02-31",   // pattern-valid, calendrically unreal
            "10:30:00",     // a clock: the TIME kind's own natural spelling
            "24:00:00",     // no end-of-day spelling on any declaration
            "",             // empty is present and value-free
        )
    }

    /** One model, four cells: three date-format declarations across three kinds, plus the time-format control. */
    private val json: String = DocumentModelDraft.named("temporal-value-gate").locale("en_US")
        .rootGroup("Probe") { p ->
            p.timeField("TimeAsDate") { it.format(ISO) }
                .dateField("DateAsDate") { it.format(ISO) }
                .dateTimeField("StampAsDate") { it.format(ISO) }
                .timeField("TimeAsTime") { it.format("HH:mm:ss") }
        }.build().toJson()

    private val javaEngine by lazy { RuntimeLaws.kernelJavaEngine(json) }

    /** Every value's formal codes at [field], measured on one strategy — `groovy` selects dynamic over static. */
    private fun codes(field: String, groovy: Boolean): Map<String, List<String>> =
        VALUES.associateWith { value ->
            val doc = RuntimeLaws.doc().group("/Probe", 1)
            if (value.isNotEmpty()) doc.field("/Probe/$field", value, 1, 1)
            if (groovy) RuntimeLaws.messages(json, doc).map { it.code() }.sorted()
            else javaEngine.firedCodesSorted(javaEngine.instance(doc))
        }

    private fun onBothStrategies(field: String): Map<String, List<String>> {
        val groovy = codes(field, groovy = true)
        assertEquals(groovy, codes(field, groovy = false), "$field — groovy-dynamic vs generated-java")
        return groovy
    }

    @Test fun aDateDeclaredCellReportsTheDateErrorWhateverItsKind() {
        val expected = mapOf(
            "2024-06-15" to emptyList<String>(),
            "1583-10-16" to emptyList(),
            "1583-10-15" to listOf("datumFalsch"),
            "1582-01-01" to listOf("datumFalsch"),
            "2024-02-31" to listOf("datumFormatFalsch"),
            "10:30:00" to listOf("datumFormatFalsch"),
            "24:00:00" to listOf("datumFormatFalsch"),
            "" to emptyList(),
        )
        assertEquals(expected, onBothStrategies("TimeAsDate"),
            "a TIME field declared $ISO carries the Gregorian floor, so the date error is reachable on a clock kind")
        assertEquals(expected, onBothStrategies("DateAsDate"), "the DATE reference row for the same declaration")
        assertEquals(expected, onBothStrategies("StampAsDate"),
            "DATE_TIME declared $ISO agrees too, so this is not a TIME peculiarity")
    }

    @Test fun aTimeDeclaredCellNeverReportsTheDateError() {
        val f = "datumFormatFalsch"
        assertEquals(
            mapOf(
                "2024-06-15" to listOf(f),
                "1583-10-16" to listOf(f),
                "1583-10-15" to listOf(f),
                "1582-01-01" to listOf(f),
                "2024-02-31" to listOf(f),
                "10:30:00" to emptyList(),
                "24:00:00" to listOf(f),
                "" to emptyList<String>(),
            ),
            onBothStrategies("TimeAsTime"),
            "the polarity control — with a time format declared, the published never-the-date-error claim holds",
        )
    }
}
