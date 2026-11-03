package io.github.mbackschat.a12.dm.cli;

/**
 * The process shell — the only place that touches {@code System.*} and exit codes. All behavior lives
 * in {@link Cli#run} so it stays unit-testable in-process (CLI-SPEC §8/§9).
 */
public final class CliMain {

    private CliMain() {
    }

    public static void main(String[] args) {
        // stdin belongs to the PROCESS, so its `-` claim is cleared here and nowhere else: `Cli.run` is
        // re-entered per child by `batch` and the MCP invoker, and clearing there would let a second `-`
        // through the very refusal that exists to stop it.
        JsonInput.resetForInvocation();
        KernelCopyReaderTrace.install();
        System.exit(Cli.run(args, System.out, System.err));
    }
}
