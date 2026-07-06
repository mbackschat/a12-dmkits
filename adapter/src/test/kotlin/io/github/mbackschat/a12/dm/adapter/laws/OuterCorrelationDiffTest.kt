package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — the `$` OUTER-ITERATION CORRELATION (IG23) through [DmInterpreter], the interpreter twin of
 * the kernel-only `CorrelationLawsTest`. A `Having` filter over a starred field keeps the rows whose value
 * equals the OUTER row's value (`[$Count]`), self-excluded via `CurrentRepetition($)`; the quantifier then
 * fires on the rows with a cross-row duplicate. The interpreter must fire on exactly the rows the kernel does
 * — the reported bug was `model eval` crashing at parse on `$`. Also exercises the filtered-quantifier-over-a-
 * star path (IG25's starred-field form), which the reported condition needs alongside `$`.
 */
class OuterCorrelationDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = DslRenderer().render(c)
    private val fields = listOf(
        FieldDef(CN, Kind.STRING), FieldDef(SKU, Kind.STRING), FieldDef(COUNT, Kind.NUMBER, scale = 0))

    // FieldFilled(Count) And AtLeastOneFieldFilled(Items* / Count Having
    //   CurrentRepetition(Items) != CurrentRepetition($Items) And [Count] == [$Count])
    private val corr = render(m.field(COUNT).filled().and(
        Dsl.atLeastOneFieldFilled(m.flatten("/Order/Items*/Count").having(
            Dsl.currentRepetition(m.group("/Order/Items"))
                .ne(Dsl.currentRepetition(m.group("/Order/Items"), true))
                .and(m.num(COUNT).eq(m.num(COUNT).outer()))))))

    private fun kFires(vararg counts: String): Int {
        val doc = RuntimeLaws.doc().group("/Order", 1).field(CN, "Acme", 1, 1)
        counts.forEachIndexed { i, c ->
            val r = i + 1
            doc.group("/Order/Items", 1, r).field(SKU, ('A' + i).toString(), 1, r, 1).field(COUNT, c, 1, r, 1)
        }
        val model = RuntimeLaws.withRule(order, "/Order", "Corr", "../Items/Count", "CORR", corr)
        return RuntimeLaws.messages(model, doc).count { it.code() == "CORR" }
    }

    private fun iFires(vararg counts: String): Int {
        val def = ModelDef(fields, listOf(RuleDef("/Order/Corr", "/Order", corr, "CORR", "../Items/Count")),
            repeatableGroups = setOf("/Order/Items"), declaredReps = mapOf("/Order/Items" to 1000), indexFields = setOf(SKU))
        val doc = Document(def.kinds, def.declaredReps, def.unsignedFields, def.rangeConfig, def.dateFormats, def.scales)
        counts.forEachIndexed { i, c ->
            val r = i + 1
            doc.field(SKU, ('A' + i).toString(), 1, r, 1).field(COUNT, c, 1, r, 1)
        }
        return DmInterpreter(def).validateFull(doc).count { it.code == "CORR" }
    }

    @Test fun duplicateFiresOnBothMatchingRows() =
        assertEquals(kFires("5", "5", "9"), iFires("5", "5", "9"), "5,5,9 → rows 1 and 2 have a cross-row duplicate")

    @Test fun allDistinctFiresNowhere() =
        assertEquals(kFires("5", "6", "9"), iFires("5", "6", "9"), "5,6,9 → distinct → no fire")

    @Test fun allSameFiresOnEveryRow() =
        assertEquals(kFires("5", "5", "5"), iFires("5", "5", "5"), "5,5,5 → every row has a duplicate")

    private companion object {
        const val CN = "/Order/CustomerName"
        const val SKU = "/Order/Items/Sku"
        const val COUNT = "/Order/Items/Count"
    }
}
