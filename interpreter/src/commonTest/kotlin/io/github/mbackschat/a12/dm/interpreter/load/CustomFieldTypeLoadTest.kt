package io.github.mbackschat.a12.dm.interpreter.load

import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldTypeDeclaration
import kotlin.test.Test
import kotlin.test.assertEquals

/** A CustomFieldType's name and optional validator bounds survive the DM-JSON → interpreter-model boundary. */
class CustomFieldTypeLoadTest {

    @Test
    fun preservesNameAndBoundsWithoutTurningThemIntoStringConstraints() {
        val model = ModelLoader.load(
            """
            {
              "content": { "modelRoot": { "rootGroups": [ {
                "type": "Group", "id": "/Order", "name": "Order", "Group": { "elements": [ {
                  "type": "Field", "id": "/Order/Code", "name": "Code", "Field": { "fieldType": {
                    "type": "CustomFieldType",
                    "CustomFieldType": { "name": "ProjectCode", "minLength": 3, "maxLength": 5 }
                  } }
                } ] }
              } ] } }
            }
            """.trimIndent(),
        )

        val field = model.fields.single()
        assertEquals(CustomFieldTypeDeclaration("ProjectCode", 3, 5), field.customType)
        assertEquals(CustomFieldTypeDeclaration("ProjectCode", 3, 5), model.customTypeOf(field.path))
        assertEquals(false, model.constraintsOf(field.path).any, "custom bounds belong to the validator context")
    }

    @Test
    fun absentBoundsStayAbsent() {
        val model = ModelLoader.load(
            """
            { "content": { "modelRoot": { "rootGroups": [ {
              "type": "Group", "id": "/G", "name": "G", "Group": { "elements": [ {
                "type": "Field", "id": "/G/Code", "name": "Code", "Field": { "fieldType": {
                  "type": "CustomFieldType", "CustomFieldType": { "name": "ProjectCode" }
                } }
              } ] }
            } ] } } }
            """.trimIndent(),
        )

        assertEquals(CustomFieldTypeDeclaration("ProjectCode"), model.fields.single().customType)
    }
}
