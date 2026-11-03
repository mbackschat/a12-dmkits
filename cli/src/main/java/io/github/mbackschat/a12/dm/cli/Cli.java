package io.github.mbackschat.a12.dm.cli;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.core.ModelInputException;
import io.github.mbackschat.a12.dm.core.UnresolvedReferenceException;
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationError;
import picocli.CommandLine;

/**
 * The standalone CLI (PROJECT-DESIGN N10 / docs/CLI-SPEC.md) — the modeller/BA front-end over the engine.
 *
 * <p>{@link #run} is deliberately <b>pure and testable</b>: it returns an exit code and writes to the
 * passed sinks (no {@code System.exit}, no static streams), so every verb is a sub-second JUnit test
 * with no packaging and no spawned process (CLI-SPEC §9 — the fast inner loop). {@link CliMain} is the
 * thin shell that wires it to the process.
 *
 * <p>Dispatch, parsing, {@code --help}, and the machine-readable manifest are all driven by
 * <b>picocli</b> from one declaration per verb (the {@link RootCommand} subcommand tree), so the help
 * can't drift from the parsing (CLI-SPEC §2 — the self-explaining requirement).
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
        PrintWriter errW = new PrintWriter(new AppendableWriter(err), true);
        // R2 (CLI-SPEC §2): with --text, capture stdout so a result envelope can be
        // re-rendered as human-readable text; non-envelope output (catalogs, artifacts, --help, errors)
        // passes through unchanged (TextRenderer is gated). JSON is the default. The flag is detected here,
        // before execute, to choose the output sink — it is also a self-describing @Option on RootCommand.
        boolean textMode = textMode(args);
        StringWriter textBuf = textMode ? new StringWriter() : null;
        PrintWriter outW = new PrintWriter(textMode ? textBuf : new AppendableWriter(out), true);
        // Built OUTSIDE the try so the catch below can read its ParseResult to label the error envelope.
        CommandLine cli = new CommandLine(new RootCommand())
                .setOut(outW)
                .setErr(errW)
                .setExpandAtFiles(false) // model paths are plain args, never @argfiles
                // Input refusals must not claim an internal defect or a consistency verdict the loader
                // never reached (CLI-CONFORMANCE). Unexpected failures retain their stderr trace.
                .setExecutionExceptionHandler((ex, commandLine, parseResult) -> {
                    ResultIdentity identity = ResultIdentity.from(parseResult);
                    boolean internal = !(ex instanceof ModelConsistencyException)
                            && !(ex instanceof ModelInputException)
                            && !(ex instanceof UnresolvedReferenceException)
                            && !(ex instanceof ModelPreparationError);
                    ResultEnvelope env;
                    if (ex instanceof ModelConsistencyException mce) {
                        env = BaseCommand.kernelRejectionEnvelope(identity,
                                "the model is rejected by the kernel — it is invalid as stored", mce);
                    } else if (ex instanceof UnresolvedReferenceException ure) {
                        env = BaseCommand.unresolvedReferenceEnvelope(identity, ure);
                    } else if (ex instanceof ModelInputException malformed) {
                        RootCommand root = (RootCommand) commandLine.getCommandSpec().root().userObject();
                        env = BaseCommand.modelInputEnvelope(identity, malformed, root.model);
                    } else if (ex instanceof ModelPreparationError preparation) {
                        env = BaseCommand.modelPreparationEnvelope(identity, preparation);
                    } else {
                        env = BaseCommand.internalErrorEnvelope(identity, ex);
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
                    String hint = semanticSubcommandHint(ex);
                    if (hint == null) {
                        hint = metadataRelocationHint(ex);
                    }
                    if (hint == null) {
                        hint = includeAliasHint(ex);
                    }
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
                })
                // An unknown subcommand beats a help request. picocli honours `--help` during parsing and skips the
                // unmatched-argument check when it fires, so a group plus an unknown verb plus `--help` printed the
                // GROUP's help and exited 0 — while the same line WITHOUT `--help` correctly exited 2. The exit code
                // therefore turned on a flag unrelated to whether the verb exists, and 0 is the one answer an agent
                // reads as "that verb is fine": it goes on to use a verb that does not exist, and parent help never
                // says otherwise, because group help is a plausible reply to a request nobody flagged as malformed.
                // Both behaviours are right on their own; only the PRECEDENCE was wrong, and the unknown verb has to
                // win because there is no command whose help was asked for.
                .setExecutionStrategy(parseResult -> {
                    rejectUnknownSubcommand(parseResult);
                    return new CommandLine.RunLast().execute(parseResult);
                });
        CommandContracts.decorateHelp(cli);
        if (nativeProfile) {
            hideJvmOnlyVerbs(cli);
        }
        try {
            return executeWithErrorBoundary(cli, args, outW, errW);
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

    /** Resolve the inherited boolean through the live picocli contract before choosing the stdout sink. */
    private static boolean textMode(String[] args) {
        try {
            return Boolean.parseBoolean(CommandContracts.parsedParameters(List.of(args)).value("--text"));
        } catch (RuntimeException invalidInvocation) {
            // Parse errors produce no result envelope, so capturing stdout cannot add value. More importantly,
            // never guess from raw tokens here: `--` can make a literal positional named `--text`, and the batch
            // observation preflight uses this same parsed-parameter owner to decide whether JSON is preserved.
            return false;
        }
    }

    /** Execute one configured command tree while containing Errors that picocli's Exception handler cannot catch. */
    static int executeWithErrorBoundary(CommandLine cli, String[] args, PrintWriter outW, PrintWriter errW) {
        try {
            return cli.execute(args);
        } catch (Throwable t) {
            // picocli routes only Exceptions to the handler above; an Error — notably GraalVM's
            // MissingReflectionRegistrationError (a native reflection-metadata gap) — propagates uncaught on
            // the calling thread. Map it to the SAME structured `error` envelope so no raw stack trace
            // escapes a verb; the full trace goes to stderr (never the machine-readable stdout).
            CommandLine.ParseResult pr = cli.getParseResult();
            ResultIdentity identity = pr != null ? ResultIdentity.from(pr) : ResultIdentity.UNPARSED_FALLBACK;
            ResultEnvelope env = BaseCommand.internalErrorEnvelope(identity, t);
            outW.print(env.toJsonString());
            outW.flush();
            t.printStackTrace(errW);
            return env.exitCode();
        }
    }

    /**
     * Throw the {@code UnmatchedArgumentException} picocli suppressed because a help option was also present, so an
     * unknown subcommand is a usage error regardless of {@code --help}.
     *
     * <p>Walks the whole {@code ParseResult} chain: the unmatched token belongs to the deepest command that saw it,
     * and a group's help option is declared on the group, so the two are not necessarily at the same level. The
     * exception is thrown rather than handled here so it lands in the existing parameter-exception handler and gets
     * the same message, did-you-mean suggestions, and exit code as an unknown subcommand without {@code --help} —
     * one rejection path, not a second one that could drift from it.
     */
    private static void rejectUnknownSubcommand(CommandLine.ParseResult parseResult) {
        for (CommandLine.ParseResult pr = parseResult; pr != null; pr = pr.subcommand()) {
            List<String> unmatched = pr.unmatched();
            if (!unmatched.isEmpty()) {
                throw new CommandLine.UnmatchedArgumentException(pr.commandSpec().commandLine(), unmatched);
            }
        }
    }

    /** Correct a plausible verb guess when the equivalent workflow intentionally has a different shape. */
    private static String semanticSubcommandHint(CommandLine.ParameterException ex) {
        if (!(ex instanceof CommandLine.UnmatchedArgumentException unmatched)) {
            return null;
        }
        boolean guessedCheck = unmatched.getUnmatched().stream().anyMatch("check"::equals);
        if (guessedCheck && ex.getCommandLine().getCommandSpec().qualifiedName().endsWith(" computation")) {
            return "→ statically check a computation spec with `dmtool -m <model.json> computation add "
                    + "<computation-spec.json> --dry-run` (writes nothing); discover its shape with "
                    + "`dmtool schema computation add`.";
        }
        return null;
    }

    /** Common wrong guesses for the runtime verbs' {@code --instance} flag — agents reach for these after
     *  reading "the document INSTANCE". */
    private static final Set<String> INSTANCE_FLAG_SYNONYMS = Set.of("--doc", "--document", "--data");

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

    /** Point a DM 28 include-alias argument, or the retired {@code include rename}, at the reference-only form. */
    private static String includeAliasHint(CommandLine.ParameterException ex) {
        return ex instanceof CommandLine.UnmatchedArgumentException unmatched
                ? IncludeAliasRetirement.parseHint(ex.getCommandLine().getCommandSpec().qualifiedName(),
                        unmatched.getUnmatched()).orElse(null)
                : null;
    }

    /**
     * The locator corrective for a parse error: when a verb that takes its target as a POSITIONAL
     * {@code <…PathInModel>}/{@code <…Id>} was handed a wrong locator FLAG (an unmatched {@code --option}),
     * name the positional form instead of leaving a bare "Unknown option". Returns {@code null} when it
     * doesn't apply — no unmatched {@code --flag}, or the verb has no locator positional (so a spec-input
     * verb whose positional is {@code <spec.json>} never misfires). Probe 06-rr: an agent carries
     * {@code rule check --field} over to {@code field read}/{@code rule read}, which locate by a positional.
     */
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

    /**
     * Remove the {@link #JVM_ONLY_VERBS} from the live command tree. Since {@code manifest}, {@code schema},
     * and {@code --help} are all derived from this tree (the no-drift contract), this single removal hides
     * them from every self-describing surface — so a cold native agent can't even explore them.
     */
    static void hideJvmOnlyVerbs(CommandLine cli) {
        for (String[] verb : JVM_ONLY_VERBS) {
            CommandLine target = cli.getSubcommands().get(verb[0]);
            if (target != null) {
                target.getCommandSpec().removeSubcommand(verb[1]);
            }
        }
    }
}
