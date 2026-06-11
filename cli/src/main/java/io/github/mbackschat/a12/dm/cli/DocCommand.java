package io.github.mbackschat.a12.dm.cli;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * The {@code doc} target group (CLI-SPEC §5) — whole-document operations on the model named by {@code -m}.
 * A bare {@code dmtool doc} prints its op list (exit 2), like the root; the ops are its subcommands.
 */
@Command(name = "doc", mixinStandardHelpOptions = true,
        description = "Whole-document operations (validate; describe/export/eval/compute to follow).",
        subcommands = { DocValidateCommand.class })
final class DocCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().getErr().println(spec.commandLine().getUsageMessage());
        return 2;
    }
}
