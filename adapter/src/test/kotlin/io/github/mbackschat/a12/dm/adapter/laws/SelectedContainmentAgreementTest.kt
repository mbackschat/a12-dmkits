package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SelectedContainmentAgreementTest {
    private enum class Scope { PayloadAncestor, KeyAncestor, SelectedOnly }
    private enum class Carrier { Field, Group, StarField, StarGroup }

    @Test
    fun containmentChecksOnlyTheOrdinaryLevelsLeftBySelection() =
        checkContainment(listOf(Carrier.Field, Carrier.Group))

    @Test
    fun lowerStarsPreserveTheSameContainmentBoundary() =
        checkContainment(listOf(Carrier.StarField, Carrier.StarGroup))

    private fun checkContainment(carriers: List<Carrier>) {
        val base = DocumentModelDraft.named("depot-selected-containment").locale("en_US")
            .rootGroup("Store") { store -> store.stringField("Reference") { }
                .group("Rules") { it.numberField("Total") { } }
                .group("Depots") { depot -> depot.repeatable(2).stringField("Wanted") { }
                    .group("Bins") { it.repeatable(3).indexField("Code").stringField("Code") { }
                        .numberField("Stock") { }.group("Shelf") { it.numberField("Stock") { } }
                        .group("Slots") { it.repeatable(2).numberField("Qty") { } } } }
                .group("Catalog") { it.repeatable(3).indexField("Code").stringField("Code") { }
                    .numberField("Stock") { }.group("Shelf") { it.numberField("Stock") { } }
                        .group("Slots") { it.repeatable(2).numberField("Qty") { } } }
            }.build().toJson()
        val session = EditSession.read(base)
        val store = session.group("/Store")
        val outside = session.group("/Store/Rules")
        val target = store.field("Reference")
        val wanted = store.field("Depots/Wanted")
        val wrong = mutableListOf<String>()
        for (carrier in carriers) for (scope in Scope.entries) {
            val path = if (scope == Scope.PayloadAncestor) "Depots/Bins" else "Catalog"
            val selected = when (carrier) {
                Carrier.Field -> store.num("$path/Stock").let {
                    (if (scope == Scope.KeyAncestor) it.forIndex(wanted) else it.forIndex("b")).gt(0)
                }
                Carrier.Group, Carrier.StarGroup -> store.flattenGroup(if (carrier == Carrier.Group) "$path/Shelf" else "$path/Slots*").let {
                    Dsl.sum(if (scope == Scope.KeyAncestor) it.forIndex(wanted) else it.forIndex("b")).gt(0)
                }
                Carrier.StarField -> store.flatten("$path/Slots*/Qty").let {
                    Dsl.sum(if (scope == Scope.KeyAncestor) it.forIndex(wanted) else it.forIndex("b")).gt(0)
                }
            }
            val condition = Dsl.groupFilled(store.ref()).and(selected)
            for (declaring in listOf(store, outside)) {
                val pathRef = declaring.ref().path()
                // Rule.build's deliberate containment restriction cannot author these kernel controls (KF212).
                val draft = DraftRule.builder().groupPath(pathRef.value()).name("Selected")
                    .errorEntityRelPath(pathRef.elementReference(target.path()))
                    .errorCondition(RuntimeLaws.render(pathRef.value(), condition))
                    .errorCode("Selected").severity("ERROR").messages(listOf(Dsl.en("Check selected stock."))).build()
                val expected = when {
                    scope == Scope.SelectedOnly -> emptySet()
                    declaring == outside -> setOf("MVK_ERROR_FIELD_NOT_IN_RULEGROUP")
                    scope == Scope.KeyAncestor -> setOf("MVK_SEMANTIC_INDEX_INVALID_ITER")
                    else -> setOf("MVK_NO_WILDCARD")
                }
                val result = RuleValidator().check(base, draft)
                val kernel = result.diagnostics().filter { !result.ok() }.map { it.code().orElse("uncoded") }.toSet()
                assertEquals(expected, kernel, "$carrier $scope ${pathRef.value()} kernel")
                val portable = rejectingCodes(RuleValidator().inject(base, draft)).toSet()
                if (kernel != portable) wrong += "$carrier $scope ${pathRef.value()}: kernel=$kernel portable=$portable"
            }
        }
        assertEquals(emptyList<String>(), wrong)
    }
}
