package io.github.mbackschat.a12.dm.cli.workspace;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * The {@code workspace} target group (CLI-SPEC §5) — cross-model discovery over a <b>directory</b> of models,
 * the level above the single-model {@code -m} verbs. A bare {@code dmtool workspace} prints its op list
 * (exit 2), like the root; the ops are its subcommands. First op: {@code workspace list} (the agent-friendly
 * "ls" into a folder of models).
 */
@Command(name = "workspace", mixinStandardHelpOptions = true,
        description = "Cross-model discovery over a directory of models: list (index + resolved references), "
                + "graph (the sub/supertype inheritance hierarchy), validate (the kernel gate over every model), "
                + "roles (the access-control lint — resolve each model's roles annotation against roles.yaml).",
        subcommands = { WorkspaceListCommand.class, WorkspaceGraphCommand.class, WorkspaceValidateCommand.class,
                WorkspaceRolesCommand.class })
public final class WorkspaceCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().getErr().println(spec.commandLine().getUsageMessage());
        return 2;
    }
}
