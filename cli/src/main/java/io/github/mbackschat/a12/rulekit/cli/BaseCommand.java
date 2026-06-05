package io.github.mbackschat.a12.rulekit.cli;

import java.io.PrintWriter;
import java.util.concurrent.Callable;

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
abstract class BaseCommand implements Callable<Integer> {

    @Spec
    protected CommandSpec spec;

    protected PrintWriter out() {
        return spec.commandLine().getOut();
    }

    protected PrintWriter err() {
        return spec.commandLine().getErr();
    }

    /** Print a diagnostic to stderr and return exit code 2 (usage / IO error). */
    protected int fail(String message) {
        err().println(message);
        return 2;
    }
}
