package io.github.mbackschat.a12.dm.bridge

import io.github.mbackschat.a12.dm.core.ModelIndex
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.model.ConditionLanguage
import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.DateFragmentRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * A date-fragment field narrows to rulekit's `DateFragmentRef`. The arm used to ask `BoundModel.date`, which
 * refuses a fragment, so every operator narrowing a fragment operand failed in the bridge rather than in rulekit
 * (2026-10-25 cold review, R12). Own domain (an e-commerce invoice).
 */
class DateFragmentTypedRefTest {

    private val json = DocumentModelDraft.named("invoice-months").locale("en_US")
        .rootGroup("Invoice") { invoice ->
            invoice.stringField("Number") { }
            invoice.dateFragmentField("BillingMonth") { it.fragmentFormat("yyyy-MM") }
        }.build().toJson()

    @Test
    fun aFragmentFieldNarrowsToItsOwnHandle() {
        val operands = Operands(ConditionBridge(BoundModel.ofJson(json), ModelIndex.of(json, ReferenceResolvers.selfContained())))
        val parsed = parseCondition("NumberOfFilledFields(BillingMonth) > 0", "/Invoice", ConditionLanguage.EN)
        val node: Expr = ((parsed as Cond.Cmp).left as Expr.Func).args[0]
        val ref = assertInstanceOf(DateFragmentRef::class.java, operands.typedRef(node))
        assertEquals("/Invoice/BillingMonth", ref.field().fieldPath())
    }
}
