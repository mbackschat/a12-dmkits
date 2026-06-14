package io.github.mbackschat.a12.dm.cli;

import java.io.PrintWriter;
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
        PrintWriter outW = new PrintWriter(new AppendableWriter(out), true);
        PrintWriter errW = new PrintWriter(new AppendableWriter(err), true);
        // the removed --include-dir option is a fail-fast tombstone (renamed to -w/--workspace) — a pre-parse
        // guard so a stale caller is corrected, not met with a generic "Unknown option" (the no-silent-alias stance)
        for (String a : args) {
            if (a.equals("--include-dir") || a.startsWith("--include-dir=")) {
                errW.println("the `--include-dir` option was renamed to `-w/--workspace` (the workspace root — "
                        + "it resolves a model's includes AND type-def imports). Rerun with `-w <dir>`.");
                errW.flush();
                return 2;
            }
        }
        try {
            CommandLine cli = new CommandLine(new RootCommand())
                    .setOut(outW)
                    .setErr(errW)
                    .setExpandAtFiles(false); // model paths are plain args, never @argfiles
            if (nativeProfile) {
                hideJvmOnlyVerbs(cli);
            }
            // the renamed `doc` target is a fail-fast tombstone — let any trailing op/flags reach it (so it
            // can echo the corrected `model …` command) instead of erroring on an unknown subcommand/option
            CommandLine doc = cli.getSubcommands().get("doc");
            if (doc != null) {
                doc.setUnmatchedArgumentsAllowed(true).setUnmatchedOptionsArePositionalParams(true);
            }
            return cli.execute(args);
        } finally {
            outW.flush();
            errW.flush();
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
