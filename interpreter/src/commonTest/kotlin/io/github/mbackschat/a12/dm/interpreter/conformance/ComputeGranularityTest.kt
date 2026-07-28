package io.github.mbackschat.a12.dm.interpreter.conformance

import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument

/**
 * [kernelComputeGranularity] — the projection of the interpreter's report-ALL compute signatures onto the
 * KERNEL's compute reporting granularity (CONFORMANCE-CORPUS-SPEC §7), on BOTH targets (JVM + JS). The kernel's
 * `IDocumentComputationResult` reports a VALUE only when it CHANGED vs the input document (a TYPED-value
 * equality), a CLEARED instance only when the input had it FILLED, and an ERRORED instance unconditionally.
 * On a placement-built input document the cell values are raw STRINGS, so the typed equality can hold only
 * for a STRING-kind computed target (a number/date/boolean computes to a typed value, never equal to the raw
 * string) — kernel-probe-verified (KERNEL-FINDINGS "compute reporting granularity").
 */
class ComputeGranularityTest {

    private val model = ModelDef(
        listOf(
            FieldDef("/Order/Total", Kind.NUMBER, scale = 0),
            FieldDef("/Order/Label", Kind.STRING),
            FieldDef("/Order/Note", Kind.STRING),
            FieldDef("/Order/Items/Name", Kind.STRING),
        ),
        emptyList(),
    )

    /** The supplied-cell side, as a document — the projection now reads the document the case carries. */
    private val document = model.newDocument()
        .group("/Order", 1)
        .field("/Order/Total", "20", 1, 1)
        .field("/Order/Label", "AB-X", 1, 1)
        .group("/Order/Items", 1, 2)
        .field("/Order/Items/Name", "B-X", 1, 2, 1)
        .emptyField("/Order/Note", 1, 1)

    private fun project(vararg sigs: String): List<String> =
        kernelComputeGranularity(sigs.toList(), document, model)

    @Test
    fun dropsAStringValueEqualToItsSeededInput() {
        assertEquals(
            emptyList(),
            project("/Order[1]/Label|VALUE|AB-X"),
            "a STRING computed back to its seed is a kernel NON-change (typed String == seed string) — not reported",
        )
    }

    @Test
    fun keepsANumberValueEqualToItsSeededInput() {
        assertEquals(
            listOf("/Order[1]/Total|VALUE|20"),
            project("/Order[1]/Total|VALUE|20"),
            "a NUMBER cell's typed value (BigDecimal) never equals the raw seed string — the kernel reports it",
        )
    }

    @Test
    fun keepsAStringValueDifferingFromItsSeed() {
        assertEquals(
            listOf("/Order[1]/Label|VALUE|CD-X"),
            project("/Order[1]/Label|VALUE|CD-X"),
        )
    }

    @Test
    fun keepsAValueOnAnUnseededCell() {
        assertEquals(
            listOf("/Order[1]/Note|VALUE|AB"),
            project("/Order[1]/Note|VALUE|AB"),
            "an EMPTY placement is present but not filled — computing a value into it is a change",
        )
    }

    @Test
    fun dropsAClearOnACellTheInputDidNotFill() {
        assertEquals(
            emptyList(),
            project("/Order[1]/Grand|CLEARED", "/Order[1]/Note|CLEARED"),
            "the kernel reports CLEARED only for instances FILLED in the input",
        )
    }

    @Test
    fun keepsAClearOnASeededCell() {
        assertEquals(
            listOf("/Order[1]/Total|CLEARED"),
            project("/Order[1]/Total|CLEARED"),
        )
    }

    @Test
    fun keepsErroredRegardlessOfTheInput() {
        assertEquals(
            listOf("/Order[1]/Grand|ERRORED", "/Order[1]/Total|ERRORED"),
            project("/Order[1]/Grand|ERRORED", "/Order[1]/Total|ERRORED"),
            "the kernel reports an ERRORED computed instance unconditionally",
        )
    }

    @Test
    fun matchesSeedsPerRow() {
        assertEquals(
            listOf("/Order[1]/Items[1]/Name|VALUE|B-X"),
            project("/Order[1]/Items[1]/Name|VALUE|B-X", "/Order[1]/Items[2]/Name|VALUE|B-X"),
            "the seed is matched at the exact row pointer — only row 2 was seeded with B-X",
        )
    }

    @Test
    fun storedEmptyStringIsPresentButNotFilledForClearProjection() {
        val projection = KernelComputeProjection.prepare(
            model.newDocument().field("/Order/Note", "", 1, 1),
            model,
        )

        assertEquals(
            emptyList(),
            projection.project(listOf("/Order[1]/Note|CLEARED")),
            "an explicitly stored empty string is unfilled, so the kernel reports no clear delta",
        )
    }

    @Test
    fun aPreparedProjectionIsReusableAndAgreesWithAFreshlyPreparedOne() {
        // Reduced deliberately when the input became a Document (step 4): this used to wrap the placement LIST in a
        // read-counting decorator and assert preparation indexed each element exactly once, with no further reads
        // per projected result. `placementsSnapshot()` offers no such interception seam, and the no-rescan property
        // is now structural — `prepare` fills a HashMap once and `project` only reads it. What stays observable,
        // and is asserted here, is that a prepared projection is reusable and order-independent.
        val projection = KernelComputeProjection.prepare(document, model)
        // Both are STRING value-matches against seeded input, so the kernel reports neither.
        val sigs = listOf("/Order[1]/Label|VALUE|AB-X", "/Order[1]/Items[2]/Name|VALUE|B-X")

        repeat(3) { assertEquals(emptyList(), projection.project(sigs)) }
        assertEquals(
            projection.project(sigs.reversed()),
            KernelComputeProjection.prepare(document, model).project(sigs.reversed()),
            "a reused projection answers exactly as a freshly prepared one",
        )
    }
}
