package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Differential — the `GroupFilled` OPERATOR for a NON-repeatable group counts an INSTANTIATED repeatable child
 * row as content, exactly like the rule-eval gate (IF53's sibling: both once scanned valued fields only). Over
 * `/Order { Note, Box { Items[3] { Sku } } }` (Box non-repeatable, Items repeatable), the rule
 * `FieldFilled(Note) And GroupNotFilled(Box)` fires iff `Box` is NOT filled:
 *
 *  - **0 Item rows** → Box truly empty → NOT filled → `GroupNotFilled` FIRES;
 *  - **3 instantiated-empty Item rows** → the instantiated rows are content → Box IS filled → does NOT fire;
 *  - **3 rows, Sku filled** → filled → does NOT fire.
 *
 * Compared kernel-vs-interpreter (no hardcoded expectation). Complements [RepeatableRowContentDiffTest] (the
 * rule-eval-gate half of IF53); the second row was the sibling the root-cause sweep found.
 */
class NonRepGroupFilledContentDiffTest {

    private val json = DocumentModelDraft.named("order").locale("en_US")
        .rootGroup("Order", Consumer { g: GroupBuilder ->
            g.numberField("Note", Consumer { })
                .group("Box", Consumer<GroupBuilder> { b ->
                    b.group("Items", Consumer<GroupBuilder> { it.repeatable(3).stringField("Sku", Consumer { }) })
                })
        })
        .build().toJson()

    private val edit = EditSession.read(json)
    private val order = edit.group(ORDER)
    private val note = order.num("Note")
    private val box = edit.group(BOX)

    private val rule = order.rule {
        it.name("R_GNF").code("GNF").errorField(note.field())
            .`when`(Dsl.fieldFilled(note.field()).and(Dsl.groupNotFilled(box.ref())))
            .severity(Severity.ERROR).message(Dsl.en("x")).build()
    }
    private val kModel = TypedRules.inject(json, rule)
    private val def = ModelDef(listOf(FieldDef(NOTE, Kind.NUMBER), FieldDef(SKU, Kind.STRING)),
        listOf(TypedRules.def(rule)), repeatableGroups = setOf(ITEMS), declaredReps = mapOf(ITEMS to 3))

    private fun agree(label: String, itemRows: Int, skuFilled: Boolean) {
        val kDoc = RuntimeLaws.doc().group(ORDER, 1).field(NOTE, "1", 1, 1).group(BOX, 1, 1).let { d ->
            for (r in 1..itemRows) {
                d.group(ITEMS, 1, 1, r)
                if (skuFilled) d.field(SKU, "S$r", 1, 1, r, 1) else d.emptyField(SKU, 1, 1, r, 1)
            }
            d
        }
        val iDoc = Document(mapOf(NOTE to Kind.NUMBER, SKU to Kind.STRING), mapOf(ITEMS to 3))
            .group(ORDER, 1).field(NOTE, "1", 1, 1).group(BOX, 1, 1).let { d ->
                for (r in 1..itemRows) {
                    d.group(ITEMS, 1, 1, r)
                    if (skuFilled) d.field(SKU, "S$r", 1, 1, r, 1) else d.emptyField(SKU, 1, 1, r, 1)
                }
                d
            }
        val k = RuntimeLaws.messages(kModel, kDoc).any { it.code() == "GNF" }
        val i = DmInterpreter(def).validate(iDoc).messages.any { it.code == "GNF" }
        assertEquals(k, i, "$label — kernel GroupNotFilled(Box) fires=$k interp=$i")
    }

    @Test fun nonRepGroupFilledCountsInstantiatedRepeatableChildRows() {
        agree("0 Item rows (Box truly empty) → fires", 0, false)
        agree("3 instantiated-empty Item rows (Box filled) → does not fire", 3, false)
        agree("3 rows, Sku filled (Box filled) → does not fire", 3, true)
    }

    private companion object {
        const val ORDER = "/Order"; const val NOTE = "/Order/Note"
        const val BOX = "/Order/Box"; const val ITEMS = "/Order/Box/Items"; const val SKU = "/Order/Box/Items/Sku"
    }
}
