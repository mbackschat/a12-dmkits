package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.mgmtp.a12.kernel.md.document.apiV2.immutable.DocumentV2
import com.mgmtp.a12.kernel.md.document.apiV2.immutable.GroupInstanceV2
import io.github.mbackschat.a12.dm.interpreter.A12PointerFormat
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.interpreter.ValidationFindingType
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * a12-kernel-lean **EXP-2026-08-25-01** — when a source-classified computation result is applied to a SEPARATE
 * compatible destination, what repetition TOPOLOGY does the applied document carry, and what does a later
 * validation over that document see?
 *
 * **Why a cell probe could not answer it.** `SeparateDestinationClearDiffTest` established that a retained
 * CLEARED or VALUE action creates its missing addressed row, but it reads exactly one target cell and exposes no
 * repetition count. Three questions ride on the count rather than on that cell: whether creating row 3 also
 * materializes row 2, whether a later action at row 1 keeps row 3 at its own address, and which rows a
 * later validation enumerates. So this file normalizes the WHOLE `Lines` extent — its repetition count and both
 * cells of every physical row — and compares that projection across dynamic Groovy, generated Java, and the
 * standalone interpreter's retained-result route.
 *
 * **The two order cases are each other's control.** Case A places the changed VALUE at row 1 and the CLEARED at
 * row 3; case B swaps the action classes without moving their addressed coordinates. The kernel's application
 * owner processes CLEARED before changed VALUE, so if the applied rows were appended in action encounter order
 * rather than written at their addresses, exactly one of the two cases would come out reversed. Neither case
 * alone distinguishes those accounts.
 *
 * **The preservation case attacks the same question from the destination side**: an existing row 2 carrying a
 * noncomputed sentinel must keep it, at row 2, while the row-1 and row-3 actions land on their own addresses. A
 * shifted or lost sentinel is the negative result.
 *
 * **The later-validation case is the only one that observes the applied state as an INPUT.** The applied document
 * is validated with no second computation, so the pointers the rule reports are a statement about which rows the
 * application actually instantiated.
 *
 * Projection vocabulary, per physical row: `C` is `Candidate`, `A` is `Amount`; `-` is an absent cell, `()` a
 * present-empty one, and anything else is the stored value in plain form.
 */
class SeparateDestinationTopologyDiffTest {

    /** The retained-result fixture, plus the ONE ordinary row-iterated validation the request names. */
    private val model = RetainedComputationFixture.modelJson().let { structure ->
        val bound = BoundModel.ofJson(structure)
        val amount = bound.num(AMOUNT)
        TypedRules.inject(
            structure,
            bound.rule(bound.group(LINES)).name("AmountPositive").code(CODE).errorField(amount)
                .`when`(amount.asNumber().gt(0))
                .severity(Severity.ERROR)
                .message(Dsl.en("This line carries a projected amount.")).build(),
        )
    }

    private val java = RuntimeLaws.kernelJavaEngine(model)
    private val prepared = PreparedModel.loadExpandedJson(model)
    private val mapper = ObjectMapper()

    // ---- the four cases -------------------------------------------------------------------------------------

    /** Row 1 changes to a VALUE, row 3 CLEARS: the class the application owner processes FIRST sits LAST. */
    private fun reverseOrderSource() = lines(3)
        .field(CANDIDATE, "7", 1, 1, 1)
        .field(AMOUNT, "9", 1, 3, 1)

    /** The same two addressed coordinates with the action classes swapped. */
    private fun forwardOrderSource() = lines(3)
        .field(AMOUNT, "9", 1, 1, 1)
        .field(CANDIDATE, "7", 1, 3, 1)

    /** Two VALUE actions at rows 1 and 3 with no stale target anywhere — the later-validation input. */
    private fun laterValidationSource() = lines(3)
        .field(CANDIDATE, "5", 1, 1, 1)
        .field(CANDIDATE, "7", 1, 3, 1)

