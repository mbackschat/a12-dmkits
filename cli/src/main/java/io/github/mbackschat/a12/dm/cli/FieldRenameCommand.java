package io.github.mbackschat.a12.dm.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool field rename <fieldPath> --to <newName>} — a reference-preserving rename (CLI-SPEC §6). The
 * safety gate runs first: a new name already taken by a sibling is <b>refused</b> ({@code RK_NAME_EXISTS}).
 * Only when free does it rewrite every referrer to the new path (the kernel's {@code MoveSupportDM}) and
 * rename the declaration. The shared gate/rewrite/validate/write flow lives in
 * {@link ReferenceRewritingRefactorCommand}; the per-op specifics in {@link StructureRefactor#fieldRename}.
 */
@Command(name = "rename", mixinStandardHelpOptions = true,
        description = "Rename a field; references are rewritten to the new name (a taken name is refused).")
final class FieldRenameCommand extends ReferenceRewritingRefactorCommand {

    @ParamKind(ParamKind.Kind.FIELD_PATH)
    @Parameters(index = "0", paramLabel = "<fieldPathInModel>", description = "the field's full name-path")
    String field;

    @ParamKind(ParamKind.Kind.NEW_NAME)
    @Option(names = "--to", required = true, paramLabel = "<newName>", description = "the field's new name")
    String newName;

    @Override
    StructureRefactor refactor() {
        return StructureRefactor.fieldRename(field, newName);
    }
}
