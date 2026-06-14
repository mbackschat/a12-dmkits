package io.github.mbackschat.a12.dm.cli;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;
import picocli.CommandLine.Spec;

/**
 * The CLI root (proposal N10 / CLI-SPEC). Picocli assembles `--help`, usage, and the machine-readable
 * manifest ({@code manifest} verb) from the subcommand tree below — one declaration per verb, so the
 * tool fully describes itself (CLI-SPEC §1a). Adding a verb = adding it here + its {@code @Command}
 * class; nothing else needs to know.
 */
@Command(
        name = "dmtool",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        synopsisSubcommandLabel = "<target> <op>",
        description = "Typed, model-aware authoring & edit kit for A12 document models. JSON in / JSON out.",
        subcommands = {
            ModelCommand.class,
            DocTombstoneCommand.class,
            RuleCommand.class,
            ComputationCommand.class,
            FieldCommand.class,
            GroupCommand.class,
            TypedefCommand.class,
            IncludeCommand.class,
            ConfigCommand.class,
            WorkspaceCommand.class,
            ExportCommand.class,
            DescribeCommand.class,
            ValidateCommand.class,
            CheckCommand.class,
            EvalDocCommand.class,
            ComputeDocCommand.class,
            AddCommand.class,
            ReadCommand.class,
            ExplainCommand.class,
            WhereUsedCommand.class,
            MetaCommand.class,
            ModifyCommand.class,
            AddFieldCommand.class,
            AddGroupCommand.class,
            AddTypedefCommand.class,
            AddIncludeCommand.class,
            RemoveCommand.class,
            BatchCommand.class,
            ApplyCommand.class,
            SchemaCommand.class,
            OperatorsCommand.class,
            DiagnosticsCommand.class,
            ManifestCommand.class
        })
final class RootCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Option(names = {"-m", "--model"}, scope = ScopeType.INHERIT, paramLabel = "<model.json>",
            description = "the DM-JSON model file to operate on (set once; accepted before or after the verb)")
    String model;

    @Option(names = {"-w", "--workspace"}, scope = ScopeType.INHERIT, paramLabel = "<dir>",
            description = "the workspace root: the directory tree to resolve included / type-def-imported "
                    + "models from, set once for every verb (so multi-file models resolve without a per-verb "
                    + "--include-dir). Env DMTOOL_WORKSPACE is the fallback; a verb's own --include-dir overrides.")
    String workspace;

    @Override
    public Integer call() {
        // no verb given → usage to stderr, exit 2 (preserves the prior bare-invocation behavior)
        spec.commandLine().getErr().println(spec.commandLine().getUsageMessage());
        return 2;
    }
}
