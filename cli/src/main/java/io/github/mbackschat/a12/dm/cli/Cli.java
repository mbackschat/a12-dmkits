package io.github.mbackschat.a12.dm.cli;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.core.UnresolvedReferenceException;
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationError;
import picocli.CommandLine;

/**
 * The standalone CLI (PROJECT-DESIGN N10 / docs/CLI-SPEC.md) — the modeller/BA front-end over the engine.
 *
 * <p>{@link #run} is deliberately <b>pure and testable</b>: it returns an exit code and writes to the
 * passed sinks (no {@code System.exit}, no static streams), so every verb is a sub-second JUnit test
 * with no packaging and no spawned process (CLI-SPEC §3 — the fast inner loop). {@link CliMain} is the
 * thin shell that wires it to the process.
 *
 * <p>Dispatch, parsing, {@code --help}, and the machine-readable manifest are all driven by
 * <b>picocli</b> from one declaration per verb (the {@link RootCommand} subcommand tree), so the help
 * can't drift from the parsing (CLI-SPEC §1a — the self-explaining requirement).
 */
public final class Cli {

    private Cli() {
    }

    /**
     * The shipped command name — the <b>single source of truth</b> for the tool's name. {@link RootCommand}
     * binds it as the picocli command name (so {@code --help}, {@code manifest}, and {@code schema} all
     * report it without drift); every other surface should read the <i>live</i> name from the command tree
     * rather than re-hardcode the literal. A rename is a one-line change here, and
     * {@code CommandReferencesResolveTest} fails the build if the example text still names the old tool.
     */
    public static final String TOOL_NAME = "dmtool";

    /**
     * Verbs hidden from the native command tree (and thus from every derived self-describing surface —
     * {@code manifest}/{@code schema}/{@code --help} all derive from the live tree). The native seam for a
     * verb that genuinely cannot run without the JVM.
     *
     * <p><b>Currently empty.</b> The runtime-eval verbs ({@code model eval}/{@code compute}, {@code rule
     * eval}) used to live here because they ran the kernel's Groovy codegen, which a native image forbids —
     * but they now run on the native-safe <b>interpreter</b> ({@code dm.interpreter}, no Groovy) on every
     * target — the sole runtime eval engine (the user-facing {@code --kernel} eval flag was retired) — so
     * they are native-capable and stay in the tree. {@code {target, op}} pairs.
     */
    static final List<String[]> JVM_ONLY_VERBS = List.of();

    /**
     * Native-image detection — overridable in tests (the guard test forces the native profile on the JVM).
     * GraalVM sets {@code org.graalvm.nativeimage.imagecode} ("runtime") in a native image; it is null on a JVM.
     */
    static BooleanSupplier nativeImageDetector =
            () -> System.getProperty("org.graalvm.nativeimage.imagecode") != null;

    /** The effective native profile for the current dispatch (set by {@link #run}); read by verbs. */
    private static volatile boolean nativeProfileActive;

    /**
     * Whether the current run is the native-image profile — where the kernel's Groovy runtime engine is
     * unavailable, so the runtime verbs use the interpreter and any JVM-only verb ({@link #JVM_ONLY_VERBS})
     * is hidden from the command tree. Honors the test seam ({@link #run(String[], Appendable, Appendable, boolean)}).
     */
    public static boolean isNativeImage() {
        return nativeProfileActive;
    }

    /** Dispatch a verb. Returns a process exit code (0 = success, 1 = invalid, 2 = usage/IO error, 70 = internal tool error). */
    public static int run(String[] args, Appendable out, Appendable err) {
        return run(args, out, err, nativeImageDetector.getAsBoolean());
    }

