package io.github.mbackschat.a12.dm.interpreter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

public final class ModelWorkspaceJavaConsumerFixture {

    public static void verify() throws Exception {
        completionStageProviderCollectsAndPreparesThroughOnlyPublicTypes();
        cancellingReturnedFutureReachesTheProviderSignalAndInFlightStage();
        providerStageCancellationBecomesThePortableCancelledDiagnostic();
    }

    private static void completionStageProviderCollectsAndPreparesThroughOnlyPublicTypes() throws Exception {
        var source = WorkspaceModelSource.builder()
                .json(fixture("workspace-preparation/self-contained/permit-basic.dm.json"))
                .build();
        var requests = new AtomicReference<String>();
        JavaModelSourceProvider provider = (modelId, context) -> {
            requests.set(modelId);
            return CompletableFuture.completedFuture(source);
        };

        var workspace = ModelWorkspaceJava.collect("permit-basic", provider)
                .toCompletableFuture()
                .get();

        requireEquals("permit-basic", requests.get());
        requireEquals("permit-basic", workspace.prepare("permit-basic").getEntryModelId());
    }

    private static void cancellingReturnedFutureReachesTheProviderSignalAndInFlightStage() {
        var load = new CompletableFuture<WorkspaceModelSource>();
        var context = new AtomicReference<ModelSourceLoadContext>();
        JavaModelSourceProvider provider = (modelId, suppliedContext) -> {
            context.set(suppliedContext);
            return load;
        };
        var collection = ModelWorkspaceJava.collect("permit-basic", provider).toCompletableFuture();

        collection.cancel(true);

        requireTrue(collection.isCancelled());
        requireTrue(load.isCancelled());
        requireTrue(context.get().getSignal().isCancelled());
    }

    private static void providerStageCancellationBecomesThePortableCancelledDiagnostic() {
        JavaModelSourceProvider provider = (modelId, context) -> {
            var cancelled = new CompletableFuture<WorkspaceModelSource>();
            cancelled.cancel(true);
            return cancelled;
        };

        try {
            ModelWorkspaceJava.collect("permit-basic", provider).toCompletableFuture().get();
            throw new AssertionError("expected provider cancellation to fail collection");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("collection was interrupted", interrupted);
        } catch (ExecutionException failed) {
            var preparation = (ModelPreparationError) failed.getCause();
            requireEquals(
                    ModelSourceProviderFailureReason.CANCELLED,
                    preparation.getDiagnostics().getFirst().getProviderFailureReason());
        }
    }

    private static String fixture(String resource) throws IOException {
        try (var stream = ModelWorkspaceJavaConsumerFixture.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("missing test resource " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void requireEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected " + expected + ", got " + actual);
        }
    }

    private static void requireTrue(boolean condition) {
        if (!condition) {
            throw new AssertionError("expected true");
        }
    }
}
