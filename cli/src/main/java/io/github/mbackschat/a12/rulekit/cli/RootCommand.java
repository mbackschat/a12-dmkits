package io.github.mbackschat.a12.rulekit.cli;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * The CLI root (proposal N10 / CLI-SPEC). Picocli assembles `--help`, usage, and the machine-readable
 * manifest ({@code manifest} verb) from the subcommand tree below — one declaration per verb, so the
 * tool fully describes itself (CLI-SPEC §1a). Adding a verb = adding it here + its {@code @Command}
 * class; nothing else needs to know.
 */
@Command(
        name = "rulekit",
        mixinStandardHelpOptions = true,
        version = "a12-rulekit CLI",
        synopsisSubcommandLabel = "<verb>",
        description = "Typed, model-aware authoring & edit kit for A12 validation rules. JSON in / JSON out.",
        subcommands = {
            DumpCommand.class,
            DescribeCommand.class,
            ValidateCommand.class,
            CheckCommand.class,
            AddCommand.class,
            IngestCommand.class,
            ExplainCommand.class,
            DiffCommand.class,
            BatchCommand.class,
            SchemaCommand.class,
            OperatorsCommand.class,
            ManifestCommand.class
        })
final class RootCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        // no verb given → usage to stderr, exit 2 (preserves the prior bare-invocation behavior)
        spec.commandLine().getErr().println(spec.commandLine().getUsageMessage());
        return 2;
    }
}