    /**
     * Test seam + native entry point: run with the given build profile. When {@code nativeProfile} is true,
     * the {@link #JVM_ONLY_VERBS} are removed from the command tree (and thus from every derived
     * self-describing surface) before dispatch.
     */
    static int run(String[] args, Appendable out, Appendable err, boolean nativeProfile) {
        nativeProfileActive = nativeProfile;
        PrintWriter errW = new PrintWriter(new AppendableWriter(err), true);
        // R2 (CLI-SPEC §2): with --text, capture stdout so a result envelope can be
        // re-rendered as human-readable text; non-envelope output (catalogs, artifacts, --help, errors)
        // passes through unchanged (TextRenderer is gated). JSON is the default. The flag is detected here,
        // before execute, to choose the output sink — it is also a self-describing @Option on RootCommand.
        boolean textMode = false;
        for (String a : args) {
            if (a.equals("--text")) {
                textMode = true;
                break;
            }
        }
        StringWriter textBuf = textMode ? new StringWriter() : null;
        PrintWriter outW = new PrintWriter(textMode ? textBuf : new AppendableWriter(out), true);
        // Built OUTSIDE the try so the catch below can read its ParseResult to label the error envelope.
        CommandLine cli = new CommandLine(new RootCommand())
                .setOut(outW)
                .setErr(errW)
                .setExpandAtFiles(false) // model paths are plain args, never @argfiles
                // No raw exception escapes a verb (CLI-CONFORMANCE). Three escape classes, ONE structured shape:
                // (1) a verb that LOADS an invalid-on-disk model throws ModelConsistencyException → the same
                //     `rejected` envelope a write-path kernel rejection produces (probe 01-de Finding 7);
                // (2) an unresolvable include / type-def import (UnresolvedReferenceException) is an INPUT
                //     problem → a corrective `rejected` (RK_UNRESOLVED_REFERENCE), exit 1 — not an internal error;
                // (3) ANY other thrown Exception is the tool failing unexpectedly → a structured `error`
                //     envelope (RK_INTERNAL_ERROR), never a raw stack trace. picocli routes only Exceptions
                //     here; an Error (e.g. a native reflection gap) is caught around execute() below.
                .setExecutionExceptionHandler((ex, commandLine, parseResult) -> {
                    String[] to = targetOp(parseResult);
                    boolean internal = !(ex instanceof ModelConsistencyException)
                            && !(ex instanceof UnresolvedReferenceException)
                            && !(ex instanceof ModelPreparationError);
                    ResultEnvelope env;
                    if (ex instanceof ModelConsistencyException mce) {
                        env = BaseCommand.kernelRejectionEnvelope(to[0], to[1],
                                "the model is rejected by the kernel — it is invalid as stored", mce);
                    } else if (ex instanceof UnresolvedReferenceException ure) {
                        env = BaseCommand.unresolvedReferenceEnvelope(to[0], to[1], ure);
                    } else if (ex instanceof ModelPreparationError preparation) {
                        env = BaseCommand.modelPreparationEnvelope(to[0], to[1], preparation);
                    } else {
                        env = BaseCommand.internalErrorEnvelope(to[0], to[1], ex);
                    }
                    commandLine.getOut().print(env.toJsonString());
                    commandLine.getOut().flush();
                    if (internal) {
                        ex.printStackTrace(commandLine.getErr()); // a real failure: keep the trace on stderr (stdout is the envelope)
                    }
                    return env.exitCode();
                })
                // Default picocli parse-error handling (message + suggestions/usage, exit 2) PLUS a locator
                // corrective: a verb that locates its target by a POSITIONAL <…PathInModel>/<…Id>, handed a
                // wrong locator FLAG instead (the agent reuses the error-field flag of `rule check` on a read
                // verb, or guesses a flag for `rule read`), gets told the positional form, not a bare
                // "Unknown option" (probe 06-rr — the cross-verb locator inconsistency).
                .setParameterExceptionHandler((ex, parsedArgs) -> {
                    CommandLine offending = ex.getCommandLine();
                    PrintWriter w = offending.getErr();
                    w.println(ex.getMessage());
                    // The instance-flag hint wins over the locator hint: `rule eval` takes BOTH an --instance
                    // and a positional <rulePathInModel>, so a wrong --doc must be corrected to --instance, not
                    // sent to the positional. Fall back to the locator corrective otherwise.
                    String hint = metadataRelocationHint(ex);
                    if (hint == null) {
                        hint = instanceFlagHint(ex, parsedArgs);
                    }
                    if (hint == null) {
                        hint = locatorPositionalHint(ex);
                    }
                    if (hint == null) {
                        hint = specInputHint(ex);
                    }
                    if (hint != null) {
                        w.println(hint);
                    }
                    if (!CommandLine.UnmatchedArgumentException.printSuggestions(ex, w)) {
                        offending.usage(w);
                    }
                    return offending.getCommandSpec().exitCodeOnInvalidInput();
                });
        if (nativeProfile) {
            hideJvmOnlyVerbs(cli);
        }
        try {
            return cli.execute(args);
        } catch (Throwable t) {
            // picocli routes only Exceptions to the handler above; an Error — notably GraalVM's
            // MissingReflectionRegistrationError (a native reflection-metadata gap) — propagates uncaught on
            // the calling thread. Map it to the SAME structured `error` envelope so no raw stack trace
            // escapes a verb; the full trace goes to stderr (never the machine-readable stdout).
            CommandLine.ParseResult pr = cli.getParseResult();
            String[] to = pr != null ? targetOp(pr) : new String[]{"model", ""};
            ResultEnvelope env = BaseCommand.internalErrorEnvelope(to[0], to[1], t);
            outW.print(env.toJsonString());
            outW.flush();
            t.printStackTrace(errW);
            return env.exitCode();
        } finally {
            outW.flush();
            errW.flush();
            if (textMode) {
                PrintWriter realOut = new PrintWriter(new AppendableWriter(out), true);
                realOut.print(TextRenderer.renderIfEnvelope(textBuf.toString()));
                realOut.flush();
            }
        }
    }

