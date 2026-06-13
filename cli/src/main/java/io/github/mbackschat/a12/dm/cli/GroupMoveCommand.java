package io.github.mbackschat.a12.dm.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool group move <groupPath> --to <newParentGroup>} — a reference-preserving move (CLI-SPEC §6).
 * It re-homes the group and every descendant (their id-paths move in step; an internal relative-pathed rule
 * travels intact). The safety gate runs first: a destination group that does not exist is <b>refused</b>
 * ({@code RK_NO_SUCH_GROUP}), a destination inside the moved subtree is refused ({@code RK_MOVE_INTO_SELF} —
 * it would orphan the subtree), and a name already taken under the destination is refused
 * ({@code RK_NAME_EXISTS}). Only when safe does it rewrite every reference into the subtree to its new path
 * (the kernel's {@code MoveSupportDM}) and re-home the group. The shared gate/rewrite/validate/write flow
 * lives in {@link ReferenceRewritingRefactorCommand}; the per-op specifics in {@link StructureRefactor#groupMove}.
 */
@Command(name = "move", mixinStandardHelpOptions = true,
        description = "Move a group (re-homing its subtree) to a different parent; references are rewritten.")
final class GroupMoveCommand extends ReferenceRewritingRefactorCommand {

    @ParamKind(ParamKind.Kind.GROUP_PATH)
    @Parameters(index = "0", paramLabel = "<groupPathInModel>", description = "the group's full name-path")
    String group;

    @ParamKind(ParamKind.Kind.GROUP_PATH)
    @Option(names = "--to", required = true, paramLabel = "<newParentGroup>",
            description = "the destination group's full name-path (the group keeps its name)")
    String newParent;

    @Override
    StructureRefactor refactor() {
        return StructureRefactor.groupMove(group, newParent);
    }
}
