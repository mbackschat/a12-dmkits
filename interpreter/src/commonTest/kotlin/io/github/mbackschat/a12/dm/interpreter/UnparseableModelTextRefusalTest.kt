package io.github.mbackschat.a12.dm.interpreter

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A rule condition or a computation operation that does not parse in the model's condition language is a model A12
 * cannot evaluate: its check refuses it with `MVK_UNEXPECTED_TOKEN` and neither strategy generates code for it
 * (KERNEL-FINDINGS KF283; `adapter.laws.UnsupportedConditionLanguageStageDiffTest`, its `de_DE` row). So binding an
 * interpreter refuses it as a preparation error naming the element, not as a failed host factory. The English model
 * is the control that the same texts parse.
 */
class UnparseableModelTextRefusalTest {

    @Test
    fun bindingRefusesTheElementWhoseTextDoesNotParse() {
        val observed = linkedMapOf(
            "en_US" to model("en_US"),
            "de_DE, a rule in English" to model("de_DE"),
            "de_DE, a computation in English" to model("de_DE", condition = "FeldNichtAngegeben(Ref)"),
        ).mapValues { (_, json) ->
            try {
                PreparedModel.loadExpandedJson(json).createInterpreter()
                "bound"
            } catch (refused: ModelPreparationError) {
                refused.diagnostics.joinToString { "${it.code} ${it.path}" }
            }
        }
        assertEquals(
            mapOf(
                "en_US" to "bound",
                "de_DE, a rule in English" to "INVALID_MODEL_SOURCE /Policy/RRef",
                "de_DE, a computation in English" to "INVALID_MODEL_SOURCE /Policy/CTotal",
            ),
            observed,
        )
    }

    private fun model(language: String, condition: String = "FieldNotFilled(Ref)") = """
        {"header":{"id":"policy","modelVersion":"28.4.0","locales":[{"code":"en_US"}]},
         "content":{"modelInfo":{"name":"policy"},"modelConfig":{"conditionLanguage":{"code":"$language"}},
          "modelRoot":{"rootGroups":[{"type":"Group","id":"/Policy","name":"Policy","Group":{"elements":[
           {"type":"Field","id":"/Policy/Ref","name":"Ref","Field":{"fieldType":{"type":"StringType"}}},
           {"type":"Field","id":"/Policy/Total","name":"Total","Field":{"fieldType":{"type":"NumberType","NumberType":{"fractionDigits":0}}}},
           {"type":"Rule","id":"/Policy/RRef","name":"RRef","Rule":{"errorCode":"REF","errorEntityRelPath":"../Ref",
            "errorCondition":"$condition","errorMessage":[{"locale":"en_US","text":"x"}]}},
           {"type":"Computation","id":"/Policy/CTotal","name":"CTotal","Computation":{"computedFieldRelPath":"../Total",
            "computationAlternatives":[{"precondition":"FieldFilled(Ref)","operation":"1"}]}}]}}]}}}
    """.trimIndent()
}
