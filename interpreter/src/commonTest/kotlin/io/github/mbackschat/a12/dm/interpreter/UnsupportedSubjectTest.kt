package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldTypeDeclaration
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.interpreter.model.UnsupportedSubject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `unsupported` report carries **two kinds of address**, and [UnsupportedSubject] is what tells them apart.
 *
 * <p>An unregistered **custom condition** cannot be evaluated for the rule as declared, so the entry names a model
 * ELEMENT — a path with no repetition, which a consumer resolves against the model. An unregistered **custom field
 * type** is decided per stored value, so the entry names a document CELL — an instance pointer with repetitions,
 * which only means anything against the document just evaluated. Rendering one as the other is wrong in both
 * directions: a model path has no row to show, and a cell pointer is not in the model.
 *
 * <p>This exists because the field shipped without it. `subject` was added to the engine and projected by dmtool,
 * but nothing asserted that a CELL entry actually says CELL — so the distinguishing value was, until now, carried
 * by no test at all. The two cases below are the two producers, and they are deliberately in the same class: the
 * defect that matters is not one of them being wrong, it is the two becoming indistinguishable again.
 */
class UnsupportedSubjectTest {

    private val CODE = "/Order/Code"

    private val customTypedField = ModelDef(
        listOf(FieldDef(CODE, Kind.STRING, customType = CustomFieldTypeDeclaration("Code"))),
        listOf(),
    )

    private val customConditionRule = ModelDef(
        listOf(FieldDef(CODE, Kind.STRING)),
        listOf(RuleDef("/Order/R", "/Order", "CustomCondition Eligibility", "R", "../Code")),
    )

    private fun docWith(def: ModelDef, value: String): Document =
        def.newDocument().group("/Order", 1).also { it.field(CODE, value, 1, 1) }

    @Test
    fun anUnregisteredCustomFieldTypeReportsTheCELLItCouldNotCheck() {
        val outcome = DmInterpreter(customTypedField).validate(docWith(customTypedField, "abcd"))

        val entry = outcome.unsupported.single()
        assertEquals(UnsupportedSubject.CELL, entry.subject,
            "a custom field type is decided per stored value, so the entry addresses a document cell")
        assertTrue(entry.rulePath.contains("["),
            "and a cell pointer carries its repetitions — got '${entry.rulePath}'")
    }

    @Test
    fun anUnregisteredCustomConditionReportsTheELEMENTItCouldNotEvaluate() {
        val outcome = DmInterpreter(customConditionRule).validate(docWith(customConditionRule, "abcd"))

        val entry = outcome.unsupported.single()
        assertEquals(UnsupportedSubject.ELEMENT, entry.subject,
            "the whole rule is unevaluable, so the entry addresses the rule's model path")
        assertEquals("/Order/R", entry.rulePath,
            "a model element path carries no repetition")
    }
}
