package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of "an enumeration's value list reports every defect it carries".
 *
 * Mirrors `rulekit.validate.laws.EnumValueMultipleDefectLawsTest`. Three families read one `values` array —
 * the tokens' identity, their alphabet, and the categories paired with them — and unlike the condition
 * member none of them withholds anything: every defect is reported, so the rows here are about the CLASSES
 * and their multiplicity rather than about a gate.
 *
 * **`MVK_CATEGORY_VALUE_DUPLICATE` is the class the pairing pass turned up.** A12 draws it for a repeated
 * INTERNAL value while a category exists, once per duplicated value per category; the checker reported the
 * value class alone until this file. A category whose own tokens repeat is admitted, which is what makes the
 * name misleading and the row necessary.
 */
class EnumValueMultipleDefectTest {

    private val duplicate = "MVK_INTERNAL_VALUES_DUPLICATE"
    private val spoiltPairing = "MVK_CATEGORY_VALUE_DUPLICATE"
    private val missingCategory = "MVK_MISSING_CATEGORY_VALUE"
    /** A12's codeless basic sentence, once per repeated value ([KERNEL-FINDINGS KF626]). */
    private val repeated = "RK_ENUM_VALUE_DUPLICATE"

    @Test
    fun aRepeatedValueIsReportedAgainstEveryCategoryItLeavesAmbiguous() {
        val actual = mutableMapOf<String, List<String>>()
        actual["distinct values — the control"] = codes(listOf("BASIC", "PLUS"), listOf(listOf("A", "B")))
        actual["a repeated value under one category"] =
            codes(listOf("BASIC", "BASIC"), listOf(listOf("A", "B")))
        actual["a repeated value under NO category"] = codes(listOf("BASIC", "BASIC"), emptyList())
        actual["a repeated CATEGORY token, values distinct"] =
            codes(listOf("BASIC", "PLUS"), listOf(listOf("A", "A")))
        actual["one repeated value under TWO categories"] =
            codes(listOf("BASIC", "BASIC"), listOf(listOf("A", "B"), listOf("C", "D")))
        actual["TWO repeated values under TWO categories"] =
            codes(listOf("BASIC", "BASIC", "PLUS", "PLUS"),
                listOf(listOf("A", "B", "C", "D"), listOf("E", "F", "G", "H")))
        actual["one value repeated THREE times"] =
            codes(listOf("BASIC", "BASIC", "BASIC"), listOf(listOf("A", "B", "C")))

        val expected = mutableMapOf<String, List<String>>()
        expected["distinct values — the control"] = emptyList()
        expected["a repeated value under one category"] = listOf(spoiltPairing, duplicate, repeated)
        // The row that makes the class about the PAIRING rather than the list: no category, no second class.
        expected["a repeated value under NO category"] = listOf(duplicate, repeated)
        // …and its mirror image, which is why the class cannot be read off its name.
        expected["a repeated CATEGORY token, values distinct"] = emptyList()
        // The cross product, at the cell that settles it: 1×2 and 2×1 both give two, 2×2 gives four.
        expected["one repeated value under TWO categories"] = listOf(spoiltPairing, spoiltPairing, duplicate, repeated)
        expected["TWO repeated values under TWO categories"] = listOf(
            spoiltPairing, spoiltPairing, spoiltPairing, spoiltPairing, duplicate, duplicate, repeated, repeated)
        // Duplicated VALUES, not extra occurrences.
        expected["one value repeated THREE times"] = listOf(spoiltPairing, duplicate, repeated)
        assertEquals(expected, actual, "a repeated value is reported against every category it spoils")
    }

    /** …and it rides beside the misalignment class rather than replacing it. */
    @Test
    fun aSpoiltPairingAndAMisalignmentAreBothReported() {
        assertEquals(
            listOf(missingCategory, spoiltPairing, duplicate, repeated),
            codes(listOf("BASIC", "BASIC"), listOf(listOf("A"))),
            "a repeated value under a category one token short",
        )
    }

    private fun codes(values: List<String>, categories: List<List<String>>): List<String> =
        checkRead(ModelSource(model(values, categories))).findings
            .filter { it.at == "/Store/Tier" }.map { it.code }

    private fun model(values: List<String>, categories: List<List<String>>): String {
        val valueJson = values.joinToString(",") { """{"value":"$it"}""" }
        val categoryJson = categories.mapIndexed { index, tokens ->
            """{"name":"Band$index","values":[${tokens.joinToString(",") { "\"$it\"" }}]}"""
        }.joinToString(",")
        val categoryMember = if (categories.isEmpty()) "" else ""","categories":[$categoryJson]"""
        return """
            {"header":{"id":"store","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Store","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Tier","Field":{"fieldType":{
                  "type":"EnumerationType","EnumerationType":{"values":[$valueJson]$categoryMember}}}}
              ]}}
             ]}}}
        """.trimIndent()
    }
}
