package io.github.mbackschat.a12.dm.cli.profile;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * The {@code profile} target — extract a domain-blind model-performance profile.
 *
 * <p>A bare {@code dmtool profile} prints its op list (exit 2), like the other target groups.
 */
@Command(name = "profile", mixinStandardHelpOptions = true,
        description = "Performance-calibration profiles: extract a domain-blind workload shape from a model.",
        subcommands = ProfileExtractCommand.class)
public final class ProfileCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().getErr().println(spec.commandLine().getUsageMessage());
        return 2;
    }
}
