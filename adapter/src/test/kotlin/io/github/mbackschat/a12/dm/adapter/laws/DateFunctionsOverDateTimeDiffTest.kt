package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Differential — the date-component EXTRACTORS over a **DATE_TIME operand** (IF95, the A3 re-drill's VCV
 * minimal): the kernel has no datetime-specific format definition — a DATE_TIME field rides
 * `FormatDefinitionDatum` with a time-bearing format string, so `feldWertAlsDatum` parses the full value and
 * `getTagAusDatum`/`getMonatAusDatum`/`getJahrAusDatum`/`getQuartalAusDatum` extract from its DATE part. The
 * interpreter's `dateParts` accepted only the strict ISO `yyyy-MM-dd`, so `DayFromDate(<datetime>)` resolved
 * Unknown and the computation CLEARED where the kernel computes the day. Own-domain (e-commerce logistics):
 * a shipment's `LoggedAt` datetime. Tri-check groovy-dynamic == java-static == interpreter.
 */
class DateFunctionsOverDateTimeDiffTest {

    private val LOGGED = "/Ops/Shipment/LoggedAt"
    private val OUT = "/Ops/Shipment/Extracted"

    private val base = DocumentModelDraft.named("ops").locale("en_US")
        .rootGroup("Ops", Consumer { g: GroupBuilder ->
            g.group("Shipment", Consumer { s ->
                s.dateTimeField("LoggedAt", Consumer { })
                    .dateTimeField("DeliveredAt", Consumer { })
                    .numberField("Extracted", Consumer { })
            })
        }).build().toJson()

    private fun model(op: String): String =
        RuntimeLaws.withComputation(base, "/Ops/Shipment", "ExtractComp", "../Extracted", op)

    private fun sig(c: RuntimeLaws.Computed?): String = when {
        c == null -> "NONE"
        c.cleared() -> "CLEARED"
        c.errored() -> "ERRORED"
        else -> "VALUE|${c.value().orElse("")}"
    }

    private fun iSig(c: io.github.mbackschat.a12.dm.interpreter.model.Computed?): String = when (c?.outcome) {
        null -> "NONE"; ComputeOutcome.CLEARED -> "CLEARED"; ComputeOutcome.ERRORED -> "ERRORED"
        ComputeOutcome.VALUE -> "VALUE|${c.value}"
    }

    private fun quad(op: String, loggedAt: String?, expected: String, msg: String) {
        val m = model(op)
        fun kdoc(): RuntimeLaws.Doc {
            val d = RuntimeLaws.doc().group("/Ops", 1).group("/Ops/Shipment", 1, 1)
            d.field(OUT, "-7", 1, 1, 1)
            loggedAt?.let { d.field(LOGGED, it, 1, 1, 1) }
            return d
        }
        val def = ModelLoader.load(m)
        fun idoc(): Document {
            val d = def.newDocument().group("/Ops", 1).group("/Ops/Shipment", 1, 1)
            d.field(OUT, "-7", 1, 1, 1)
            loggedAt?.let { d.field(LOGGED, it, 1, 1, 1) }
            return d
        }
        val legacy = sig(RuntimeLaws.compute(m, kdoc()).firstOrNull { it.path() == OUT })
        val engine = RuntimeLaws.kernelJavaEngine(m)
        val javaStatic = sig(engine.compute(engine.instance(kdoc())).firstOrNull { it.path() == OUT })
        val interp = iSig(DmInterpreter(def).compute(idoc()).firstOrNull { it.path == OUT })
        assertEquals(expected, legacy, "$msg — kernel groovy-dynamic")
        assertEquals(legacy, javaStatic, "$msg — groovy vs java-static (tri-check)")
        assertEquals(legacy, interp, "$msg — kernel vs interpreter")
    }

    private val TS = "2024-06-25T05:21:07"

    @Test fun dayFromDateTimeExtractsTheDay() =
        quad("DayFromDate(LoggedAt)", TS, "VALUE|25", "DayFromDate over a datetime (the VCV minimal's shape)")

    @Test fun monthFromDateTimeExtractsTheMonth() =
        quad("MonthFromDate(LoggedAt)", TS, "VALUE|6", "MonthFromDate over a datetime")

    @Test fun yearFromDateTimeExtractsTheYear() =
        quad("YearFromDate(LoggedAt)", TS, "VALUE|2024", "YearFromDate over a datetime")

    @Test fun quarterFromDateTimeExtractsTheQuarter() =
        quad("QuarterFromDate(LoggedAt)", TS, "VALUE|2", "QuarterFromDate over a datetime")

    @Test fun emptyDateTimeExtractsTheFillableZero() =
        quad("DayFromDate(LoggedAt)", null, "VALUE|0", "an EMPTY datetime → the fillable 0 (IF73 semantics)")

    // ── the date DIFFS accept datetime operands too (the same shared date resolution) ───────────────────

    @Test fun differenceInDaysBetweenDateTimesUsesTheDateParts() {
        val m = model("DifferenceInDays(LoggedAt, DeliveredAt)")
        val def = ModelLoader.load(m)
        fun kdoc() = RuntimeLaws.doc().group("/Ops", 1).group("/Ops/Shipment", 1, 1)
            .field(LOGGED, TS, 1, 1, 1).field("/Ops/Shipment/DeliveredAt", "2024-06-27T23:59:59", 1, 1, 1)
            .field(OUT, "-7", 1, 1, 1)
        val idoc = def.newDocument().group("/Ops", 1).group("/Ops/Shipment", 1, 1)
            .field(LOGGED, TS, 1, 1, 1).field("/Ops/Shipment/DeliveredAt", "2024-06-27T23:59:59", 1, 1, 1)
            .field(OUT, "-7", 1, 1, 1)
        val legacy = sig(RuntimeLaws.compute(m, kdoc()).firstOrNull { it.path() == OUT })
        val engine = RuntimeLaws.kernelJavaEngine(m)
        val javaStatic = sig(engine.compute(engine.instance(kdoc())).firstOrNull { it.path() == OUT })
        val interp = iSig(DmInterpreter(def).compute(idoc).firstOrNull { it.path == OUT })
        assertEquals("VALUE|2", legacy, "the diff uses the DATE parts (times ignored)")
        assertEquals(legacy, javaStatic, "groovy vs java-static")
        assertEquals(legacy, interp, "kernel vs interpreter")
    }

    /** The reachability control: `AddYears` over a DATE_TIME operand is REJECTED at codegen ("Wrong date
     *  format"), so the date-ARITHMETIC family over datetime is parity by construction — only the extractors
     *  and the diffs are reachable. */
    @Test fun addYearsOverADateTimeIsRejectedAtCodegen() {
        val thrown = org.junit.jupiter.api.Assertions.assertThrows(Exception::class.java) {
            RuntimeLaws.compute(model("AddYears(LoggedAt, 1)"),
                RuntimeLaws.doc().group("/Ops", 1).group("/Ops/Shipment", 1, 1).field(LOGGED, TS, 1, 1, 1))
        }
        org.junit.jupiter.api.Assertions.assertTrue(thrown.message.orEmpty().contains("Wrong date format"),
            "expected the codegen's date-format gate, got: " + thrown.message)
    }
}
