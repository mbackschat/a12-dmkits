package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for IF106 — a bare GROUP reference (a group-scope quantifier argument, legal
 * un-starred on an INDEX-keyed repeatable group) is an iteration anchor: the rule runs PER ROW of the
 * referenced group and judges each row's own cells (`scopeFromRefs` ancestor-or-SELF). The error entity is NOT
 * an anchor (kernel-captured corpus cases pin that). Kernel-verified by the tri-check differential
 * `adapter.laws.CollectivelyFilledGroupScopeDiffTest`.
 */
class GroupRefRuleScopeTest {

    private val NUM = "/Order/OrderInformation/OrderNumber"
    private val QTY = "/Order/OrderInformation/Qty"

    private val def = ModelDef(
        fields = listOf(FieldDef(NUM, Kind.NUMBER, scale = 0), FieldDef(QTY, Kind.NUMBER, scale = 0),
            FieldDef("/Order/Note", Kind.STRING)),
        rules = listOf(RuleDef("/Order/R", "/Order", "FieldsNotCollectivelyFilled(OrderInformation)",
            "COLL", "../OrderInformation/OrderNumber")),
        computations = emptyList(),
        repeatableGroups = setOf("/Order/OrderInformation"),
        indexFields = setOf(NUM),
    )

    private fun fired(rows: List<Pair<String?, String?>>): List<String> {
        val doc = def.newDocument().group("/Order", 1)
        rows.forEachIndexed { i, (num, qty) ->
            doc.group("/Order/OrderInformation", 1, i + 1)
            num?.let { doc.field(NUM, it, 1, i + 1, 1) }
            qty?.let { doc.field(QTY, it, 1, i + 1, 1) }
        }
        return DmInterpreter(def).validateFull(doc).filter { it.code == "COLL" }.map { it.errorPath }.sorted()
    }

    @Test fun everyRowCompleteIsSilent() =
        assertEquals(emptyList(), fired(listOf("557" to "5", "82" to "7")))

    @Test fun aPartialRowFiresAtThatRowOnly() = assertEquals(
        listOf("/Order[1]/OrderInformation[2]/OrderNumber"),
        fired(listOf("557" to "5", "82" to null)),
        "per-row judgment: only the partially-filled row fires, at its own pointer")

    @Test fun anAllEmptyRowIsNotPartial() =
        assertEquals(emptyList(), fired(listOf("557" to "5", null to null)))
}
