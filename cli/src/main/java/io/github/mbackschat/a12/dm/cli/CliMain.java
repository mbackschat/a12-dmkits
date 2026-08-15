package io.github.mbackschat.a12.dm.cli;

/**
 * The process shell — the only place that touches {@code System.*} and exit codes. All behavior lives
 * in {@link Cli#run} so it stays unit-testable in-process (CLI-SPEC §8/§9).
 */
public final class CliMain {

    private CliMain() {
    }

    public static void main(String[] args) {
        System.exit(Cli.run(args, System.out, System.err));
    }
}
