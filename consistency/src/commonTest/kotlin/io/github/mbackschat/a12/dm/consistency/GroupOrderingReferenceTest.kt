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

    /** A second field carrying the short name `Sku`, so that name exists at two levels. */
    private val SKU = """,{"type":"Field","id":"f3","name":"Sku","Field":{"fieldType":{"type":"StringType"}}}"""

    private fun codesFor(itemsBody: String = "", catalogBody: String = "", catalogExtra: String = "") = prepare(
        assertIs<Admission.Admitted>(
            admit(
                ModelSource(
                    """
                    {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
                     "content":{"modelRoot":{"rootGroups":[
                      {"type":"Group","id":"g0","name":"Catalog","Group":{$catalogBody"elements":[
                        {"type":"Field","id":"f0","name":"Label","Field":{"fieldType":{"type":"StringType"}}}$catalogExtra,
                        {"type":"Group","id":"g1","name":"Items","Group":{$itemsBody"elements":[
                          {"type":"Field","id":"f1","name":"Sku","Field":{"fieldType":{"type":"StringType"}}},
                          {"type":"Field","id":"f2","name":"Count","Field":{"fieldType":{"type":"StringType"}}}
                        ]}}
                      ]}}
                     ]}}}
                    """.trimIndent(),
                ),
            ),
        ),
    ).tracked().groupOrderingFindings().map { it.code }

    private fun index(written: String) = """"indexFieldName":"$written","""

    /**
     * A sort list needs a REPEATABLE carrier, so the helper declares one.
     *
     * Without it every sort row also draws `MVK_INVALID_SORTINFO` — a real A12 class, measured, and a
     * different law from the one these rows are about. Making the carrier repeatable is what keeps each row
     * on one variable.
     */
    private fun sort(written: String) = """"repeatability":9,"sortFields":[{"name":"$written"}],"""

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
                              {"type":"Field","id":"f1","name":"Sku","Field":{"fieldType":{"type":"StringType"}}}
                            ]}}
                          ]}}
                         ]}}}
                        """.trimIndent(),
                    ),
                ),
            ),
        ).tracked().groupOrderingFindings().single().explain
        assertTrue("illegal relative path" in explain, explain)
    }

    /**
     * Which of the two accounts the checker follows — the row that separates the LANDING from the NAME.
     *
     * The fixture above has disjoint short names per level, so "landed outside this group" and "this group
     * has no field of that name" are one predicate on every one of its rows. Giving `Catalog` a `Sku` of its
     * own separates them: `../Sku` written in `Items` lands outside and A12 ADMITS it, because `Items` has a
     * `Sku`. A checker judging the landing rejects a model the Kernel takes — the over-rejection class.
     */
    @Test
    fun aDuplicateShortNameSeparatesTheLandingFromTheName() {
        val actual = mutableMapOf<String, List<String>>()
        actual["../Sku, no Catalog/Sku — the control"] = codesFor(index("../Sku"))
        actual["../Sku, with Catalog/Sku"] = codesFor(index("../Sku"), catalogExtra = SKU)
        actual["sort ../Sku, with Catalog/Sku"] = codesFor(sort("../Sku"), catalogExtra = SKU)
        actual["../Label, with Catalog/Sku"] = codesFor(index("../Label"), catalogExtra = SKU)

        val expected = mapOf(
            // Without the second field the spelling lands on nothing, so it never reaches the second gate.
            "../Sku, no Catalog/Sku — the control" to listOf("CK_MODEL_NOT_BUILDABLE"),
            "../Sku, with Catalog/Sku" to listOf(),
            "sort ../Sku, with Catalog/Sku" to listOf(),
            // …and the NAME is what decides, not the duplication: a landing outside on a name this group does
            // not carry is still refused in the very same model.
            "../Label, with Catalog/Sku" to listOf("MVK_INVALID_INDEX_FIELD"),
        )
        assertEquals(expected, actual, "whether the landing or the short name decides")
    }

    /**
     * A numeric sort name is a NAME, not a missing one — the branch the code table cannot see.
     *
     * Both shapes leave the model unusable and draw the same code, so only the explanation separates them,
     * and it is what an author acts on: `{"name": 5}` needs a field named, `{}` needs a name added.
     */
    @Test
    fun aNumericSortNameIsExplainedAsANameThatNamesNothing() {
        val explain = prepare(
            assertIs<Admission.Admitted>(
                admit(
                    ModelSource(
                        """
                        {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
                         "content":{"modelRoot":{"rootGroups":[
                          {"type":"Group","id":"g0","name":"Catalog","Group":{
                            "repeatability":9,"sortFields":[{"name":5}],"elements":[
                            {"type":"Field","id":"f0","name":"Label","Field":{"fieldType":{"type":"StringType"}}}
                          ]}}
                         ]}}}
                        """.trimIndent(),
                    ),
                ),
            ),
        ).tracked().groupOrderingFindings().single().explain
        assertTrue("names no field at all" in explain, explain)
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
        actual["an entry with no name"] = codesFor(""""repeatability":9,"sortFields":[{}],""")
        actual["two entries, one bad"] = codesFor(""""repeatability":9,"sortFields":[{"name":"Sku"},{"name":"Missing"}],""")
        actual["a numeric name"] = codesFor(""""repeatability":9,"sortFields":[{"name":5}],""")

        val expected = mapOf(
            "a field of its own group" to listOf(),
            "an empty list" to listOf(),
            "a field that does not exist" to listOf("CK_MODEL_NOT_BUILDABLE"),
            "an empty name" to listOf("CK_MODEL_NOT_BUILDABLE"),
            "a field outside the group" to listOf("MVK_INVALID_SORT_FIELD"),
            "an entry with no name" to listOf("CK_MODEL_NOT_BUILDABLE"),
            // Each entry is judged on its own, so a good one never covers for a bad one beside it.
            "two entries, one bad" to listOf("CK_MODEL_NOT_BUILDABLE"),
            // Coerced to the name "5", which resolves to nothing — the same CODE as a reader refusal, and a
            // different reason, so the explanation had to stop calling it one.
            "a numeric name" to listOf("CK_MODEL_NOT_BUILDABLE"),
        )
        assertEquals(expected, actual, "a group's sortFields")
    }
}
