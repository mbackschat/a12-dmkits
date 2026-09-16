package io.github.mbackschat.a12.dm.gate

import io.github.mbackschat.a12.dm.consistency.ConsistencyWorkspace
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check as checkSource
import io.github.mbackschat.a12.dm.core.ModelResolver
import io.github.mbackschat.a12.dm.modelkit.ModelDocument
import kotlin.jvm.JvmStatic

/**
 * Ask the Kernel-free portable checker for a consistency verdict, from a dmkits consumer with no kernel.
 *
 * **What this adds over calling the checker directly.** Nothing about the verdict — the checker decides, and
 * this façade never re-derives or softens what it said. What it adds is the SHAPE a dmkits caller already
 * consumes: a [io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult] whose `diagnostics()` carry the
 * checker's own codes, whose receipt says [io.github.mbackschat.a12.dm.core.Verification.PORTABLE_CHECKED],
 * and whose includes were resolved through the kits' own [ModelResolver] rather than through a source list
 * assembled by hand.
 *
 * **It is never a kernel confirmation and never a pass.** The receipt is deliberately neither of its
 * neighbours: not `KERNEL_CONFIRMED`, because no kernel saw the model, and not `STRUCTURAL_ONLY`, because a
 * measured re-implementation of A12's constraints ran rather than our own prechecks. And this checker cannot
 * conclude VALID at all, so an accepted model comes back INCONCLUSIVE with its coverage evidence — read
 * [PortableGateRun.report] for that half, never `ok()` alone.
 *
 * **Selection is explicit by design.** There is no `ServiceLoader` registration here and no
 * [io.github.mbackschat.a12.dm.core.spi.ConsistencyOracle] provider: a portable verdict arriving through that
 * port would make `Backends.verification()` report `KERNEL_CONFIRMED` for a run no kernel arbitrated, which
 * is the one direction a consumer cannot detect. A caller reaches this object by name.
 */
object PortableConsistencyGate {

    /** The label the checker echoes on a diagnostic about the entry source when the caller named none. */
    private const val ENTRY_LABEL = "model"

    /**
     * Check a self-contained model's DM-JSON.
     *
     * A model with includes needs the [ModelResolver] overload: without a resolver its mount groups cannot
     * expand, which the checker reports as a coverage gap at the mount rather than as a finding. That is an
     * honest under-report, not a failure.
     */
    @JvmStatic
    fun check(dmJson: String): PortableGateRun = check(ModelSource(dmJson, ENTRY_LABEL))

    /** Check one source, the caller having chosen the label its diagnostics are echoed under. */
    @JvmStatic
    fun check(entry: ModelSource): PortableGateRun = PortableGateRun.of(checkSource(entry))

    /**
     * Check [entry], resolving its `include`s and type-definition imports through [resolver].
     *
     * The reference closure is walked from the entry's own header, so a provider that itself includes a third
     * model is supplied too. A reference the resolver answers with `null` is simply not supplied, and the
     * checker then reports the mount as a coverage gap — the same honest under-report as the self-contained
     * overload. A resolver that treats a miss as an error instead
     * ([io.github.mbackschat.a12.dm.core.ReferenceResolvers.directory]) throws, which is its own contract and
     * is deliberately not swallowed here; the gate itself raises nothing.
     */
    @JvmStatic
    fun check(entry: ModelSource, resolver: ModelResolver): PortableGateRun {
        // Unreadable as DM-JSON declares no references to follow. The checker says so in its own report, at
        // the source level, which is a gate that ran and a verdict like any other.
        val document = headerOf(entry.json) ?: return check(entry)
        val sources = mutableListOf(entry)
        val seen = linkedSetOf(document.id())
        val pending = ArrayDeque(referencedIds(document))
        while (pending.isNotEmpty()) {
            val referenced = pending.removeFirst()
            if (!seen.add(referenced)) continue
            val json = resolver.resolve(referenced) ?: continue
            sources += ModelSource(json, referenced)
            headerOf(json)?.let { pending += referencedIds(it) }
        }
        return PortableGateRun.of(ConsistencyWorkspace.fromSources(sources).check(document.id()))
    }

    /**
     * Every model id this document references — mounted includes and type-definition imports alike, because
     * both contribute declarations the checker needs and neither is optional to supply.
     */
    private fun referencedIds(document: ModelDocument): List<String> =
        document.authoredReferences().values.flatMap { it.values }

    /**
     * The document's header, or null when the text is not readable as DM-JSON at all.
     *
     * Reading it here is a means to an end — the entry's id and its declared references — so a failure is not
     * this façade's verdict to report. The checker's own admission phase reports an unreadable source with its
     * label attached, which is both more precise and the same answer every other entry point gives.
     */
    private fun headerOf(dmJson: String): ModelDocument? =
        try {
            ModelDocument.read(dmJson)
        } catch (unreadable: RuntimeException) {
            null
        }
}
