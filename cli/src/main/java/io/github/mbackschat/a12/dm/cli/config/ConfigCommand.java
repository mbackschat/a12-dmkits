package io.github.mbackschat.a12.dm.cli.config;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * The {@code config} target group (CLI-SPEC §5) — model-level document configuration on the model named by
 * {@code -m}: {@code read} (the whole {@code modelConfig}) and {@code modify} (decimal-separator / time-zone
 * / condition-language). Config is a fixed object, not a collection, so there is no {@code add}/{@code remove}.
 */
@Command(name = "config", mixinStandardHelpOptions = true,
        description = "Document-config operations: read, modify.",
        subcommands = { ConfigReadCommand.class, ConfigModifyCommand.class })
public final class ConfigCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().getErr().println(spec.commandLine().getUsageMessage());
        return 2;
    }
}
