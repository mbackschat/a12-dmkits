package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — a **duplicate index value makes the field "unknown"**, so a rule REFERENCING it is suppressed
 * (KERNEL-FINDINGS §4 supplement; surfaced by the fuzz net). It is **branch-scoped**, exactly like a formal
 * error: a rule on the duplicate index field does not fire, but a rule on a *sibling* field still fires on the
 * same duplicate rows. Compared on the count of the author rule's firings across the two rows.
 */
class UniqSuppressionDiffTest {

    private val fuzz = RuntimeLaws.model("models/fuzz.dm.json")

    // The referencing rule's condition is authored with the rulekit typed DSL ([m]) and rendered to the canonical
    // string fed to BOTH engines — no free-text condition literals. Refs use ABSOLUTE paths; the renderer emits
    // absolute and both engines accept it (IF24). The duplicate-index doc setup is unchanged.
    private val m = BoundModel.ofJson(fuzz)
    private fun render(c: Condition): String = RuntimeLaws.render(c)

    private val fields = listOf(FieldDef(ITEM, Kind.STRING), FieldDef(PRICE, Kind.NUMBER, scale = 2))

    /** Two Lines with index values [k1]/[k2] (Price 5 each); the author rule's firing count must match. */
    private fun agree(cond: String, errorRel: String, k1: String, k2: String, msg: String) {
        val kModel = RuntimeLaws.withRule(fuzz, "/Cart/Lines", "RR", errorRel, "RR", cond)
        val def = ModelDef(fields, listOf(RuleDef("/Cart/Lines/RR", "/Cart/Lines", cond, "RR", errorRel)),
            repeatableGroups = setOf("/Cart/Lines"), declaredReps = mapOf("/Cart/Lines" to 1000), indexFields = setOf(ITEM))
        val kDoc = RuntimeLaws.doc().group("/Cart", 1)
            .group("/Cart/Lines", 1, 1).field(ITEM, k1, 1, 1, 1).field(PRICE, "5", 1, 1, 1)
            .group("/Cart/Lines", 1, 2).field(ITEM, k2, 1, 2, 1).field(PRICE, "5", 1, 2, 1)
        val iDoc = def.newDocument()
            .field(ITEM, k1, 1, 1, 1).field(PRICE, "5", 1, 1, 1).field(ITEM, k2, 1, 2, 1).field(PRICE, "5", 1, 2, 1)
        val kernel = RuntimeLaws.messages(kModel, kDoc).count { it.code == "RR" }
        val interp = DmInterpreter(def).validateFull(iDoc).count { it.code == "RR" }
        assertEquals(kernel, interp, "$msg  (kernel=$kernel interp=$interp)")
    }

    @Test fun ruleOnUniqueIndexFires() =
        agree(render(m.field(ITEM).filled().and(Dsl.length(m.str(ITEM)).gt(0))), "../ItemId", "A", "B", "unique index → rule fires both rows")

    @Test fun ruleOnDuplicateIndexIsSuppressed() =
        agree(render(m.field(ITEM).filled().and(Dsl.length(m.str(ITEM)).gt(0))), "../ItemId", "A", "A", "duplicate index → field unknown → rule suppressed")

    @Test fun ruleOnSiblingFieldStillFiresOnDuplicateRows() =
        agree(render(m.num(PRICE).gt(0)), "../Price", "A", "A", "a rule on a sibling field still fires on duplicate-index rows (branch-scoped)")

    private companion object {
        const val ITEM = "/Cart/Lines/ItemId"
        const val PRICE = "/Cart/Lines/Price"
    }
}
