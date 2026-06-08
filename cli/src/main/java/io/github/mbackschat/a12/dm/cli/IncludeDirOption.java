package io.github.mbackschat.a12.dm.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;

import io.github.mbackschat.a12.dm.core.ReferenceResolvers;
import picocli.CommandLine.Option;

/**
 * Shared option for the model-loading verbs (F-RES): where to resolve a model's {@code include}d and
 * typedef-imported models from. A multi-file A12 application references other document models by id —
 * without a resolver the kernel's expand step fails with "model references a separate model …". Mixed
 * into each verb that loads a {@code <model.json>} so the whole CLI works on multi-file apps, not just
 * self-contained models.
 *
 * <p><b>Default:</b> the directory containing {@code <model.json>}, so sibling models resolve with no
 * flag. {@code --include-dir} (repeatable) points at the workspace tree(s) when the included models live
 * elsewhere. A directory resolver indexes every document model under the tree(s) by {@code header.id} and
 * is simply never called when the model is self-contained — so it is strictly safer than the old
 * self-contained default (which threw on the first reference).
 */
final class IncludeDirOption {

    @Option(names = "--include-dir", paramLabel = "<dir>", arity = "1",
            description = "directory tree(s) holding the included / typedef models to resolve by id "
                    + "(repeatable). Default: the directory containing <model.json>, so sibling models resolve automatically.")
    List<Path> includeDirs = new ArrayList<>();

    /**
     * The reference resolver for a model loaded from {@code modelPath}: the {@code --include-dir} trees if
     * any were given, else the model file's own directory.
     */
    IDocumentModelReferenceResolver resolverFor(String modelPath) {
        List<Path> roots = includeDirs.isEmpty() ? List.of(modelDir(modelPath)) : includeDirs;
        return ReferenceResolvers.directory(roots.toArray(Path[]::new));
    }

    private static Path modelDir(String modelPath) {
        Path parent = Path.of(modelPath).toAbsolutePath().getParent();
        return parent != null ? parent : Path.of(".");
    }
}
