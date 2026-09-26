package io.github.mbackschat.a12.dm.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import io.github.mbackschat.a12.dm.core.ModelIndex
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.interpreter.model.ConditionLanguage
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.ComparisonCondition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr

/**
 * DEV-API-GAPS DG77, the kernel-free read half: a count's text maps to the record either authored spelling builds.
 * The bridge routes a fixed-group count through the `GroupRef` factories and an ancestor-starred one through the
 * scope factory, so the route must not reach the value. Own domain: an order in parcels.
 */
class CountAggregateReadShapeTest {

    private val json = DocumentModelDraft.named("shop-bridge").locale("en_US")
        .rootGroup("Order") { order ->
            order.field("Note", FieldKind.STRING)
                .group("Billing") { it.field("Account", FieldKind.STRING) }
                .group("Shipping") { it.field("Carrier", FieldKind.STRING) }
                .group("Parcels") { parcels ->
                    parcels.repeatable(5).field("Weight", FieldKind.NUMBER)
                        .group("Seal") { it.field("Code", FieldKind.STRING) }
                }
        }
        .build().toJson()

    private val model = BoundModel.ofJson(json)

    private val bridge = ConditionBridge(model, ModelIndex.of(json, ReferenceResolvers.selfContained()))

    @Test
    fun eachCountTextMapsToTheRecordBothSpellingsBuild() {
        val billing = model.group("/Order/Billing")
        val shipping = model.group("/Order/Shipping")
        val parcels = model.group("/Order/Parcels")
        val spellings: Map<String, List<NumberExpr>> = linkedMapOf(
            "NumberOfFilledGroups(Billing, Shipping) > 0" to listOf(
                Dsl.numberOfFilledGroups(billing, shipping),
                Dsl.numberOfFilledGroups(model.flattenGroup("/Order/Billing"), model.flattenGroup("/Order/Shipping"))
            ),
            "NumberOfFilledGroups(Parcels*) > 0" to listOf(
                Dsl.numberOfFilledGroups(parcels), Dsl.numberOfFilledGroups(model.flattenGroup("/Order/Parcels*"))
            ),
            "NumberOfFilledFields(Billing, Shipping) > 0" to listOf(
                Dsl.numberOfFilledFields(billing, shipping),
                Dsl.numberOfFilledFields(listOf(model.flattenGroup("/Order/Billing"), model.flattenGroup("/Order/Shipping")))
            ),
            "NumberOfFilledGroups(Parcels*/Seal) > 0" to listOf(
                Dsl.numberOfFilledGroups(model.flattenGroup("/Order/Parcels*/Seal"))
            ),
        )
        val unequal = spellings.mapValues { (text, authored) ->
            val read = assertInstanceOf(ComparisonCondition::class.java, bridge.translate(text, "/Order", ConditionLanguage.EN)).left()
            authored.count { it.expression() != read }
        }
        assertEquals(spellings.mapValues { 0 }, unequal)
    }
}
