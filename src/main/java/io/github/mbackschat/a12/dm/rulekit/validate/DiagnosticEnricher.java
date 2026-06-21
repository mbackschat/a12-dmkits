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
     * {@code diagnostic} with a fix/explain attached when its code is known: first the operator
     * catalog-join (operator-level {@code MVK_*} codes), then the {@link StructuralKernelFix}
     * fallback for placement / iteration-scope codes that no operator documents.
     */
    Diagnostic enrich(Diagnostic diagnostic) {
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
        Optional<StructuralKernelFix.Hint> hint = diagnostic.code().flatMap(StructuralKernelFix::forCode);
        if (hint.isPresent()) {
            return diagnostic.withFix(hint.map(StructuralKernelFix.Hint::fix), hint.map(StructuralKernelFix.Hint::explain));
        }
        return diagnostic;
    }
}