    @Test fun theAppliedTopologyIsAddressedRatherThanAppendedInActionOrder() {
        assertEquals(
            listOf(
                "reverse order  => Order=1 | Lines=3 | r1 C=- A=7 | r2 C=- A=- | r3 C=- A=()",
                "forward order  => Order=1 | Lines=3 | r1 C=- A=() | r2 C=- A=- | r3 C=- A=7",
            ),
            listOf(
                "reverse order  => ${applied(reverseOrderSource(), lines(0))}",
                "forward order  => ${applied(forwardOrderSource(), lines(0))}",
            ),
            "the complete normalized Lines extent as ALL THREE engines report it",
        )
    }

    @Test fun anExistingPredecessorRowKeepsItsNoncomputedSentinelAtItsOwnAddress() {
        assertEquals(
            "Order=1 | Lines=3 | r1 C=- A=7 | r2 C=4 A=- | r3 C=- A=()",
            applied(reverseOrderSource(), lines(2).field(CANDIDATE, "4", 1, 2, 1)),
            "the sentinel must stay at row 2 while the row-1 and row-3 actions take their exact addresses",
        )
    }

    @Test fun aLaterValidationOverTheAppliedDocumentEnumeratesTheInstantiatedRows() {
        assertEquals(
            "Order=1 | Lines=3 | r1 C=- A=5 | r2 C=- A=- | r3 C=- A=7",
            applied(laterValidationSource(), lines(0)),
            "the applied topology the validation below runs over",
        )
        assertEquals(
            listOf(
                "$CODE|VALUE_ERROR|/Order[1]/Lines[1]/Amount",
                "$CODE|VALUE_ERROR|/Order[1]/Lines[3]/Amount",
            ),
            laterValidation(laterValidationSource(), lines(0)),
            "the rule reports at the rows the application instantiated, on BOTH kernel strategies",
        )
        assertEquals(
            listOf(
                "$CODE|VALUE|Order/Lines/Amount",
                "$CODE|VALUE|Order/Lines[3]/Amount",
            ),
            interpreterLaterValidation(laterValidationSource(), lines(0)),
            "the standalone route reports the same two rows, and each is pinned in its OWN dialect rather than " +
                "bridged by a string tweak: the kernel signature above is the house form with its leading " +
                "slash and explicit [1], while A12's canonical exact pointer OMITS a repetition of 1 " +
                "(kernel-measured in A12PointerKernelDomainDiffTest). So row 1 carries no bracket and row 3 " +
                "does — which is itself what shows the two findings sit at different addresses.",
        )
    }

    // ---- the three engines ----------------------------------------------------------------------------------

    /** The normalized topology of the applied document, asserted identical on all three engines. */
    private fun applied(source: RuntimeLaws.Doc, destination: RuntimeLaws.Doc): String {
        val dynamic = topology(RuntimeLaws.appliedDocumentToDestination(model, source, destination))
        val static = topology(java.appliedDocumentToDestination(source, destination))
        val standalone = interpreterTopology(source, destination)
        assertEquals(dynamic, static, "dynamic Groovy vs generated Java")
        assertEquals(dynamic, standalone, "kernel vs the standalone interpreter's retained-result route")
        return dynamic
    }

    /** Validation of the ALREADY-APPLIED document, with no second computation, on both kernel strategies. */
    private fun laterValidation(source: RuntimeLaws.Doc, destination: RuntimeLaws.Doc): List<String> {
        val dynamic = RuntimeLaws.appliedValidationSignaturesSorted(
            model, RuntimeLaws.appliedDocumentToDestination(model, source, destination),
        )
        val static = java.validationSignaturesSorted(java.appliedDocumentToDestination(source, destination))
        assertEquals(dynamic, static, "later validation — dynamic Groovy vs generated Java")
        return dynamic
    }

