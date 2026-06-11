package io.github.mbackschat.a12.dm.cli;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * The {@code field} target group (CLI-SPEC §5) — modelkit field operations on the model named by {@code -m}:
 * {@code add} (Local), {@code read}, {@code remove} (Cascading — the safe-delete gate). {@code rename} is
 * the deferred Refactor op (§6).
 */
@Command(name = "field", mixinStandardHelpOptions = true,
        description = "Field operations: add, read, remove.",
        subcommands = { FieldAddCommand.class, FieldReadCommand.class, FieldRemoveCommand.class })
final class FieldCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().getErr().println(spec.commandLine().getUsageMessage());
        return 2;
    }
}
