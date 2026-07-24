package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tri-engine controls for the SPEC-2026-07-24-01 separating cases the existing iteration estate did not pin:
 * ordinary rule iteration derives from the CONDITION's iteration-bearing references (KERNEL-FINDINGS §9;
 * `RuleIterationLimitCreator`/`AnalyseService.isIteration` per level) — the error field is separately
 * VALIDATED (referenced + compatible scope) but never adds an iteration level, and an iterating scope
 * evaluates only ACTUAL rows.
 *
 * The three corners locked here (the rest of the estate: per-row ref + matching error field and the
 * error-inside-a-bare-group-ref shape in [CollectivelyFilledGroupScopeDiffTest], star-never-surrounding in
 * [StarOnlyIterationScopeDiffTest], the once-evaluation re-anchor in [CrossRootRuleScopeDiffTest], static
 * scope-compatibility rejections in `validate.laws.IterationLawsTest`):
 *
 *  1. **Compatible NESTED references select the deepest path** — a condition reading both an outer
 *     (`Lines/Weight`) and an inner (`Lines/Items/Qty`) repeatable field iterates the INNER rows, once per
 *     actual inner row across all outer rows, with the outer operand resolving against the CURRENT row's own
 *     parent coordinates (collapsing/aliasing to another parent's row would flip the fire set below).
 *  2. **Zero actual rows produce zero evaluations** — an outer row with no inner rows contributes nothing
 *     (no synthesized row), while
 *  3. **an instantiated-but-empty row is an actual row** — it is evaluated exactly once and can fire.
 *
 * E-commerce domain (own fixtures): an order's Lines (with a per-line weight limit) each carry Items rows
 * whose Qty is checked per item against its own line's limit.
 */
class ReferenceDrivenIterationScopeDiffTest {

    private companion object {
        const val ORDER = "/Order"
        const val LINES = "/Order/Lines"
        const val WEIGHT = "/Order/Lines/Weight"
        const val ITEMS = "/Order/Lines/Items"
        const val QTY = "/Order/Lines/Items/Qty"
        const val NOTE = "/Order/Lines/Items/Note"
        const val OVER = "OVER"
        const val EMPTYQ = "EMPTYQ"
    }

    /** Nested repeatable Lines → Items; two rules on the Items scope, authored through the typed surface:
     *  `OVER` reads BOTH levels (`[Qty] > [../Weight]` — the deepest-path case), `EMPTYQ` reads only the
     *  inner level (`FieldFilled(Note) And FieldNotFilled(Qty)` — the actual-rows discriminator: fires per
     *  existing row, never on an absent one; the positive guard is MANDATORY — the unguarded pure negative is
     *  statically rejected as `MVK_NEG_CONDITION_IN_ITERATION` "the rule can fire for not filled repetitions",
     *  flagging the OUTER `/Order/Lines` level (per-level iteration markers) — `RuleGroupIterationLawsTest`
     *  owns that law). */
    private val model: String = run {
        val base = DocumentModelDraft.named("order").locale("en_US")
            .rootGroup("Order") { o ->
                o.group("Lines") { lines ->
                    lines.repeatable(5).numberField("Weight") { it.fractionDigits(0, 0) }
                    lines.group("Items") { items ->
                        items.repeatable(5).numberField("Qty") { it.fractionDigits(0, 0) }
                            .stringField("Note") { }
                    }
                }
            }
            .build().toJson()
        EditSession.read(base).also { edit ->
            edit.addRule { bound ->
                bound.rule(bound.group(ITEMS)).name("QtyOverWeight").code(OVER).errorField(bound.num(QTY))
                    .`when`(bound.num(QTY).gt(bound.num(WEIGHT))).severity(Severity.ERROR)
                    .message(io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en("qty exceeds the line weight limit"))
                    .build()
            }
            edit.addRule { bound ->
                bound.rule(bound.group(ITEMS)).name("QtyMissing").code(EMPTYQ).errorField(bound.num(QTY))
                    .`when`(bound.str(NOTE).filled().and(bound.num(QTY).notFilled())).severity(Severity.ERROR)
                    .message(io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en("item row without a quantity"))
                    .build()
            }
            edit.validate()
        }.toJson()
    }

    private val definition = ModelLoader.load(model)

    /** One Items row: its Qty and Note cells (null = leave that cell empty — the row itself is created). */
    private data class Item(val qty: Int?, val note: String? = null)

    /** One line: its weight (null = leave empty) and its item ROWS; an empty list = NO Items rows. */
    private data class Line(val weight: Int?, val items: List<Item>)

    private fun build(lines: List<Line>, k: RuntimeLaws.Doc, i: Document) {
        k.group(ORDER, 1); i.group(ORDER, 1)
        lines.forEachIndexed { li0, line ->
            val li = li0 + 1
            k.group(LINES, 1, li); i.group(LINES, 1, li)
            line.weight?.let { k.field(WEIGHT, "$it", 1, li, 1); i.field(WEIGHT, "$it", 1, li, 1) }
            line.items.forEachIndexed { ii0, item ->
                val ii = ii0 + 1
                k.group(ITEMS, 1, li, ii); i.group(ITEMS, 1, li, ii)
                item.qty?.let { k.field(QTY, "$it", 1, li, ii, 1); i.field(QTY, "$it", 1, li, ii, 1) }
                item.note?.let { k.field(NOTE, it, 1, li, ii, 1); i.field(NOTE, it, 1, li, ii, 1) }
            }
        }
    }

    /** Assert the [code] fire set (full nested `path[rep]` coordinates + message type) agrees across
     *  groovy-dynamic (normative), java-static, and the interpreter, and equals [expect]. */
    private fun agree(code: String, lines: List<Line>, expect: List<String>, msg: String) {
        val kDoc = RuntimeLaws.doc(); val iDoc = definition.newDocument()
        build(lines, kDoc, iDoc)
        val groovy = RuntimeLaws.messages(model, kDoc).filter { it.code() == code }
            .map { "${it.field()}|${it.type().name}" }.sorted()
        val engine = RuntimeLaws.kernelJavaEngine(model)
        val kDoc2 = RuntimeLaws.doc(); val iDoc2 = definition.newDocument()
        build(lines, kDoc2, iDoc2)
        val javaStatic = engine.messagesModern(kDoc2, null, java.util.Locale.US).filter { it.code() == code }
            .map { "${it.field()}|${it.type().name}" }.sorted()
        val interp = DmInterpreter(definition).validateFull(iDoc).filter { it.code == code }
            .map { "${it.errorPath}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" }.sorted()
        assertEquals(expect.sorted(), groovy, "$msg — the kernel groovy-dynamic fire set")
        assertEquals(groovy, javaStatic, "$msg — groovy vs java-static (tri-check)")
        assertEquals(groovy, interp, "$msg — kernel vs interpreter")
    }

    private fun at(li: Int, ii: Int, type: String = "VALUE_ERROR") =
        "/Order[1]/Lines[$li]/Items[$ii]/Qty|$type"

    /** The deepest-path + per-parent-resolution discriminator: Lines[2]/Items[2] (30) exceeds Lines[1]'s
     *  weight (10) but NOT its own line's (50) — an aliased/collapsed parent coordinate would fire it. */
    @Test fun nestedReferencesIterateTheDeepestPathResolvingTheOuterOperandPerParent() =
        agree(OVER,
            listOf(Line(10, listOf(Item(5), Item(20))), Line(50, listOf(Item(60), Item(30)))),
            listOf(at(1, 2), at(2, 1)),
            "one evaluation per ACTUAL inner row; [../Weight] reads the current row's own parent")

    /** Zero inner rows under an outer row contribute zero evaluations — no synthesized row. */
    @Test fun anOuterRowWithZeroInnerRowsContributesNoEvaluations() =
        agree(OVER,
            listOf(Line(1, emptyList()), Line(1, listOf(Item(5)))),
            listOf(at(2, 1)),
            "Lines[1] has NO Items rows — nothing fires there; Lines[2]/Items[1] (5 > 1) does")

    /** An EXISTING row with content IS evaluated: exactly one evaluation, which fires on the empty Qty. */
    @Test fun anExistingRowWithAnEmptyTargetCellEvaluatesExactlyOnce() =
        agree(EMPTYQ,
            listOf(Line(7, listOf(Item(null, "fragile")))),
            listOf(at(1, 1, "OMISSION_ERROR")),
            "Items[1] exists (Note filled) with Qty empty — one row, one evaluation, one fire")

    /** The zero-rows twin of the case above: no Items rows → zero evaluations → silent. */
    @Test fun zeroRowsEvaluateZeroTimes() =
        agree(EMPTYQ,
            listOf(Line(7, emptyList())),
            emptyList(),
            "no Items rows exist — the kernel runs no rule instance, nothing is synthesized")
}
