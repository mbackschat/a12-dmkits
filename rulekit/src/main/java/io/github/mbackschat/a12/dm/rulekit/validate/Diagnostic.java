package io.github.mbackschat.a12.dm.rulekit.validate;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.mbackschat.a12.dm.core.Notification;
import io.github.mbackschat.a12.dm.core.Severity;
import io.github.mbackschat.a12.dm.core.Verification;

/**
 * One structured finding about a rule, modelled on LSP's {@code Diagnostic} so the same shape serves
 * the kernel ground-truth check (F4), the build-time pre-checks, and (later) the AST lint layer —
 * the lingua franca editors and agents already parse (PROJECT-DESIGN §F4a).
 *
 * <p>The fields answer <b>what / where / how / why</b>: {@code summary} (what), {@code where} (which
 * rule, plus the AST operator-id once read lands), {@code fix} (an applicable DSL replacement or
 * curated prose), {@code explain} (the operator meaning / rationale). {@code code} carries whichever class the
 * reporting engine named: from the kernel an {@code MVK_*} <em>or</em> an error-text class
 * ({@code INVALID_ENTITY}, {@code NO_BASE_YEAR}, … — see {@link #fromKernel}), from rulekit an {@code RK_*}.
 *
 * <p><b>Resolving a code's meaning (library consumers).</b> A {@code Diagnostic} is public surface — a dep
 * that drives rulekit/modelkit receives these directly (e.g. from {@link ValidationResult#diagnostics()}).
 * For an {@code RK_*} code, look up its meaning + canonical fix via {@link RkCode#byCode(String)} — the same
 * single-source registry the CLI {@code diagnostics} verb serves, so the library and the tool never diverge:
 * <pre>{@code
 * String meaning = diagnostic.code()
 *         .flatMap(RkCode::byCode)
 *         .map(RkCode::meaning)
 *         .orElse(diagnostic.summary());   // MVK_* codes are the kernel's — see the operator catalog
 * }</pre>
 */
