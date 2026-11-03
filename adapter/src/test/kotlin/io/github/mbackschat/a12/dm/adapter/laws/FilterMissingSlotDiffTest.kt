package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.DocumentDifferential.underUtc
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListRef
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Whether a `Having` filter is evaluated on a declared slot no row is stored for (INTERPRETER-GAPS IG165). The filter
 * `[Weight] > 0 Or [Stamp] == <gap label>` reaches the label only where the weight leaves the `Or` unknown, and the
 * label fails at runtime, so reaching it is observable. Seven consumers read the filtered weights, the five numeric
 * ones each computed into a target and checked by a rule, over documents that store fewer legs, sibling rows or nested
 * items than the walk's bound. All three engines agree on every document; the separating rows are pinned.
 *
 * The JVM default is pinned to UTC, under which the kernel's static check admits the gap label
 * (`DateTimeConstantZoneSplitDiffTest`); the model zone is Europe/Berlin.
 *
 * Own domain (parcel delivery legs).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FilterMissingSlotDiffTest {
    private companion object {
        const val ISO_DT = "yyyy-MM-dd'T'HH:mm:ss"

        /** A Julian leap day, which Europe/Berlin cannot read: the label fails when reached (KERNEL-FINDINGS KF665). */
        const val GAP = "29.02.1500T10:00:00"
        val TARGETS = listOf("Total", "Counted", "Heaviest", "First", "Sevens", "ItemTotal")
        val OTHERS = listOf("NoteCount")
    }

    private val model = build(readsReturns = true)

    /** The same model without the computation that reads `Returns`, whose rows then no longer extend a computation's walk. */
    private val unreadModel = build(readsReturns = false)

    private fun build(readsReturns: Boolean): String = underUtc {
        val draft = DocumentModelDraft.named("parcel-filter-slots").locale("en_US")
            .rootGroup("Parcel") { p ->
                p.stringField("Ref") { }
                p.group("Legs") { l -> l.repeatable(3)
                    .numberField("Weight") { n -> n.fractionDigits(0) }
                    .dateTimeField("Stamp") { it.format(ISO_DT) } }
                p.group("Returns") { r -> r.repeatable(3).stringField("Note") { } }
                p.group("Shipments") { s -> s.repeatable(2).group("Items") { i -> i.repeatable(3)
                    .numberField("Weight") { n -> n.fractionDigits(0) }
                    .dateTimeField("Stamp") { it.format(ISO_DT) } } }
                for (name in TARGETS + OTHERS) p.numberField(name) { n -> n.fractionDigits(0) }
            }.build().toJson()
        val session = EditSession.read(draft).also { it.config().timeZone("Europe/Berlin") }
        val p = session.group("/Parcel")
        val legs = session.group("/Parcel/Legs")
        val filter = legs.num("Weight").gt(0).or(legs.dateTime("Stamp").eq(Dsl.dateTimeValue(GAP)))
        fun kept(): FieldListRef = p.flatten("Legs*/Weight").having(filter)
        val numbers: Map<String, NumberExpr> = linkedMapOf(
            "Total" to Dsl.sum(kept()),
            "Counted" to Dsl.numberOfFilledFields(kept()),
            "Heaviest" to Dsl.maxValue(kept()),
            "First" to Dsl.firstFilledNumber(kept()),
            "Sevens" to Dsl.numberOfValueInFields(7L, kept()),
            "ItemTotal" to run {
                val items = session.group("/Parcel/Shipments/Items")
                Dsl.sum(p.flatten("Shipments*/Items*/Weight").having(
                    items.num("Weight").gt(0).or(items.dateTime("Stamp").eq(Dsl.dateTimeValue(GAP)))))
            },
        )
        val ref = p.str("Ref")
        var json = session.toJson()
        for ((name, value) in numbers) {
            json = TypedRules.inject(json, p.computation(p.num(name)) {
                it.name(name + "Comp").calc(value).message(Dsl.en("Check the legs.")).build()
            })
            json = TypedRules.inject(json, p.rule {
                it.name(name + "Rule").code(name + "Rule").errorField(ref.field())
                    .`when`(ref.filled().and(value.gt(0))).message(Dsl.en("Check the legs.")).build()
            })
        }
        if (readsReturns) json = TypedRules.inject(json, p.computation(p.num("NoteCount")) {
            it.name("NoteCountComp").calc(Dsl.numberOfFilledFields(p.flatten("Returns*/Note")))
                .message(Dsl.en("Check the legs.")).build()
        })
        val conditions: Map<String, Condition> = linkedMapOf(
            "AnyKept" to Dsl.atLeastOneFieldFilled(kept()),
            "NoneKept" to Dsl.noFieldFilled(kept()),
            "Repeated" to Dsl.fieldValuesNotUnique(kept()),
        )
        for ((name, condition) in conditions) {
            json = TypedRules.inject(json, p.rule {
                it.name(name + "Rule").code(name + "Rule").errorField(ref.field())
                    .`when`(ref.filled().and(condition)).message(Dsl.en("Check the legs.")).build()
            })
        }
        json
    }

    private fun parcel(legs: List<String>, returns: Int = 0, shipments: List<Int> = emptyList()) =
        """{"Parcel":{"Ref":"P-1","Legs":[${legs.joinToString(",")}],""" +
            """"Returns":[${List(returns) { """{"Note":"r"}""" }.joinToString(",")}],""" +
            """"Shipments":[${shipments.joinToString(",") { n -> """{"Items":[${List(n) { leg }.joinToString(",")}]}""" }}],""" +
            TARGETS.joinToString(",") { "\"$it\":\"99\"" } + "}}"

    private val leg = """{"Weight":"7","Stamp":"2024-03-31T01:30:00"}"""

    private val documents = linkedMapOf(
        "no legs" to parcel(emptyList()),
        "one leg" to parcel(listOf(leg)),
        "every leg" to parcel(listOf(leg, leg, leg)),
        "one leg, three returns" to parcel(listOf(leg), returns = 3),
        "one leg, two blank legs" to parcel(listOf(leg, "{}", "{}")),
        "items 1 and 3" to parcel(listOf(leg, leg, leg), shipments = listOf(1, 3)),
        "items 0 and 1" to parcel(listOf(leg, leg, leg), shipments = listOf(0, 1)),
        "items 1 and 1" to parcel(listOf(leg, leg, leg), shipments = listOf(1, 1)),
        "one leg, three blank returns" to parcel(listOf(leg), returns = 3).replace("{\"Note\":\"r\"}", "{}"),
        "one shipment of one item, three legs" to parcel(listOf(leg, leg, leg), shipments = listOf(1)),
        "one shipment of one item, one leg" to parcel(listOf(leg), shipments = listOf(1)),
    )

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        underUtc {
            DocumentDifferential.assertAgree(model, documents)
            DocumentDifferential.assertAgree(unreadModel, documents)
        }
    }

    /**
     * The separating rows, pinned against the normative kernel: whether the walk reaches the label, read as the
     * computed `Total` clearing with a failure at its target and the rule over it reporting VALUE. The walk stops at
     * the highest stored repetition, so one stored leg leaves the missing two unread, while a sibling group's rows at
     * the same depth extend it in validation always and in a computation only once a computation names that group
     * (here by reading it; [FilterCacheScopeDiffTest] covers a target, an unreached read and a deeper fixed group).
     * The nested rows show the same bound across parents and up an outer level.
     */
    @Test
    fun theWalkReachesTheHighestStoredRepetitionAtEachDepth() {
        fun reached(doc: String, target: String, json: String = model): Pair<Boolean, Boolean> {
            val observed = underUtc { DocumentDifferential.kernel(json, documents.getValue(doc), dynamic = true) }
            return observed.operandErrors.any { it.code == "berechnungsWertFehler" && it.pointer.endsWith("/$target") } to
                observed.messages.any { it.code == target + "Rule" && it.type == "VALUE_ERROR" }
        }
        val actual = linkedMapOf(
            "no legs" to reached("no legs", "Total"),
            "one leg" to reached("one leg", "Total"),
            "one leg, three returns a computation reads" to reached("one leg, three returns", "Total"),
            "one leg, three returns no computation reads" to reached("one leg, three returns", "Total", unreadModel),
            "one leg, three blank returns" to reached("one leg, three blank returns", "Total"),
            "items 1 and 3" to reached("items 1 and 3", "ItemTotal"),
            "items 1 and 1" to reached("items 1 and 1", "ItemTotal"),
            "one shipment of one item, three legs" to reached("one shipment of one item, three legs", "ItemTotal"),
            "one shipment of one item, one leg" to reached("one shipment of one item, one leg", "ItemTotal"),
        )
        assertEquals(linkedMapOf(
            "no legs" to (true to true),
            "one leg" to (false to false),
            "one leg, three returns a computation reads" to (true to true),
            "one leg, three returns no computation reads" to (false to true),
            "one leg, three blank returns" to (true to true),
            "items 1 and 3" to (true to true),
            "items 1 and 1" to (false to false),
            "one shipment of one item, three legs" to (true to true),
            "one shipment of one item, one leg" to (false to false),
        ), actual)
    }

    @Test
    fun theModelIsKernelConsistentUnderTheAdmittingDefault() {
        assertEquals(emptyList<String>(), underUtc { MessageParameterProbes.kernel(model) + MessageParameterProbes.kernel(unreadModel) })
    }
}
