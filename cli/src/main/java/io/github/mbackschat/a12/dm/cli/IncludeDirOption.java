package io.github.mbackschat.a12.dm.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;

import io.github.mbackschat.a12.dm.core.ReferenceResolvers;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Shared option for the model-loading verbs (F-RES): where to resolve a model's {@code include}d and
 * typedef-imported models from. A multi-file A12 application references other document models by id —
 * without a resolver the kernel's expand step fails with "model references a separate model …". Mixed
 * into each verb that loads a {@code <model.json>} so the whole CLI works on multi-file apps, not just
 * self-contained models.
 *
 * <p><b>Resolution precedence</b> (the auto-resolving "workspace root"): a per-verb {@code --include-dir}
 * wins; else the session <b>{@code -w/--workspace}</b> root (the inherited root option, set once for all
 * verbs); else the {@code DMTOOL_WORKSPACE} environment variable (the true set-once-per-shell-session
 * default); else the directory containing {@code <model.json>}. So a multi-file workspace resolves with no
 * per-verb flag once the root is set, and an explicit {@code --include-dir} still overrides. A directory
 * resolver indexes every document model under the chosen tree(s) by {@code header.id}; it is simply never
 * called when the model is self-contained.
 */
final class IncludeDirOption {

    /** The environment variable that sets the session workspace root (lowest-precedence source). */
    static final String WORKSPACE_ENV = "DMTOOL_WORKSPACE";

    // the spec of the command this option is mixed INTO — so the mixin can read the inherited -w/--workspace
    // off the root without every verb threading it through (avoids touching ~60 resolverFor call sites)
    @Spec(Spec.Target.MIXEE)
    CommandSpec mixee;

    @Option(names = "--include-dir", paramLabel = "<dir>", arity = "1",
            description = "directory tree(s) holding the included / typedef models to resolve by id "
                    + "(repeatable). Overrides the -w/--workspace root and $DMTOOL_WORKSPACE for this verb; "
                    + "default: the directory containing <model.json>.")
    List<Path> includeDirs = new ArrayList<>();

    /**
     * The reference resolver for a model loaded from {@code modelPath}, over the resolution {@link
     * #roots(String) roots} (the precedence above).
     */
    IDocumentModelReferenceResolver resolverFor(String modelPath) {
        return ReferenceResolvers.directory(roots(modelPath).toArray(Path[]::new));
    }

    /**
     * The resolution roots for {@code modelPath} under the precedence — exposed so a kernel-free caller (the
     * {@code model info} / {@code workspace} scan path) resolves references the same way the kernel resolver does.
     */
    List<Path> roots(String modelPath) {
        return roots(includeDirs, workspaceRoot(), System.getenv(WORKSPACE_ENV), modelPath);
    }

    /** The session workspace root from the inherited {@code -w/--workspace} option on the root command, or {@code null}. */
    private String workspaceRoot() {
        return mixee != null && mixee.root().userObject() instanceof RootCommand root ? root.workspace : null;
    }

    /**
     * Pure precedence (so it is unit-testable, env included): explicit {@code --include-dir} &gt;
     * {@code -w/--workspace} &gt; {@code $DMTOOL_WORKSPACE} &gt; the model's own directory.
     */
    static List<Path> roots(List<Path> includeDirs, String workspaceFlag, String workspaceEnv, String modelPath) {
        if (!includeDirs.isEmpty()) {
            return List.copyOf(includeDirs);
        }
        if (notBlank(workspaceFlag)) {
            return List.of(Path.of(workspaceFlag));
        }
        if (notBlank(workspaceEnv)) {
            return List.of(Path.of(workspaceEnv));
        }
        return List.of(modelDir(modelPath));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static Path modelDir(String modelPath) {
        Path parent = Path.of(modelPath).toAbsolutePath().getParent();
        return parent != null ? parent : Path.of(".");
    }
}
