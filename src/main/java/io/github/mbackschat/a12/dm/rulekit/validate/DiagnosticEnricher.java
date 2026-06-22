package io.github.mbackschat.a12.dm.rulekit.validate;

import java.util.List;
import java.util.Optional;

import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog;
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorEntry;

/**
 * Enriches a raw kernel {@link Diagnostic} via the <b>catalog-join</b> (PROJECT-DESIGN §F4a): a
 * {@code MVK_*} code is matched against the catalog entry that documents it in its
 * {@code invalidExample}, and that entry's curated {@code fix} and operator {@code meaning} are
 * attached. The fix database thus lives in one place — the N7-locked catalog — so kernel
 * diagnostics and authoring examples never drift apart.
 *
 * <p><b>The join is SOUND, not a guess.</b> A kernel code is <em>not</em> owned by one operator —
 * several operators document the same code, and some codes also fire from contexts no operator
 * describes. The old join took {@code operatorsForError(code).findFirst()}, so a code documented by
 * more than one operator was stamped with <em>whichever operator was first in {@code operators.json}</em>
 * (the F4a {@code where.operator} disambiguator is empty at enrich time — it only lands on a later read
 * pass). That mis-stamped every other construct (probe findings F06-2 / dec-places). Resolution split
 * by the two faces of the flaw:
 * <ul>
 *   <li><b>cross-operator</b> — when {@code >1} operator documents a code with <em>differing</em>
 *       correctives, the join attaches the <em>union</em> of them (never one) — so no construct is
 *       mis-stamped. One documenter (or several that agree) attaches the single corrective, unchanged.</li>
 *   <li><b>cross-context</b> — a code whose catalog documenter's fix is wrong for <em>another</em> real
 *       trigger ({@code MVK_NO_WILDCARD} from an aggregate; {@code MVK_INVALID_COMPARE_DEC_PLACES} from a
 *       computed-field scale) is carried by {@link StructuralKernelFix}, which is consulted <b>first</b>.</li>
 * </ul>
 */
final class DiagnosticEnricher {

    private final OperatorCatalog catalog;

    DiagnosticEnricher(OperatorCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * {@code diagnostic} with a fix/explain attached when its code is known: <b>first</b> the
     * {@link StructuralKernelFix} hints (orphan codes the catalog never documents, and the cross-context
     * codes whose documenter's fix is wrong for another trigger — {@code MVK_NO_WILDCARD},
     * {@code MVK_INVALID_COMPARE_DEC_PLACES}); <b>then</b> the sound catalog-join.
     */
    Diagnostic enrich(Diagnostic diagnostic) {
        Optional<StructuralKernelFix.Hint> hint = diagnostic.code().flatMap(StructuralKernelFix::forCode);
        if (hint.isPresent()) {
            return diagnostic.withFix(hint.map(StructuralKernelFix.Hint::fix), hint.map(StructuralKernelFix.Hint::explain));
        }
        List<OperatorEntry> documenters = diagnostic.code().map(catalog::operatorsForError).orElse(List.of());
        if (!documenters.isEmpty()) {
            return diagnostic.withFix(joinFix(documenters), firstMeaning(documenters));
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

    /**
     * The corrective for a code from the operators that document it: ONE documenter (or several that
     * AGREE) → that single fix; several that DISAGREE → the <em>union</em>, so no construct is mis-stamped
     * by an arbitrary {@code findFirst}. Distinct fixes are sorted for a deterministic message.
     */
    private static Optional<String> joinFix(List<OperatorEntry> documenters) {
        List<String> distinct = documenters.stream()
                .map(OperatorEntry::invalidExample)
                .filter(e -> e != null && e.fix() != null && !e.fix().isBlank())
                .map(OperatorEntry.InvalidExample::fix)
                .distinct()
                .sorted()
                .toList();
        if (distinct.isEmpty()) {
            return Optional.empty();
        }
        if (distinct.size() == 1) {
            return Optional.of(distinct.get(0));
        }
        return Optional.of("this code is raised by more than one construct; the corrective depends on which "
                + "you used — " + String.join("; ", distinct));
    }

    private static Optional<String> firstMeaning(List<OperatorEntry> documenters) {
        return documenters.stream()
                .map(OperatorEntry::meaning)
                .filter(s -> s != null && !s.isBlank())
                .findFirst();
    }
}
