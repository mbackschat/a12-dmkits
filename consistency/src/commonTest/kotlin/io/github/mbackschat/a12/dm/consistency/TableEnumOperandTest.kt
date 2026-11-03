package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of "`ValueNotConsistent` has no admissible subject in a stored model".
 *
 * Mirrors `rulekit.validate.laws.TableEnumConditionLawsTest` on the same order shape. The rows that carry the
 * risk are the ones where the class is NOT this family's: a group subject and a starred one answer to checks
 * ahead of the table-enum demand, so a family folding them into its own class would name the wrong defect on
 * a correct rejection — and a subject naming nothing declared belongs to the reference family entirely.
 *
 * The kind rows are the other half: a string, a number and an ordinary enumeration all draw the same class,
 * which is what makes this a law about the missing TABLE rather than about the subject's type.
 */
class TableEnumOperandTest {

    private val expectsTableEnum = "MVK_TABLE_ENUM_EXPECTED"

    private val noGroups = "MVK_NO_GROUPS_ALLOWED"

    private val noWildcards = "MVK_NO_WILDCARDS_ALLOWED"

    private val noWildcard = "MVK_NO_WILDCARD"

    private val mine = setOf(expectsTableEnum, noGroups, noWildcards, noWildcard)

    @Test
    fun whichClassEachSubjectShapeDraws() {
        val actual = mutableMapOf<String, List<String>>()
        actual["an ordinary ENUM subject"] = codes("""ValueNotConsistent(Priority To \"HIGH\")""")
        actual["a STRING subject"] = codes("""ValueNotConsistent(Sku To \"HIGH\")""")
        actual["a NUMBER subject"] = codes("""ValueNotConsistent(Quantity To \"HIGH\")""")
        actual["a subject with TWO value-list members"] =
            codes("""ValueNotConsistent(Priority To \"HIGH\", \"LOW\")""")
        actual["a subject with a FIELD as the value-list member"] =
            codes("""ValueNotConsistent(Priority To Priority)""")
        actual["a GROUP subject"] = codes("""ValueNotConsistent(Shipping To \"HIGH\")""")
        actual["an unstarred REPEATABLE group subject"] = codes("""ValueNotConsistent(Items To \"HIGH\")""")
        actual["a STARRED subject"] = codes("""ValueNotConsistent(Items*/Count To \"HIGH\")""")
        actual["a subject naming nothing declared"] = codes("""ValueNotConsistent(Nowhere To \"HIGH\")""")
        actual["another operator on the same subject — the liveness control"] = codes("FieldFilled(Priority)")

        val expected = mutableMapOf<String, List<String>>()
        expected["an ordinary ENUM subject"] = listOf(expectsTableEnum)
        expected["a STRING subject"] = listOf(expectsTableEnum)
        expected["a NUMBER subject"] = listOf(expectsTableEnum)
        // The arity and member-kind laws sit BEHIND the gate, so a wrong member count and a non-enum member
        // are both invisible here — the table-enum demand answers first, measured on the kernel.
        expected["a subject with TWO value-list members"] = listOf(expectsTableEnum)
        expected["a subject with a FIELD as the value-list member"] = listOf(expectsTableEnum)
        expected["a GROUP subject"] = listOf(noGroups)
        // The reference's walk refuses a repeating group read one row at a time once the reference is read,
        // ahead of the operator's chain at `)` — measured in the laws twin.
        expected["an unstarred REPEATABLE group subject"] = listOf(noWildcard)
        expected["a STARRED subject"] = listOf(noWildcards)
        // MVK_INVALID_ENTITY, the reference family's — an address that names nothing never reaches this gate.
        expected["a subject naming nothing declared"] = emptyList()
        expected["another operator on the same subject — the liveness control"] = emptyList()
        assertEquals(expected, actual, "which class each ValueNotConsistent subject draws")
    }

    private fun codes(condition: String): List<String> =
        checkRead(ModelSource(model(condition))).findings.filter { it.code in mine }.map { it.code }

    /**
     * An order with a group that repeats and one that does not, one enum field and two scalar kinds, so every
     * row has a subject.
     */
    private fun model(condition: String): String = """
        {"header":{"modelVersion":"29.4.0","id":"order","locales":[{"code":"en_US"}]},
         "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Quantity","Field":{"fieldType":{"type":"NumberType"}}},
            {"type":"Field","id":"f1","name":"Sku","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f2","name":"Priority","Field":{"fieldType":{
              "type":"EnumerationType","EnumerationType":{"values":[{"value":"HIGH"},{"value":"LOW"}]}}}},
            {"type":"Group","id":"g2","name":"Shipping","Group":{"elements":[
              {"type":"Field","id":"f4","name":"Street","Field":{"fieldType":{"type":"StringType"}}}
            ]}},
            {"type":"Group","id":"g1","name":"Items","Group":{"repeatability":50,"elements":[
              {"type":"Field","id":"f3","name":"Count","Field":{"fieldType":{"type":"NumberType"}}}
            ]}},
            {"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":"FieldFilled(Quantity) And $condition",
              "errorEntityRelPath":"../Quantity",
              "errorCode":"C0","errorMessage":[{"locale":"en_US","text":"x"}]}}
          ]}}
         ]}}}
    """.trimIndent()
}
