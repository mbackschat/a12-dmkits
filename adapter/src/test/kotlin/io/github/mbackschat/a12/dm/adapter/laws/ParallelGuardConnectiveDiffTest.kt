package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * The connective shapes a parallel iteration admits (KERNEL-FINDINGS KF537) fire alike on both kernel strategies
 * and the interpreter, including on a key only one group carries, where the other side reads "not specified".
 *
 * Own domain (an order's shipment lines joined with its picks by line reference). One document carries the four
 * key profiles: both sides filled (A), the line empty (B), lines only (C), picks only with nothing picked (D).
 */
class ParallelGuardConnectiveDiffTest {

    private val model = RULES.entries.fold(
        DocumentModelDraft.named("shipment-parallel-guards").locale("en_US")
            .rootGroup("Shipment", Consumer { s ->
                s.stringField("ShipmentRef", Consumer { })
                    .group("Lines", Consumer { l ->
                        l.repeatable(10).indexField("LineRef")
                            .stringField("LineRef", Consumer { })
                            .numberField("OrderedQty", Consumer { it.fractionDigits(0) })
                    })
                    .group("Picks", Consumer { p ->
                        p.repeatable(10).indexField("LineRef")
                            .stringField("LineRef", Consumer { })
                            .numberField("PickedQty", Consumer { it.fractionDigits(0) })
                    })
            }).build().toJson(),
    ) { json, (code, condition) ->
        RuntimeLaws.withRule(json, "/Shipment/Lines", "R$code", "../OrderedQty", code, condition)
    }

    @Test
    fun theAdmittedConnectiveShapesFireAlikeOnEveryEngine() {
        val definition = ModelLoader.load(model)
        val kernelDoc = RuntimeLaws.doc().group("/Shipment", 1)
        val interpreterDoc = definition.newDocument().group("/Shipment", 1)
        LINES.forEachIndexed { i, (key, qty) ->
            kernelDoc.group(LINES_GROUP, 1, i + 1).field("$LINES_GROUP/LineRef", key, 1, i + 1, 1)
            interpreterDoc.group(LINES_GROUP, 1, i + 1).field("$LINES_GROUP/LineRef", key, 1, i + 1, 1)
            qty?.let {
                kernelDoc.field(ORDERED, it, 1, i + 1, 1)
                interpreterDoc.field(ORDERED, it, 1, i + 1, 1)
            }
        }
        PICKS.forEachIndexed { i, (key, qty) ->
            kernelDoc.group(PICKS_GROUP, 1, i + 1).field("$PICKS_GROUP/LineRef", key, 1, i + 1, 1)
            interpreterDoc.group(PICKS_GROUP, 1, i + 1).field("$PICKS_GROUP/LineRef", key, 1, i + 1, 1)
            qty?.let {
                kernelDoc.field(PICKED, it, 1, i + 1, 1)
                interpreterDoc.field(PICKED, it, 1, i + 1, 1)
            }
        }

        val dynamic = RuntimeLaws.messages(model, kernelDoc).map { it.code() }.filter(RULES::containsKey).sorted()
        val java = RuntimeLaws.kernelJavaEngine(model)
        assertEquals(dynamic, java.firedCodesSorted(java.instance(kernelDoc)).filter(RULES::containsKey),
            "generated Java")
        assertEquals(dynamic, DmInterpreter(definition).validateFull(interpreterDoc).map { it.code }
            .filter(RULES::containsKey).sorted(), "interpreter")
        assertEquals(EXPECTED, dynamic)
    }

    /** The portable checker must not refuse a connective shape A12 admits; it leaves this guard to the kernel. */
    @Test
    fun theCheckerIsSilentOnTheModelA12Admits() {
        assertEquals(emptyList<String>(), check(ModelSource(model)).findings.filter { it.justifiesRejection }.map { it.code })
    }

    private companion object {
        const val LINES_GROUP = "/Shipment/Lines"
        const val PICKS_GROUP = "/Shipment/Picks"
        const val ORDERED = "/Shipment/Lines/OrderedQty"
        const val PICKED = "/Shipment/Picks/PickedQty"
        const val LINE_FILLED = "FieldFilled($ORDERED)"
        const val LINE_EMPTY = "FieldNotFilled($ORDERED)"
        const val PICK_FILLED = "FieldFilled($PICKED)"
        const val PICK_EMPTY = "FieldNotFilled($PICKED)"

        val LINES = listOf("A" to "5", "B" to null, "C" to "3")
        val PICKS = listOf("A" to "2", "B" to "1", "D" to null)

        val RULES = linkedMapOf(
            "PICKED_NOT_ORDERED" to "$PICK_FILLED And $LINE_EMPTY",
            "ORDERED_NOT_PICKED" to "$LINE_FILLED And $PICK_EMPTY",
            "EITHER_MISMATCH" to "($PICK_FILLED And $LINE_EMPTY) Or ($LINE_FILLED And $PICK_EMPTY)",
            "OR_UNDER_AND" to "($PICK_FILLED Or $LINE_EMPTY) And $LINE_FILLED",
            "ANY_QUANTITY" to "$LINE_FILLED Or $PICK_FILLED",
        )

        // Per key, A/B/C/D: PICKED_NOT_ORDERED at B; ORDERED_NOT_PICKED at C, whose pick is unmatched;
        // EITHER_MISMATCH at both; OR_UNDER_AND at A only; ANY_QUANTITY at A, B and C.
        val EXPECTED = listOf("ANY_QUANTITY", "ANY_QUANTITY", "ANY_QUANTITY", "EITHER_MISMATCH", "EITHER_MISMATCH",
            "ORDERED_NOT_PICKED", "OR_UNDER_AND", "PICKED_NOT_ORDERED")
    }
}
