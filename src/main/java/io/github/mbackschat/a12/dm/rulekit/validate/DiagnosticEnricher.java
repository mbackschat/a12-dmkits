package io.github.mbackschat.a12.dm.rulekit.validate;

import java.util.Optional;

import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog;
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorEntry;

/**
 * Enriches a raw kernel {@link Diagnostic} via the <b>catalog-join</b> (PROJECT-DESIGN §F4a): a
 * {@code MVK_*} code is matched against the catalog entry that documents it in its
 * {@code invalidExample}, and that entry's curated {@code fix} and operator {@code meaning} are
 * attached. The fix database thus lives in one place — the N7-locked catalog — so kernel
 * diagnostics and authoring examples never drift apart.
 */
final class DiagnosticEnricher {

    private final OperatorCatalog catalog;

    DiagnosticEnricher(OperatorCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * {@code diagnostic} with a fix/explain attached when its code is known: <b>first</b> the
     * {@link StructuralKernelFix} hints (placement / iteration-scope codes <em>and</em> the shared
     * structural codes like {@code MVK_NO_WILDCARD} that several operators trigger — these must win over
     * any single operator's {@code invalidExample}, else the fix is mis-stamped: probe finding F06-2),
     * <b>then</b> the operator catalog-join for operator-specific {@code MVK_*} codes.
     */
    Diagnostic enrich(Diagnostic diagnostic) {
        Optional<StructuralKernelFix.Hint> hint = diagnostic.code().flatMap(StructuralKernelFix::forCode);
        if (hint.isPresent()) {
            return diagnostic.withFix(hint.map(StructuralKernelFix.Hint::fix), hint.map(StructuralKernelFix.Hint::explain));
        }
        Optional<OperatorEntry> entry = diagnostic.code().flatMap(catalog::byInvalidError);
        if (entry.isPresent()) {
            Optional<String> fix = entry
                    .map(OperatorEntry::invalidExample)
                    .map(OperatorEntry.InvalidExample::fix)
                    .filter(s -> s != null && !s.isBlank());
            Optional<String> explain = entry
                    .map(OperatorEntry::meaning)
                    .filter(s -> s != null && !s.isBlank());
            return diagnostic.withFix(fix, explain);
        }
        // last resort — a content-free KERNEL rejection with a message but no MVK_ code (e.g. the date/time
        // field-format reject); attach a message-matched hint so it isn't left fix-less (probe 08).
        if (diagnostic.fix().isEmpty()) {
            Optional<StructuralKernelFix.Hint> byMessage = StructuralKernelFix.forMessage(diagnostic.summary());
            if (byMessage.isPresent()) {
                return diagnostic.withFix(byMessage.map(StructuralKernelFix.Hint::fix),
                        byMessage.map(StructuralKernelFix.Hint::explain));
            }
        }
        return diagnostic;
    }
}
