package io.github.mbackschat.a12.dm.cli.computation;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * The {@code computation} target group (CLI-SPEC §5) — computation operations on the model named by
 * {@code -m}. A bare {@code dmtool computation} prints its op list (exit 2); the ops are its subcommands.
 */
@Command(name = "computation", mixinStandardHelpOptions = true,
        description = "Computation operations: add, read, explain, format, modify, remove. Use add --dry-run "
                + "to statically check a computation spec without writing.",
        subcommands = { ComputationAddCommand.class, ComputationReadCommand.class,
                ComputationExplainCommand.class, ComputationFormatCommand.class, ComputationModifyCommand.class,
                ComputationRemoveCommand.class })
public final class ComputationCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().getErr().println(spec.commandLine().getUsageMessage());
        return 2;
    }
}
