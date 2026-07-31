package io.github.mbackschat.a12.dm.rulekit.validate;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

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
 *   <li><b>cross-operator</b> — governed by one invariant: <b>an operator-specific {@code fix} or
 *       {@code explain} may be emitted only once exactly ONE owning operator has been established</b>
 *       ({@link #resolveOwner} — structured {@code where.operator} first, then whole-token identifier
 *       intersection over the message). Unestablished ownership yields a <em>code-level</em> corrective that
 *       enumerates the candidates and <b>no {@code explain} at all</b>.
 *       <p><b>Corrected 2026-07-31, twice.</b> First the old {@code findFirst} mis-stamped every non-first
 *       construct (probe F06-2 / dec-places), and the union fixed the {@code fix} — but kept a
 *       {@code firstMeaning()} {@code explain}, so an ambiguous join still asserted an owner: a
 *       {@code where.operator = LessThan} rejection was explained as "less than or equal". Then a first repair
 *       extracted the owner from the message with an {@code At '(\w+)'} regex, which is English-only — the
 *       default bundle renders {@code MVK_NO_CONST} as {@code "Kein Fehler definiert!"}, naming no operator —
 *       so it silently fell back to the ambiguous path it was meant to remove. Ownership is now evidence-based
 *       and localization-independent.</li>
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
            Optional<OperatorEntry> owner = resolveOwner(diagnostic, documenters);
            if (owner.isPresent()) {
                return diagnostic.withFix(owner.flatMap(DiagnosticEnricher::ownFix), owner.flatMap(DiagnosticEnricher::ownMeaning));
            }
            // OWNERSHIP UNESTABLISHED → a generic, code-level corrective that ENUMERATES the possibilities, and
            // deliberately NO explain: an operator meaning is a claim about which construct rejected, so
            // emitting one here would assert an ownership we could not establish.
            return diagnostic.withFix(genericFix(documenters), Optional.empty());
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
     * The <b>code-level</b> corrective used when no single owner could be established: documenters that AGREE
     * → their one shared fix (safe without ownership); documenters that DISAGREE → the enumerated
     * <em>union</em>, so the reader chooses rather than being mis-stamped. Sorted for a deterministic message.
     */
    private static Optional<String> genericFix(List<OperatorEntry> documenters) {
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
        // Every catalog corrective NAMES its operator, so even a single one is an ownership claim when nobody
        // established the owner. Hedge it rather than assert it — the catalog may simply not document the
        // sibling that actually rejected.
        String lead = distinct.size() == 1
                ? "this code is documented for the construct(s) below; the corrective applies if that is what you used — "
                : "this code is raised by more than one construct; the corrective depends on which you used — ";
        return Optional.of(lead + String.join("; ", distinct));
    }

    /**
     * The <b>single owning operator</b>, or empty when ownership cannot be established. Two evidence sources,
     * strongest first, and both must yield <em>exactly one</em> candidate:
     *
     * <ol>
     *   <li><b>structured</b> — {@code where.operator}, the AST operator id, when the producing stage set it;</li>
     *   <li><b>the message</b>, matched WITHOUT parsing its sentence: intersect the documenting candidates with
     *       the operator identifiers (id + either keyword) that occur in the summary as whole tokens.</li>
     * </ol>
     *
     * <p>The message must never be parsed by <em>sentence shape</em>. The kernel's text is localized per bundle
     * — {@code error_messages_en.properties} renders {@code MVK_NO_CONST} as {@code "At '%s'It is not allowed to
     * use constants."} while the default bundle renders it {@code "Kein Fehler definiert!"} with <b>no operator
     * at all</b> — so an {@code At '(\w+)'} extraction is an English-only accident that silently degrades
     * elsewhere. Token intersection degrades to "unestablished", which is honest, instead of to a wrong owner.
     */
    private static Optional<OperatorEntry> resolveOwner(Diagnostic diagnostic, List<OperatorEntry> documenters) {
        // NO sole-documenter shortcut. The catalog is a PARTIAL map of code → operators, so "exactly one entry
        // documents this code" is not evidence that this rejection came from that operator — it is equally
        // consistent with an undocumented sibling having raised it. Measured: the catalog documents
        // MVK_NOT_SORTABLE for MaxValue and not for MinValue, so a MinValue rejection was explained as "the
        // largest of the listed values". Ownership must be positively established or left unestablished.
        Optional<String> structured = diagnostic.where() == null ? Optional.empty() : diagnostic.where().operator();
        if (structured.isPresent()) {
            List<OperatorEntry> byId = documenters.stream()
                    .filter(entry -> namesOperator(entry, structured.get()))
                    .toList();
            if (byId.size() == 1) {
                return Optional.of(byId.get(0));
            }
        }
        List<OperatorEntry> mentioned = documenters.stream()
                .filter(entry -> mentionedIn(diagnostic.summary(), entry))
                .toList();
        return mentioned.size() == 1 ? Optional.of(mentioned.get(0)) : Optional.empty();
    }

    /** Whether any of {@code entry}'s identifiers occurs in {@code text} as a whole token (never a substring:
     *  {@code Abs} must not match inside {@code AbsValue}). */
    private static boolean mentionedIn(String text, OperatorEntry entry) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String identifier : identifiers(entry)) {
            if (java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(identifier) + "\\b")
                    .matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private static List<String> identifiers(OperatorEntry entry) {
        OperatorEntry.Keywords keywords = entry.keywords();
        return Stream.of(entry.id(), keywords == null ? null : keywords.en(), keywords == null ? null : keywords.de())
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .toList();
    }

    private static boolean namesOperator(OperatorEntry entry, String named) {
        return identifiers(entry).stream().anyMatch(named::equalsIgnoreCase);
    }

    private static Optional<String> ownFix(OperatorEntry entry) {
        OperatorEntry.InvalidExample example = entry.invalidExample();
        return example == null || example.fix() == null || example.fix().isBlank()
                ? Optional.empty() : Optional.of(example.fix());
    }

    private static Optional<String> ownMeaning(OperatorEntry entry) {
        return entry.meaning() == null || entry.meaning().isBlank() ? Optional.empty() : Optional.of(entry.meaning());
    }
}
