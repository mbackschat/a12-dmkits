package io.github.mbackschat.a12.dm.interpreter

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/**
 * Java acquisition port for the common collector. One provider instance represents one logical source snapshot;
 * returning `null` means that the requested exact model id does not exist.
 */
fun interface JavaModelSourceProvider {
    fun load(
        modelId: String,
        context: ModelSourceLoadContext,
    ): CompletionStage<WorkspaceModelSource?>

    val snapshotRevision: String? get() = null
}

/** Java-only asynchronous projection over the single common workspace collector. */
object ModelWorkspaceJava {
    @JvmStatic
    @JvmOverloads
    fun collect(
        entryModelId: String,
        provider: JavaModelSourceProvider,
        limits: ModelPreparationLimits = ModelPreparationLimits.defaults(),
    ): CompletionStage<ModelWorkspace> {
        val signal = JavaCollectionSignal()
        val collection = CollectionFuture<ModelWorkspace>(signal)
        val commonProvider = object : ModelSourceProvider {
            override val snapshotRevision: String? get() = provider.snapshotRevision

            override suspend fun load(
                modelId: String,
                context: ModelSourceLoadContext,
            ): WorkspaceModelSource? =
                try {
                    provider.load(modelId, context).await(signal)
                } catch (error: CancellationException) {
                    throw ModelSourceProviderError.builder()
                        .reason(ModelSourceProviderFailureReason.CANCELLED)
                        .modelId(modelId)
                        .cause(error)
                        .build()
                }
        }
        suspend {
            ModelWorkspace.collect(
                entryModelId = entryModelId,
                provider = commonProvider,
                limits = limits,
                signal = signal,
            )
        }.startCoroutine(object : Continuation<ModelWorkspace> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<ModelWorkspace>) {
                result.fold(collection::complete) { failure ->
                    collection.completeExceptionally(failure)
                }
            }
        })
        return collection
    }
}

private class CollectionFuture<T>(
    private val signal: JavaCollectionSignal,
) : CompletableFuture<T>() {
    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        val cancelled = super.cancel(mayInterruptIfRunning)
        if (cancelled) {
            signal.cancel(mayInterruptIfRunning)
        }
        return cancelled
    }
}

private class JavaCollectionSignal : ModelCollectionSignal {
    private val cancelled = AtomicBoolean()
    private val inFlight = AtomicReference<CompletableFuture<*>?>()

    override val isCancelled: Boolean get() = cancelled.get()

    fun track(future: CompletableFuture<*>) {
        inFlight.set(future)
        if (isCancelled) {
            future.cancel(true)
        }
    }

    fun release(future: CompletableFuture<*>) {
        inFlight.compareAndSet(future, null)
    }

    fun cancel(mayInterruptIfRunning: Boolean) {
        cancelled.set(true)
        inFlight.get()?.cancel(mayInterruptIfRunning)
    }
}

private suspend fun <T> CompletionStage<T>.await(signal: JavaCollectionSignal): T =
    suspendCoroutine { continuation ->
        val future = toCompletableFuture()
        signal.track(future)
        future.whenComplete { value, failure ->
            signal.release(future)
            if (failure == null) {
                continuation.resume(value)
            } else {
                continuation.resumeWithException(failure.unwrapCompletionFailure())
            }
        }
    }

private fun Throwable.unwrapCompletionFailure(): Throwable =
    when (this) {
        is CompletionException, is ExecutionException -> cause?.unwrapCompletionFailure() ?: this
        else -> this
    }
