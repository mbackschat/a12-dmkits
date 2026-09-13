package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * B1: resolving a bare reference, and telling ABSENCE apart from AMBIGUITY.
 *
 * The four rows are [proposal §3.4](../../../../../../../docs/PORTABLE-CONSISTENCY-PROPOSAL.md)'s executed
 * grid — one axis changes only how many model-wide `Code` fields exist, the other only
 * `fieldRefByShortNameAllowed`, and the Kernel answers with two different codes. The existing static helper
 * collapses both into one unresolved-path report, which is exactly what this classification exists to fix:
 * a consumer told "unresolved" cannot know whether to declare the field or to qualify the reference.
 */
class ReferenceBindingTest {

    /** The grid's model: a root `Note`, a `Warehouse/Code`, and a sibling that does or does not repeat it. */
    private fun catalog(sibling: String, shortNames: Boolean) = prepare(
        assertIs<Admission.Admitted>(
            admit(
                ModelSource(
                    """
                    {"header":{"id":"catalog","locales":[{"code":"en_US"}]},"content":{
                      "modelConfig":{"fieldRefByShortNameAllowed":$shortNames},
                      "modelRoot":{"rootGroups":[
                        {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                          {"type":"Field","id":"f0","name":"Note","Field":{"fieldType":{"type":"StringType"}}},
                          {"type":"Group","id":"g1","name":"Warehouse","Group":{"elements":[
                            {"type":"Field","id":"f1","name":"Code","Field":{"fieldType":{"type":"StringType"}}}
                          ]}},
                          {"type":"Group","id":"g2","name":"Store","Group":{"elements":[
                            {"type":"Field","id":"f2","name":"$sibling","Field":{"fieldType":{"type":"StringType"}}}
                          ]}}
                        ]}}
                      ]}}}
                    """.trimIndent(),
                ),
            ),
        ),
    )

    private fun oneCode(shortNames: Boolean) = catalog("Other", shortNames)

    private fun twoCodes(shortNames: Boolean) = catalog("Code", shortNames)

    @Test
    fun aUniqueShortNameResolvesWhenTheFallbackIsOn() {
        assertEquals(
            Reference.Resolved("/Catalog/Warehouse/Code"),
            oneCode(shortNames = true).resolve("/Catalog", "Code"),
        )
    }

    @Test
    fun aUniqueShortNameIsUndeclaredWhenTheFallbackIsOff() {
        assertEquals(
            Reference.Undeclared("/Catalog/Code"),
            oneCode(shortNames = false).resolve("/Catalog", "Code"),
        )
    }

    @Test
    fun anAmbiguousShortNameIsUndeclaredWhenTheFallbackIsOff() {
        assertEquals(
            Reference.Undeclared("/Catalog/Code"),
            twoCodes(shortNames = false).resolve("/Catalog", "Code"),
        )
    }

    /**
     * The row the classification exists for: with the fallback on, two candidates is a DIFFERENT Kernel
     * rejection (`MVK_FIELDNAME_NOT_UNIQUE`) from none at all (`MVK_INVALID_ENTITY`), and the corrective
     * differs with it — qualify the reference rather than declare the field.
     */
    @Test
    fun anAmbiguousShortNameIsReportedAsAmbiguousRatherThanAbsent() {
        assertEquals(
            Reference.Ambiguous("Code", listOf("/Catalog/Warehouse/Code", "/Catalog/Store/Code")),
            twoCodes(shortNames = true).resolve("/Catalog", "Code"),
        )
    }

    /**
     * Declaring-group precedence: the Kernel tries `base/name` first, so a field the group actually has wins
     * over any model-wide candidate — with the fallback ON, which is where a naive model-wide-first resolver
     * would silently pick the wrong field.
     */
    @Test
    fun theDeclaringGroupWinsOverTheModelWideFallback() {
        assertEquals(
            Reference.Resolved("/Catalog/Warehouse/Code"),
            twoCodes(shortNames = true).resolve("/Catalog/Warehouse", "Code"),
        )
    }

    /** A name nothing declares is absent, not ambiguous, whichever way the flag is set. */
    @Test
    fun aNameNothingDeclaresIsUndeclared() {
        listOf(true, false).forEach { flag ->
            assertEquals(
                Reference.Undeclared("/Catalog/Missing"),
                oneCode(shortNames = flag).resolve("/Catalog", "Missing"),
                "fallback=$flag",
            )
        }
    }

    /**
     * The fallback is fields only: a GROUP's short name is not a candidate, because the Kernel's own
     * fallback indexes fields. Without this a resolver that indexed every element would resolve `Warehouse`
     * from an unrelated group and never be noticed.
     */
    @Test
    fun theModelWideFallbackConsidersFieldsOnly() {
        assertEquals(
            Reference.Undeclared("/Catalog/Store/Warehouse"),
            oneCode(shortNames = true).resolve("/Catalog/Store", "Warehouse"),
        )
    }
}
