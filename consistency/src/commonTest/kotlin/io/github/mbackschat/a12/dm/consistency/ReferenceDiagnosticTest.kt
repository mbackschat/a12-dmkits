package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * C2: a reference outcome becomes a finding in A12's own vocabulary.
 *
 * The rejections are not interchangeable, and the classification is the whole point of carrying them
 * separately — `MVK_INVALID_ENTITY` says no field carries the name, `MVK_FIELDNAME_NOT_UNIQUE` says several
 * do, `MVK_TOO_MANY_PARENT_GROUPS` says the path names no position at all. Each is a measured Kernel
 * constraint, so each may reject a model; none is a policy of ours.
 */
class ReferenceDiagnosticTest {

    @Test
    fun anUndeclaredConditionReferenceReportsTheKernelsEntityCode() {
        val finding = Reference.Undeclared("/Catalog/Code")
            .asFinding(ReferenceRole.CONDITION_ENTITY, at = "/Catalog/Rule")

        assertEquals("MVK_INVALID_ENTITY", finding?.code)
        assertEquals(FindingCategory.A12_CONSTRAINT, finding?.category)
        assertEquals(FindingSeverity.ERROR, finding?.severity)
        assertEquals("/Catalog/Rule", finding?.at)
    }

    @Test
    fun anAmbiguousReferenceReportsTheKernelsUniquenessCode() {
        val finding = Reference.Ambiguous("Code", listOf("/A/Code", "/B/Code"))
            .asFinding(ReferenceRole.CONDITION_ENTITY, at = "/Catalog/Rule")

        assertEquals("MVK_FIELDNAME_NOT_UNIQUE", finding?.code)
        assertEquals(FindingCategory.A12_CONSTRAINT, finding?.category)
    }

    @Test
    fun aConditionPathAboveTheRootReportsTheKernelsUpwardStepCode() {
        val finding = Reference.AboveRoot("../../Code")
            .asFinding(ReferenceRole.CONDITION_ENTITY, at = "/Order/Rule")

        assertEquals("MVK_TOO_MANY_PARENT_GROUPS", finding?.code)
    }

    /**
     * The SAME failure in a different place is a different rejection, which is why the role is a parameter
     * and not a detail: A12 answers an unresolvable error target with `MVK_ERROR_FIELD_MISSING`, and it
     * answers it the same way whether the path names nothing or climbs too far — where a condition
     * reference distinguishes those two. Measured on whole models through `ModelLoader.loadAndCheck`.
     */
    @Test
    fun theSameFailureInADifferentRoleIsADifferentRejection() {
        listOf(Reference.Undeclared("/Order/Nope"), Reference.AboveRoot("../../../Nope")).forEach { outcome ->
            assertEquals(
                "MVK_ERROR_FIELD_MISSING",
                outcome.asFinding(ReferenceRole.ERROR_TARGET, at = "/Order/Rule")?.code,
            )
        }
        assertEquals(
            listOf("MVK_INVALID_ENTITY", "MVK_TOO_MANY_PARENT_GROUPS"),
            listOf(Reference.Undeclared("/Order/Nope"), Reference.AboveRoot("../../../Nope"))
                .map { it.asFinding(ReferenceRole.CONDITION_ENTITY, at = "/Order/Rule")?.code },
        )
    }

    /**
     * A missing computed field is the one rejection A12 issues with no code: the Kernel refuses to build the
     * model at all. The checker owns a code for it rather than borrowing an `MVK_*` key that means something
     * else, and the spelling says whose it is.
     */
    @Test
    fun aMissingComputedFieldCarriesTheCheckersOwnCode() {
        val finding = Reference.Undeclared("/Order/Nope")
            .asFinding(ReferenceRole.COMPUTED_TARGET, at = "/Order/Comp")

        assertEquals(CK_COMPUTED_FIELD_NOT_FOUND, finding?.code)
        assertEquals(false, finding?.code?.startsWith("MVK_"))
    }

    /** A resolved reference is not a finding — the absence is deliberate, not an unhandled arm. */
    @Test
    fun aResolvedReferenceIsNotAFinding() {
        ReferenceRole.entries.forEach {
            assertNull(Reference.Resolved("/Catalog/Warehouse/Code").asFinding(it, at = "/Catalog/Rule"))
        }
    }

    /**
     * The explain names the candidates, because that is what separates this rejection from the other one for
     * a reader: an ambiguity is fixed by qualifying the reference to one of them, and the reader cannot
     * qualify what the finding does not show.
     */
    @Test
    fun anAmbiguityNamesTheCandidatesItCouldNotChooseBetween() {
        val finding = Reference.Ambiguous("Code", listOf("/A/Code", "/B/Code"))
            .asFinding(ReferenceRole.CONDITION_ENTITY, at = "/R")

        assertEquals(true, finding?.explain?.contains("/A/Code"), finding?.explain)
        assertEquals(true, finding?.explain?.contains("/B/Code"), finding?.explain)
    }

    /** Every reference rejection stands on its own and the verdict is INVALID: all are A12 constraints. */
    @Test
    fun anyReferenceRejectionMakesTheModelInvalid() {
        listOf(
            Reference.Undeclared("/Catalog/Code"),
            Reference.Ambiguous("Code", listOf("/A/Code", "/B/Code")),
            Reference.AboveRoot("../../Code"),
        ).forEach { outcome ->
            assertEquals(
                ConsistencyStatus.Invalid,
                statusOf(listOfNotNull(outcome.asFinding(ReferenceRole.CONDITION_ENTITY, at = "/R"))),
            )
        }
    }
}
