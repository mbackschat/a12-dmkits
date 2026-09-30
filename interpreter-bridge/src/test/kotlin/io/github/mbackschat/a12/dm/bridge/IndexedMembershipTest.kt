package io.github.mbackschat.a12.dm.bridge

import io.github.mbackschat.a12.dm.core.ModelIndex
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.interpreter.model.ConditionLanguage
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IndexedMembershipTest {
    private val json = DocumentModelDraft.named("dispatch-membership").locale("en_US")
        .rootGroup("Order") { order ->
            order.field("Title", FieldKind.STRING)
                .enumField("Mode") { it.value("ROAD").value("AIR") }
                .group("Packages") { packages ->
                    packages.repeatable(5).indexField("Code")
                        .field("Code", FieldKind.STRING)
                        .numberField("Weight") { it.fractionDigits(0) }
                        .enumField("Mode") { it.value("ROAD").value("AIR") }
                }
        }.build().toJson()
    private val bridge = ConditionBridge(BoundModel.ofJson(json), ModelIndex.of(json, ReferenceResolvers.selfContained()))

    private fun roundTrip(condition: String) {
        assertEquals(condition, DslRenderer().render(bridge.translate(condition, "/Order", ConditionLanguage.EN)))
    }

    @Test fun enumMembershipRetainsBothKeyFormsInEveryQuantifier() {
        for (key in listOf("\"P1\"", "/Order/Title")) {
            for (op in listOf("AtLeastOneFieldValueIncludedInValueList", "NoFieldValueIncludedInValueList",
                "NotAllFieldValuesIncludedInValueList")) {
                roundTrip("$op(/Order/Packages/Mode For $key, /Order/Mode In \"ROAD\")")
                roundTrip("$op(/Order/Mode, /Order/Packages/Mode For $key In \"ROAD\")")
                roundTrip("$op(/Order/Packages/Mode For $key, /Order/Packages/Mode For \"P2\" In \"ROAD\")")
            }
        }
    }

    @Test fun numericMembershipRetainsBothKeyFormsAndPolarities() {
        for (key in listOf("\"P1\"", "/Order/Title")) {
            for (op in listOf("FieldValueIncludedInValueList", "FieldValueNotIncludedInValueList")) {
                roundTrip("$op(/Order/Packages/Weight For $key, 0, 5)")
            }
        }
    }

    @Test fun plainEnumMembershipStillChecksItsDeclaredDomain() {
        roundTrip("NoFieldValueIncludedInValueList(/Order/Mode In \"ROAD\")")
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            roundTrip("NoFieldValueIncludedInValueList(/Order/Mode In \"UNKNOWN\")")
        }
    }
}
