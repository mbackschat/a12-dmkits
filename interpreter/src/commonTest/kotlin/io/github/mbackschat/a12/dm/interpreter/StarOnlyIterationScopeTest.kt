package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for IF127 — the STAR-ONLY iteration scope: a rule's iteration is
 * REFERENCE-driven, and with no per-row (non-starred) repeatable reference the scope comes from the
 * starred refs' BINDING levels (the levels strictly above the first `*`-marked group, IF93); with no
 * repeatable reference level at all the rule evaluates exactly ONCE — content-gated at the error path's
 * root instance, every unreferenced repeatable level pinned to row 1 (phantom anchors included). The
 * declaring group never drives iteration. Kernel-verified by the tri-check differential
 * `adapter.laws.StarOnlyIterationScopeDiffTest` (groovy-dynamic == java-static == interpreter).
 */
class StarOnlyIterationScopeTest {

    private val REFERENCE = "/Order/Reference"
    private val COUNT = "/Order/Items/Count"
    private val QTY = "/Order/Items/Lines/Qty"

    private val model = ModelDef(
        fields = listOf(
            FieldDef(REFERENCE, Kind.STRING),
            FieldDef(COUNT, Kind.NUMBER, scale = 0),
            FieldDef(QTY, Kind.NUMBER, scale = 0),
        ),
        rules = listOf(
            RuleDef("/Order/Items/SO", "/Order/Items", "Sum(/Order/Items*/Count) > -1", "SO", "../Count"),
            RuleDef("/Order/Items/Lines/DS", "/Order/Items/Lines", "Sum(/Order/Items/Lines*/Qty) > -1", "DS", "../Qty"),
            RuleDef("/Order/Items/XR", "/Order/Items", "FieldFilled(/Order/Reference)", "XR", "../../Reference"),
        ),
        computations = emptyList(),
        repeatableGroups = setOf("/Order/Items", "/Order/Items/Lines"),
    )

    private fun fired(code: String, build: (io.github.mbackschat.a12.dm.interpreter.eval.Document) -> Unit): List<String> {
        val doc = model.newDocument()
        build(doc)
        return DmInterpreter(model).validateFull(doc).filter { it.code == code }
            .map { "${it.errorPath}|${it.type.name}" }.sorted()
    }

    @Test fun sameGroupStarOnlyRuleFiresOnceAtTheFirstRow() {
        val fired = fired("SO") { d ->
            d.group("/Order", 1)
            for (i in 1..3) d.group("/Order/Items", 1, i).field(COUNT, "$i", 1, i, 1)
        }
        assertEquals(listOf("/Order[1]/Items[1]/Count|${MsgType.OMISSION.name}"), fired,
            "a star-only rule at its OWN repeatable group fires ONCE, anchored at row 1 — never per row")
    }

    @Test fun sameGroupStarOnlyPhantomFiresWhenTheRootHasContent() {
        val fired = fired("SO") { d ->
            d.group("/Order", 1).field(REFERENCE, "ORD-77", 1, 1)
        }
        assertEquals(listOf("/Order[1]/Items[1]/Count|${MsgType.OMISSION.name}"), fired,
            "zero Items rows + root content: the once-evaluation phantom-fires at the pinned Items[1]")
    }

    @Test fun sameGroupStarOnlyStaysSilentOnAFullyEmptyDocument() {
        val fired = fired("SO") { d -> d.group("/Order", 1) }
        assertEquals(emptyList(), fired, "no valued field anywhere — the root content gate suppresses the once-fire")
    }

    @Test fun deepStarIteratesItsBindingLevelWithPhantomAnchors() {
        val fired = fired("DS") { d ->
            d.group("/Order", 1)
            d.group("/Order/Items", 1, 1).field(COUNT, "2", 1, 1, 1)
            d.group("/Order/Items/Lines", 1, 1, 1).field(QTY, "1", 1, 1, 1, 1)
            d.group("/Order/Items/Lines", 1, 1, 2).field(QTY, "2", 1, 1, 2, 1)
            d.group("/Order/Items", 1, 2).field(COUNT, "5", 1, 2, 1)   // no Lines row at all
        }
        assertEquals(
            listOf("/Order[1]/Items[1]/Lines[1]/Qty|${MsgType.OMISSION.name}",
                "/Order[1]/Items[2]/Lines[1]/Qty|${MsgType.OMISSION.name}"), fired,
            "the star's binding level (Items) iterates; Lines collapses to the pinned row-1 anchor, phantom included")
    }

    @Test fun starFreeNonRepeatableRefRuleFiresOnceWithoutDuplicates() {
        val fired = fired("XR") { d ->
            d.group("/Order", 1).field(REFERENCE, "ORD-77", 1, 1)
            for (i in 1..3) d.group("/Order/Items", 1, i).field(COUNT, "$i", 1, i, 1)
        }
        assertEquals(listOf("/Order[1]/Reference|${MsgType.VALUE.name}"), fired,
            "a star-free rule at a repeatable group whose only ref is a root field fires ONCE — no per-row duplicates")
    }
}
