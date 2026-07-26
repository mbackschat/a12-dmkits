package io.github.mbackschat.a12.dm.cli.model;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * The {@code doc} target group (CLI-SPEC §5) — whole-document operations on the model named by {@code -m}.
 * A bare {@code dmtool model} prints its op list (exit 2), like the root; the ops are its subcommands.
 */
@Command(name = "model", mixinStandardHelpOptions = true,
        description = "Whole-model operations: new, info, check, describe, read, usage, plus the runtime verbs "
                + "eval/compute/seed against a document instance, and JSON-Schema import/export.",
        subcommands = { ModelNewCommand.class, ModelInfoCommand.class, ModelCheckCommand.class, ModelDescribeCommand.class,
                ModelDiffCommand.class, ModelReadCommand.class, ModelUsageCommand.class, ModelReportCommand.class,
                ModelRenameCommand.class, ModelNormalizeCommand.class, ModelExpandCommand.class,
                ModelEvalCommand.class, ModelComputeCommand.class, ModelSeedCommand.class,
                ModelImportJsonschemaCommand.class, ModelExportJsonschemaCommand.class })
public final class ModelCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().getErr().println(spec.commandLine().getUsageMessage());
        return 2;
    }
}
