package io.github.mbackschat.a12.dm.cli;

import java.io.PrintWriter;

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

    /** Dispatch a verb. Returns a process exit code (0 = success, 1 = invalid, 2 = usage/IO error). */
    public static int run(String[] args, Appendable out, Appendable err) {
        PrintWriter outW = new PrintWriter(new AppendableWriter(out), true);
        PrintWriter errW = new PrintWriter(new AppendableWriter(err), true);
        try {
            return new CommandLine(new RootCommand())
                    .setOut(outW)
                    .setErr(errW)
                    .setExpandAtFiles(false) // model paths are plain args, never @argfiles
                    .execute(args);
        } finally {
            outW.flush();
            errW.flush();
        }
    }
}
