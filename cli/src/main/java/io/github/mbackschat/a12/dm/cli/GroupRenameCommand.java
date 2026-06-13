package io.github.mbackschat.a12.dm.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool group rename <groupPath> --to <newName>} — a reference-preserving rename (CLI-SPEC §6). It
 * renames the group and re-homes every descendant (their id-paths move in step; an internal relative-pathed
 * rule travels intact). The safety gate runs first: a new name already taken by a sibling is <b>refused</b>
 * ({@code RK_NAME_EXISTS}). Only when free does it rewrite every reference into the subtree to its new path
 * (the kernel's {@code MoveSupportDM}) and rename the group. The shared gate/rewrite/validate/write flow
 * lives in {@link ReferenceRewritingRefactorCommand}; the per-op specifics in {@link StructureRefactor#groupRename}.
 */
@Command(name = "rename", mixinStandardHelpOptions = true,
        description = "Rename a group (re-homing its subtree); references are rewritten (a taken name is refused).")
final class GroupRenameCommand extends ReferenceRewritingRefactorCommand {

    @ParamKind(ParamKind.Kind.GROUP_PATH)
    @Parameters(index = "0", paramLabel = "<groupPathInModel>", description = "the group's full name-path")
    String group;

    @ParamKind(ParamKind.Kind.NEW_NAME)
    @Option(names = "--to", required = true, paramLabel = "<newName>", description = "the group's new name")
    String newName;

    @Override
    StructureRefactor refactor() {
        return StructureRefactor.groupRename(group, newName);
    }
}
