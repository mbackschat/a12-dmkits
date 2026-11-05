package io.github.mbackschat.a12.dm.cli.criterion;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * The {@code criterion} target group (CLI-SPEC §5): the model's document uniqueness criteria, each a named list of
 * fields no two stored documents may share (KERNEL-FINDINGS KF671). A criterion has no path, so every verb addresses
 * it by name; {@code add} and {@code remove} are kernel-gated, and {@code read} returns criteria in A12's stored entry shape.
 */
@Command(name = "criterion", mixinStandardHelpOptions = true,
        description = "Document uniqueness criterion operations: add, read, remove.",
        subcommands = { CriterionAddCommand.class, CriterionReadCommand.class, CriterionRemoveCommand.class })
public final class CriterionCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().getErr().println(spec.commandLine().getUsageMessage());
        return 2;
    }
}
