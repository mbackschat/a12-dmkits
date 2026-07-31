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
 *       ({@link #resolveOwner} — structured {@code where.operator} first, then the operand the kernel NAMED,
 *       via {@link KernelMessageOperands}). Unestablished ownership yields a <em>code-level</em> corrective that
 *       enumerates the candidates and <b>no {@code explain} at all</b>.
 *       <p><b>Corrected 2026-07-31, four times — each repair narrowing the previous case and leaving the next.</b>
 *       (1) {@code findFirst} stamped whichever documenter came first in {@code operators.json}, mis-stamping every
 *       other construct (probe F06-2 / dec-places). (2) The union fixed the {@code fix} but kept a
 *       {@code firstMeaning()} {@code explain}, so an ambiguous join still asserted an owner — a
 *       {@code where.operator = LessThan} rejection was explained as "less than or equal". (3) A
 *       {@code documenters.size() == 1} shortcut treated a sole documenter as proven, though the catalog is a
 *       PARTIAL map: a {@code MinValue} rejection got MaxValue's meaning. (4) Whole-message identifier matching let
 *       the kernel's own <em>boilerplate</em> elect an operator. (5) The repair for (4) then <em>widened</em> the
 *       electable set from the documenters to the whole catalog, on the reasoning that whoever the kernel names is
 *       who rejected — which was worse than (4): the kernel quotes <b>values</b> too, so an enum comparison against
 *       the S-value {@code "Max"} elected the {@code Max} operator, explained an enum-value error with {@code Max}'s
 *       meaning, and suppressed the correct corrective. Election is once again restricted to the documenters, and
 *       {@link KernelMessageOperands} can now only ever NARROW a candidate set the caller supplies.
 *       <p>An intermediate repair was also justified by a <b>false</b> claim, recorded here as measured: that the
 *       default bundle renders {@code MVK_NO_CONST} as {@code "Kein Fehler definiert!"} and so names no operator.
 *       That is true only of the base stub {@code error_messages.properties}; the populated
 *       {@code error_messages_de.properties} shipped in {@code kernel-core-parser} reads
 *       {@code "Bei '%s' darf keine Konstante verwendet werden."} and <em>does</em> name it — verified live on a
 *       {@code de_DE} model, where ownership resolved correctly. (The file is ISO-8859-1, so a plain {@code grep}
 *       treats it as binary and finds nothing, which is the likely origin of the error.) The <em>conclusion</em>
 *       stands on other grounds — never parse a localized sentence — but the evidence for it did not.</li>
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
                // The owner is one of the documenters (resolveOwner cannot return anything else), so both its curated
                // fix and its meaning are about THIS code.
                return diagnostic.withFix(owner.flatMap(DiagnosticEnricher::ownFix),
                        owner.flatMap(DiagnosticEnricher::ownMeaning));
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
     *   <li><b>the operand the kernel NAMED in its message</b> — see {@link KernelMessageOperands}.</li>
     * </ol>
     *
     * <p>Resolution runs <b>only among the code's documenters</b>. Widening it to the whole catalog — on the
     * reasoning that whoever the kernel names is who rejected — produced a worse defect than the one it fixed: the
     * kernel quotes <em>values</em> as well as operators, so an enum comparison against the S-value {@code "Max"}
     * elected the {@code Max} operator, explained an enum-value error with {@code Max}'s meaning, and suppressed the
     * correct corrective. Restricting the electable set is the safety property; see {@link KernelMessageOperands}.
     *
     * <p>The message must never be parsed by <em>sentence shape</em>: the kernel's text is a localized properties
     * value, so any {@code At '(\w+)'}-style extraction is an accident of one bundle. Matching the quoted operand is
     * strictly better than parsing its sentence — though NOT localization-independent, and not the whole basis:
     * see {@link KernelMessageOperands} for what the delimiters do and do not establish.
     */
    private Optional<OperatorEntry> resolveOwner(Diagnostic diagnostic, List<OperatorEntry> documenters) {
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
        List<OperatorEntry> named = KernelMessageOperands.namedAmong(catalog, diagnostic.summary(), documenters);
        return named.size() == 1 ? Optional.of(named.get(0)) : Optional.empty();
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
