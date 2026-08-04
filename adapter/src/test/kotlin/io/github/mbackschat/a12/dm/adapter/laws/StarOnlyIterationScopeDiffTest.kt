package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check (groovy-dynamic == java-static == interpreter) — the **STAR-ONLY iteration-scope
 * family** (IG73 → IF127): a rule whose condition carries NO per-row (non-starred) repeatable reference, so
 * its iteration scope comes entirely from the starred refs' BINDING levels. Kernel mechanism (source: the
 * codegen's `RuleIterationLimitCreator.getIterationsEndeFuerKontexte` × `AnalyseService.isIteration`): an
 * error-entity context level iterates only when some reference carries the ITERATION marker at that level —
 * a star is a WILDCARD, never ITERATION, so the starred level and everything below never drive iteration,
 * while unindexed repeatable levels STRICTLY ABOVE the first star do (the same levels the star BINDS, IF93).
 * The rule's declared group appears nowhere — placement never drives iteration (the §9-supplement law).
 *
 * The interpreter's `IterationScopes` excluded starred refs from scope determination and fell back to the
 * DECLARING group, so a rule at a repeatable group whose condition is star-only OVER-fired per row of its
 * own group (arm a: 3 messages vs the kernel's 1, the G42 investigation's STAR-ONLY arm) — and a star-only
 * rule with a repeatable level above the star iterated the WRONG scope (arm c2/d: the declaring group's rows
 * instead of the above-star level's).
 */
class StarOnlyIterationScopeDiffTest {

    private companion object {
        const val CODE = "STARSCOPE"
        const val ITEMS = "/Order/Items"
        const val COUNT = "/Order/Items/Count"
        const val LINES = "/Order/Items/Lines"
        const val QTY = "/Order/Items/Lines/Qty"
        const val FEES = "/Order/Fees"
        const val AMOUNT = "/Order/Fees/Amount"
        const val REFERENCE = "/Order/Reference"
    }

    private val base = DocumentModelDraft.named("orderbook").locale("en_US")
        .rootGroup("Order") { g ->
            g.stringField("Reference") { }
            g.group("Items") { i ->
                i.repeatable(10).numberField("Count") { it.fractionDigits(0, 0) }
                i.group("Lines") { l -> l.repeatable(10).numberField("Qty") { it.fractionDigits(0, 0) } }
            }
            g.group("Fees") { f -> f.repeatable(10).numberField("Amount") { it.fractionDigits(0, 2) } }
            g.group("Archive") { a ->
                a.group("Snapshots") { s -> s.repeatable(10).stringField("Stamp") { } }
            }
        }.build().toJson()

    init { BoundModel.ofJson(base) }   // model sanity (parses + indexes)

    private class Both(val kDoc: RuntimeLaws.Doc, val iDoc: Document) {
        fun group(path: String, vararg reps: Int): Both { kDoc.group(path, *reps); iDoc.group(path, *reps); return this }
        fun field(path: String, v: String, vararg reps: Int): Both { kDoc.field(path, v, *reps); iDoc.field(path, v, *reps); return this }
    }

    /** [counts] one Count per Items row; [qtysPerItem] one entry per Items row = its Lines' Qty values;
     *  [fees] one Amount per Fees row; [reference] the root string field. */
    private fun build(b: Both, counts: List<String> = emptyList(), qtysPerItem: List<List<String>> = emptyList(),
                      fees: List<String> = emptyList(), reference: String? = null) {
        b.group("/Order", 1)
        reference?.let { b.field(REFERENCE, it, 1, 1) }
        val items = maxOf(counts.size, qtysPerItem.size)
        for (i in 1..items) {
            b.group(ITEMS, 1, i)
            counts.getOrNull(i - 1)?.let { b.field(COUNT, it, 1, i, 1) }
            qtysPerItem.getOrNull(i - 1)?.forEachIndexed { l, qty ->
                b.group(LINES, 1, i, l + 1).field(QTY, qty, 1, i, l + 1, 1)
            }
        }
        fees.forEachIndexed { f, amount ->
            b.group(FEES, 1, f + 1).field(AMOUNT, amount, 1, f + 1, 1)
        }
    }

    /** Tri-check the fire-set (pointer|polarity, optionally rich references) of the rule at [group]/[errorRel]
     *  with [cond]. */
    private fun agree(group: String, errorRel: String, cond: String, expect: List<String>, msg: String,
                      includeReferences: Boolean = false,
                      buildDoc: (Both) -> Unit) {
        val kModel = RuntimeLaws.withRule(base, group, "R", errorRel, CODE, cond)
        val def = ModelLoader.load(kModel)
        val b = Both(RuntimeLaws.doc(), def.newDocument())
        buildDoc(b)
        val groovy = RuntimeLaws.messages(kModel, b.kDoc).filter { it.code() == CODE }
            .map { "${it.field()}|${it.type().name}" + if (includeReferences) "|${it.referenced().sorted()}" else "" }.sorted()
        val engine = RuntimeLaws.kernelJavaEngine(kModel)
        val kDoc2 = RuntimeLaws.doc()
        buildDoc(Both(kDoc2, def.newDocument()))
        val javaStatic = engine.messagesModern(kDoc2, null, java.util.Locale.US).filter { it.code() == CODE }
            .map { "${it.field()}|${it.type().name}" + if (includeReferences) "|${it.referenced().sorted()}" else "" }.sorted()
        val interp = DmInterpreter(def).validateFull(b.iDoc).filter { it.code == CODE }
            .map {
                "${it.errorPath}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" +
                    if (includeReferences) "|${it.referenced.sorted()}" else ""
            }.sorted()
        assertEquals(expect.sorted(), groovy, "$msg — the kernel groovy-dynamic fire set")
        assertEquals(groovy, javaStatic, "$msg — groovy vs java-static (tri-check)")
        assertEquals(groovy, interp, "$msg — kernel vs interpreter")
    }

    // ── arm (a): the reported shape — rule.group IS the starred group ────────────────────────────────────────

    @Test fun ownGroupStarOnlyFiresOnceAtTheFirstRow() =
        agree(ITEMS, "../Count", "Sum(/Order/Items*/Count) > -1",
            expect = listOf("/Order[1]/Items[1]/Count|OMISSION_ERROR"),
            msg = "always-true Sum over the rule's OWN starred group fires ONCE, anchored at row 1") { b ->
            build(b, counts = listOf("2", "5", "1"))
        }

    @Test fun ownGroupStarOnlyCarriesItsWildcardReference() =
        agree(ITEMS, "../Count", "Sum(/Order/Items*/Count) > -1",
            expect = listOf("/Order[1]/Items[1]/Count|OMISSION_ERROR|[/Order[1]/Items[0]/Count]"),
            msg = "a starred aggregate's message retains the kernel wildcard operand pointer",
            includeReferences = true) { b ->
            build(b, counts = listOf("2", "5", "1"))
        }

    @Test fun ownGroupStarOnlyWithZeroRowsIsSilent() =
        agree(ITEMS, "../Count", "Sum(/Order/Items*/Count) > -1",
            expect = emptyList(),
            msg = "with NO Items row the pinned first-row anchor does not exist — no phantom fire") { b ->
            build(b)
        }

    @Test fun ownGroupStarOnlyWithZeroRowsPhantomFiresWhenTheRootHasContent() =
        agree(ITEMS, "../Count", "Sum(/Order/Items*/Count) > -1",
            expect = listOf("/Order[1]/Items[1]/Count|OMISSION_ERROR"),
            msg = "a valued sibling field passes the content gate — the kernel PHANTOM-fires at the missing Items[1]") { b ->
            build(b, reference = "ORD-77")
        }

    @Test fun ownGroupStarOnlySilentControl() =
        agree(ITEMS, "../Count", "Sum(/Order/Items*/Count) > 999",
            expect = emptyList(),
            msg = "the false comparison is silent everywhere (no phantom per-row fires either)") { b ->
            build(b, counts = listOf("2", "5", "1"))
        }

    // ── arm (b): rule.group repeatable but NOT the starred group — placement still never iterates ───────────

    @Test fun otherGroupStarOnlyCollapsesDespiteRepeatableRuleGroup() =
        agree(ITEMS, "../../Fees/Amount", "Sum(/Order/Fees*/Amount) > -1",
            expect = listOf("/Order[1]/Fees[1]/Amount|OMISSION_ERROR"),
            msg = "a rule AT repeatable Items whose refs are star-only over Fees fires ONCE (never per Items row)") { b ->
            build(b, counts = listOf("2", "5", "1"), fees = listOf("10.00", "20.00"))
        }

    @Test fun otherGroupStarOnlyPhantomFiresAtTheMissingAnchorRow() =
        agree(ITEMS, "../../Fees/Amount", "Sum(/Order/Fees*/Amount) > -1",
            expect = listOf("/Order[1]/Fees[1]/Amount|OMISSION_ERROR"),
            msg = "Items content passes the gate — the kernel phantom-fires at the uninstantiated Fees[1]") { b ->
            build(b, counts = listOf("2", "5", "1"))
        }

    @Test fun otherGroupStarOnlyFiresWithAnEmptyDeclaringGroup() =
        agree(ITEMS, "../../Fees/Amount", "Sum(/Order/Fees*/Amount) > -1",
            expect = listOf("/Order[1]/Fees[1]/Amount|OMISSION_ERROR"),
            msg = "the declaring group's content is irrelevant — zero Items rows, the Fees anchor fires") { b ->
            build(b, fees = listOf("10.00", "20.00"))
        }

    // ── arm (c): nested MULTI-star at its own group — both levels collapse (IF94 bindDepth interplay) ───────

    @Test fun multiStarAtOwnGroupCollapsesBothLevels() =
        agree(LINES, "../Qty", "Sum(/Order/Items*/Lines*/Qty) > -1",
            expect = listOf("/Order[1]/Items[1]/Lines[1]/Qty|OMISSION_ERROR"),
            msg = "a multi-star over the rule's own nested group collapses BOTH starred levels — once, first-row anchor") { b ->
            build(b, qtysPerItem = listOf(listOf("1", "2"), listOf("3", "4")))
        }

    // ── arm (c2): a DEEP single star — the unindexed repeatable level ABOVE the star still iterates ──────────

    @Test fun deepStarIteratesTheUnstarredLevelAboveIt() =
        agree(LINES, "../Qty", "Sum(/Order/Items/Lines*/Qty) > -1",
            expect = listOf("/Order[1]/Items[1]/Lines[1]/Qty|OMISSION_ERROR", "/Order[1]/Items[2]/Lines[1]/Qty|OMISSION_ERROR"),
            msg = "the star's BINDING level (unindexed repeatable Items above Lines*) iterates; Lines collapses") { b ->
            build(b, qtysPerItem = listOf(listOf("1", "2"), listOf("3", "4")))
        }

    @Test fun deepStarPhantomFiresOnAnIteratedRowWithoutTheAnchorRow() =
        agree(LINES, "../Qty", "Sum(/Order/Items/Lines*/Qty) > -1",
            expect = listOf("/Order[1]/Items[1]/Lines[1]/Qty|OMISSION_ERROR", "/Order[1]/Items[2]/Lines[1]/Qty|OMISSION_ERROR"),
            msg = "Items[2] has NO Lines row yet content (Count) — the kernel phantom-fires at its Lines[1]") { b ->
            build(b, qtysPerItem = listOf(listOf("1", "2"), emptyList()), counts = listOf("2", "5"))
        }

    // ── arm (d): the SAME condition from a different declaring group — placement is irrelevant ───────────────

    @Test fun placementDoesNotChangeTheStarOnlyFireSet() =
        agree(ITEMS, "../Lines/Qty", "Sum(/Order/Items/Lines*/Qty) > -1",
            expect = listOf("/Order[1]/Items[1]/Lines[1]/Qty|OMISSION_ERROR", "/Order[1]/Items[2]/Lines[1]/Qty|OMISSION_ERROR"),
            msg = "declaring the same star-only rule at Items instead of Lines leaves the fire set unchanged") { b ->
            build(b, qtysPerItem = listOf(listOf("1", "2"), listOf("3", "4")))
        }

    @Test fun relativeFormBindsTheRuleGroupLikeTheAbsoluteForm() =
        agree(ITEMS, "../Lines/Qty", "Sum(Lines*/Qty) > -1",
            expect = listOf("/Order[1]/Items[1]/Lines[1]/Qty|OMISSION_ERROR", "/Order[1]/Items[2]/Lines[1]/Qty|OMISSION_ERROR"),
            msg = "the relative stored form of the deep star iterates the binding level identically") { b ->
            build(b, qtysPerItem = listOf(listOf("1", "2"), listOf("3", "4")))
        }

    // ── arm (d2): the skill's scope-choice pair — the PARENT-declared variant of arm (a) fires identically ──

    @Test fun parentScopeVariantFiresIdenticallyToTheInRowScope() =
        agree("/Order", "../Items/Count", "Sum(/Order/Items*/Count) > -1",
            expect = listOf("/Order[1]/Items[1]/Count|OMISSION_ERROR"),
            msg = "the same star-only rule declared at the non-repeatable PARENT fires exactly like arm (a): once, first row") { b ->
            build(b, counts = listOf("2", "5", "1"))
        }

    // ── arm (e): the once-evaluation's content gate — root instance vs the star's collapse level ────────────

    @Test fun onceFireIsGatedOnRootContentNotTheCollapseSubtree() =
        agree("/Order/Archive/Snapshots", "../Stamp", "NumberOfFilledFields(/Order/Archive/Snapshots*/Stamp) > -1",
            expect = listOf("/Order[1]/Archive[1]/Snapshots[1]/Stamp|VALUE_ERROR"),
            msg = "content exists ONLY outside the collapse subtree (root Reference) — the once-fire still happens") { b ->
            build(b, reference = "ORD-77")
        }

    @Test fun onceFireStaysSilentOnAFullyEmptyDocument() =
        agree("/Order/Archive/Snapshots", "../Stamp", "NumberOfFilledFields(/Order/Archive/Snapshots*/Stamp) > -1",
            expect = emptyList(),
            msg = "no valued field anywhere — the content gate suppresses the once-fire") { b ->
            build(b)
        }

    // ── arm (mixed): a non-repeatable plain ref + a star with a repeatable binding level ─────────────────────

    @Test fun nonRepeatableRefPlusDeepStarIteratesTheStarBindingLevel() =
        agree(ITEMS, "../Lines/Qty", "FieldFilled(/Order/Reference) And Sum(Lines*/Qty) > -1",
            expect = listOf("/Order[1]/Items[1]/Lines[1]/Qty|OMISSION_ERROR", "/Order[1]/Items[2]/Lines[1]/Qty|OMISSION_ERROR"),
            msg = "the plain root ref adds no anchor; the star's binding level (Items) drives the iteration") { b ->
            build(b, qtysPerItem = listOf(listOf("1", "2"), listOf("3", "4")), reference = "ORD-77")
        }

    // ── arm (f): exploratory — star-FREE, only a non-repeatable ref, rule at a repeatable group ─────────────

    @Test fun nonRepeatableRefOnlyAtARepeatableGroup() =
        agree(ITEMS, "../../Reference", "FieldFilled(/Order/Reference)",
            expect = listOf("/Order[1]/Reference|VALUE_ERROR"),
            msg = "a star-free rule at repeatable Items whose only ref is the root field fires ONCE (no per-row duplicates)") { b ->
            build(b, counts = listOf("2", "5", "1"), reference = "ORD-77")
        }
}
