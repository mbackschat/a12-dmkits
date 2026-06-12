package io.github.mbackschat.a12.dm.cli;

import java.util.Set;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool group rename <groupPath> --to <newName>} — a reference-preserving rename (CLI-SPEC §6). It
 * renames the group and re-homes every descendant (their id-paths move in step; an internal relative-pathed
 * rule travels intact). The safety gate runs first: a new name already taken by a sibling is <b>refused</b>
 * ({@code RK_NAME_EXISTS}). Only when free does it rewrite every reference into the subtree to its new path
 * (the kernel's {@code MoveSupportDM}) and rename the group; the result is kernel-revalidated. The shared
 * rewrite/validate/write flow lives in {@link ReferenceRewritingRefactorCommand}.
 */
@Command(name = "rename", mixinStandardHelpOptions = true,
        description = "Rename a group (re-homing its subtree); references are rewritten (a taken name is refused).")
final class GroupRenameCommand extends ReferenceRewritingRefactorCommand {

    @Parameters(index = "0", paramLabel = "<groupPathInModel>", description = "the group's full name-path")
    String group;

    @Option(names = "--to", required = true, paramLabel = "<newName>", description = "the group's new name")
    String newName;

    @Override String target() { return "group"; }
    @Override String op() { return "rename"; }
    @Override String changedKey() { return "renamed"; }
    @Override String source() { return group; }
    @Override String newPath() { return group.substring(0, group.lastIndexOf('/') + 1) + newName; }

    @Override
    SafetyGate gate(ModelDocument doc, String newPath) {
        return new SafetyGate()
                .require(SafetyChecks.exists(doc, group, true))
                .require(SafetyChecks.pathAvailable(doc, newPath));
    }

    @Override void mutate(ModelDocument doc) { doc.renameGroup(group, newName); }

    @Override
    Set<String> referrers(KernelAdapter adapter, ModelDocument doc) {
        return subtreeReferrers(adapter, doc, group);
    }
}