    /**
     * Remove the {@link #JVM_ONLY_VERBS} from the live command tree. Since {@code manifest}, {@code schema},
     * and {@code --help} are all derived from this tree (the no-drift contract), this single removal hides
     * them from every self-describing surface — so a cold native agent can't even explore them.
     */
    /** The {@code (target, op)} names below the root for a parse result — e.g. {@code {"rule","modify"}};
     *  {@code op} is {@code ""} for a single-token verb. Used to stamp the load-failure {@code rejected} envelope. */
    private static String[] targetOp(CommandLine.ParseResult pr) {
        List<String> names = new ArrayList<>();
        for (CommandLine.ParseResult cur = pr.subcommand(); cur != null; cur = cur.subcommand()) {
            names.add(cur.commandSpec().name());
        }
        return new String[]{names.isEmpty() ? "model" : names.get(0), names.size() > 1 ? names.get(1) : ""};
    }

    /**
     * The locator corrective for a parse error: when a verb that takes its target as a POSITIONAL
     * {@code <…PathInModel>}/{@code <…Id>} was handed a wrong locator FLAG (an unmatched {@code --option}),
     * name the positional form instead of leaving a bare "Unknown option". Returns {@code null} when it
     * doesn't apply — no unmatched {@code --flag}, or the verb has no locator positional (so a spec-input
     * verb whose positional is {@code <spec.json>} never misfires). Probe 06-rr: an agent carries
     * {@code rule check --field} over to {@code field read}/{@code rule read}, which locate by a positional.
     */
    /** Common wrong guesses for the runtime verbs' {@code --instance} flag — agents reach for these after
     *  reading "the document INSTANCE". */
    private static final java.util.Set<String> INSTANCE_FLAG_SYNONYMS = java.util.Set.of("--doc", "--document", "--data");

    /**
     * The instance-flag corrective: the runtime verbs ({@code model eval}/{@code compute}, {@code rule eval})
     * describe their input as the document INSTANCE, so an agent naturally guesses {@code --doc} /
     * {@code --document} / {@code --data}. When a command that HAS an {@code --instance} option saw one of
     * those synonyms in its args, name {@code --instance} instead of leaving a bare error — a clean break (the
     * flag is {@code --instance}, not a silent alias) with a corrective error (the agent-first design).
     * Scans the raw args, not just the unmatched set, because {@code --instance} is REQUIRED: picocli reports
     * the missing required option (a {@code MissingParameterException}) before flagging the unknown synonym,
     * so the synonym never reaches {@code getUnmatched()}. Returns {@code null} when it doesn't apply.
     */
    private static String instanceFlagHint(CommandLine.ParameterException ex, String[] args) {
        if (ex.getCommandLine().getCommandSpec().findOption("--instance") == null) {
            return null; // only the instance-consuming verbs
        }
        for (String arg : args) {
            String flag = arg.split("=", 2)[0];
            if (INSTANCE_FLAG_SYNONYMS.contains(flag)) {
                return "→ the document-instance flag is --instance (you passed " + flag + ").";
            }
        }
        return null;
    }

