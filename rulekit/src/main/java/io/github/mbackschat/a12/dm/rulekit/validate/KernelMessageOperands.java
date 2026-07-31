package io.github.mbackschat.a12.dm.rulekit.validate;

import java.util.ArrayList;
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
 * <p>A kernel message is a localized template with its operand substituted in, and the kernel <b>delimits what it
 * substitutes</b>: of the 148 templates in {@code kernel-core-parser}'s bundle that carry a {@code %s}, 146 render it
 * as {@code '%s'}, and the two that do not substitute a group path or a fractional-digit count — never an operator
 * name. So the message splits cleanly in two:
 *
 * <ul>
 *   <li><b>inside</b> the delimiters — the operand the kernel actually saw. Evidence about this invocation;</li>
 *   <li><b>outside</b> them — fixed prose, character-identical for every occurrence of the code. Evidence about
 *       nothing.</li>
 * </ul>
 *
 * <p><b>Why this is its own class.</b> Ignoring that split is a defect two consumers made independently, so the
 * mechanism and its measurement live in one place rather than being re-derived per caller. Searching the <em>whole</em>
 * message lets the kernel's boilerplate name a culprit: {@code MVK_INVALID_COMPARE_TO_YESNO}'s text ends "…or with the
 * constants <b>True</b> or False", and {@code True} is an operator the catalog documents for that very code — so a
 * boolean-versus-string comparison, which never mentions {@code True}, was explained as "the boolean constant 'true'".
 * That message has no {@code %s} at all, so it contained nothing about the culprit to read.
 * {@code MVK_NO_INDEX_FIELD} has the same shape through {@code For}, {@code SemanticIndex}'s English keyword.
 *
 * <p>Reading only the delimited span is also <b>localization-independent</b>, which the alternatives are not: every
 * bundle inherits the same {@code '%s'} from the template, whereas any sentence-shape extraction
 * ({@code At '(\w+)'}) is an accident of one language's wording.
 *
 * <p>The two consumers use it for different strengths of claim, and both are sound:
 * {@link DiagnosticEnricher} has nothing but the message, so a named operator <b>elects</b> the owner;
 * {@code lint.SpanResolver} additionally holds the AST, so there the message <b>vetoes</b> an inference it
 * contradicts. A veto can only ever withhold a claim, never manufacture one.
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
     * The catalog operators {@code message} <b>names</b> — its delimited operands resolved through the catalog by id
     * or either keyword. Empty when the kernel named no operator, which is the common case and must degrade to
     * "unknown", never to a guess.
     */
    public static List<OperatorEntry> namedOperators(OperatorCatalog catalog, String message) {
        return operands(message).stream()
                .map(token -> catalog.byId(token).or(() -> catalog.byKeyword(token)))
                .flatMap(Optional::stream)
                .distinct()
                .toList();
    }

    /**
     * Whether {@code message} names a known operator and {@code candidate} is not among them — i.e. the message
     * <b>contradicts</b> attributing the diagnostic to {@code candidate}.
     *
     * <p>This is the veto. It is deliberately one-directional: naming nobody is not a contradiction (so an inference
     * drawn from other evidence survives), while naming somebody else is.
     */
    public static boolean contradicts(OperatorCatalog catalog, String message, OperatorEntry candidate) {
        List<OperatorEntry> named = namedOperators(catalog, message);
        return !named.isEmpty() && !named.contains(candidate);
    }
}
