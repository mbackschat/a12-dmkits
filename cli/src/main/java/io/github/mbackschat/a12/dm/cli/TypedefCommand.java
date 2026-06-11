package io.github.mbackschat.a12.dm.cli;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * The {@code typedef} target group (CLI-SPEC §5) — reusable type-definition operations on the model named by
 * {@code -m}: {@code add} (Local), {@code read} (lists the declared ids), {@code remove} (kernel-gated). A
 * typedef is not path-addressed and not tracked by the safe-delete gate; a typedef still used by a field is
 * caught by the kernel on re-bind (a {@code rejected} envelope).
 */
@Command(name = "typedef", mixinStandardHelpOptions = true,
        description = "Type-definition operations: add, read, remove.",
        subcommands = { TypedefAddCommand.class, TypedefReadCommand.class, TypedefRemoveCommand.class })
final class TypedefCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().getErr().println(spec.commandLine().getUsageMessage());
        return 2;
    }
}
