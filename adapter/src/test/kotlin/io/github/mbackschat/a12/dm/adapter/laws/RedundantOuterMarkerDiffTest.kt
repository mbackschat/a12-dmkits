package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Tri-engine differential — on an unstarred ANCESTOR of the operand's own path, the `$` marker is REDUNDANT:
 * the bare reference already reads the coordinate the candidate environment inherits from the captured prefix.
 * a12-kernel-lean's `SPEC-2026-09-03-07` corrects a clause that called `$` the sole way a filter reaches an
 * out-of-scope field; the corrected rule splits by LEVEL rather than by scope, and this file measures the
 * non-reopened half.
 *
 * **The carrier is the ancestor, not every bound level** — [KF237]'s 2026-09-05 correction. On a repeatable
 * SIBLING the enclosing iteration also binds the level, and there the bare spelling is a static refusal
 * (`MVK_INVALID_ITERATION_IN_SET_EXPRESSION`, [KF244]), so the marker is load-bearing rather than redundant.
 * This file's topology is an ancestor throughout; nothing here transfers to a sibling.
 *
 * **The topology is what makes the claim non-trivial.** The rule iterates `Lines` and the operand stars only
 * `Items`, so `Lines` is bound-but-not-reopened. On a level the star DOES reopen the two spellings differ — the
 * unmarked one reads the candidate row and the marked one the captured coordinate, which is the whole of
 * correlation — so a consumer may add or drop the marker here and must never do so there.
 *
 * **Why a ladder rather than one comparison.** Both spellings answering the same on one document would also
 * happen if the filter never varied. `Cap` is `5, 0, 5`, so the kept count is `2` on two rows and `0` on the
 * third, and the `== 2` and `== 0` rules fire on complementary, non-empty row sets. The agreement is between two
 * VARYING answers, which is what makes it informative.
 *
 * **Asserted on the message POINTERS, not on whether a code fired at all**, because the claim is that the two
 * spellings select the same ROWS. A code-set assertion would pass even if the marked spelling fired on the wrong
 * rows.
 *
 * Own domain (retail catalogue).
 */