    /** The standalone route's own later validation over the SAME applied snapshot — no second computation. */
    private fun interpreterLaterValidation(source: RuntimeLaws.Doc, destination: RuntimeLaws.Doc): List<String> {
        val interpreter = prepared.createInterpreter()
        val sourceSnapshot = prepared.documents.readDocument(RuntimeLaws.canonicalDocumentJson(model, source))
        val destinationSnapshot =
            prepared.documents.readDocument(RuntimeLaws.canonicalDocumentJson(model, destination))
        val applied = interpreter.compute(sourceSnapshot).applyTo(destinationSnapshot)
        return interpreter.validateFull(applied).findings.map { finding ->
            val type = if (finding.type == ValidationFindingType.OMISSION) "OMISSION" else "VALUE"
            val pointer = finding.errorPath.toExactPointer()?.let { A12PointerFormat.render(it) } ?: "(inexact)"
            "${finding.code}|$type|$pointer"
        }.sorted()
    }

    private fun topology(applied: DocumentV2): String {
        val orderRows = applied.directSubgroup("Order")
        val parts = mutableListOf("Order=${orderRows.size()}")
        val lines = orderRows.get(1).directSubgroup("Lines")
        parts += "Lines=${lines.size()}"
        for (r in 1..lines.size()) {
            val row = lines.get(r)
            parts += "r$r C=${cell(row, "Candidate")} A=${cell(row, "Amount")}"
        }
        return parts.joinToString(" | ")
    }

    private fun cell(row: GroupInstanceV2, name: String): String {
        val field = row.directField(name) ?: return "-"
        val value = field.value() ?: return "()"
        return if (value is BigDecimal) value.toPlainString() else value.toString()
    }

    /**
     * The standalone route: read both documents through the public A12 Document JSON transport, apply the
     * retained result, encode the applied snapshot, and project the SAME normalized topology off that canonical
     * JSON — where a repeating group is an array and an empty row is `{}`, so the encoded text already carries the
     * repetition count this projection needs.
     */
    private fun interpreterTopology(source: RuntimeLaws.Doc, destination: RuntimeLaws.Doc): String {
        val sourceSnapshot = prepared.documents.readDocument(RuntimeLaws.canonicalDocumentJson(model, source))
        val destinationSnapshot =
            prepared.documents.readDocument(RuntimeLaws.canonicalDocumentJson(model, destination))
        val report = prepared.createInterpreter().compute(sourceSnapshot)
        val encoded = prepared.documents.encodeDocument(report.applyTo(destinationSnapshot))
        val root = mapper.readTree(encoded)
        val order = root.path("Order")
        val orderRows = if (order.isArray) order.size() else if (order.isMissingNode) 0 else 1
        val orderRow = if (order.isArray) order.get(0) else order
        val lines = orderRow.path("Lines")
        val lineRows = if (lines.isArray) lines.size() else if (lines.isMissingNode) 0 else 1
        val parts = mutableListOf("Order=$orderRows", "Lines=$lineRows")
        for (r in 0 until lineRows) {
            val row = if (lines.isArray) lines.get(r) else lines
            parts += "r${r + 1} C=${jsonCell(row, "Candidate")} A=${jsonCell(row, "Amount")}"
        }
        return parts.joinToString(" | ")
    }

    private fun jsonCell(row: JsonNode, name: String): String {
        if (!row.has(name)) return "-"
        val node: JsonNode = row.get(name)
        return if (node.isNull) "()" else node.asText()
    }

    private fun lines(count: Int): RuntimeLaws.Doc = RuntimeLaws.doc().group(ROOT, 1).also { doc ->
        for (row in 1..count) doc.group(LINES, 1, row)
    }

    private companion object {
        const val ROOT = RetainedComputationFixture.ROOT
        const val LINES = RetainedComputationFixture.LINES
        const val CANDIDATE = RetainedComputationFixture.CANDIDATE
        const val AMOUNT = RetainedComputationFixture.AMOUNT
        const val CODE = "AMOUNT_POSITIVE"
    }
}
