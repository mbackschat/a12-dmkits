package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — `FieldValueIncludedInValueList` / `FieldValueNotIncludedInValueList` (single field + a
 * string-literal value list). The interpreter must fire (or not) exactly when the kernel does, across a
 * string field, a number field (numeric membership — `"5"` matches a stored `5`), and an EMPTY field. The
 * scalar pair is a one-subject specialization: an empty subject is NOT evaluated, so BOTH forms are
 * non-firing on it — `NotIncluded` is NOT the empty-firing multi-field `No` (SPEC-2026-07-22-05). The
 * multi-field `In`-syntax quantifiers and the `->`-category form are separate constructs. Drives the
 * interpreter from PARSED condition text.
 */
class ValueListDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(FieldDef(PC, Kind.STRING), FieldDef(Q, Kind.NUMBER, scale = 0), FieldDef(CN, Kind.STRING))

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines — no free-text condition literals. `m.str(p).in(...)`/`.notIn(...)` are the value-list operators.
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = DslRenderer().render(c)

    private fun interpFires(cond: String, errField: String, doc: Document): Boolean =
        DmInterpreter(ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "VL", errField))))
            .validate(doc).messages.any { it.code == "VL" }

    private fun kernelFires(cond: String, errField: String, doc: RuntimeLaws.Doc): Boolean =
        RuntimeLaws.fires(RuntimeLaws.withRule(order, "/Order", "R", errField, "VL", cond), "VL", doc)

    private fun both(cond: String, errField: String, kDoc: RuntimeLaws.Doc, iDoc: Document, msg: String) =
        assertEquals(kernelFires(cond, errField, kDoc), interpFires(cond, errField, iDoc), msg)

    @Test
    fun stringValueInList() {
        val cond = render(m.str(PC).`in`("LEGACY-1", "LEGACY-2"))
        both(cond, "../ProductCode", RuntimeLaws.doc().group("/Order", 1).field(PC, "LEGACY-1", 1, 1),
            Document(kinds).field(PC, "LEGACY-1", 1, 1), "in-list → fires")
        both(cond, "../ProductCode", RuntimeLaws.doc().group("/Order", 1).field(PC, "NEW-9", 1, 1),
            Document(kinds).field(PC, "NEW-9", 1, 1), "out-of-list → no fire")
    }

    @Test
    fun stringValueNotInList() {
        val cond = render(m.str(PC).notIn("LEGACY-1", "LEGACY-2"))
        both(cond, "../ProductCode", RuntimeLaws.doc().group("/Order", 1).field(PC, "NEW-9", 1, 1),
            Document(kinds).field(PC, "NEW-9", 1, 1), "out-of-list → Not fires")
        both(cond, "../ProductCode", RuntimeLaws.doc().group("/Order", 1).field(PC, "LEGACY-1", 1, 1),
            Document(kinds).field(PC, "LEGACY-1", 1, 1), "in-list → Not does not fire")
    }

    @Test
    fun emptyFieldIsNotEvaluatedByEitherForm() {
        // CustomerName filled so the row is evaluated; ProductCode empty → the subject is not evaluated, so
        // NEITHER form fires (NotIncluded is a one-subject specialization, not the empty-firing multi-field No).
        val incl = render(m.str(PC).`in`("LEGACY-1"))
        both(incl, "../ProductCode", RuntimeLaws.doc().group("/Order", 1).field(CN, "Acme", 1, 1).emptyField(PC, 1, 1),
            Document(kinds).field(CN, "Acme", 1, 1).emptyField(PC, 1, 1), "empty → Included does not fire")
        val notIncl = render(m.str(PC).notIn("LEGACY-1"))
        both(notIncl, "../ProductCode", RuntimeLaws.doc().group("/Order", 1).field(CN, "Acme", 1, 1).emptyField(PC, 1, 1),
            Document(kinds).field(CN, "Acme", 1, 1).emptyField(PC, 1, 1), "empty → NotIncluded does NOT fire")
    }

    @Test
    fun numberValueMembershipIsNumeric() {
        // A number field's value list uses NUMBER literals (string literals → MVK_INVALID_TYPES_FOR_COMPARISON).
        val cond = render(m.num(Q).`in`(5, 10))
        both(cond, "../Quantity", RuntimeLaws.doc().group("/Order", 1).field(Q, "5", 1, 1),
            Document(kinds).field(Q, "5", 1, 1), "number in list → fires")
        both(cond, "../Quantity", RuntimeLaws.doc().group("/Order", 1).field(Q, "7", 1, 1),
            Document(kinds).field(Q, "7", 1, 1), "number out of list → no fire")
    }

    private val kinds = mapOf(PC to Kind.STRING, Q to Kind.NUMBER, CN to Kind.STRING)

    private companion object {
        const val PC = "/Order/ProductCode"
        const val Q = "/Order/Quantity"
        const val CN = "/Order/CustomerName"
    }
}
