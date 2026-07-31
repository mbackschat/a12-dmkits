package io.github.mbackschat.a12.dm.rulekit.validate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog;
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorEntry;

/**
 * What a kernel diagnostic's message says about <b>this</b> invocation — as opposed to what it says about the code in
 * general.
 *
 * <p>A kernel message is a localized template with its operands substituted in, and the kernel usually
 * <b>delimits what it substitutes</b> — {@code At '%s': …}. So the message splits roughly in two: inside the
 * delimiters is what the kernel actually saw on this invocation, outside them is fixed prose that is
 * character-identical for every occurrence of the code and therefore evidence about nothing.
 *
 * <p><b>"Roughly" is doing real work, and the limits are measured, not assumed</b> (corrected 2026-07-31 after a
 * first version overstated them):
 *
 * <ul>
 *   <li>the split is <b>not exhaustive</b> — in the EN bundle 144 templates carry a {@code %s} and 140 delimit it;
 *       one exception, {@code MVK_GROUP_NOT_REPEATABLE}'s {@code Corrupt: %s.}, belongs to a catalog-documented
 *       operator;</li>
 *   <li>it is <b>not localization-independent</b>, which an earlier version of this class claimed. Delimiters are
 *       hand-typed per language and already disagree: {@code MVK_MAX_GROUP_RESTRICTION_TO_ONE} and
 *       {@code MVK_INTERNAL_ERROR} drop them in DE, and {@code MVK_SEMANTIC_INDEX_AND_WILDCARD} DE writes
 *       {@code ' %s'} with a leading space;</li>
 *   <li>most importantly, a delimited span is <b>not necessarily an operator</b>. The kernel quotes <em>values</em>
 *       too — {@code MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION} is
 *       {@code At '%s': '%s' is not valid for enumeration '%s'.}, so two of its three quoted spans are an author's
 *       enum S-value and an enum name — and it quotes fixed prose as well: DE {@code MVK_INVALID_CONST} prints
 *       {@code 'Wahr'} and {@code 'Falsch'}, which are {@code True}/{@code False}'s German keywords, and
 *       {@code MVK_NO_BASE_YEAR} quotes {@code 'BaseYear'} with no {@code %s} in the template at all.</li>
 * </ul>
 *
 * <p><b>Hence the API shape:</b> there is no "which operator does this message name" method. A caller must supply the
 * <em>candidates</em> it already has independent grounds to consider, and the message can only ever narrow that set.
 * That is what keeps a quoted enum value from electing an operator: {@code "Max"} is a perfectly ordinary S-value,
 * and nothing in the message distinguishes it from the {@code Max} operator's name.
 *
 * <p><b>Why this is its own class.</b> Ignoring that split is a defect two consumers made independently, so the
 * mechanism and its measurement live in one place rather than being re-derived per caller. Searching the <em>whole</em>
 * message lets the kernel's boilerplate name a culprit: {@code MVK_INVALID_COMPARE_TO_YESNO}'s text ends "…or with the
 * constants <b>True</b> or False", and {@code True} is an operator the catalog documents for that very code — so a
 * boolean-versus-string comparison, which never mentions {@code True}, was explained as "the boolean constant 'true'".
 * That message has no {@code %s} at all, so it contained nothing about the culprit to read.
 * {@code MVK_NO_INDEX_FIELD} has the same shape through {@code For}, {@code SemanticIndex}'s English keyword.
 *
 * <p>Reading a delimited span is still strictly better than parsing the sentence — an {@code At '(\w+)'} extraction
 * is an accident of one language's wording, while the delimiters at least travel with most templates. It is simply
 * not the *independent* mechanism the first version of this class claimed, which is why the candidate set above,
 * rather than the delimiters, carries the soundness.
 *
 * <p>The two consumers pass different candidate sets, which is what makes each sound:
 * {@link DiagnosticEnricher} passes the code's catalog <em>documenters</em>, so the message can only pick among
 * operators already known to raise this code; {@code lint.SpanResolver} passes the operators the condition's AST
 * actually <em>uses</em>, and uses the result to <b>veto</b> a pin rather than to make one.
 */
public final class KernelMessageOperands {

    /** Single- or double-quoted spans; the kernel uses both ({@code '%s'} overwhelmingly, {@code "for %s"} once). */
    private static final Pattern DELIMITED = Pattern.compile("'([^']*)'|\"([^\"]*)\"");

    private KernelMessageOperands() {
    }

    /** The delimited substrings of {@code message}, in order — the operands the kernel substituted. */
    public static List<String> operands(String message) {
        if (message == null || message.isBlank()) {
            return List.of();
        }
        List<String> operands = new ArrayList<>();
        Matcher m = DELIMITED.matcher(message);
        while (m.find()) {
            String token = m.group(1) != null ? m.group(1) : m.group(2);
            if (token != null && !token.isBlank()) {
                operands.add(token.strip());
            }
        }
        return operands;
    }

    /**
     * Those of {@code candidates} that {@code message} names in a delimited span — a <b>narrowing</b> of a set the
     * caller already had grounds to consider, never an election out of the whole catalog.
     *
     * <p>The restriction is the safety property, and removing it caused a live defect. A version of this method
     * resolved every delimited span against the entire catalog, on the reasoning that "whoever the kernel names is
     * who rejected". But the kernel also quotes values: an enum comparison against the S-value {@code "Max"} produces
     * {@code 'Max' is not valid in the used enumeration.}, which then elected the {@code Max} operator, published its
     * meaning as the explanation of an enum-value error, and suppressed the correct {@code EnumCategory} corrective —
     * strictly worse on both fields than the ambiguous-but-honest behaviour it replaced. Values that collide with
     * operator names are ordinary ({@code Max}, {@code Min}, {@code Sum}, {@code Valid}, {@code Date}, {@code Length}),
     * so this is not an exotic case.
     */
    public static List<OperatorEntry> namedAmong(OperatorCatalog catalog, String message,
            Collection<OperatorEntry> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        return operands(message).stream()
                .map(token -> catalog.byId(token).or(() -> catalog.byKeyword(token)))
                .flatMap(Optional::stream)
                .filter(candidates::contains)
                .distinct()
                .toList();
    }

    /**
     * Whether {@code message} names, among {@code corroborated}, some operator other than {@code candidate} — i.e. the
     * message <b>contradicts</b> attributing the diagnostic to {@code candidate}.
     *
     * <p>The veto is one-directional by design: naming nobody is not a contradiction, so an inference drawn from
     * other evidence survives, while naming somebody else withholds it. A veto can only ever remove a claim.
     *
     * <p>{@code corroborated} is what makes it sound, and it must be a set the caller established
     * <em>independently of the message</em> — {@code lint.SpanResolver} passes the operators the condition's AST
     * actually uses. Without that, a quoted enum S-value spelled like an operator name would veto a correct pin: the
     * message alone cannot tell an operator from a value.
     */
    public static boolean contradicts(OperatorCatalog catalog, String message, OperatorEntry candidate,
            Collection<OperatorEntry> corroborated) {
        List<OperatorEntry> named = namedAmong(catalog, message, corroborated);
        return !named.isEmpty() && !named.contains(candidate);
    }
}
