package io.github.mbackschat.a12.dm.rulekit.validate;

import io.github.mbackschat.a12.dm.core.Notification;
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog;

/**
 * The public seam for mapping a kernel notification — our {@link Notification}, converted from the kernel's
 * own type at the backend's {@code ConsistencyOracle} boundary (a {@code checkConsistency} notification
 * <em>or</em> a per-rule {@code IProblem}) — into an enriched core {@link Diagnostic}. It bundles {@link Diagnostic#fromKernel}
 * (the LSP-shape mapping) with the {@link DiagnosticEnricher} catalog-join (the curated
 * {@code fix}/{@code gotchas} from {@code operators.json}), both package-private, so the opt-in
 * {@code adapter} can surface the kernel's per-rule problems as structured {@code Diagnostic}s rather than
 * discarding them (F4a — completes the IProblem→Diagnostic wiring).
 */
public final class KernelDiagnostics {

    private static final DiagnosticEnricher ENRICHER = new DiagnosticEnricher(OperatorCatalog.load());

    private KernelDiagnostics() {
    }

    /** Map one kernel notification to an enriched {@link Diagnostic} attributed to {@code rulePath}. */
    public static Diagnostic enriched(Notification notification, String rulePath) {
        return ENRICHER.enrich(Diagnostic.fromKernel(notification, rulePath));
    }
}
