package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A group's `indexFieldName` and `sortFields[].name` — the two members every earlier report named as
 * unresolved.
 *
 * Every verdict below is the Kernel's, measured by
 * `rulekit.validate.laws.GroupIndexAndSortReferenceLawsTest` (kernel 30.8.1, 2026-09-12).
 *
 * The fixture mirrors the one measured there: a root `Catalog` holding the field `Label` and the subgroup
 * `Items` holding `Sku` and `Count`, so a row can name a field of its own group, a field one level down, and
 * a field of the parent without changing anything else.
 */
class GroupOrderingReferenceTest {

    private fun codesFor(itemsBody: String = "", catalogBody: String = "") = prepare(
        assertIs<Admission.Admitted>(
            admit(
                ModelSource(
                    """
                    {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
                     "content":{"modelRoot":{"rootGroups":[
                      {"type":"Group","id":"g0","name":"Catalog","Group":{$catalogBody"elements":[
                        {"type":"Field","id":"f0","name":"Label","Field":{}},
                        {"type":"Group","id":"g1","name":"Items","Group":{$itemsBody"elements":[
                          {"type":"Field","id":"f1","name":"Sku","Field":{}},
                          {"type":"Field","id":"f2","name":"Count","Field":{}}
                        ]}}
                      ]}}
                     ]}}}
                    """.trimIndent(),
                ),
            ),
        ),
    ).groupOrderingFindings().map { it.code }

    private fun index(written: String) = """"indexFieldName":"$written","""

    private fun sort(written: String) = """"sortFields":[{"name":"$written"}],"""

    /**
     * One mechanism, discriminated: A12 resolves the spelling from the group and then judges what it landed
     * on.
     *
     * The `Items/Sku` pair is what separates that from "`..` is the special ingredient". Written inside
     * `Items` it names nothing and stops the build; written in `Catalog` it names a real field outside the
     * group and draws the code. A checker reading the SPELLING rather than the landing calls the second one
     * a direct child and reports nothing.
     */
    @Test
    fun anOrderingReferenceIsJudgedByWhatItLandsOn() {
        val actual = mutableMapOf<String, List<String>>()
        actual["absent — the control"] = codesFor()
        actual["a field of its own group"] = codesFor(index("Sku"))
        actual["another field of its own group"] = codesFor(index("Count"))
        actual["empty"] = codesFor(index(""))
        actual["whitespace only"] = codesFor(index("   "))
        actual["a field that does not exist"] = codesFor(index("Missing"))
        actual["a bare name from the PARENT group"] = codesFor(index("Label"))
        actual["a field of the parent reached with .."] = codesFor(index("../Label"))
        actual["a descending path that names nothing"] = codesFor(index("Items/Sku"))
        actual["a descending path that DOES resolve"] = codesFor(catalogBody = index("Items/Sku"))
        actual["a child GROUP"] = codesFor(catalogBody = index("Items"))
        actual["an absolute path"] = codesFor(index("/Catalog/Items/Sku"))

        val unusable = listOf("CK_MODEL_NOT_BUILDABLE")
        val outside = listOf("MVK_INVALID_INDEX_FIELD")
        val expected = mapOf(
            "absent — the control" to listOf(),
            "a field of its own group" to listOf(),
            "another field of its own group" to listOf(),
            // A blank one is FATAL where the absent member is legal — the sibling slot families' law, which
            // reads a blank string as absent, inverted as far as it goes.
            "empty" to unusable,
            "whitespace only" to unusable,
            "a field that does not exist" to unusable,
            "a bare name from the PARENT group" to unusable,
            "a field of the parent reached with .." to outside,
            "a descending path that names nothing" to unusable,
            "a descending path that DOES resolve" to outside,
            // The bare lookup is over FIELDS, so a child group of that name is not one.
            "a child GROUP" to unusable,
            "an absolute path" to unusable,
        )
        assertEquals(expected, actual, "a group's indexFieldName")
    }

    /**
     * An absolute spelling is named for what it is, not for what it fails to find.
     *
     * It draws the same CODE as an unresolvable relative one — both leave the model unusable — so the code
     * table above cannot tell the two apart, and without this the branch that recognises it is unobservable.
     * The distinction is worth keeping because the corrective differs: an absolute path is not a spelling of
     * this slot at all, where an unresolvable relative one asks the author to name an existing field.
     */
    @Test
    fun anAbsoluteSpellingIsExplainedAsAnIllegalRelativePath() {
        val explain = prepare(
            assertIs<Admission.Admitted>(
                admit(
                    ModelSource(
                        """
                        {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
                         "content":{"modelRoot":{"rootGroups":[
                          {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                            {"type":"Group","id":"g1","name":"Items","Group":{
                              "indexFieldName":"/Catalog/Items/Sku","elements":[
                              {"type":"Field","id":"f1","name":"Sku","Field":{}}
                            ]}}
                          ]}}
                         ]}}}
                        """.trimIndent(),
                    ),
                ),
            ),
        ).groupOrderingFindings().single().explain
        assertTrue("illegal relative path" in explain, explain)
    }

    /**
     * The sort member answers the same way and with its OWN code.
     *
     * `MVK_INVALID_INDEX_FIELD` is named for the index, and nothing about the shape says a sort field shares
     * its gate, so the outside-the-group row was measured on this member rather than transferred.
     */
    @Test
    fun aSortFieldAnswersTheSameWayUnderItsOwnCode() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a field of its own group"] = codesFor(sort("Sku"))
        actual["an empty list"] = codesFor(""""sortFields":[],""")
        actual["a field that does not exist"] = codesFor(sort("Missing"))
        actual["an empty name"] = codesFor(sort(""))
        actual["a field outside the group"] = codesFor(sort("../Label"))
        actual["an entry with no name"] = codesFor(""""sortFields":[{}],""")
        actual["two entries, one bad"] = codesFor(""""sortFields":[{"name":"Sku"},{"name":"Missing"}],""")

        val expected = mapOf(
            "a field of its own group" to listOf(),
            "an empty list" to listOf(),
            "a field that does not exist" to listOf("CK_MODEL_NOT_BUILDABLE"),
            "an empty name" to listOf("CK_MODEL_NOT_BUILDABLE"),
            "a field outside the group" to listOf("MVK_INVALID_SORT_FIELD"),
            "an entry with no name" to listOf("CK_MODEL_NOT_BUILDABLE"),
            // Each entry is judged on its own, so a good one never covers for a bad one beside it.
            "two entries, one bad" to listOf("CK_MODEL_NOT_BUILDABLE"),
        )
        assertEquals(expected, actual, "a group's sortFields")
    }
}
