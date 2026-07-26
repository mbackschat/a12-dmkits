package io.github.mbackschat.a12.dm.cli.interp;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.core.UnresolvedReferenceException;
import io.github.mbackschat.a12.dm.interpreter.JavaModelSourceProvider;
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationError;
import io.github.mbackschat.a12.dm.interpreter.ModelWorkspace;
import io.github.mbackschat.a12.dm.interpreter.ModelWorkspaceJava;
import io.github.mbackschat.a12.dm.interpreter.PreparedModel;
import io.github.mbackschat.a12.dm.interpreter.WorkspaceModelSource;

/**
 * dmtool's filesystem/resolver acquisition adapter over the public portable workspace boundary. It supplies
 * only exact ids requested by the common collector; include/typedef discovery and all assembly semantics stay
 * in {@link ModelWorkspace}.
 */
public final class PortableModelPreparation {

    private PortableModelPreparation() {
    }

    /**
     * Resolve and assemble the entry model's discovered closure, stopping before interpreter-only evaluability
     * checks. This is the model-conversion boundary used by {@code model expand}.
     */
    public static String expand(String entryJson, String diagnosticLabel, ModelResolver resolver) {
        CollectedEntry collected = collect(entryJson, diagnosticLabel, resolver);
        return collected.workspace().expand(collected.entryModelId());
    }

    /**
     * Assemble once, then run the public strict expanded-input preparation gate over those exact bytes. The
     * returned JSON is what the established CLI evaluator consumes; using the fast path avoids assembling the
     * same closure a second time while preserving {@link ModelWorkspace#prepare} semantics.
     */
    public static String prepareForRuntime(String entryJson, String diagnosticLabel, ModelResolver resolver) {
        String expanded = expand(entryJson, diagnosticLabel, resolver);
        PreparedModel.loadExpandedJson(expanded);
        return expanded;
    }

    private static CollectedEntry collect(String entryJson, String diagnosticLabel, ModelResolver resolver) {
        WorkspaceModelSource entry = source(entryJson, diagnosticLabel);
        String entryModelId = ModelWorkspace.fromSources(List.of(entry)).getModelIds().getFirst();
        JavaModelSourceProvider provider = (modelId, context) -> {
            if (entryModelId.equals(modelId)) {
                return CompletableFuture.completedFuture(entry);
            }
            String json;
            try {
                json = resolver.resolve(modelId);
            } catch (UnresolvedReferenceException missing) {
                json = null;
            }
            return CompletableFuture.completedFuture(
                    json == null ? null : source(json, modelId));
        };
        try {
            ModelWorkspace workspace = ModelWorkspaceJava.collect(entryModelId, provider)
                    .toCompletableFuture()
                    .join();
            return new CollectedEntry(entryModelId, workspace);
        } catch (CompletionException failed) {
            if (failed.getCause() instanceof ModelPreparationError preparation) {
                throw preparation;
            }
            throw failed;
        }
    }

    private static WorkspaceModelSource source(String json, String diagnosticLabel) {
        return WorkspaceModelSource.builder()
                .json(json)
                .diagnosticLabel(diagnosticLabel)
                .build();
    }

    private record CollectedEntry(String entryModelId, ModelWorkspace workspace) {
    }
}
