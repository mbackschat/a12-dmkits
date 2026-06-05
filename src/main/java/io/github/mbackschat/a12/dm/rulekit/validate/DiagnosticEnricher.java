package io.github.mbackschat.a12.dm.rulekit.validate;

import java.util.Optional;

import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog;
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorEntry;

/**
 * Enriches a raw kernel {@link Diagnostic} via the <b>catalog-join</b> (proposal §F4a): a
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

    /** {@code diagnostic} with the catalog-joined fix/explain attached when its code is known. */
    Diagnostic enrich(Diagnostic diagnostic) {
        Optional<OperatorEntry> entry = diagnostic.code().flatMap(catalog::byInvalidError);
        if (entry.isEmpty()) {
            return diagnostic;
        }
        Optional<String> fix = entry
                .map(OperatorEntry::invalidExample)
                .map(OperatorEntry.InvalidExample::fix)
                .filter(s -> s != null && !s.isBlank());
        Optional<String> explain = entry
                .map(OperatorEntry::meaning)
                .filter(s -> s != null && !s.isBlank());
        return diagnostic.withFix(fix, explain);
    }
}
