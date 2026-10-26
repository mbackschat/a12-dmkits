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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.core.Notification;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.core.ModelInputException;
import io.github.mbackschat.a12.dm.core.UnresolvedReferenceException;
import io.github.mbackschat.a12.dm.interpreter.DocumentInputDiagnostic;
import io.github.mbackschat.a12.dm.interpreter.DocumentInputError;
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationDiagnostic;
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationError;
import io.github.mbackschat.a12.dm.interpreter.model.UndeclaredReference;
import io.github.mbackschat.a12.dm.modelkit.ModelTimeZoneContract;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.PrecheckException;
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

    /** The {@code -m} option as its own help text names it, so the refusal points at the caller's argument. */
    private static final String MODEL_LABEL = "-m <model.json>";

    /** The document input the three runtime verbs read, likewise named as its help text names it. */
    private static final String INSTANCE_LABEL = "--instance <document.json>";

    /**
     * The DM-JSON at {@code modelPath}, or {@code null} after emitting the coded refusal (the caller then returns
     * exit 2, which is what a {@code refused} envelope carries). The single implementation of the step every
     * model-aware verb opens with — it was written 41 more times inline before G61, which is how the coded
     * refusal that landed on the nine JSON inputs came to miss the model argument beside them.
     *
     * <p>The two failure modes get <b>different codes on purpose</b>. An unreadable file is
     * {@link RkCode#INPUT_UNREADABLE}, exactly as for the JSON inputs, and its corrective is to check the path.
     * An absent {@code -m} is {@link RkCode#MODEL_NOT_NAMED}: there is no path to check, and telling an agent to
     * check one would send it looking at a file it never supplied.
     */
    protected String readModelJson(String modelPath) {
        return readModelJson(resultIdentity(), modelPath);
    }

    /**
     * Identity-carrying counterpart for the one-token and multi-mode verbs, whose identity cannot be derived
     * from a depth-2 leaf. Mirrors {@link #refuse(ResultIdentity, Refusal)} and
     * {@link #rejectInvalidSpec(ResultIdentity, String, JsonNode)}; without it, emitting an envelope here would
     * throw for {@code meta}, {@code where-used}, {@code patterns}, {@code apply} and {@code export} — a failure
     * reachable only on the path where the model is already missing, so exactly the one no happy-path test walks.
     */
    protected String readModelJson(ResultIdentity identity, String modelPath) {
        if (modelPath == null) {
            refusePrecheck(identity, RkCode.MODEL_NOT_NAMED,
                    "no model was named — this verb operates on a document model", MODEL_LABEL,
                    RkCode.MODEL_NOT_NAMED.fix());
            return null;
        }
        try {
            return Files.readString(Path.of(modelPath));
        } catch (IOException e) {
            refuseUnreadableInput(identity, MODEL_LABEL, modelPath, e);
            return null;
        }
    }

    /**
     * The document instance at {@code instancePath} as text, or {@code null} after emitting the coded refusal.
     * Deliberately <b>unparsed</b>: the runtime verbs hand the text straight to the interpreter's canonical A12
     * Document JSON codec, which is the only reader of that shape in the tool. This is the model-file helper's
     * twin, so the three runtime verbs cannot drift on either input — which is also why it refuses in the same
     * coded form rather than keeping a plain-text channel its twin no longer uses.
     */
    protected String readInstanceJson(String instancePath) {
        try {
            return Files.readString(Path.of(instancePath));
        } catch (IOException e) {
            refuseUnreadableInput(INSTANCE_LABEL, instancePath, e);
            return null;
        }
    }

    /**
     * Print every defect the A12 Document JSON reader found, then exit 2. An invalid <i>input file</i> stays on
     * the plain-text {@code fail} channel.
     *
     * <p><b>The reason this used to give is gone; the channel is still right.</b> It read "the channel
     * {@code cannot read model:} already uses — a structured refusal is for a precheck on a resolvable target,
     * not for input the tool could not read at all". G58/G61/G62 moved every UNREADABLE input and UNWRITABLE
     * output onto coded refusals, so that precedent no longer exists. What keeps this on the plain-text channel
     * is this boundary's typed {@link DocumentInputError}: it reports diagnosed Document input defects,
     * never an arbitrary reader or renderer exception. Unexpected failures belong to {@link Cli}'s
     * internal-error mapper.
     *
     */
    protected int failInvalidInstance(DocumentInputError error) {
        String detail = error.getDiagnostics().stream()
                .map(DocumentInputDiagnostic::getMessage)
                .collect(java.util.stream.Collectors.joining("; "));
        return fail("invalid document: " + detail);
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
     * The same coded {@code refused} envelope, located by the OPTION that carried the bad value rather than by a
     * model path — for a precheck on an {@code --option} argument, where {@code path} is often not a model locus
     * at all (a relative {@code --field} is refused precisely because it is not one). Pass {@code path} only when
     * the value IS a well-formed entity path; {@code null} leaves {@code where.path} unset rather than publishing
     * a locus the model does not have.
     */
    protected int refuseOptionValue(RkCode code, String summary, String option, String path, String fix) {
        Diagnostic diagnostic = Diagnostic.precheck(Optional.of(code.code()), summary,
                Diagnostic.Where.arg(path, option), Optional.of(fix), Optional.empty());
        return refuse(resultIdentity(), new Refusal(summary, null, diagnostic));
    }

    /** The refusal a malformed {@code --clock} earns, worded once for every verb that mixes the option in. */
    protected int refuseMalformedClock(EvalClockOption clock) {
        return refuseOptionValue(RkCode.INVALID_VALUE, clock.refusalSummary(), "--clock", null, EvalClockOption.FIX);
    }

    /**
     * Refuse a JSON input that could not be READ — a coded envelope on stdout, not a bare stderr line.
     *
     * <p>Single-sourced because there are eleven catch sites and the interesting half is what they must NOT do:
     * a plain {@code fail} leaves stdout empty, so an agent parsing it for a result gets nothing at all, and two
     * of the old prefixes lied outright once {@code -} existed ("cannot read ops file" when there is no file).
     * The code and {@code where} carry that instead, so the message never has to guess whether its source was a
     * path or a stream. {@code label} is the parameter as its help text names it, e.g. {@code <spec.json>}.
     */
    protected int refuseUnreadableInput(String label, String source, IOException failure) {
        return refuseUnreadableInput(resultIdentity(), label, source, failure);
    }

    /** Identity-carrying counterpart, for the one-token and multi-mode verbs — see {@link #readModelJson}. */
    protected int refuseUnreadableInput(ResultIdentity identity, String label, String source,
            IOException failure) {
        String where = JsonInput.STDIN.equals(source) ? "stdin" : source;
        return refusePrecheck(identity, RkCode.INPUT_UNREADABLE,
                label + " could not be read from " + where + ": " + reason(where, failure),
                label, RkCode.INPUT_UNREADABLE.fix());
    }

    /**
     * Refuse an OUTPUT that could not be WRITTEN — a coded envelope on stdout, the mirror of
     * {@link #refuseUnreadableInput} (G62). {@code label} names the destination parameter as its help text does.
     *
     * <p><b>{@code written} stays false even when bytes reached the disk.</b> The published contract defines it
     * as whether this invocation wrote its <i>selected</i> destination, and a half-written bundle is not that.
     * What the caller cannot recover from the boolean is what debris to clean up, so {@code alreadyWritten}
     * carries it into {@code data.written} — empty for the single-file writers, which really did write nothing.
     */
    protected int refuseUnwritableOutput(String label, String destination, IOException failure) {
        return refuseUnwritableOutput(resultIdentity(), label, destination, failure, List.of());
    }

    /** Identity-carrying counterpart, for the one-token verbs — same reason as {@link #readModelJson}. */
    protected int refuseUnwritableOutput(ResultIdentity identity, String label, String destination,
            IOException failure) {
        return refuseUnwritableOutput(identity, label, destination, failure, List.of());
    }

    /** Multi-file counterpart: {@code alreadyWritten} names the files that landed before the failure. */
    protected int refuseUnwritableOutput(ResultIdentity identity, String label, String destination,
            IOException failure, List<String> alreadyWritten) {
        String summary = label + " could not be written to " + destination + ": " + reason(destination, failure)
                + (alreadyWritten.isEmpty() ? "" : " — " + alreadyWritten.size()
                        + " file(s) were already written and are left behind");
        Diagnostic diagnostic = Diagnostic.environmentFailure(Optional.of(RkCode.OUTPUT_UNWRITABLE.code()),
                summary, Diagnostic.Where.path(label), Optional.of(RkCode.OUTPUT_UNWRITABLE.fix()));
        ObjectNode data = null;
        if (!alreadyWritten.isEmpty()) {
            data = JSON.createObjectNode();
            ArrayNode written = data.putArray("written");
            alreadyWritten.forEach(written::add);
        }
        return refuse(identity, new Refusal(summary, data, diagnostic));
    }

    /**
     * What actually went wrong, with the path not said twice.
     *
     * <p>{@code java.nio.file} exceptions carry the PATH as their whole message — {@code NoSuchFileException}'s
     * message IS the filename — so the obvious {@code "… from " + path + ": " + e.getMessage()} renders as
     * {@code could not be read from /x: /x}, which reads like a defect in the tool rather than a missing file.
     * A {@code FileSystemException} prefixes its own reason with the path too ({@code /x: Not a directory}).
     * So strip a leading path echo, and when nothing informative is left, name the condition instead.
     */
    private static String reason(String source, IOException failure) {
        String message = failure.getMessage() == null ? "" : failure.getMessage();
        // Strip ONLY the two java.nio.file shapes: the message IS the path (NoSuchFileException), or it is
        // `<path>: <reason>` (FileSystemException). Requiring the colon matters — a bare startsWith(source)
        // also ate the front of our OWN double-claim message, whose text begins with the word `stdin` and is
        // not a path echo at all, leaving `could not be read from stdin:  is already being read as …`.
        if (message.equals(source)) {
            message = "";
        } else if (message.startsWith(source + ":")) {
            message = message.substring(source.length() + 1).strip();
        }
        if (!message.isBlank()) {
            return message;
        }
        return switch (failure) {
            case java.nio.file.NoSuchFileException ignored -> "no such file";
            case java.nio.file.AccessDeniedException ignored -> "access denied";
            case java.nio.file.NotDirectoryException ignored -> "not a directory";
            default -> failure.getClass().getSimpleName();
        };
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
     * Emit a {@code rejected} envelope for a spec the CLI's own PARSER refused — the second half of the input
     * gate, after {@link #rejectInvalidSpec}'s key/shape check has passed and the parser found a value it cannot
     * build from (a non-absolute path, a missing required slot, an unknown tolerance band).
     *
     * <p>Same shape as its sibling, deliberately: a {@link RejectionBasis#preflight()} rejection, because the
     * CLI refused before the kernel was consulted and no kernel provenance may be claimed. It lives here rather
     * than on the spec parser so the parser stays a parser — {@code apply} runs the same parse inside a
     * transaction and must NOT produce an envelope from it.
     */
    protected int rejectSpecParse(Diagnostic problem) {
        return rejectSpecParse(resultIdentity(), problem);
    }

    /**
     * Identity-carrying counterpart, for a verb whose leaf is not target/op depth 2 — {@code patterns} is the
     * one, and {@link ResultIdentity#from} refuses to derive an identity for it.
     */
    protected int rejectSpecParse(ResultIdentity identity, Diagnostic problem) {
        ResultEnvelope env = ResultEnvelope.rejected(identity, problem.summary(), List.of(problem),
                RejectionBasis.preflight());
        out().print(env.toJsonString());
        return env.exitCode();
    }

    /**
     * The same {@code rejected} envelope for a spec the kits' own <b>builders</b> refused — a value the schema
     * gate admits as well-typed but that {@code modelkit}/{@code rulekit} will not build from (a scale above
     * the kernel's cap, an enum category that does not classify every value).
     *
     * <p><b>Why this exists rather than a {@code fail()} line.</b> The schema half of the same verb's input gate
     * already answers inside the envelope, so a spec carrying an unknown key got a coded, located, parseable
     * refusal while a spec carrying an out-of-domain value got prose on stderr and an empty stdout — one input,
     * two contracts, and the second one indistinguishable from a kernel verdict to a consumer that records the
     * outcome without inspecting stdout. Reported by a12-kernel-lean (dmtool feedback, 2026-08-29), which came
     * one step from filing a local gate as a kernel gate.
     *
     * <p>{@code RK_INVALID_VALUE} is deliberate rather than a new class: to a caller this is the same mistake
     * the schema gate reports under that code — a recognized key carrying a value outside its domain — and the
     * two halves of one gate should not need two vocabularies. The offending key is named in the message; the
     * locator carries the input parameter, since the builders raise a message rather than a structured key.
     */
    protected int rejectSpecValue(String label, String specPath, IllegalArgumentException refused) {
        return rejectSpecValue(resultIdentity(), label, specPath, refused);
    }

    /** Identity-carrying counterpart — see {@link #rejectSpecParse(ResultIdentity, Diagnostic)}. */
    protected int rejectSpecValue(ResultIdentity identity, String label, String source,
            IllegalArgumentException refused) {
        return rejectSpecParse(identity, Diagnostic.precheck(RkCode.INVALID_VALUE,
                "invalid " + label + ": " + refused.getMessage(),
                Diagnostic.Where.arg(source, "<spec.json>")));
    }

    /**
     * A kits {@link PrecheckException} published as the {@code rejected} envelope it was BUILT to produce.
     *
     * <p>That type carries its own structured {@link Diagnostic} precisely so a caller can publish it "instead
     * of scraping the message" — and until 2026-08-30 <b>no CLI site read it</b>, so every one surfaced as
     * prose. On the scaffolding route that mattered most: {@code Computation.Builder}'s pre-checks raise an
     * {@code MVK_*} code, so a bare stderr line was indistinguishable from a kernel verdict — the defect
     * a12-kernel-lean reported on {@code field add}, one verb over.
     */
    protected int rejectPrecheck(ResultIdentity identity, PrecheckException refused) {
        return rejectSpecParse(identity, refused.diagnostic());
    }

    /** The same refusal built from an {@link RkCode} + the spec KEY that carried the problem. */
    protected int rejectSpecParse(RkCode code, String summary, String arg, String path) {
        return rejectSpecParse(Diagnostic.precheck(code, summary, Diagnostic.Where.arg(path, arg)));
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
            return refuseUnwritableOutput("-o <file>", write.destination(), e);
        }
        return print(write.applied());
    }

    /** Print an envelope and return its exit code — the one place a result reaches stdout. */
    protected int print(ResultEnvelope env) {
        out().print(env.toJsonString());
        return env.exitCode();
    }

    /** Our own renderers supply this JSON; a decoding failure is an internal defect (CLI-CONFORMANCE §1). */
    protected static JsonNode readGeneratedJson(String json) {
        try {
            return JSON.readerFor(JsonNode.class)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(json);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("cannot decode generated JSON", failure);
        }
    }

    /**
     * Add reader context without changing the typed refusals understood by {@link Cli}'s exception mapper.
     * An unclassified failure remains an internal error, with its original cause available on stderr.
     */
    protected RuntimeException withFailureContext(String messagePrefix, RuntimeException failure) {
        if (failure instanceof ModelConsistencyException || failure instanceof UnresolvedReferenceException
                || failure instanceof ModelInputException || failure instanceof ModelPreparationError) {
            return failure;
        }
        return new IllegalStateException(messagePrefix + failure.getMessage(), failure);
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

    static ResultEnvelope modelInputEnvelope(ResultIdentity identity, ModelInputException failure, String source) {
        Diagnostic diagnostic = Diagnostic.precheck(RkCode.MODEL_INPUT_INVALID, failure.getMessage(),
                Diagnostic.Where.arg(null, source == null ? MODEL_LABEL : source));
        return ResultEnvelope.refused(identity, "the model input is malformed", List.of(diagnostic));
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