public record Diagnostic(
        Severity severity,
        DiagnosticSource source,
        Optional<String> code,
        String summary,
        Where where,
        Optional<String> fix,
        Optional<String> explain) {

    private static final Pattern MVK = Pattern.compile("MVK_[A-Z0-9_]+");

    /**
     * The kernel's own way of naming the class it reported: {@code MVKErrorLookup.getErrorMessage} returns
     * {@code getBareErrorMessage(…) + " [" + displayKey + "]"}, so <b>every</b> classified notification ends
     * with its key in square brackets — {@code MVK_*} being one family of key among many.
     */
    private static final Pattern TERMINAL_CLASS = Pattern.compile("\\[([A-Z][A-Z0-9_]+)]\\s*$");

    /**
     * The kernel's rule attribution, likewise its own: {@code RankedNotificationUtils} prefixes a rule-scoped
     * notification with {@code "Rule with path '<fullName>': "}. Read only when the caller supplies no path.
     */
    private static final Pattern RULE_PREFIX = Pattern.compile("^Rule with path '([^']+)': ");

    public Diagnostic {
        code = code == null ? Optional.empty() : code;
        fix = fix == null ? Optional.empty() : fix;
        explain = explain == null ? Optional.empty() : explain;
    }

    /**
     * Where a diagnostic points. Either a rule-condition locus — a rule's {@code rule} path, the AST
     * {@code operator}-id, and a rendered-text {@code span} — or a structure locus — a model-element
     * {@code path} and/or an input {@code arg} (modelkit/structure diagnostics and arg-level errors,
     * which have no rule). Exactly one family is populated; the other fields stay empty.
     */
    public record Where(String rule, Optional<String> operator, Optional<Span> span,
            Optional<String> path, Optional<String> arg) {
        public Where {
            operator = operator == null ? Optional.empty() : operator;
            span = span == null ? Optional.empty() : span;
            path = path == null ? Optional.empty() : path;
            arg = arg == null ? Optional.empty() : arg;
        }

        /** Rule + operator + span (the rule-condition locus). */
        public Where(String rule, Optional<String> operator, Optional<Span> span) {
            this(rule, operator, span, Optional.empty(), Optional.empty());
        }

        /** Rule + operator, no span (the common case before span-pointing attaches one). */
        public Where(String rule, Optional<String> operator) {
            this(rule, operator, Optional.empty(), Optional.empty(), Optional.empty());
        }

        static Where rule(String rule) {
            return new Where(rule, Optional.empty());
        }

        /** A structure locus: a model-element path, no rule. */
        public static Where path(String path) {
            return new Where(null, Optional.empty(), Optional.empty(), Optional.of(path), Optional.empty());
        }

        /** A structure locus tied to an input arg (e.g. {@code --group}); {@code path} may be null. */
        public static Where arg(String path, String arg) {
            return new Where(null, Optional.empty(), Optional.empty(),
                    Optional.ofNullable(path), Optional.of(arg));
        }

        /** This locator with {@code span} attached (span-pointing, F4a). */
        public Where withSpan(Span span) {
            return new Where(rule, operator, Optional.of(span), path, arg);
        }
    }

    /** A build-time pre-check diagnostic (PRECHECK origin, ERROR) — what {@link PrecheckException} carries. */
    public static Diagnostic precheck(Optional<String> code, String summary, Where where,
            Optional<String> fix, Optional<String> explain) {
        return new Diagnostic(Severity.ERROR, DiagnosticSource.PRECHECK, code, summary, where, fix, explain);
    }

    /**
     * A pre-check diagnostic whose code and corrective come from the {@link RkCode} itself — the common shape,
     * so a call site names the code once instead of spelling {@code Optional.of(code.code())} beside
     * {@code Optional.of(code.fix())} and risking a mismatched pair.
     */
    public static Diagnostic precheck(RkCode code, String summary, Where where) {
        return precheck(Optional.of(code.code()), summary, where, Optional.of(code.fix()), Optional.empty());
    }

    /**
     * The same, with a corrective COMPUTED from the offending value instead of the code's generic one — for a
     * refusal where the code's {@code fix} would describe every rejected value and therefore none of them.
     *
     * <p>It exists because three call sites had begun spelling the conversion by hand
     * ({@code Optional.of(code.code())} beside {@code Optional.of(fix)}), which is both the mismatched-pair
     * hazard the overload above was created to remove and a five-argument call with four adjacent strings.
     */
    public static Diagnostic precheck(RkCode code, String summary, Where where, String fix) {
        return precheck(Optional.of(code.code()), summary, where, Optional.of(fix), Optional.empty());
    }

    /**
     * A <i>deferred</i> kernel-semantic pre-check (PRECHECK origin, WARNING) — advisory, not a
     * {@code build()} throw (F11): rulekit predicts a kernel judgment but lets the live kernel be the
     * authority, so a heuristic that drifts from a newer kernel can never be the sole reason a rule
     * the kernel accepts is reported corrupt.
     */
    public static Diagnostic precheckWarning(Optional<String> code, String summary, Where where,
            Optional<String> fix, Optional<String> explain) {
        return new Diagnostic(Severity.WARNING, DiagnosticSource.PRECHECK, code, summary, where, fix, explain);
    }

    /**
     * A consistency gate's refusal that produced <b>no notification</b> — the deserialize/expand tier, which
     * fails before {@code checkConsistency} can report an {@code MVK_*} code, so all it can carry is the
     * refusing engine's own message.
     *
     * <p>The origin is <b>derived from which gate ran</b> rather than fixed, because the two modes are refused
     * by different engines: with a backend the kernel deserializer refused ({@link DiagnosticSource#KERNEL}),
     * without one our kernel-free reader did ({@link DiagnosticSource#PRECHECK}). Hard-coding either makes the
     * envelope contradict its own {@code verification} in the other mode, which is exactly the defect this
     * replaces — a {@code PRECHECK}-sourced diagnostic published beside {@code verification: KERNEL_CONFIRMED}
     * for a refusal the kernel had in fact made.
     */
    public static Diagnostic gateRefusal(Verification gate, String summary, Where where) {
        DiagnosticSource source = switch (gate) {
            case KERNEL_CONFIRMED -> DiagnosticSource.KERNEL;
            case STRUCTURAL_ONLY -> DiagnosticSource.PRECHECK;
        };
        return new Diagnostic(Severity.ERROR, source, Optional.empty(), summary, where,
                Optional.empty(), Optional.empty());
    }

    /**
     * An <b>environment</b> failure (ENVIRONMENT origin, ERROR): the input was valid and the work was done, but
     * something outside the tool refused — an unwritable destination being the case this exists for.
     *
     * <p>The distinction is load-bearing in both directions. {@link DiagnosticSource#PRECHECK} would say the
     * caller's input was bad, and {@link DiagnosticSource#INTERNAL} would say the tool is broken and should be
     * reported as a defect; for a destination the caller chose, both send them to the wrong place. The other
     * ENVIRONMENT codes are advisories at INFO, so this is the first one that is a failure — the origin marks
     * WHERE the problem is, not how severe it is.
     */
    public static Diagnostic environmentFailure(Optional<String> code, String summary, Where where,
            Optional<String> fix) {
        return new Diagnostic(Severity.ERROR, DiagnosticSource.ENVIRONMENT, code, summary, where, fix,
                Optional.empty());
    }

    /**
     * A tool-internal failure (INTERNAL origin, ERROR): an unexpected throwable caught at the command
     * boundary — the tool itself broke (e.g. a native-image reflection gap), distinct from a kernel
     * rejection of valid-but-invalid input. The {@code fix} points at recovery; carries no {@code explain}.
     */
    public static Diagnostic internal(Optional<String> code, String summary, Where where, Optional<String> fix) {
        return new Diagnostic(Severity.ERROR, DiagnosticSource.INTERNAL, code, summary, where, fix, Optional.empty());
    }

    /**
     * A raw kernel notification, before catalog-join enrichment. The kernel's class and rule attribution are
     * recovered from the message by the kernel's own emission grammar (see {@link #TERMINAL_CLASS} /
     * {@link #RULE_PREFIX}); {@code fix}/{@code explain} stay empty until {@link DiagnosticEnricher} joins the
     * catalog.
     *
     * <p><b>Why the terminal bracket rather than a scan for {@code MVK_}.</b> Our {@link Notification} carries
     * only a severity and a message — the audit that introduced it proved those two are the whole consumed
     * surface — so any structure has to come out of the text. Reading {@code MVK_*} anywhere in the message
     * captured one family of key and silently dropped every other, which is how a rule's <em>error-text</em>
     * rejection ({@code INVALID_ENTITY}, {@code NO_BASE_YEAR}, {@code FIELD_NO_ENUM}, … — the kernel's
     * {@code FehlertextErrorType}) reached callers with no {@code code} at all. The bracket is not a heuristic
     * over observed messages: it is where {@code MVKErrorLookup} puts the key, for every key.
     *
     * <p>The {@code MVK_} scan stays as the fallback for a message that carries a code but no terminal bracket
     * (the {@code getBareErrorMessage} paths compose one message out of several), so the change can only add a
     * classification, never remove one.
     */
    static Diagnostic fromKernel(Notification notification, String rulePath) {
        String message = notification.message() == null ? "" : notification.message();
        return new Diagnostic(notification.severity(), DiagnosticSource.KERNEL, kernelClass(message), message,
                Where.rule(rulePath != null ? rulePath : attributedRule(message)),
                Optional.empty(), Optional.empty());
    }

    /** The class the kernel named: its terminal bracketed key, else the last embedded {@code MVK_*}. */
    private static Optional<String> kernelClass(String message) {
        Matcher terminal = TERMINAL_CLASS.matcher(message);
        if (terminal.find()) {
            return Optional.of(terminal.group(1));
        }
        Matcher embedded = MVK.matcher(message);
        String last = null;
        while (embedded.find()) {
            last = embedded.group();
        }
        return Optional.ofNullable(last);
    }

    /** The rule the kernel attributed the notification to, or {@code null} when it named none. */
    private static String attributedRule(String message) {
        Matcher prefix = RULE_PREFIX.matcher(message);
        return prefix.find() ? prefix.group(1) : null;
    }

    /** This diagnostic with the catalog-joined {@code fix} and {@code explain} attached. */
    Diagnostic withFix(Optional<String> fix, Optional<String> explain) {
        return new Diagnostic(severity, source, code, summary, where, fix, explain);
    }
}
