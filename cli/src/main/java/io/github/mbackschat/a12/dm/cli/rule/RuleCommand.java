package io.github.mbackschat.a12.dm.cli.rule;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * The {@code rule} target group (CLI-SPEC §5) — validation-rule operations on the model named by {@code -m}.
 * A bare {@code dmtool rule} prints its op list (exit 2); the ops are its subcommands.
 */
@Command(name = "rule", mixinStandardHelpOptions = true,
        description = "Validation-rule operations: check, read, explain, deps, format, eval, add, modify, remove, rename. "
                + "`rule eval` runs a rule against a sample document on the native-safe interpreter (the sole runtime eval engine).",
        subcommands = { RuleCheckCommand.class, RuleReadCommand.class, RuleExplainCommand.class,
                RuleDepsCommand.class, RuleFormatCommand.class, RuleEvalCommand.class, RuleAddCommand.class,
                RuleModifyCommand.class, RuleRemoveCommand.class, RuleRenameCommand.class })
public final class RuleCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().getErr().println(spec.commandLine().getUsageMessage());
        return 2;
    }
}
