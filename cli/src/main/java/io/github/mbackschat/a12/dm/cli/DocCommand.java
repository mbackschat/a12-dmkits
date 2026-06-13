package io.github.mbackschat.a12.dm.cli;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * The {@code doc} target group (CLI-SPEC §5) — whole-document operations on the model named by {@code -m}.
 * A bare {@code dmtool doc} prints its op list (exit 2), like the root; the ops are its subcommands.
 */
@Command(name = "doc", mixinStandardHelpOptions = true,
        description = "Whole-document operations: new, validate, describe, read, eval, compute (export keeps artifact mode).",
        subcommands = { DocNewCommand.class, DocValidateCommand.class, DocDescribeCommand.class, DocReadCommand.class,
                DocEvalCommand.class, DocComputeCommand.class })
final class DocCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().getErr().println(spec.commandLine().getUsageMessage());
        return 2;
    }
}
