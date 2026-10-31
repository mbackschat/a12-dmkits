package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Two temporal index keys of DIFFERENT kinds that A12 admits as a join (KERNEL-FINDINGS KF353) found no partner on
 * any measured row, on both kernel strategies and the interpreter, even where both keys name the same day or clock
 * time. Each row joins one booked delivery with one dispatch; the rule fires exactly when the join binds no
 * dispatch. The Date-with-Date pair is the control that a join of one kind matches at all.
 *
 * Every row here declares a different format on each side, so different kinds also have different texts: these
 * rows cannot tell "match by equal text" from "never match across kinds", and the lock claims neither as the
 * mechanism. They do refute matching by calendar value or instant, which would join the midnight, fragment and
 * clock-time rows.
 *
 * Own domain (an e-commerce delivery schedule).
 */
class CrossKindTemporalJoinDiffTest {

    private val base = DocumentModelDraft.named("delivery-cross-kind-join").locale("en_US")
        .rootGroup("Delivery") { delivery ->
            for ((pair, kinds) in PAIRS) {
                delivery.group("Booked$pair") { booked ->
                    kinds.first(booked.repeatable(10).indexField("Slot")); booked.stringField("Item") { }
                }
                delivery.group("Dispatched$pair") { dispatched ->
                    kinds.second(dispatched.repeatable(10).indexField("Slot")); dispatched.stringField("Carrier") { }
                }
            }
        }
        .build().toJson()

    private val model = BoundModel.ofJson(base).let { m ->
        PAIRS.keys.fold(base) { json, pair ->
            RuntimeLaws.withRule(json, "/Delivery/Booked$pair", "Undispatched$pair", "../Item", "UNDISPATCHED_$pair",
                RuntimeLaws.render(m.str("/Delivery/Booked$pair/Item").filled()
                    .and(m.str("/Delivery/Dispatched$pair/Carrier").notFilled())))
        }
    }

    private val documents = linkedMapOf(
        "DD-eq" to document("DD", "2026-01-02", "2026-01-02"),
        "DD-ne" to document("DD", "2026-01-02", "2026-01-03"),
        "DT-midnight" to document("DT", "2026-01-02", "2026-01-02T00:00:00"),
        "DM-midnight" to document("DM", "2026-01-02", "00:00:00"),
        "DF-in-month" to document("DF", "2026-01-02", "2026-01"),
        "TM-same-clock" to document("TM", "2026-01-02T13:45:00", "13:45:00"),
    )

    @Test
    fun everyEngineAgreesOnEveryRow() {
        DocumentDifferential.assertAgree(model, documents)
    }

    @Test
    fun onlyTheSameKindPairWithEqualKeysFindsItsPartner() {
        val fired = documents.mapValues { (_, json) ->
            DocumentDifferential.kernel(model, json, dynamic = true).messages
                .filter { it.code.startsWith("UNDISPATCHED_") }.map { it.code + " " + it.pointer }
        }
        assertEquals(linkedMapOf(
            "DD-eq" to emptyList(),
            "DD-ne" to listOf("UNDISPATCHED_DD /Delivery[1]/BookedDD[1]/Item"),
            "DT-midnight" to listOf("UNDISPATCHED_DT /Delivery[1]/BookedDT[1]/Item"),
            "DM-midnight" to listOf("UNDISPATCHED_DM /Delivery[1]/BookedDM[1]/Item"),
            "DF-in-month" to listOf("UNDISPATCHED_DF /Delivery[1]/BookedDF[1]/Item"),
            "TM-same-clock" to listOf("UNDISPATCHED_TM /Delivery[1]/BookedTM[1]/Item"),
        ), fired)
    }

    private fun document(pair: String, booked: String, dispatched: String) =
        """{"Delivery":{"Booked$pair":[{"Slot":"$booked","Item":"parcel"}],""" +
            """"Dispatched$pair":[{"Slot":"$dispatched","Carrier":"courier"}]}}"""

    private companion object {
        val DATE: (DocumentModelDraft.GroupBuilder) -> Unit = { it.dateField("Slot") { f -> f.format("yyyy-MM-dd") } }
        val DATE_TIME: (DocumentModelDraft.GroupBuilder) -> Unit =
            { it.dateTimeField("Slot") { f -> f.format("yyyy-MM-dd'T'HH:mm:ss") } }
        val TIME: (DocumentModelDraft.GroupBuilder) -> Unit = { it.timeField("Slot") { f -> f.format("HH:mm:ss") } }
        val MONTH: (DocumentModelDraft.GroupBuilder) -> Unit =
            { it.dateFragmentField("Slot") { f -> f.fragmentFormat("yyyy-MM") } }

        /** The error side's key first, as KF353's pairing table reads; each pair is one the kernel admits. */
        val PAIRS = linkedMapOf(
            "DD" to (DATE to DATE),
            "DT" to (DATE to DATE_TIME),
            "DM" to (DATE to TIME),
            "DF" to (DATE to MONTH),
            "TM" to (DATE_TIME to TIME),
        )
    }
}
