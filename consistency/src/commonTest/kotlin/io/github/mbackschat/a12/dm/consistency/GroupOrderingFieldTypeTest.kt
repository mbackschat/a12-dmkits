package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The third gate on a group's ordering members: the field they name must be one A12 accepts as a key.
 *
 * Every verdict below is the Kernel's, measured by `rulekit.validate.laws.GroupOrderingFieldTypeLawsTest`
 * (kernel 30.8.1, 2026-09-12). Resolving and being usable are different claims, which is why this is its own
 * family — a report that merged them would let a resolution fix read as a type approval.
 *
 * The index member carries a FOURTH gate in source, `MVK_INDEX_FIELD_NO_TABLE_ENUM`, which no stored A12
 * model can reach: the reader refuses the table reference outright, and the only implementor of the interface
 * behind it returns no table name at all. It is excluded on that cited dead path rather than left as silence,
 * so there is deliberately no row for it here.
 */
class GroupOrderingFieldTypeTest {

    /** A string field A12 never validates — both flags, because A12 refuses the first without the second. */
    private val noValueValidation =
        """"fieldType":{"type":"StringType","StringType":{"noValueValidation":true,"lineBreaksPermitted":true}}"""

    private fun findingsFor(skuBody: String, ordering: String) = prepare(
        assertIs<Admission.Admitted>(
            admit(
                ModelSource(
                    """
                    {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
                     "content":{"modelRoot":{"rootGroups":[
                      {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                        {"type":"Group","id":"g1","name":"Items","Group":{$ordering"elements":[
                          {"type":"Field","id":"f1","name":"Sku","Field":{$skuBody}},
                          {"type":"Field","id":"f2","name":"Count","Field":{}}
                        ]}}
                      ]}}
                     ]}}}
                    """.trimIndent(),
                ),
            ),
        ),
    ).groupOrderingFindings()

    private fun codesFor(skuBody: String, ordering: String) = findingsFor(skuBody, ordering).map { it.code }

    private fun index(written: String) = """"indexFieldName":"$written","""

    private fun sort(written: String) = """"sortFields":[{"name":"$written"}],"""

    /**
     * A `noValueValidation` string is refused on both members, each with its OWN code.
     *
     * The two codes are not interchangeable and neither was transferred from the other — a checker naming one
     * for both would be right about the rejection and wrong about what A12 said.
     */
    @Test
    fun aNoValueValidationStringIsRefusedAsEitherOrderingKey() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a plain field — the control"] = codesFor("", index("Sku"))
        actual["as the index field"] = codesFor(noValueValidation, index("Sku"))
        actual["as a sort field"] = codesFor(noValueValidation, sort("Sku"))
        actual["named by both members at once"] =
            codesFor(noValueValidation, index("Sku") + sort("Sku"))
        // A field that is never an ordering key is never asked about its type.
        actual["declared but named by neither"] = codesFor(noValueValidation, "")
        actual["named by a member that points elsewhere"] = codesFor(noValueValidation, index("Count"))

        val expected = mapOf(
            "a plain field — the control" to listOf(),
            "as the index field" to listOf("MVK_INDEX_FIELD_NO_VALUE_VALIDATION"),
            "as a sort field" to listOf("MVK_SORT_FIELD_NO_VALUE_VALIDATION"),
            "named by both members at once" to
                listOf("MVK_INDEX_FIELD_NO_VALUE_VALIDATION", "MVK_SORT_FIELD_NO_VALUE_VALIDATION"),
            "declared but named by neither" to listOf(),
            "named by a member that points elsewhere" to listOf(),
        )
        assertEquals(expected, actual, "a no-value-validation string as an ordering key")
    }

    /**
     * The flag is read only out of a `StringType` body, and only when it is actually `true`.
     *
     * `FieldUtils.isNoValueValidation` asks `isString` FIRST and reads the flag second, so the same flag
     * carried anywhere else is not this law. These rows are what keep the implementation from degrading into
     * "the text `noValueValidation` appears somewhere in the field".
     */
    @Test
    fun theFlagCountsOnlyAsAStringTypesOwnAndOnlyWhenSet() {
        val actual = mutableMapOf<String, List<String>>()
        actual["the flag set — the control"] = codesFor(noValueValidation, index("Sku"))
        actual["the flag explicitly false"] = codesFor(
            """"fieldType":{"type":"StringType","StringType":{"noValueValidation":false}}""",
            index("Sku"),
        )
        actual["a string type carrying no flag"] =
            codesFor(""""fieldType":{"type":"StringType","StringType":{}}""", index("Sku"))
        actual["the flag on a type that is not a string"] = codesFor(
            """"fieldType":{"type":"EnumerationType","EnumerationType":{"noValueValidation":true}}""",
            index("Sku"),
        )
        actual["a fieldType that is not an object"] = codesFor(""""fieldType":"StringType"""", index("Sku"))
        actual["no fieldType at all"] = codesFor("", index("Sku"))

        val expected = mapOf(
            "the flag set — the control" to listOf("MVK_INDEX_FIELD_NO_VALUE_VALIDATION"),
            "the flag explicitly false" to listOf(),
            "a string type carrying no flag" to listOf(),
            "the flag on a type that is not a string" to listOf(),
            "a fieldType that is not an object" to listOf(),
            "no fieldType at all" to listOf(),
        )
        assertEquals(expected, actual, "what the no-value-validation predicate reads")
    }

    /**
     * An unresolvable reference never reaches the type gate, however unusable the field it failed to name.
     *
     * A12 returns at the first gate that fires, so the two verdicts are exclusive rather than cumulative. The
     * discriminator is that `Sku` is a no-value-validation string in BOTH rows: only the spelling moves.
     */
    @Test
    fun theFirstGateToFireIsTheOnlyOneThatReports() {
        val actual = mutableMapOf<String, List<String>>()
        actual["the reference resolves"] = codesFor(noValueValidation, index("Sku"))
        actual["the reference names nothing"] = codesFor(noValueValidation, index("Missing"))

        val expected = mapOf(
            "the reference resolves" to listOf("MVK_INDEX_FIELD_NO_VALUE_VALIDATION"),
            "the reference names nothing" to listOf("CK_MODEL_NOT_BUILDABLE"),
        )
        assertEquals(expected, actual, "gate order")
    }

    /**
     * The finding attaches to the FIELD, not to the group that named it.
     *
     * A12 reports this one against the field — `reportError(problemReporter, indexField, …)` — and the
     * corrective is to retype that field or key the group by another, so the group's path would send a reader
     * to the wrong declaration. Every other finding in this family is attached to the group, which is exactly
     * why this one needs saying.
     */
    @Test
    fun theTypeFindingNamesTheFieldRatherThanTheGroup() {
        val finding = findingsFor(noValueValidation, index("Sku")).single()
        assertEquals("/Catalog/Items/Sku", finding.at)
        assertEquals(FindingCategory.A12_CONSTRAINT, finding.category)
    }
}
