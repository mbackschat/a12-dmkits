package io.github.mbackschat.a12.dm.cli;

import io.github.mbackschat.a12.dm.cli.computation.ComputationCommand;
import io.github.mbackschat.a12.dm.cli.config.ConfigCommand;
import io.github.mbackschat.a12.dm.cli.field.FieldCommand;
import io.github.mbackschat.a12.dm.cli.group.GroupCommand;
import io.github.mbackschat.a12.dm.cli.include.IncludeCommand;
import io.github.mbackschat.a12.dm.cli.model.ModelCommand;
import io.github.mbackschat.a12.dm.cli.rule.RuleCommand;
import io.github.mbackschat.a12.dm.cli.typedef.TypedefCommand;
import io.github.mbackschat.a12.dm.cli.workspace.WorkspaceCommand;

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
        name = Cli.TOOL_NAME,
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        synopsisSubcommandLabel = "<target> <op>",
        description = "Typed, model-aware authoring & edit kit for A12 document models. JSON in / JSON out.",
        // R1 orientation footer (CLI-ORIENTATION-PROPOSAL §3): the read-when map in miniature, shown at
        // first contact. Routes a cold agent to the catalog verbs and positions `manifest` as the one-call
        // BULK map (not the only door — agents navigate via --help + the catalog verbs; see CLI-SPEC §2).
        // Tool name is single-sourced via Cli.TOOL_NAME (a compile-time constant — never re-hardcoded);
        // every command ref resolves (CommandReferencesResolveTest), and only the ROOT carries this footer.
        footerHeading = "%nGetting started (the tool describes itself):%n",
        footer = {
            "  Whole verb tree as JSON, one call:     " + Cli.TOOL_NAME + " manifest",
            "  A verb's params + a worked example:    " + Cli.TOOL_NAME + " <target> <op> --help",
            "  DSL vocabulary (all, or one by <id>):  " + Cli.TOOL_NAME + " operators",
            "  Rule/computation idioms (scaffolds):   " + Cli.TOOL_NAME + " patterns",
            "  Decode an error code:                  " + Cli.TOOL_NAME + " diagnostics <CODE>",
        },
        subcommands = {
            ModelCommand.class,
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
            PatternsCommand.class,
            DiagnosticsCommand.class,
            ManifestCommand.class
        })
final class RootCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Option(names = {"-m", "--model"}, scope = ScopeType.INHERIT, paramLabel = "<model.json>",
            description = "the DM-JSON model file to operate on (set once; accepted before or after the verb)")
    String model;

    @Option(names = {"-w", "--workspace"}, scope = ScopeType.INHERIT, arity = "1", paramLabel = "<dir>",
            description = "the workspace root(s): directory tree(s) to resolve a model's included / "
                    + "type-def-imported models from (repeatable), set once for every verb so multi-file models "
                    + "resolve with no per-verb flag. Env DMTOOL_WORKSPACE is the set-once-per-session fallback; "
                    + "default is the model's own folder.")
    java.util.List<java.nio.file.Path> workspace = new java.util.ArrayList<>();

    // R2 (CLI-ORIENTATION-PROPOSAL §3): render the result envelope as human text instead of JSON, for the
    // secondary direct-BA/dev path. JSON stays the default + the machine contract; this is opt-in (never
    // TTY-detected, so an agent in a PTY can't silently get text). Declared here so it is self-describing
    // (manifest/--help); the value is detected by an arg-scan in Cli.run *before* execute (to set up output
    // capture), so this field exists only to surface + accept the flag.
    @Option(names = "--text", scope = ScopeType.INHERIT,
            description = "render the result envelope as human-readable text instead of JSON (JSON is the "
                    + "default and the machine contract). Catalogs (operators/manifest/…) stay JSON.")
    boolean text;

    @Override
    public Integer call() {
        // no verb given → usage to stderr, exit 2 (preserves the prior bare-invocation behavior)
        spec.commandLine().getErr().println(spec.commandLine().getUsageMessage());
        return 2;
    }
}
