package io.github.mbackschat.a12.dm.cli;

import java.util.Set;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool field rename <fieldPath> --to <newName>} — a reference-preserving rename (CLI-SPEC §6). The
 * safety gate runs first: a new name already taken by a sibling is <b>refused</b> ({@code RK_NAME_EXISTS}).
 * Only when free does it rewrite every referrer to the new path (the kernel's {@code MoveSupportDM}) and
 * rename the declaration (name + path); the result is kernel-revalidated. The shared rewrite/validate/write
 * flow lives in {@link ReferenceRewritingRefactorCommand}.
 */
@Command(name = "rename", mixinStandardHelpOptions = true,
        description = "Rename a field; references are rewritten to the new name (a taken name is refused).")
final class FieldRenameCommand extends ReferenceRewritingRefactorCommand {

    @Parameters(index = "0", paramLabel = "<fieldPathInModel>", description = "the field's full name-path")
    String field;

    @Option(names = "--to", required = true, paramLabel = "<newName>", description = "the field's new name")
    String newName;

    @Override String target() { return "field"; }
    @Override String op() { return "rename"; }
    @Override String changedKey() { return "renamed"; }
    @Override String source() { return field; }
    @Override String newPath() { return field.substring(0, field.lastIndexOf('/') + 1) + newName; }

    @Override
    SafetyGate gate(ModelDocument doc, String newPath) {
        return new SafetyGate().require(SafetyChecks.pathAvailable(doc, newPath));
    }

    @Override void mutate(ModelDocument doc) { doc.renameField(field, newName); }

    @Override
    Set<String> referrers(KernelAdapter adapter, ModelDocument doc) {
        return singleReferrers(adapter, field);
    }
}
