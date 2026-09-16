package io.github.mbackschat.a12.dm.gate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.mbackschat.a12.dm.consistency.CheckKt;
import io.github.mbackschat.a12.dm.consistency.ConsistencyWorkspace;
import io.github.mbackschat.a12.dm.consistency.ModelSource;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;

/**
 * Ask the Kernel-free portable checker for a consistency verdict, from a dmkits consumer with no kernel.
 *
 * <p><b>What this adds over calling the checker directly.</b> Nothing about the verdict — the checker decides,
 * and this façade never re-derives or softens what it said. What it adds is the SHAPE a dmkits caller already
 * consumes: a {@link io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult} whose {@code diagnostics()}
 * carry the checker's own codes, whose receipt says
 * {@link io.github.mbackschat.a12.dm.core.Verification#PORTABLE_CHECKED}, and whose includes were resolved
 * through the kits' own {@link ModelResolver} rather than through a source list assembled by hand.
 *
 * <p><b>It is never a kernel confirmation and never a pass.</b> The receipt is deliberately neither of its
 * neighbours: not {@code KERNEL_CONFIRMED}, because no kernel saw the model, and not {@code STRUCTURAL_ONLY},
 * because a measured re-implementation of A12's constraints ran rather than our own prechecks. And this
 * checker cannot conclude VALID at all, so an accepted model comes back INCONCLUSIVE with its coverage
 * evidence — read {@link PortableGateRun#report()} for that half, never {@code ok()} alone.
 *
 * <p><b>Selection is explicit by design.</b> There is no {@code ServiceLoader} registration here and no
 * {@link io.github.mbackschat.a12.dm.core.spi.ConsistencyOracle} provider: a portable verdict arriving through
 * that port would make {@code Backends.verification()} report {@code KERNEL_CONFIRMED} for a run no kernel
 * arbitrated, which is the one direction a consumer cannot detect. A caller reaches this class by name.
 */
public final class PortableConsistencyGate {

    /** The label the checker echoes on a diagnostic about the entry source when the caller named none. */
    private static final String ENTRY_LABEL = "model";

    private PortableConsistencyGate() {
    }

    /**
     * Check a self-contained model's DM-JSON.
     *
     * <p>A model with includes needs {@link #check(ModelSource, ModelResolver)}: without a resolver its mount
     * groups cannot expand, which the checker reports as a coverage gap at the mount rather than as a finding.
     * That is an honest under-report, not a failure.
     */
    public static PortableGateRun check(String dmJson) {
        return check(new ModelSource(dmJson, ENTRY_LABEL));
    }

    /** Check one source, the caller having chosen the label its diagnostics are echoed under. */
    public static PortableGateRun check(ModelSource entry) {
        return PortableGateRun.of(CheckKt.check(entry));
    }

    /**
     * Check {@code entry}, resolving its {@code include}s and type-definition imports through {@code resolver}.
     *
     * <p>The reference closure is walked from the entry's own header, so a provider that itself includes a
     * third model is supplied too. A reference the resolver answers with {@code null} is simply not supplied,
     * and the checker then reports the mount as a coverage gap — the same honest under-report as the
     * self-contained overload. A resolver that treats a miss as an error instead
     * ({@link io.github.mbackschat.a12.dm.core.ReferenceResolvers#directory}) throws, which is its own
     * contract and is deliberately not swallowed here; the gate itself raises nothing.
     */
    public static PortableGateRun check(ModelSource entry, ModelResolver resolver) {
        ModelDocument document = headerOf(entry.getJson());
        if (document == null) {
            // Unreadable as DM-JSON, so it declares no references to follow. The checker says so in its own
            // report, at the source level, which is a gate that ran and a verdict like any other.
            return check(entry);
        }
        List<ModelSource> sources = new ArrayList<>();
        sources.add(entry);
        Set<String> seen = new LinkedHashSet<>();
        seen.add(document.id());
        Deque<String> pending = new ArrayDeque<>(referencedIds(document));
        while (!pending.isEmpty()) {
            String referenced = pending.removeFirst();
            if (!seen.add(referenced)) {
                continue;
            }
            String json = resolver.resolve(referenced);
            if (json == null) {
                continue;
            }
            sources.add(new ModelSource(json, referenced));
            ModelDocument provider = headerOf(json);
            if (provider != null) {
                pending.addAll(referencedIds(provider));
            }
        }
        return PortableGateRun.of(
                ConsistencyWorkspace.Companion.fromSources(sources).check(document.id()));
    }

    /**
     * Every model id this document references — mounted includes and type-definition imports alike, because
     * both contribute declarations the checker needs and neither is optional to supply.
     */
    private static List<String> referencedIds(ModelDocument document) {
        List<String> ids = new ArrayList<>();
        for (Map<String, String> byAlias : document.authoredReferences().values()) {
            ids.addAll(byAlias.values());
        }
        return ids;
    }

    /**
     * The document's header, or {@code null} when the text is not readable as DM-JSON at all.
     *
     * <p>Reading it here is a means to an end — the entry's id and its declared references — so a failure is
     * not this façade's verdict to report. The checker's own admission phase reports an unreadable source with
     * its label attached, which is both more precise and the same answer every other entry point gives.
     */
    private static ModelDocument headerOf(String dmJson) {
        try {
            return ModelDocument.read(dmJson);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }
}
