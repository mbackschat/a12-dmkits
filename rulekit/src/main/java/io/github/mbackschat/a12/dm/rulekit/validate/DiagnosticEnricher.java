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
 *       mis-stamped. One documenter (or several that agree) attaches the single corrective, unchanged.
 *       <b>Refined 2026-07-31:</b> the claim above that the disambiguator "is empty at enrich time" holds for
 *       {@code where.operator} but <em>not</em> for the message — an operator-scoped MVK summary names the
 *       construct as {@code At '<Operator>'}, so {@link #narrowToNamedOperator} selects that documenter and the
 *       union survives only as the fallback. Measured on {@code MVK_NO_CONST}, which 7 operators document.</li>
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
            List<OperatorEntry> scoped = narrowToNamedOperator(diagnostic.summary(), documenters);
            return diagnostic.withFix(joinFix(scoped), firstMeaning(scoped));
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

    /**
     * Narrow the documenters to the operator <b>the kernel itself named</b>, when it did. An operator-scoped
     * MVK message carries an {@code At '<Operator>'} prefix — {@code "At 'Abs'It is not allowed to use
     * constants."} — so for exactly those codes the disambiguator the union works around <em>is</em> present at
     * enrich time, in the summary rather than in {@code where.operator}. Selecting by it is a <b>narrowing of
     * the sound union, not a guess</b>: the kernel says which construct rejected.
     *
     * <p>It matters most for {@code explain}: without it a 7-documenter code (`MVK_NO_CONST`) answered an
     * {@code Abs} rejection with {@code RoundDown}'s meaning and all seven correctives concatenated. Degrades
     * to the union whenever the summary names nothing, or names an operator that does not document the code.
     */
    private static List<OperatorEntry> narrowToNamedOperator(String summary, List<OperatorEntry> documenters) {
        if (summary == null || documenters.size() < 2) {
            return documenters;
        }
        java.util.regex.Matcher named = AT_OPERATOR.matcher(summary);
        if (!named.find()) {
            return documenters;
        }
        List<OperatorEntry> scoped = documenters.stream()
                .filter(entry -> namesOperator(entry, named.group(1)))
                .toList();
        return scoped.isEmpty() ? documenters : scoped;
    }

    /** The kernel's operator-scoped message prefix; the keyword is the model's condition language, so match both. */
    private static final java.util.regex.Pattern AT_OPERATOR = java.util.regex.Pattern.compile("At '([\\w-]+)'");

    private static boolean namesOperator(OperatorEntry entry, String named) {
        if (named.equalsIgnoreCase(entry.id())) {
            return true;
        }
        OperatorEntry.Keywords keywords = entry.keywords();
        return keywords != null
                && (named.equalsIgnoreCase(keywords.en()) || named.equalsIgnoreCase(keywords.de()));
    }

    private static Optional<String> firstMeaning(List<OperatorEntry> documenters) {
        return documenters.stream()
                .map(OperatorEntry::meaning)
                .filter(s -> s != null && !s.isBlank())
                .findFirst();
    }
}
