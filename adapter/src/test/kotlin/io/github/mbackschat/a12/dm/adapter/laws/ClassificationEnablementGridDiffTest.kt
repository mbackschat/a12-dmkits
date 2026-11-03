package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.DocumentDifferential.underUtc
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.model.EvalClock
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.Date

/**
 * Which `Or` leaf keeps a per-row rule iterating over every row, and which collapses it to the first row only,
 * on the grid a12-kernel-lean SPEC-2026-10-26-07 and -10 named ([KERNEL-FINDINGS KF631]). Each rule fires on
 * every stop through its base disjunct; the added leaf alone decides how many rows A12 visits. Dynamic Groovy,
 * static Java and the interpreter agree on every row.
 *
 * Own domain (a parcel delivery).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClassificationEnablementGridDiffTest {
    private companion object {
        const val ISO_DT = "yyyy-MM-dd'T'HH:mm:ss"
        const val GAP = "29.02.1500T10:00:00"
        val KERNEL_CLOCK: Date = Date.from(Instant.parse("2026-10-04T10:00:00Z"))
        val CLOCK = EvalClock(today = "2026-10-04", now = "2026-10-04T12:00:00")
    }

    private val leaves = linkedMapOf<String, (EditSession) -> Condition?>()

    private val model: String = underUtc { buildModel() }

    private fun buildModel(): String {
        val draft = DocumentModelDraft.named("parcel-classification-grid").locale("en_US")
            .rootGroup("Delivery") { root ->
                root.field("Express", FieldKind.BOOLEAN)
                root.field("Agreed", FieldKind.CONFIRM)
                root.group("Extras") { it.stringField("Remark") { } }
                root.group("Parcels") { it.repeatable(3).indexField("Tag").stringField("Tag") { } }
                root.group("Stops") { stops ->
                    stops.repeatable(9)
                    stops.dateTimeField("Arrival") { it.format(ISO_DT) }
                    stops.stringField("Signed") { }
                    stops.stringField("Ref") { }
                    stops.stringField("Alt") { }
                }
            }.build().toJson()
        val session = EditSession.read(draft).also { it.config().timeZone("Europe/Berlin") }
        val root = session.group("/Delivery")
        val stops = session.group("/Delivery/Stops")
        val arrival = stops.dateTime("Arrival")
        val signed = stops.str("Signed")
        val ref = stops.field("Ref")
        val alt = stops.field("Alt")
        val gap = Dsl.dateTimeValue(GAP)
        val base = signed.filled().or(arrival.filled().and(Dsl.now().before(gap)))
        val grid: List<Pair<String, Condition?>> = listOf(
            "no added leaf" to null,
            "FieldNotFilled" to signed.notFilled(),
            "root GroupFilled" to root.ref().filled(),
            "root GroupNotFilled" to Dsl.groupNotFilled(root.ref()),
            "root NoGroupFilled" to Dsl.noGroupFilled(root.ref()),
            "root AtLeastOneGroupFilled" to Dsl.atLeastOneGroupFilled(root.ref()),
            "child GroupFilled" to root.group("Extras").ref().filled(),
            "AllFieldsFilled of two" to Dsl.allFieldsFilled(ref, alt),
            "MoreThanOneFieldFilled" to Dsl.moreThanOneFieldFilled(ref, alt),
            "AtLeastOneFieldFilled" to Dsl.atLeastOneFieldFilled(ref, alt),
            "FieldValuesNotUnique" to Dsl.fieldValuesNotUnique(ref, alt),
            "Boolean comparison" to root.bool("Express").isTrue(),
            "Confirm comparison" to root.confirm("Agreed").confirmed(),
            "keyed AtLeastOneGroupFilled" to Dsl.atLeastOneGroupFilled(root.flattenGroup("Parcels").forIndex("k")),
            "keyed NoGroupFilled" to Dsl.noGroupFilled(root.flattenGroup("Parcels").forIndex("k")),
        )
        var json = session.toJson()
        grid.forEachIndexed { index, (_, leaf) ->
            val code = "L$index"
            json = TypedRules.inject(json, stops.rule {
                it.name(code).code(code).errorField(arrival.field()).`when`(arrival.filled().and(if (leaf == null) base else base.or(leaf)))
                    .message(Dsl.en("Check the stop.")).build()
            })
        }
        labels = grid.mapIndexed { index, (label, _) -> "L$index" to label }.toMap()
        return json
    }

    private lateinit var labels: Map<String, String>

    private val allSigned = "{\"Delivery\":{\"Express\":false,\"Stops\":[" +
        List(3) { "{\"Arrival\":\"2024-03-31T01:00:00\",\"Signed\":\"yes\"}" }.joinToString(",") + "]}}"

    @Test
    fun eachLeafDecidesHowManyRowsTheRuleVisits() {
        val (kernel, static, interpreted) = underUtc {
            val clock = DifferentialClock(KERNEL_CLOCK, CLOCK)
            listOf(
                DocumentDifferential.kernel(model, allSigned, dynamic = true, clock),
                DocumentDifferential.kernel(model, allSigned, dynamic = false, clock),
                DocumentDifferential.interpreter(model, allSigned, clock),
            ).map(::firingRows)
        }
        assertEquals(kernel, static, "static Java")
        assertEquals(kernel, interpreted, "the interpreter")
        val everyRow = listOf("[1]", "[2]", "[3]")
        val firstRow = listOf("[1]")
        assertEquals(linkedMapOf(
            "no added leaf" to everyRow,
            "FieldNotFilled" to firstRow,
            "root GroupFilled" to everyRow,
            "root GroupNotFilled" to firstRow,
            "root NoGroupFilled" to firstRow,
            "root AtLeastOneGroupFilled" to everyRow,
            "child GroupFilled" to firstRow,
            "AllFieldsFilled of two" to everyRow,
            "MoreThanOneFieldFilled" to everyRow,
            "AtLeastOneFieldFilled" to firstRow,
            "FieldValuesNotUnique" to everyRow,
            "Boolean comparison" to firstRow,
            "Confirm comparison" to firstRow,
            "keyed AtLeastOneGroupFilled" to firstRow,
            "keyed NoGroupFilled" to firstRow,
        ), kernel)
    }

    /** Each grid leaf's label, mapped to the stop rows its rule fires on. */
    private fun firingRows(o: DocumentObservation): Map<String, List<String>> =
        labels.entries.associate { (code, label) ->
            label to o.messages.filter { it.code == code }.map { it.pointer.substringAfter("/Stops").substringBefore('/') }
        }
}
