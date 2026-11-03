package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.readDocumentJson
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * JVM + Node peer of `adapter.laws.MessageParameterRowDiffTest` (KERNEL-FINDINGS KF561): every spelling of a
 * message field reads the same cell, whitespace inside the parameter included, and a key-selected token reads the
 * row its key selects. A value read through a
 * key that selects no row renders empty on every iterated row (KERNEL-FINDINGS KF668).
 */
class MessageParameterRowRenderTest {

    private fun rule(id: String, group: String, error: String, condition: String, text: String) = """
        { "type": "Rule", "id": "$group/$id", "name": "$id", "Rule": {
            "errorEntityRelPath": "$error", "errorCode": "$id", "severity": "ERROR",
            "errorCondition": "$condition",
            "errorMessage": [ { "locale": "en_US", "text": "$text" } ] } }
    """

    private fun field(path: String) = """
        { "type": "Field", "id": "$path", "name": "${path.substringAfterLast('/')}",
          "Field": { "fieldType": { "type": "StringType" } } }
    """

    private val model = ModelLoader.load("""
        {
          "header": { "id": "policy-message-rows", "locales": [ { "code": "en_US" } ] },
          "content": {
            "modelInfo": { "name": "policy-message-rows" },
            "modelRoot": { "rootGroups": [ {
              "type": "Group", "id": "/Policy", "name": "Policy", "Group": { "elements": [
                ${field("/Policy/Holder")},
                { "type": "Group", "id": "/Policy/Claims", "name": "Claims", "Group": { "repeatability": 5,
                  "indexFieldName": "Ref", "elements": [
                    ${field("/Policy/Claims/Ref")}, ${field("/Policy/Claims/Note")},
                    { "type": "Group", "id": "/Policy/Claims/Items", "name": "Items", "Group": { "repeatability": 3,
                      "elements": [ ${field("/Policy/Claims/Items/Item")},
                        ${rule("ENCLOSING", "/Policy/Claims/Items", "../Item", "FieldFilled(Item) And FieldFilled(../Note)",
                            "rel=[${'$'}../Note.value${'$'}] abs=[${'$'}/Policy/Claims/Note.value${'$'}]")} ] } },
                    ${rule("SHORT", "/Policy/Claims", "../Note", "FieldFilled(Note) And FieldFilled(/Policy/Holder)",
                        "h=[${'$'}Holder.value${'$'}] hl=[${'$'}Holder${'$'}]")},
                    ${rule("SPACED", "/Policy/Claims", "../Note", "FieldFilled(Note) And FieldFilled(/Policy/Holder)",
                        "s=[${'$'} Note.value${'$'}] h=[${'$'}Holder . value ${'$'}]")},
                    ${rule("MISS_ROW", "/Policy/Claims", "../Note",
                        "FieldFilled(Note) And FieldNotFilled(/Policy/Claims/Note For \\\"c9\\\")",
                        "mr=[${'$'}/Policy/Claims/Note.value For \\\"c9\\\"${'$'}]")} ] } },
                ${rule("KEYED", "/Policy", "../Holder", "FieldFilled(Holder) And FieldFilled(/Policy/Claims/Note For \\\"c1\\\")",
                    "kn=[${'$'}/Policy/Claims/Note.value For \\\"c1\\\"${'$'}] kl=[${'$'}/Policy/Claims/Note For \\\"c1\\\"${'$'}]")},
                ${rule("MISS_LABEL", "/Policy", "../Holder", "FieldFilled(Holder) And FieldNotFilled(/Policy/Claims/Note For \\\"c9\\\")",
                    "ml=[${'$'}/Policy/Claims/Note For \\\"c9\\\"${'$'}]")}
              ] } } ] } }
        }
    """.trimIndent())

    private val document = """{"Policy":{"Holder":"Ada","Claims":[{"Ref":"c0","Note":"n0"},
        {"Ref":"c1","Note":"n1","Items":[{"Item":"i1"}]}]}}"""

    private fun texts(): List<String> = DmInterpreter(model)
        .validateFull(model.readDocumentJson(document), "en_US")
        .map { "${it.code} ${it.text}" }.sorted()

    @Test
    fun everySpellingAndKeyReadsItsRow() {
        assertEquals(listOf(
            "ENCLOSING rel=[n1] abs=[n1]",
            "KEYED kn=[n1] kl=[Note]",
            "MISS_LABEL ml=[Note]",
            "MISS_ROW mr=[]",
            "MISS_ROW mr=[]",
            "SHORT h=[Ada] hl=[Holder]",
            "SHORT h=[Ada] hl=[Holder]",
            "SPACED s=[n0] h=[Ada]",
            "SPACED s=[n1] h=[Ada]",
        ), texts())
    }
}
