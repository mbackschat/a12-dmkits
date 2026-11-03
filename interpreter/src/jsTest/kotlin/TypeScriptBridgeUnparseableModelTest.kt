import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A model whose own rule text does not parse is refused when an interpreter is bound, as a preparation failure that
 * the TypeScript façade raises as `ModelPreparationError`: no Kotlin exception may cross this boundary, and the
 * refusal is the model's, not a failed host factory (`interpreter.UnparseableModelTextRefusalTest`).
 *
 * This file carries no `package` declaration for the reason `TypeScriptBridgePointerFormatTest` gives.
 */
class TypeScriptBridgeUnparseableModelTest {

    @Test
    fun bindingCarriesAnUnparseableRuleAsAPreparationFailure() {
        val prepared = requireNotNull(interpreterBridgeLoadExpandedJson(GERMAN_MODEL_WITH_AN_ENGLISH_RULE).value)
        val result = prepared.createInterpreter(emptyArray(), emptyArray(), null)

        assertNull(result.value)
        val failure = requireNotNull(result.failure)
        assertEquals("A12_MODEL_PREPARATION_FAILED", failure.code)
        assertTrue("/Policy/RRef" in failure.detailsJson, failure.detailsJson)
    }

    private companion object {
        val GERMAN_MODEL_WITH_AN_ENGLISH_RULE = """
            {"header":{"id":"policy","modelVersion":"29.4.0","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{"name":"policy"},"modelConfig":{"conditionLanguage":{"code":"de_DE"}},
              "modelRoot":{"rootGroups":[{"type":"Group","id":"/Policy","name":"Policy","Group":{"elements":[
               {"type":"Field","id":"/Policy/Ref","name":"Ref","Field":{"fieldType":{"type":"StringType"}}},
               {"type":"Rule","id":"/Policy/RRef","name":"RRef","Rule":{"errorCode":"REF","errorEntityRelPath":"../Ref",
                "errorCondition":"FieldNotFilled(Ref)","errorMessage":[{"locale":"en_US","text":"x"}]}}]}}]}}}
        """.trimIndent()
    }
}
