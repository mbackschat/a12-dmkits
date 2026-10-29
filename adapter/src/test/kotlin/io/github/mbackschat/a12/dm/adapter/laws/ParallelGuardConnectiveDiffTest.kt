package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * The connective shapes a parallel iteration admits (KERNEL-FINDINGS KF537) fire alike on both kernel strategies
 * and the interpreter, message pointers included, on keys only one group carries, where the other side reads
 * "not specified". The reference and fill-to-fix lists carry each joined group's index field beside that group's
 * field, which a rule over `Lines` alone does not (KF539).
 *
 * Own domain (an order's shipment lines joined with its picks by line reference). One document carries the four
 * key profiles: both sides filled (A), the line empty (B), lines only (C), and picks only (D).
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

    private val document = """{"Shipment":{"ShipmentRef":"S-1",""" +
        """"Lines":[{"LineRef":"A","OrderedQty":5},{"LineRef":"B"},{"LineRef":"C","OrderedQty":3}],""" +
        """"Picks":[{"LineRef":"A","PickedQty":2},{"LineRef":"B","PickedQty":1},{"LineRef":"D","PickedQty":4}]}}"""

    @Test
    fun theAdmittedConnectiveShapesFireAlikeOnEveryEngine() {
        DocumentDifferential.assertAgree(model, mapOf("four key profiles" to document))
    }

    /**
     * Which key each rule fires at, read off dynamic Groovy's error pointer: a key only `Picks` carries (D) reports
     * at the sentinel line `-5`, and a key only `Lines` carries (C) reads its pick as not specified.
     */
    @Test
    fun eachRuleFiresAtTheKeysItsConnectiveTreeSelects() {
        val fired = DocumentDifferential.kernel(model, document, dynamic = true).messages
            .filter { it.code in RULES }.map { it.code + " " + it.pointer }.sorted()
        assertEquals(listOf(
            "ANY_QUANTITY /Shipment[1]/Lines[-5]/OrderedQty",
            "ANY_QUANTITY /Shipment[1]/Lines[1]/OrderedQty",
            "ANY_QUANTITY /Shipment[1]/Lines[2]/OrderedQty",
            "ANY_QUANTITY /Shipment[1]/Lines[3]/OrderedQty",
            "EITHER_MISMATCH /Shipment[1]/Lines[-5]/OrderedQty",
            "EITHER_MISMATCH /Shipment[1]/Lines[2]/OrderedQty",
            "EITHER_MISMATCH /Shipment[1]/Lines[3]/OrderedQty",
            "LINE_ONLY /Shipment[1]/Lines[1]/OrderedQty",
            "LINE_ONLY /Shipment[1]/Lines[3]/OrderedQty",
            "ORDERED_NOT_PICKED /Shipment[1]/Lines[3]/OrderedQty",
            "OR_UNDER_AND /Shipment[1]/Lines[1]/OrderedQty",
            "PICKED_NOT_ORDERED /Shipment[1]/Lines[-5]/OrderedQty",
            "PICKED_NOT_ORDERED /Shipment[1]/Lines[2]/OrderedQty",
        ), fired)
    }

    /** The portable checker must not refuse a connective shape A12 admits; it leaves this guard to the kernel. */
    @Test
    fun theCheckerIsSilentOnTheModelA12Admits() {
        assertEquals(emptyList<String>(), rejectingCodes(model))
    }

    private companion object {
        const val ORDERED = "/Shipment/Lines/OrderedQty"
        const val PICKED = "/Shipment/Picks/PickedQty"
        const val LINE_FILLED = "FieldFilled($ORDERED)"
        const val LINE_EMPTY = "FieldNotFilled($ORDERED)"
        const val PICK_FILLED = "FieldFilled($PICKED)"
        const val PICK_EMPTY = "FieldNotFilled($PICKED)"

        val RULES = linkedMapOf(
            "PICKED_NOT_ORDERED" to "$PICK_FILLED And $LINE_EMPTY",
            "ORDERED_NOT_PICKED" to "$LINE_FILLED And $PICK_EMPTY",
            "EITHER_MISMATCH" to "($PICK_FILLED And $LINE_EMPTY) Or ($LINE_FILLED And $PICK_EMPTY)",
            "OR_UNDER_AND" to "($PICK_FILLED Or $LINE_EMPTY) And $LINE_FILLED",
            "ANY_QUANTITY" to "$LINE_FILLED Or $PICK_FILLED",
            // no join: the control for whether an index field is referenced because of the join or because its
            // group is index-keyed
            "LINE_ONLY" to LINE_FILLED,
        )
    }
}
