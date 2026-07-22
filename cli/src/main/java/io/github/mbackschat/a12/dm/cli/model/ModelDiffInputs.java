package io.github.mbackschat.a12.dm.cli.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import io.github.mbackschat.a12.dm.cli.WorkspaceOption;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.core.ReferenceResolvers;

/** Loads both diff sides and binds each to the matching current or Git dependency snapshot. */
record ModelDiffInputs(String baseJson, String headJson, ModelResolver baseResolver, ModelResolver headResolver) {

    static ModelDiffInputs twoFiles(String baseFile, String headFile, WorkspaceOption workspace,
            List<Path> baseWorkspace, List<Path> headWorkspace) throws IOException {
        String baseJson = Files.readString(Path.of(baseFile));
        String headJson = Files.readString(Path.of(headFile));
        List<Path> baseRoots = normalized(baseWorkspace.isEmpty() ? workspace.roots(baseFile) : baseWorkspace);
        List<Path> headRoots = normalized(headWorkspace.isEmpty() ? workspace.roots(headFile) : headWorkspace);
        ModelResolver baseResolver = lazyDirectory(baseRoots);
        ModelResolver headResolver = baseRoots.equals(headRoots)
                ? baseResolver : lazyDirectory(headRoots);
        return new ModelDiffInputs(baseJson, headJson, baseResolver, headResolver);
    }

    static ModelDiffInputs since(String modelFile, String ref, WorkspaceOption workspace) throws IOException {
        String headJson = Files.readString(Path.of(modelFile));
        List<Path> headRoots = normalized(workspace.roots(modelFile));
        ModelResolver headResolver = lazyDirectory(headRoots);
        GitBaseline.Snapshot base = GitBaseline.snapshotAt(Path.of(modelFile), ref, headRoots);
        return new ModelDiffInputs(base.content(), headJson, base.resolver(), headResolver);
    }

    private static List<Path> normalized(List<Path> roots) {
        return roots.stream().map(root -> root.toAbsolutePath().normalize()).toList();
    }

    private static ModelResolver lazyDirectory(List<Path> roots) {
        return new LazyDirectoryResolver(roots);
    }

    /** Avoids a workspace-tree walk for the common self-contained model. */
    private static final class LazyDirectoryResolver implements ModelResolver {
        private final List<Path> roots;
        private ModelResolver delegate;

        private LazyDirectoryResolver(List<Path> roots) {
            this.roots = roots;
        }

        @Override
        public String resolve(String modelId) {
            return delegate().resolve(modelId);
        }

        private synchronized ModelResolver delegate() {
            if (delegate == null) {
                delegate = ReferenceResolvers.directory(roots.toArray(Path[]::new));
            }
            return delegate;
        }
    }
}
