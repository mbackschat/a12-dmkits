package io.github.mbackschat.a12.dm.cli;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.function.BooleanSupplier;

import picocli.CommandLine;

/**
 * The standalone CLI (proposal N10 / docs/CLI-SPEC.md) — the modeller/BA front-end over the engine.
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
     * Verbs that execute the kernel's <b>runtime validation</b> (Groovy code generation), which is not
     * native-compilable — the hard ceiling in {@code docs/NATIVE-IMAGE-GRAALVM.md} §4. Registered on the
     * JVM build; <b>hidden in the native image</b>. Because {@code manifest}/{@code schema}/{@code --help}
     * all DERIVE from the live command tree, removing them here makes them vanish from <i>every</i>
     * self-describing surface — a cold native agent cannot discover them. {@code {target, op}} pairs.
     */
    static final List<String[]> JVM_ONLY_VERBS = List.of(
            new String[] {"model", "eval"},
            new String[] {"model", "compute"},
            new String[] {"rule", "test"});

    /**
     * Native-image detection — overridable in tests (the guard test forces the native profile on the JVM).
     * GraalVM sets {@code org.graalvm.nativeimage.imagecode} ("runtime") in a native image; it is null on a JVM.
     */
    static BooleanSupplier nativeImageDetector =
            () -> System.getProperty("org.graalvm.nativeimage.imagecode") != null;

    /** Dispatch a verb. Returns a process exit code (0 = success, 1 = invalid, 2 = usage/IO error). */
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
        boolean textMode = false;
        for (String a : args) {
            if (a.equals("--text")) {
                textMode = true;
                break;
            }
        }
        StringWriter textBuf = textMode ? new StringWriter() : null;
        PrintWriter outW = new PrintWriter(textMode ? textBuf : new AppendableWriter(out), true);
        try {
            CommandLine cli = new CommandLine(new RootCommand())
                    .setOut(outW)
                    .setErr(errW)
                    .setExpandAtFiles(false); // model paths are plain args, never @argfiles
            if (nativeProfile) {
                hideJvmOnlyVerbs(cli);
            }
            return cli.execute(args);
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
    static void hideJvmOnlyVerbs(CommandLine cli) {
        for (String[] verb : JVM_ONLY_VERBS) {
            CommandLine target = cli.getSubcommands().get(verb[0]);
            if (target != null) {
                target.getCommandSpec().removeSubcommand(verb[1]);
            }
        }
    }
}
