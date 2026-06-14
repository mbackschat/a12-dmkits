package io.github.mbackschat.a12.dm.cli;

import java.nio.file.Path;
import java.util.List;

import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;

import io.github.mbackschat.a12.dm.core.ReferenceResolvers;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Resolves a model's referenced models — its {@code include}s <b>and</b> type-def {@code import}s — from the
 * <b>workspace root</b>, for every verb that loads a {@code -m <model.json>} (F-RES). A multi-file A12 model
 * references others by id; without a resolver the kernel's expand fails with "model references a separate
 * model …". This mixin carries <b>no option of its own</b>: it reads the inherited {@code -w/--workspace}
 * root off the root command (via {@link Spec.Target#MIXEE}), so the whole CLI shares one workspace concept
 * and one resolution rule — no per-verb flag to thread through ~60 call sites.
 *
 * <p><b>Resolution precedence:</b> the {@code -w/--workspace} root(s) (set once for every verb, repeatable)
 * &gt; the {@code DMTOOL_WORKSPACE} environment variable (the set-once-per-shell-session default) &gt; the
 * directory containing {@code <model.json>}. A directory resolver indexes every document model under the
 * chosen tree(s) by {@code header.id}; it is simply never called when the model is self-contained.
 */
@Command // a mixin must be a "command"; it carries no options of its own — just @Spec, to read the root's -w
final class WorkspaceOption {

    /** The environment variable that sets the session workspace root (lowest-precedence source). */
    static final String WORKSPACE_ENV = "DMTOOL_WORKSPACE";

    // the spec of the command this mixin is mixed INTO — so it can read the inherited -w/--workspace off the root
    @Spec(Spec.Target.MIXEE)
    CommandSpec mixee;

    /** The reference resolver for a model loaded from {@code modelPath}, over the {@link #roots(String) roots}. */
    IDocumentModelReferenceResolver resolverFor(String modelPath) {
        return ReferenceResolvers.directory(roots(modelPath).toArray(Path[]::new));
    }

    /**
     * The resolution roots for {@code modelPath} under the precedence — exposed so a kernel-free caller (the
     * {@code model info} / {@code workspace} scan path, and the include extract/inline write paths) resolve
     * references the same way the kernel resolver does.
     */
    List<Path> roots(String modelPath) {
        return roots(workspaceRoots(), System.getenv(WORKSPACE_ENV), modelPath);
    }

    /** The {@code -w/--workspace} root(s) inherited from the root command, or empty. */
    private List<Path> workspaceRoots() {
        return mixee != null && mixee.root().userObject() instanceof RootCommand root ? root.workspace : List.of();
    }

    /**
     * Pure precedence (so it is unit-testable, env included): the {@code -w/--workspace} root(s) &gt;
     * {@code $DMTOOL_WORKSPACE} &gt; the model's own directory.
     */
    static List<Path> roots(List<Path> workspace, String workspaceEnv, String modelPath) {
        if (!workspace.isEmpty()) {
            return List.copyOf(workspace);
        }
        if (workspaceEnv != null && !workspaceEnv.isBlank()) {
            return List.of(Path.of(workspaceEnv));
        }
        return List.of(modelDir(modelPath));
    }

    private static Path modelDir(String modelPath) {
        Path parent = Path.of(modelPath).toAbsolutePath().getParent();
        return parent != null ? parent : Path.of(".");
    }
}
