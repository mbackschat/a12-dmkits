package io.github.mbackschat.a12.dm.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool field move <fieldPath> --to <newParentGroup>} — a reference-preserving move (CLI-SPEC §6).
 * The safety gate runs first: a destination group that does not exist is <b>refused</b>
 * ({@code RK_NO_SUCH_GROUP}) and a name already taken under the destination is refused
 * ({@code RK_NAME_EXISTS}). Only when safe does it rewrite every referrer to the field's new path (the
 * kernel's {@code MoveSupportDM}) and re-home the declaration. The shared gate/rewrite/validate/write flow
 * lives in {@link ReferenceRewritingRefactorCommand}; the per-op specifics in {@link StructureRefactor#fieldMove}.
 */
@Command(name = "move", mixinStandardHelpOptions = true,
        description = "Move a field to a different parent group; references are rewritten to the new path.")
final class FieldMoveCommand extends ReferenceRewritingRefactorCommand {

    @ParamKind(ParamKind.Kind.FIELD_PATH)
    @Parameters(index = "0", paramLabel = "<fieldPathInModel>", description = "the field's full name-path")
    String field;

    @ParamKind(ParamKind.Kind.GROUP_PATH)
    @Option(names = "--to", required = true, paramLabel = "<newParentGroup>",
            description = "the destination group's full name-path (the field keeps its name)")
    String newParent;

    @Override
    StructureRefactor refactor() {
        return StructureRefactor.fieldMove(field, newParent);
    }
}