    /** Point a retired G45 metadata flag at the canonical {@code meta <ref>} route. */
    private static String metadataRelocationHint(CommandLine.ParameterException ex) {
        if (!(ex instanceof CommandLine.UnmatchedArgumentException unmatched)) {
            return null;
        }
        String flag = unmatched.getUnmatched().stream()
                .filter(arg -> arg.startsWith("--"))
                .map(arg -> arg.split("=", 2)[0])
                .findFirst()
                .orElse(null);
        return flag == null ? null
                : MetadataRelocation.flagHint(ex.getCommandLine().getCommandSpec().qualifiedName(), flag)
                        .orElse(null);
    }

    private static String locatorPositionalHint(CommandLine.ParameterException ex) {
        if (!(ex instanceof CommandLine.UnmatchedArgumentException uae)) {
            return null;
        }
        String flag = uae.getUnmatched().stream().filter(a -> a.startsWith("--")).findFirst().orElse(null);
        if (flag == null) {
            return null;
        }
        CommandLine.Model.CommandSpec spec = ex.getCommandLine().getCommandSpec();
        for (CommandLine.Model.PositionalParamSpec p : spec.positionalParameters()) {
            String label = p.paramLabel();   // e.g. <fieldPathInModel>, <rulePathInModel>, <entityPath>, <typeDefId>
            // a locator positional is a "…Path…" (the *PathInModel family AND where-used's <entityPath>) or
            // an "…Id" — never a spec-input positional (<spec.json>/<ops.json> carry no "Path"/"Id"), so a
            // spec verb handed a bad flag still can't misfire here. (where-used was the missed sibling: its
            // <entityPath> didn't match the narrower "PathInModel" — probe 04-whereused-locator.)
            if (label.contains("Path") || label.endsWith("Id>") || label.endsWith("Id")) {
                return "→ " + spec.qualifiedName() + " locates its target by a POSITIONAL " + label
                        + ", not a flag — pass it directly: `" + spec.qualifiedName() + " " + label
                        + "` (you passed " + flag + ").";
            }
        }
        return null;
    }

    /**
     * The spec-input corrective: a verb whose input is a JSON spec FILE ({@code <spec.json>}/{@code <ops.json>}
     * — {@code rule add}, {@code computation add}, {@code field add}, {@code apply}, …) handed a {@code --flag}
     * it doesn't define (an agent reuses {@code rule check}'s {@code --field}/{@code --condition} on
     * {@code rule add}, whose fields live INSIDE the spec) is pointed at {@code schema <verb>} for the spec
     * shape, not left with a bare "Unknown option" (eval finding: {@code rule add} unknown-option → schema
     * {@code rule add}). Returns {@code null} when it doesn't apply (no unmatched {@code --flag}, or the verb
     * has no spec-file positional). Runs AFTER the locator/instance hints, so a locator-positional verb keeps
     * its own corrective; this is for the spec-input verbs the locator hint deliberately skips.
     */
    private static String specInputHint(CommandLine.ParameterException ex) {
        if (!(ex instanceof CommandLine.UnmatchedArgumentException uae)) {
            return null;
        }
        String flag = uae.getUnmatched().stream().filter(a -> a.startsWith("--")).findFirst().orElse(null);
        if (flag == null) {
            return null;
        }
        CommandLine.Model.CommandSpec spec = ex.getCommandLine().getCommandSpec();
        for (CommandLine.Model.PositionalParamSpec p : spec.positionalParameters()) {
            if (p.paramLabel().contains(".json")) {   // a spec-input positional (<spec.json>/<ops.json>)
                // qualifiedName is "<tool> <target> <op>" (or "<tool> <verb>"); insert "schema" after the tool
                // name to form the resolvable `schema <target> <op>` / `schema <verb>` pointer for its input shape.
                String schemaRef = spec.qualifiedName().replaceFirst(" ", " schema ");
                return "→ " + spec.qualifiedName() + " takes a JSON spec " + p.paramLabel() + " as its input — "
                        + "see `" + schemaRef + "` for the fields it expects (you passed " + flag
                        + ", which it doesn't define; for a rule the field/condition/code go INSIDE the spec).";
            }
        }
        return null;
    }

    static void hideJvmOnlyVerbs(CommandLine cli) {
        for (String[] verb : JVM_ONLY_VERBS) {
            CommandLine target = cli.getSubcommands().get(verb[0]);
            if (target != null) {
                target.getCommandSpec().removeSubcommand(verb[1]);
            }
        }
    }
}
