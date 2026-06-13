package io.github.mbackschat.a12.dm.cli;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * The {@code doc} target group (CLI-SPEC §5) — whole-document operations on the model named by {@code -m}.
 * A bare {@code dmtool model} prints its op list (exit 2), like the root; the ops are its subcommands.
 */
@Command(name = "model", mixinStandardHelpOptions = true,
        description = "Whole-model operations: new, info, validate, describe, read, eval, compute (export keeps artifact mode).",
        subcommands = { ModelNewCommand.class, ModelInfoCommand.class, ModelValidateCommand.class, ModelDescribeCommand.class,
                ModelReadCommand.class, ModelEvalCommand.class, ModelComputeCommand.class })
final class ModelCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().getErr().println(spec.commandLine().getUsageMessage());
        return 2;
    }
}