// One instance per CLASS: the model JSON, the loaded definition and the kernel Java engine are fields, and this
// Gradle task also carries timing assertions — per-method lifecycle would redo codegen for every case.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedundantOuterMarkerDiffTest {

    private companion object {
        const val CATALOG = "/Catalog"
        const val LINES = "/Catalog/Lines"
        const val LABEL = "/Catalog/Lines/Label"
        const val CAP = "/Catalog/Lines/Cap"
        const val ITEMS = "/Catalog/Lines/Items"
        const val QTY = "/Catalog/Lines/Items/Qty"
    }

    private val base = DocumentModelDraft.named("retail-catalogue-marker").locale("en_US")
        .rootGroup("Catalog") { catalog ->
            catalog.group("Lines") { lines ->
                lines.repeatable(50)
                lines.stringField("Label") { }
                lines.numberField("Cap") { it.fractionDigits(0) }
                lines.group("Items") { items ->
                    items.repeatable(50)
                    items.numberField("Qty") { it.fractionDigits(0) }
                }
            }
        }.build().toJson()

    private val bound = BoundModel.ofJson(base)
    private val qtyList = bound.flatten("$ITEMS*/Qty")

    /** The in-scope conjunct, without which the whole filter is refused for missing its binding reference. */
    private fun inScope(): Condition = bound.num(QTY).gt(0)

    /** `FieldFilled(Label) And NumberOfFilledFields(Items* / Qty Having <in-scope> And <capRef> > 0) == kept`. */
    private fun rule(code: String, capRef: Condition, kept: Long): Rule =
        bound.rule(bound.group(LINES)).name("${code}Rule").errorField(bound.str(LABEL))
            .`when`(
                Dsl.fieldFilled(bound.field(LABEL))
                    .and(Dsl.numberOfFilledFields(qtyList.having(inScope().and(capRef))).eq(kept)),
            )
            .code(code).severity(Severity.ERROR).message(Dsl.en(code)).build()

    private fun bareOuter(): Condition = bound.num(CAP).gt(0)
    private fun markedOuter(): Condition = bound.num(CAP).outer().gt(0)

    private val rules = listOf(
        rule("BARE2", bareOuter(), 2),
        rule("BARE0", bareOuter(), 0),
        rule("MARKED2", markedOuter(), 2),
        rule("MARKED0", markedOuter(), 0),
    )

    private val modelJson = rules.fold(base) { json, r -> TypedRules.inject(json, r) }
    private val definition = ModelLoader.load(modelJson)
    private val staticEngine by lazy { RuntimeLaws.kernelJavaEngine(modelJson) }

    @Test
    fun bothSpellingsAreAdmitted() =
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(modelJson),
            "the STATIC half: the bare outer reference is admitted beside an in-scope conjunct, exactly as the " +
                "marked one is")

    /**
     * The two spellings fire on identical, varying row sets. `Cap = 5, 0, 5` makes `== 2` select the first and
     * third line and `== 0` the second, per spelling — so the marker changes nothing on a level the enclosing
     * iteration already bound.
     */
    @Test
    fun theMarkerIsRedundantOnALevelTheStarDoesNotReopen() {
        val fired = firedPointers()
        assertEquals(
            mapOf(
                "BARE2" to setOf("/Catalog[1]/Lines[1]/Label", "/Catalog[1]/Lines[3]/Label"),
                "MARKED2" to setOf("/Catalog[1]/Lines[1]/Label", "/Catalog[1]/Lines[3]/Label"),
                "BARE0" to setOf("/Catalog[1]/Lines[2]/Label"),
                "MARKED0" to setOf("/Catalog[1]/Lines[2]/Label"),
            ),
            fired,
            "each spelling selects the same rows as its twin, and the two ladders select complementary rows",
        )
    }

    // ---- the instrument ----

    /** Probe code → the error-field pointers it fired on, asserted equal across all three engines. */
    private fun firedPointers(): Map<String, Set<String>> {
        val codes = rules.map { it.code() }.toSet()
        val dynamic = RuntimeLaws.messages(modelJson, kernelDocument())
            .filter { it.code() in codes }.groupBy({ it.code() }, { it.field() }).mapValues { it.value.toSet() }
        val static = staticEngine.messages(staticEngine.instance(kernelDocument()))
            .filter { it.code() in codes }.groupBy({ it.code() }, { it.field() }).mapValues { it.value.toSet() }
        val interpreter = DmInterpreter(definition).validateFull(interpreterDocument())
            .filter { it.code in codes }.groupBy({ it.code }, { it.errorPath }).mapValues { it.value.toSet() }
        assertEquals(dynamic, static, "generated Java vs normative dynamic-Groovy")
        assertEquals(dynamic, interpreter, "interpreter vs normative dynamic-Groovy")
        return dynamic
    }

    /** Three lines with `Cap` 5, 0, 5; each carries two `Items` rows whose `Qty` is above zero. */
    private fun kernelDocument(): RuntimeLaws.Doc {
        val doc = RuntimeLaws.doc().group(CATALOG, 1)
        listOf("5", "0", "5").forEachIndexed { i, cap ->
            val r = i + 1
            doc.group(LINES, 1, r).field(LABEL, "line-$r", 1, r, 1).field(CAP, cap, 1, r, 1)
            doc.group(ITEMS, 1, r, 1).field(QTY, "1", 1, r, 1, 1)
            doc.group(ITEMS, 1, r, 2).field(QTY, "2", 1, r, 2, 1)
        }
        return doc
    }

    private fun interpreterDocument(): Document {
        val doc = definition.newDocument().group(CATALOG, 1)
        listOf("5", "0", "5").forEachIndexed { i, cap ->
            val r = i + 1
            doc.group(LINES, 1, r).field(LABEL, "line-$r", 1, r, 1).field(CAP, cap, 1, r, 1)
            doc.group(ITEMS, 1, r, 1).field(QTY, "1", 1, r, 1, 1)
            doc.group(ITEMS, 1, r, 2).field(QTY, "2", 1, r, 2, 1)
        }
        return doc
    }
}
