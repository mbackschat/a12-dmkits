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
 * `rulekit.validate.laws.GroupIndexAndSortReferenceLawsTest` (kernel 31.1.1, 2026-11-02; KERNEL-FINDINGS KF653).
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
                    {"header":{"modelVersion":"29.4.0","id":"catalog","locales":[{"code":"en_US"}]},
                     "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
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
     * A12 keeps the last segment of the spelling joined to the group's path and judges that name against the
     * group's fields; the landing matters only when it is a group, or when it climbs past the root.
     *
     * The `Items/Sku` pair separates that from reading the spelling: written inside `Items` it names nothing and
     * keeps `Sku`, a field of `Items`, so it is admitted; written in `Catalog` it keeps `Sku`, which `Catalog`
     * does not carry.
     */
    @Test
    fun anOrderingReferenceIsJudgedByTheNameItKeeps() {
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
        actual["a climb to the root"] = codesFor(index("../../Sku"))
        actual["a climb past the root"] = codesFor(index("../../../Sku"))
        actual["a climb onto the parent group"] = codesFor(index(".."))

        val unusable = listOf("CK_MODEL_NOT_BUILDABLE")
        val outside = listOf("MVK_INVALID_INDEX_FIELD")
        val expected = mapOf(
            "absent — the control" to listOf(),
            "a field of its own group" to listOf(),
            "another field of its own group" to listOf(),
            // An empty one keeps the group's own name, `Items`.
            "empty" to outside,
            "whitespace only" to outside,
            "a field that does not exist" to outside,
            "a bare name from the PARENT group" to outside,
            "a field of the parent reached with .." to outside,
            "a descending path that names nothing" to listOf(),
            "a descending path that DOES resolve" to outside,
            // A group bound where a field is expected leaves no model.
            "a child GROUP" to unusable,
            "an absolute path" to unusable,
            "a climb to the root" to listOf(),
            "a climb past the root" to unusable,
            "a climb onto the parent group" to unusable,
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
                        {"header":{"modelVersion":"29.4.0","id":"catalog","locales":[{"code":"en_US"}]},
                         "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
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
            // Without the second field the spelling lands on nothing, and keeps `Sku` all the same (on 30.8.1:
            // no model).
            "../Sku, no Catalog/Sku — the control" to listOf(),
            "../Sku, with Catalog/Sku" to listOf(),
            "sort ../Sku, with Catalog/Sku" to listOf(),
            // …and the NAME is what decides, not the duplication: a landing outside on a name this group does
            // not carry is still refused in the very same model.
            "../Label, with Catalog/Sku" to listOf("MVK_INVALID_INDEX_FIELD"),
        )
        assertEquals(expected, actual, "whether the landing or the short name decides")
    }

    /** A numeric sort name is coerced to a NAME, `5`, and judged as one; `{}` has no name at all. */
    @Test
    fun aNumericSortNameIsExplainedAsANameThatNamesNothing() {
        val explain = prepare(
            assertIs<Admission.Admitted>(
                admit(
                    ModelSource(
                        """
                        {"header":{"modelVersion":"29.4.0","id":"catalog","locales":[{"code":"en_US"}]},
                         "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
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
        assertTrue("keeps only the name `5`" in explain, explain)
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
            "a field that does not exist" to listOf("MVK_INVALID_SORT_FIELD"),
            // An empty or missing name binds no reference, and A12's converter dereferences it.
            "an empty name" to listOf("CK_MODEL_NOT_BUILDABLE"),
            "a field outside the group" to listOf("MVK_INVALID_SORT_FIELD"),
            "an entry with no name" to listOf("CK_MODEL_NOT_BUILDABLE"),
            // Each entry is judged on its own, so a good one never covers for a bad one beside it.
            "two entries, one bad" to listOf("MVK_INVALID_SORT_FIELD"),
            "a numeric name" to listOf("MVK_INVALID_SORT_FIELD"),
        )
        assertEquals(expected, actual, "a group's sortFields")
    }
}
