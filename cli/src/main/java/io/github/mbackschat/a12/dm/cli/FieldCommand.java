package io.github.mbackschat.a12.dm.cli;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * The {@code field} target group (CLI-SPEC §5) — modelkit field operations on the model named by {@code -m}:
 * {@code add} (Local), {@code read}, {@code remove} (Cascading — the safe-delete gate), and the safety-gated
 * Refactor ops (§6) {@code rename} and {@code move} (both reference-preserving via the kernel's MoveSupportDM).
 */
@Command(name = "field", mixinStandardHelpOptions = true,
        description = "Field operations: add, modify, read, remove, rename, move.",
        subcommands = { FieldAddCommand.class, FieldModifyCommand.class, FieldReadCommand.class,
                FieldRemoveCommand.class, FieldRenameCommand.class, FieldMoveCommand.class })
final class FieldCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().getErr().println(spec.commandLine().getUsageMessage());
        return 2;
    }
}
