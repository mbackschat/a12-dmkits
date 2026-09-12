package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * C2: a reference outcome becomes a finding in A12's own vocabulary.
 *
 * The two rejections are not interchangeable and the classification is the whole point of carrying them
 * separately — `MVK_INVALID_ENTITY` says no field carries the name, `MVK_FIELDNAME_NOT_UNIQUE` says several
 * do. Both are measured Kernel constraints (`ShortNameTierLawsTest`, `ShortNameUniqueTierLawsTest`), so both
 * may reject a model; neither is a policy of ours.
 */
class ReferenceDiagnosticTest {

    @Test
    fun anUndeclaredReferenceReportsTheKernelsEntityCode() {
        val finding = Reference.Undeclared("/Catalog/Code").asFinding(at = "/Catalog/Rule")

        assertEquals("MVK_INVALID_ENTITY", finding?.code)
        assertEquals(FindingCategory.A12_CONSTRAINT, finding?.category)
        assertEquals(FindingSeverity.ERROR, finding?.severity)
        assertEquals("/Catalog/Rule", finding?.at)
    }

    @Test
    fun anAmbiguousReferenceReportsTheKernelsUniquenessCode() {
        val finding = Reference.Ambiguous("Code", listOf("/A/Code", "/B/Code")).asFinding(at = "/Catalog/Rule")

        assertEquals("MVK_FIELDNAME_NOT_UNIQUE", finding?.code)
        assertEquals(FindingCategory.A12_CONSTRAINT, finding?.category)
    }

    /** A resolved reference is not a finding — the absence is deliberate, not an unhandled arm. */
    @Test
    fun aResolvedReferenceIsNotAFinding() {
        assertNull(Reference.Resolved("/Catalog/Warehouse/Code").asFinding(at = "/Catalog/Rule"))
    }

    /**
     * The explain names the candidates, because that is what separates this rejection from the other one for
     * a reader: an ambiguity is fixed by qualifying the reference to one of them, and the reader cannot
     * qualify what the finding does not show.
     */
    @Test
    fun anAmbiguityNamesTheCandidatesItCouldNotChooseBetween() {
        val finding = Reference.Ambiguous("Code", listOf("/A/Code", "/B/Code")).asFinding(at = "/R")

        assertEquals(true, finding?.explain?.contains("/A/Code"), finding?.explain)
        assertEquals(true, finding?.explain?.contains("/B/Code"), finding?.explain)
    }

    /** Both rejections can stand together and the verdict is INVALID, since both are A12 constraints. */
    @Test
    fun eitherReferenceRejectionMakesTheModelInvalid() {
        listOf(
            Reference.Undeclared("/Catalog/Code"),
            Reference.Ambiguous("Code", listOf("/A/Code", "/B/Code")),
        ).forEach { outcome ->
            assertEquals(ConsistencyStatus.Invalid, statusOf(listOfNotNull(outcome.asFinding(at = "/R"))))
        }
    }
}
