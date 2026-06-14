package io.github.mbackschat.a12.dm.cli;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * The {@code include} target group (CLI-SPEC §5) — model-include operations on the model named by {@code -m}:
 * {@code add} (declare a header model-reference + mount it; resolved via {@code -w/--workspace}), {@code read}
 * (lists the declared includes), {@code remove} (kernel-gated). An include is not tracked by the safe-delete
 * gate; a rule still referencing the mounted content is caught by the kernel on re-bind (a {@code rejected}
 * envelope).
 */
@Command(name = "include", mixinStandardHelpOptions = true,
        description = "Model-include operations: add, read, remove, inline.",
        subcommands = { IncludeAddCommand.class, IncludeReadCommand.class, IncludeRemoveCommand.class,
                IncludeInlineCommand.class })
final class IncludeCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().getErr().println(spec.commandLine().getUsageMessage());
        return 2;
    }
}
