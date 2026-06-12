package io.github.mbackschat.a12.dm.cli;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * The {@code group} target group (CLI-SPEC §5) — modelkit group operations on the model named by {@code -m}:
 * {@code add} (plain or {@code --repeatable}), {@code read}, {@code remove} (the safe-delete gate). {@code
 * rename}/{@code move} are the deferred Refactor ops (§6).
 */
@Command(name = "group", mixinStandardHelpOptions = true,
        description = "Group operations: add, read, remove, rename.",
        subcommands = { GroupAddCommand.class, GroupReadCommand.class, GroupRemoveCommand.class,
                GroupRenameCommand.class })
final class GroupCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().getErr().println(spec.commandLine().getUsageMessage());
        return 2;
    }
}
