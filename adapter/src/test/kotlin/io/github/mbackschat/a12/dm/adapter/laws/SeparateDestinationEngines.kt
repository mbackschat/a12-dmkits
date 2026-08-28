package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.A12PointerFormat
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.interpreter.ValidationFindingType
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * The three engines a separate-destination topology law reads — dynamic Groovy, generated Java, and the
 * standalone interpreter's retained-result route — behind one `compute(source).applyTo(destination)` surface.
 *
 * Every observation here is a TRI-CHECK that returns the agreed value: the two kernel strategies (and, for the
 * extent, the standalone route) must produce identical text or the call fails. A law therefore states only WHAT
 * it expects, never which engine produced it, and a divergence surfaces as an engine-named failure rather than
 * as a mismatch against one pinned expectation.
 *
 * The pointer dialects are the one place the engines legitimately differ, so the two later-validation routes
 * stay separate: the kernel's house form spells a repetition of 1 explicitly, while A12's canonical exact
 * pointer omits it. Bridging them with a string tweak would hide exactly the address information these laws are
 * about.
 */
internal class SeparateDestinationEngines(
    private val model: String,
    private val chain: List<String>,
    private val cells: List<String>,
) {

    private val java = RuntimeLaws.kernelJavaEngine(model)
    private val prepared = PreparedModel.loadExpandedJson(model)

    /**
     * The complete normalized applied extent, asserted identical on all three engines.
     *
     * **The three engines are not fed through the same A12 ingress route, so this comparison has a
     * precondition** — [CrossRouteSourceGate.requireRouteAgnostic] enforces it rather than leaving it to the
     * fixture author.
     * The kernel arms build from [RuntimeLaws.Doc], A12's V1 String factory; the standalone arm reads canonical
     * Document JSON, A12's typed route. `applyTo` applies only the CHANGED actions, and the two routes classify
     * a SOURCE-IDENTICAL cell differently — on V1 a typed cell holds unconverted text and never compares equal,
     * so every non-STRING kind is changed there and none on the typed route (KERNEL-FINDINGS §11,
     * [SourceIdentityIngressRouteDiffTest]). A fixture that seeds a computed cell with its own computed value
     * would therefore diverge here for a reason that has nothing to do with topology, and it would read exactly
     * like a padding defect. Measured: a source holding `7.00` for a `7.00` computation has the destination
     * overwritten on the kernel arms and untouched on the standalone arm.
     */
    fun appliedExtent(source: RuntimeLaws.Doc, destination: RuntimeLaws.Doc): String {
        CrossRouteSourceGate.requireRouteAgnostic(model, prepared, source)
        val dynamic = AppliedTopology.ofKernel(
            RuntimeLaws.appliedDocumentToDestination(model, source, destination), chain, cells,
        )
        val static = AppliedTopology.ofKernel(
            java.appliedDocumentToDestination(source, destination), chain, cells,
        )
        val standalone = AppliedTopology.ofJson(interpreterApplied(source, destination), chain, cells)
        assertEquals(dynamic, static, "applied extent — dynamic Groovy vs generated Java")
        assertEquals(dynamic, standalone, "kernel vs the standalone interpreter's retained-result route")
        return dynamic
    }

    /**
     * How the SOURCE document classified the result at [cellName], as sorted `CLASS|pointer` entries, asserted
     * identical on both kernel strategies. Without this the non-creation controls would be vacuous: an empty
     * applied extent proves nothing unless the action that failed to create a row demonstrably existed.
     */
    fun sourceClassification(source: RuntimeLaws.Doc, cellName: String): List<String> {
        val dynamic = classify(RuntimeLaws.resultChannels(model, source), cellName)
        val static = classify(java.resultChannels(java.instance(source)), cellName)
        assertEquals(dynamic, static, "source action classification — dynamic Groovy vs generated Java")
        return dynamic
    }

    /**
     * The same question asked of the STANDALONE route: its own source-relative `actions`, rendered in A12's
     * canonical dialect. Its empty applied extent is evidence of non-creation only if it minted the action at
     * all, and the kernel's channels above cannot speak for it.
     */
    fun interpreterSourceClassification(source: RuntimeLaws.Doc, cellName: String): List<String> =
        prepared.createInterpreter().compute(snapshot(source)).actions
            .map { "${it.outcome}|${A12PointerFormat.format(it.pointer.path, it.pointer.coordinates)}" }
            .filter { cellName in it }
            .sorted()

    /** Validation of the ALREADY-APPLIED document, with no second computation, on both kernel strategies. */
    fun kernelLaterValidation(source: RuntimeLaws.Doc, destination: RuntimeLaws.Doc): List<String> {
        val dynamic = RuntimeLaws.appliedValidationSignaturesSorted(
            model, RuntimeLaws.appliedDocumentToDestination(model, source, destination),
        )
        val static = java.validationSignaturesSorted(java.appliedDocumentToDestination(source, destination))
        assertEquals(dynamic, static, "later validation — dynamic Groovy vs generated Java")
        return dynamic
    }

    /** The standalone route's own later validation over the SAME applied snapshot — no second computation. */
    fun interpreterLaterValidation(source: RuntimeLaws.Doc, destination: RuntimeLaws.Doc): List<String> {
        val interpreter = prepared.createInterpreter()
        val applied = interpreter.compute(snapshot(source)).applyTo(snapshot(destination))
        return interpreter.validateFull(applied).findings.map { finding ->
            val type = if (finding.type == ValidationFindingType.OMISSION) "OMISSION" else "VALUE"
            val pointer = finding.errorPath.toExactPointer()?.let { A12PointerFormat.render(it) } ?: "(inexact)"
            "${finding.code}|$type|$pointer"
        }.sorted()
    }

    /**
     * The standalone route's applied document as canonical A12 Document JSON — read both inputs through the
     * public transport, apply the retained result, and encode. A repeating group is an array there and an empty
     * row is `{}`, so the encoded text already carries the repetition count the projection needs.
     */
    private fun interpreterApplied(source: RuntimeLaws.Doc, destination: RuntimeLaws.Doc): String {
        val report = prepared.createInterpreter().compute(snapshot(source))
        return prepared.documents.encodeDocument(report.applyTo(snapshot(destination)))
    }

    private fun snapshot(doc: RuntimeLaws.Doc) =
        prepared.documents.readDocument(RuntimeLaws.canonicalDocumentJson(model, doc))

    private fun classify(channels: RuntimeLaws.ResultChannels, cellName: String): List<String> = buildList {
        channels.cleared().filter { cellName in it }.forEach { add("CLEARED|$it") }
        channels.withErrors().filter { cellName in it }.forEach { add("ERRORED|$it") }
        channels.withChanges().filter { cellName in it }.forEach { add("VALUE|$it") }
    }.sorted()
}
