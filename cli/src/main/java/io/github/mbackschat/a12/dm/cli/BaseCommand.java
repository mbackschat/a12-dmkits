package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.core.Notification;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.core.UnresolvedReferenceException;
import io.github.mbackschat.a12.dm.interpreter.DocumentInputDiagnostic;
import io.github.mbackschat.a12.dm.interpreter.DocumentInputError;
import io.github.mbackschat.a12.dm.interpreter.DocumentInputReasonCode;
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationDiagnostic;
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationError;
import io.github.mbackschat.a12.dm.interpreter.model.UndeclaredReference;
import io.github.mbackschat.a12.dm.modelkit.ModelTimeZoneContract;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.KernelDiagnostics;
import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Shared base for the picocli verb commands: gives each one its {@code out}/{@code err} writers (from
 * the {@link Cli#run} seam, via picocli) and the exit-2 {@code fail} helper, so a verb's class holds
 * only its options + its work. The {@code @Command} here is just to host the injected {@code @Spec}
 * (picocli-codegen requires it); the abstract base is never registered as a runnable command.
 */
@Command
public abstract class BaseCommand implements Callable<Integer> {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The model path an instance still carrying the retired {@code "fields"} wrapper reports as unknown. */
    private static final String LEGACY_FIELDS_WRAPPER_PATH = "/fields";

    @Spec
    protected CommandSpec spec;

    protected PrintWriter out() {
        return spec.commandLine().getOut();
    }

    protected PrintWriter err() {
        return spec.commandLine().getErr();
    }

    /** The current ordinary two-token verb, derived from its injected live picocli leaf. */
    protected final ResultIdentity resultIdentity() {
        return ResultIdentity.from(spec);
    }

    /** Print a diagnostic to stderr and return exit code 2 (usage / IO error). */
    protected int fail(String message) {
        err().println(message);
        return 2;
    }

    /** Whether the caller explicitly supplied any named option, including an explicit boolean {@code false}. */
    protected final boolean optionMatched(String... names) {
        for (String name : names) {
            if (spec.commandLine().getParseResult().hasMatchedOption(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The DM-JSON at {@code modelPath}, or {@code null} after printing the corrective failure to stderr (the
     * caller then returns exit 2). Centralizes the read-the-model-file step every mutating verb opens with —
     * a missing {@code -m} ({@code modelPath == null}) and an unreadable file both fail with a clear message.
     */
    protected String readModelJson(String modelPath) {
        if (modelPath == null) {
            fail("pass -m <model.json> (before or after the verb)");
            return null;
        }
        try {
            return Files.readString(Path.of(modelPath));
        } catch (IOException e) {
            fail("cannot read model: " + e.getMessage());
            return null;
        }
    }

    /**
     * The document instance at {@code instancePath} as text, or {@code null} after printing the corrective
     * failure (the caller then returns exit 2). Deliberately <b>unparsed</b>: the runtime verbs hand the text
     * straight to the interpreter's canonical A12 Document JSON codec, which is the only reader of that shape in
     * the tool. This is the model-file helper's twin, so the three runtime verbs cannot drift on either input.
     */
    protected String readInstanceJson(String instancePath) {
        try {
            return Files.readString(Path.of(instancePath));
        } catch (IOException e) {
            fail("cannot read document: " + e.getMessage());
            return null;
        }
    }

    /**
     * Print every defect the A12 Document JSON reader found, then exit 2. An invalid <i>input file</i> stays on
     * the plain-text {@code fail} channel that {@code cannot read model:} already uses — a structured refusal is
     * for a precheck on a resolvable target, not for input the tool could not read at all.
     *
     * <p>The one enriched case is the retired {@code {"fields": {…}}} wrapper: a document that still carries it
     * fails as an unknown model path {@code /fields}, which is true but unhelpful, so it also gets the migration
     * corrective. Clean break plus a corrective error — never a silent unwrap.
     */
    protected int failInvalidInstance(DocumentInputError error) {
        boolean legacyWrapper = error.getDiagnostics().stream().anyMatch(d ->
                d.getReasonCode() == DocumentInputReasonCode.UNKNOWN_MODEL_PATH
                        && LEGACY_FIELDS_WRAPPER_PATH.equals(d.getModelPath()));
        String detail = error.getDiagnostics().stream()
                .map(DocumentInputDiagnostic::getMessage)
                .collect(java.util.stream.Collectors.joining("; "));
        return fail("invalid document: " + detail + (legacyWrapper
                ? " — the `{\"fields\": {…}}` wrapper is gone; a document instance is now A12's canonical "
                        + "Document JSON, so a root group name is a top-level key. Unwrap the tree "
                        + "(`dmtool schema model eval` shows the shape)."
                : ""));
    }

    /**
     * A no-specifier read: the lean <b>name-index</b> of a kind — {@code data.<plural>} is the full-name list,
     * plus a {@code count}. The uniform "no path = list everything in scope" half of the read contract
     * (CLI-SPEC §5), shared by {@code rule}/{@code computation}/{@code field}/{@code group read}. Deliberately
     * lean (names only): it is the navigation handle to then {@code <target> read <name>} for one — distinct
     * from {@code model read} (full DSL of everything) and {@code export} (a takeaway artifact).
     */
    protected int readNameIndex(String plural, Collection<String> names, ConsistencyEvidence evidence) {
        ResultIdentity identity = resultIdentity();
        ObjectNode data = JSON.createObjectNode();
        ArrayNode arr = data.putArray(plural);
        names.forEach(arr::add);
        data.put("count", names.size());
        String summary = names.size() + " " + identity.target() + (names.size() == 1 ? "" : "s");
        // Evidence is the CALLER's, not this helper's: `rule`/`computation read` reach their names through a gated
        // KernelAdapter while `field`/`group read` only parse the document. One shared payload shape, two honest
        // provenance answers — a single hard-coded value here would have been wrong for one pair or the other.
        ResultEnvelope env = ResultEnvelope.queryResult(identity, summary, data, evidence);
        out().print(env.toJsonString());
        return env.exitCode();
    }

    /**
     * Emit a {@code refused} envelope from a refactor safety-gate {@link Refusal} (CLI-SPEC §6): the
     * structured {@code data} (e.g. the referrers) + the corrective diagnostic, nothing written, exit 2.
     */
    protected int refuse(Refusal refusal) {
        return refuse(resultIdentity(), refusal);
    }

    /** Exceptional one-token/multi-mode counterpart whose identity cannot be derived from a two-token leaf. */
    protected int refuse(ResultIdentity identity, Refusal refusal) {
        ResultEnvelope env = ResultEnvelope.refused(identity, refusal.summary(), refusal.data(),
                refusal.diagnostics());
        out().print(env.toJsonString());
        return env.exitCode();
    }

    /**
     * Emit a {@code refused} envelope for a READ whose target element does not exist — the structured,
     * corrective form a missing read target must take (an {@code RK_NO_SUCH_*} code, a {@code where.path}, a
     * {@code fix}), so it is machine-readable like the rest of the surface and never a plain-text {@code fail}.
     * The read verb has already detected non-existence; this only emits (exit 2, nothing written).
     */
    protected int refuseNotFound(RkCode code, String summary, String path, String fix) {
        return refusePrecheck(resultIdentity(), code, summary, path, fix);
    }

    /**
     * Emit a coded {@code refused} envelope for a precheck the CLI itself detected (not a kernel diagnostic): a
     * structured {@code RK_*} code, an optional {@code where.path}, and a corrective {@code fix}, so an agent
     * reads it like the rest of the surface — never a plain-text {@code fail}. Exit 2, nothing written.
     */
    protected int refusePrecheck(ResultIdentity identity, RkCode code, String summary, String path, String fix) {
        Diagnostic diagnostic = Diagnostic.precheck(Optional.of(code.code()), summary,
                Diagnostic.Where.path(path), Optional.of(fix), Optional.empty());
        return refuse(identity, new Refusal(summary, null, diagnostic));
    }

    protected int refusePrecheck(RkCode code, String summary, String path, String fix) {
        return refusePrecheck(resultIdentity(), code, summary, path, fix);
    }

    /**
     * Refuse a RUNTIME evaluation whose model or candidate names entities it does not declare (G46) — one
     * diagnostic per reference, so a consumer repairs every one of them in a single pass instead of discovering
     * the next only after fixing the last. Exit 2, nothing evaluated and nothing written.
     *
     * <p>A refusal rather than a degraded answer because there is no honest answer to give: the missing cell
     * reads as empty in a presence position (so the rule FIRES), collapses to an empty row domain in a starred
     * position (so the rule PASSES), and has no reading at all in a value position. The kernel rejects the same
     * model as {@code MVK_INVALID_ENTITY}, so this refusal is not stricter than the kernel — it is the same
     * verdict, reached without one.
     */
    protected int refuseUndeclaredReferences(List<UndeclaredReference> undeclared) {
        String summary = undeclared.size() == 1
                ? "'" + undeclared.get(0).getPath() + "' is not declared by this model — refusing to evaluate"
                : undeclared.size() + " references name entities this model does not declare — refusing to evaluate";
        List<Diagnostic> diagnostics = undeclared.stream()
                .map(reference -> Diagnostic.precheck(
                        Optional.of(RkCode.UNDECLARED_ENTITY_REFERENCE.code()),
                        reference.getLocus() + " references '" + reference.getPath()
                                + "', which this model does not declare",
                        Diagnostic.Where.path(reference.getPath()),
                        Optional.of(RkCode.UNDECLARED_ENTITY_REFERENCE.fix()),
                        Optional.of(RkCode.UNDECLARED_ENTITY_REFERENCE.meaning())))
                .toList();
        ResultEnvelope env = ResultEnvelope.refused(resultIdentity(), summary, null, diagnostics);
        out().print(env.toJsonString());
        return env.exitCode();
    }

    /** A coded precheck whose catalog meaning is part of the corrective contract, not just its lookup key. */
    protected int refuseExplainedPrecheck(RkCode code, String summary, String path, String fix) {
        Diagnostic diagnostic = Diagnostic.precheck(Optional.of(code.code()), summary,
                Diagnostic.Where.path(path), Optional.of(fix), Optional.of(code.meaning()));
        return refuse(new Refusal(summary, null, diagnostic));
    }

    /**
     * The {@code -m/--model} context option, read from the root command. It is an inherited option, so it
     * binds to {@link RootCommand} wherever it appears — before or after the verb. {@code null} if absent.
     */
    protected String model() {
        return spec.root().userObject() instanceof RootCommand root ? root.model : null;
    }

    /**
     * Resolve a {@code workspace} verb's scan directory under the same uniformity rule the rest of the CLI
     * follows: the explicit positional {@code <dir>} if given, else the inherited {@code -w/--workspace} root,
     * else {@code $DMTOOL_WORKSPACE} — so an agent that set {@code -w} once can drive {@code workspace} without
     * repeating the path positionally (probe 01-de Finding 5). Empty when none is resolvable; the caller emits
     * the corrective. (Unlike {@code -m}, the positional {@code <dir>} stays — it is the verb's natural scan
     * target — and {@code -w} is the session-default fallback, not a replacement.)
     */
    protected Optional<String> workspaceDir(String positional) {
        if (positional != null) {
            return Optional.of(positional);
        }
        List<Path> roots = spec.root().userObject() instanceof RootCommand root ? root.workspace : List.of();
        if (!roots.isEmpty()) {
            return Optional.of(roots.get(0).toString());
        }
        String env = System.getenv(WorkspaceOption.WORKSPACE_ENV);
        return env != null && !env.isBlank() ? Optional.of(env) : Optional.empty();
    }

    /**
     * Strict input gate: if {@code spec} carries a key the {@code schema/<schemaName>.schema.json} does not
     * declare, omits a required property, or carries a declared key with a wrong-typed/shaped value, emit a {@code rejected}
     * envelope of {@code RK_UNKNOWN_ARG} / {@code RK_INVALID_VALUE} diagnostics (with a did-you-mean) and return
     * its exit code; otherwise {@link Optional#empty()} so the caller proceeds. The single defense against a
     * typo'd/out-of-scope key, missing required value, or mis-shaped value being silently dropped/defaulted into
     * a false {@code applied}.
     */
    protected Optional<Integer> rejectInvalidSpec(String schemaName, JsonNode spec) {
        return rejectInvalidSpec(resultIdentity(), schemaName, spec);
    }

    /** One-token/multi-mode counterpart whose identity is intentionally named rather than leaf-derived. */
    protected Optional<Integer> rejectInvalidSpec(ResultIdentity identity, String schemaName, JsonNode spec) {
        List<Diagnostic> problems = SpecKeys.forSchema(schemaName).validate(spec).stream()
                .map(diagnostic -> MetadataRelocation.correct(identity.target(), identity.op(), diagnostic))
                .toList();
        if (problems.isEmpty()) {
            return Optional.empty();
        }
        ResultEnvelope env = ResultEnvelope.rejected(identity, specProblemSummary(problems), problems,
                RejectionBasis.preflight());
        out().print(env.toJsonString());
        return Optional.of(env.exitCode());
    }

    /**
     * Summarize a spec-validation rejection by the KIND of problem, so the {@code summary} tracks the actual
     * diagnostics — an unrecognized key vs a wrong-typed/shaped value — instead of always claiming "unrecognized
     * key" (probe 07: a JSON array handed to a single-object spec verb is an {@code RK_INVALID_VALUE}, not an
     * unknown key, yet the summary mislabeled it).
     */
    private static String specProblemSummary(List<Diagnostic> problems) {
        long invalidValues = problems.stream()
                .filter(d -> d.code().map(RkCode.INVALID_VALUE.code()::equals).orElse(false))
                .count();
        long unknownKeys = problems.size() - invalidValues;
        List<String> parts = new ArrayList<>();
        if (unknownKeys > 0) {
            parts.add(unknownKeys + (unknownKeys == 1 ? " unrecognized key" : " unrecognized keys"));
        }
        if (invalidValues > 0) {
            parts.add(invalidValues + (invalidValues == 1 ? " invalid value" : " invalid values"));
        }
        return "the spec has " + String.join(" and ", parts);
    }

    /**
     * Emit a {@code rejected} result envelope from a kernel consistency failure — the {@code MVK_*}
     * notifications surfaced as enriched {@link Diagnostic}s (catalog-joined fix/explain). Shared by the
     * modelkit edit verbs, whose {@code EditSession.validate()} funnels both kernel tiers into this exception.
     */
    protected int emitKernelRejection(String summary, ModelConsistencyException e) {
        return emitKernelRejection(resultIdentity(), summary, e);
    }

    /** Exceptional one-token/multi-mode counterpart whose identity cannot be derived from a two-token leaf. */
    protected int emitKernelRejection(ResultIdentity identity, String summary, ModelConsistencyException e) {
        ResultEnvelope env = kernelRejectionEnvelope(identity, summary, e);
        out().print(env.toJsonString());
        return env.exitCode();
    }

    /**
     * The one ending every model-editing verb shares: preview under {@code --dry-run}, else write and report.
     *
     * <p>Thirty-three verbs hand-wrote this — identical destination resolution, identical {@code Files.writeString},
     * identical failure text — differing only in the payload {@link ModelWrite} now carries. That is the shape of
     * duplication that has already cost this project twice over (the gate-rejection mapping drifted into three
     * versions; threading the receipt had to visit every copy), so it is collapsed rather than documented.
     *
     * <p>The write failure stays a plain {@link #fail}: an unwritable destination is an environment problem, not
     * an input rejection and not a consistency verdict, so it must not acquire either shape on the way out.
     */
    protected int emitMutation(ModelWrite write) {
        write.requireComplete();   // before any side effect: an incomplete record must not leave a model behind
        if (write.previewOnly()) {
            return print(write.preview());
        }
        try {
            Files.writeString(Path.of(write.destination()), write.json());
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        return print(write.applied());
    }

    /** Print an envelope and return its exit code — the one place a result reaches stdout. */
    protected int print(ResultEnvelope env) {
        out().print(env.toJsonString());
        return env.exitCode();
    }

    /**
     * Preserve input-domain rejections caught by a broad command-local {@link RuntimeException} boundary.
     * Strict model readers still use their local failure text for ordinary parse/render failures, but an invalid
     * stored model or unresolved model reference must escape to {@link Cli}'s single structured-envelope mapper.
     * Use this only around loading the caller-supplied model, never around validation of an edited candidate.
     */
    protected int failOrRethrowDomainRejection(String messagePrefix, RuntimeException failure) {
        if (failure instanceof ModelConsistencyException || failure instanceof UnresolvedReferenceException) {
            throw failure;
        }
        return fail(messagePrefix + failure.getMessage());
    }

    /**
     * The edit-gate twin of {@link #failOrRethrowDomainRejection}: fail with the gate's own message, but let an
     * {@link UnresolvedReferenceException} escape to {@link Cli}'s seam, which owns its documented contract
     * (a corrective {@code RK_UNRESOLVED_REFERENCE} rejection at exit 1 — CLI-SPEC §"exception seam").
     *
     * <p><b>Why a second helper rather than the one above.</b> An edit command must keep handling
     * {@link ModelConsistencyException} <em>itself</em>, because the rejection of an edited candidate is the
     * result it reports — so it cannot use a helper that rethrows that type. What it must not do is swallow a
     * <em>different</em> rejection into an unstructured {@code fail}, which is what a bare
     * {@code catch (RuntimeException e) -> fail(e.getMessage())} did at all 23 edit gates: a plain stderr line at
     * exit 2 in place of the corrective envelope the same model gets on the read path. Before that, the wrap in
     * {@code ModelDocument.validate} had hidden the whole class by relabelling our resolver's refusal as a
     * consistency failure — which is why the two fixes only make sense together.
     */
    protected int failEditGate(RuntimeException failure) {
        if (failure instanceof UnresolvedReferenceException) {
            throw failure;
        }
        return fail(failure.getMessage());
    }

    /**
     * Build (don't print) the {@code rejected} envelope for a kernel consistency failure — its {@code MVK_*}
     * notifications as enriched {@link Diagnostic}s. Static + shared so the {@link Cli} execution-exception
     * handler can map an <em>escaped</em> {@link ModelConsistencyException} (a verb that LOADS an
     * invalid-on-disk model — probe 01-de Finding 7) to the same structured envelope, instead of letting it
     * surface as a raw stack trace.
     */
    static ResultEnvelope kernelRejectionEnvelope(ResultIdentity identity, String summary, ModelConsistencyException e) {
        ConsistencyEvidence evidence = ConsistencyEvidence.of(e);
        return ResultEnvelope.rejected(identity, summary, gateRejectionDiagnostics(e, evidence),
                RejectionBasis.kernel(evidence));
    }

    /**
     * A consistency gate's rejection as diagnostics — its enriched {@code MVK_*} notifications, or, when the gate
     * refused <em>before</em> {@code checkConsistency} could name one, a single {@link Diagnostic#gateRefusal} carrying
     * the gate's own message.
     *
     * <p><b>Extracted because three copies had drifted apart, and each lost something different.</b>
     * {@code kernelRejectionEnvelope} handled the notification-free tier as a {@code PRECHECK} diagnostic — a
     * rulekit-precheck origin published beside {@code verification: KERNEL_CONFIRMED} on the same rejection.
     * {@code ApplyCommand}'s terminal gate was a verbatim copy of that, so fixing one did not fix the other.
     * {@code PatternsCommand} mapped the notifications and nothing else, so the notification-free tier came out as
     * {@code diagnostics: []} with the summary "0 finding(s)" — a rejection that discarded its own cause. One
     * source, three consumers, and the tier that carries no code is the one they all got wrong.
     */
    static List<Diagnostic> gateRejectionDiagnostics(ModelConsistencyException e, ConsistencyEvidence evidence) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (Notification n : e.notifications()) {
            diagnostics.add(KernelDiagnostics.enriched(n, null));
        }
        if (diagnostics.isEmpty()) {
            diagnostics.add(Diagnostic.gateRefusal(evidence.gate(), e.getMessage(),
                    new Diagnostic.Where((String) null, Optional.empty())));
        }
        return List.copyOf(diagnostics);
    }

    /**
     * Build (don't print) the {@code rejected} envelope for an unresolvable model reference — an include /
     * type-def import whose target the workspace can't provide. An INPUT problem, so it gets a corrective
     * {@code RK_UNRESOLVED_REFERENCE} diagnostic (exit 1), not the generic internal-error shape; the
     * {@link Cli} seam routes {@link UnresolvedReferenceException} here so it never escapes as a raw trace.
     */
    static ResultEnvelope unresolvedReferenceEnvelope(ResultIdentity identity, UnresolvedReferenceException e) {
        Diagnostic d = Diagnostic.precheck(Optional.of(RkCode.UNRESOLVED_REFERENCE.code()),
                e.getMessage(),
                Diagnostic.Where.arg(null, e.reference()),
                Optional.of(RkCode.UNRESOLVED_REFERENCE.fix()),
                Optional.empty());
        return ResultEnvelope.rejected(identity,
                "a model reference could not be resolved: " + e.reference(), List.of(d),
                RejectionBasis.preflight());
    }

    /** Convert every portable workspace diagnostic into the CLI's corrective, self-describing refusal shape. */
    static ResultEnvelope modelPreparationEnvelope(ResultIdentity identity, ModelPreparationError error) {
        List<Diagnostic> diagnostics = error.getDiagnostics().stream()
                .map(BaseCommand::modelPreparationDiagnostic)
                .toList();
        return ResultEnvelope.refused(
                identity,
                "the model workspace could not be prepared",
                diagnostics);
    }

    private static Diagnostic modelPreparationDiagnostic(ModelPreparationDiagnostic preparation) {
        RkCode code = switch (preparation.getCode()) {
            case UNRESOLVED_MODEL_REFERENCE -> RkCode.UNRESOLVED_REFERENCE;
            case UNMAPPED_INCLUDED_REFERENCE -> RkCode.INTERPRETER_INCLUDE_RULES_UNSUPPORTED;
            case UNSUPPORTED_TIME_ZONE -> timeZoneCode(preparation.getReference());
            case INVALID_MODEL_SOURCE -> preparation.getReference() != null
                    ? RkCode.INVALID_CONFIG_VALUE
                    : RkCode.MODEL_PREPARATION_FAILED;
            default -> RkCode.MODEL_PREPARATION_FAILED;
        };
        String arg = firstNonBlank(
                preparation.getReference(),
                preparation.getRequestedModelId(),
                preparation.getModelId(),
                preparation.getDiagnosticLabel(),
                preparation.getCode().name());
        return Diagnostic.precheck(
                Optional.of(code.code()),
                preparation.getCode().name() + ": " + preparation.getMessage(),
                Diagnostic.Where.arg(preparation.getPath(), arg),
                Optional.of(code.fix()),
                Optional.of(code.meaning()));
    }

    /**
     * Common code cannot classify the host Java time-zone registry. Preserve the CLI contract by separating a
     * valid-but-unsupported zone from an unknown id at this JVM projection boundary.
     */
    private static RkCode timeZoneCode(String timeZoneId) {
        return switch (ModelTimeZoneContract.classify(true, timeZoneId)) {
            case KERNEL_LEGAL_INTERPRETER_UNSUPPORTED -> RkCode.INTERPRETER_TIMEZONE_UNSUPPORTED;
            case ABSENT_DEFAULT, INTERPRETER_SUPPORTED, KERNEL_INVALID -> RkCode.INVALID_CONFIG_VALUE;
        };
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        throw new IllegalArgumentException("at least one diagnostic locator is required");
    }

    /**
     * Build (don't print) the {@code error} envelope for an unexpected throwable caught at the command
     * boundary — the tool itself failed (e.g. a native-image reflection gap), distinct from a kernel
     * rejection of valid-but-invalid input. Shared so the {@link Cli} seam maps BOTH a routed
     * {@code Exception} and an escaped {@code Error} to one structured shape, so no raw stack trace escapes
     * a verb — generalizing the single-{@link ModelConsistencyException} seam to the whole throwable class.
     */
    static ResultEnvelope internalErrorEnvelope(ResultIdentity identity, Throwable t) {
        String detail = t.getClass().getSimpleName()
                + (t.getMessage() != null && !t.getMessage().isBlank() ? ": " + firstLine(t.getMessage()) : "");
        String verb = identity.toString();
        Diagnostic d = Diagnostic.internal(Optional.of(RkCode.INTERNAL_ERROR.code()),
                "internal error while executing `" + verb + "`: " + detail,
                new Diagnostic.Where((String) null, Optional.empty()),
                Optional.of(RkCode.INTERNAL_ERROR.fix()));
        return ResultEnvelope.internalError(identity,
                "the tool failed internally — not an input rejection", List.of(d));
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return (nl < 0 ? s : s.substring(0, nl)).strip();
    }
}
