package io.github.mbackschat.a12.dm.cli.group;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * The {@code group} target group (CLI-SPEC §5) — modelkit group operations on the model named by {@code -m}:
 * {@code add} (plain or {@code --repeatable}), {@code modify} (change repeatability in place), {@code read},
 * {@code remove} (the safe-delete gate), and the safety-gated Refactor ops (§6): {@code rename} and
 * {@code move} (both reference-preserving via the kernel's MoveSupportDM) and {@code extract} (group → include).
 */
@Command(name = "group", mixinStandardHelpOptions = true,
        description = "Group operations: add, modify, multiselect, attachment, read, remove, rename, move, extract.",
        subcommands = { GroupAddCommand.class, GroupModifyCommand.class, GroupMultiSelectCommand.class,
                GroupAttachmentCommand.class, GroupReadCommand.class, GroupRemoveCommand.class,
                GroupRenameCommand.class, GroupMoveCommand.class, GroupExtractCommand.class })
public final class GroupCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().getErr().println(spec.commandLine().getUsageMessage());
        return 2;
    }
}
