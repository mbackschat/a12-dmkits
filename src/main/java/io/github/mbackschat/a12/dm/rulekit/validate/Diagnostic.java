package io.github.mbackschat.a12.dm.rulekit.validate;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.mbackschat.a12.dm.core.Notification;
import io.github.mbackschat.a12.dm.core.Severity;

/**
 * One structured finding about a rule, modelled on LSP's {@code Diagnostic} so the same shape serves
 * the kernel ground-truth check (F4), the build-time pre-checks, and (later) the AST lint layer —
 * the lingua franca editors and agents already parse (PROJECT-DESIGN §F4a).
 *
 * <p>The fields answer <b>what / where / how / why</b>: {@code summary} (what), {@code where} (which
 * rule, plus the AST operator-id once read lands), {@code fix} (an applicable DSL replacement or
 * curated prose), {@code explain} (the operator meaning / rationale). {@code code} carries the
 * {@code MVK_*} (kernel) or {@code RK_*} (rulekit) identifier when classifiable.
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
     * A tool-internal failure (INTERNAL origin, ERROR): an unexpected throwable caught at the command
     * boundary — the tool itself broke (e.g. a native-image reflection gap), distinct from a kernel
     * rejection of valid-but-invalid input. The {@code fix} points at recovery; carries no {@code explain}.
     */
    public static Diagnostic internal(Optional<String> code, String summary, Where where, Optional<String> fix) {
        return new Diagnostic(Severity.ERROR, DiagnosticSource.INTERNAL, code, summary, where, fix, Optional.empty());
    }

    /**
     * A raw kernel notification, before catalog-join enrichment. The {@code MVK_*} code is extracted
     * from the message when present; {@code fix}/{@code explain} stay empty until
     * {@link DiagnosticEnricher} joins the catalog.
     */
    static Diagnostic fromKernel(Notification notification, String rulePath) {
        String message = notification.message() == null ? "" : notification.message();
        Matcher matcher = MVK.matcher(message);
        Optional<String> code = matcher.find() ? Optional.of(matcher.group()) : Optional.empty();
        return new Diagnostic(notification.severity(), DiagnosticSource.KERNEL, code, message,
                Where.rule(rulePath), Optional.empty(), Optional.empty());
    }

    /** This diagnostic with the catalog-joined {@code fix} and {@code explain} attached. */
    Diagnostic withFix(Optional<String> fix, Optional<String> explain) {
        return new Diagnostic(severity, source, code, summary, where, fix, explain);
    }
}
