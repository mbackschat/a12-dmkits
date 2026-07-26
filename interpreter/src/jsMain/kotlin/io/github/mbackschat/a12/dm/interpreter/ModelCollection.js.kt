package io.github.mbackschat.a12.dm.interpreter

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine
import kotlin.js.Promise

/** Minimal host projection used by the intentional TypeScript export façade introduced in R3.2. */
internal external interface JsAbortSignal {
    val aborted: Boolean
}

/** Promise-returning acquisition function kept behind the future exported TypeScript façade. */
internal fun interface PromiseModelSourceLoader {
    fun load(
        modelId: String,
        maximumBytes: Int,
        signal: JsAbortSignal,
    ): Promise<WorkspaceModelSource?>
}

/**
 * Kotlin/JS stdlib bridge from a Promise provider and AbortSignal to the common suspending collector. This remains
 * unexported until R3.2 assigns the deliberate JavaScript names and generated TypeScript surface.
 */
internal fun collectWorkspacePromise(
    entryModelId: String,
    provider: PromiseModelSourceProvider,
    limits: ModelPreparationLimits,
): Promise<ModelWorkspace> =
    promiseFromSuspend {
        ModelWorkspace.collect(
            entryModelId = entryModelId,
            provider = provider,
            limits = limits,
            signal = provider.collectionSignal(),
        )
    }

private class AbortSignalCollectionSignal(
    private val signal: JsAbortSignal,
) : ModelCollectionSignal {
    override val isCancelled: Boolean get() = signal.aborted
}

internal class PromiseModelSourceProvider(
    override val snapshotRevision: String?,
    private val loader: PromiseModelSourceLoader,
    private val signal: JsAbortSignal,
) : ModelSourceProvider {

    fun collectionSignal(): ModelCollectionSignal = AbortSignalCollectionSignal(signal)

    override suspend fun load(
        modelId: String,
        context: ModelSourceLoadContext,
    ): WorkspaceModelSource? =
        loader.load(modelId, context.maximumBytes, signal).awaitValue()
}

internal fun <T> promiseFromSuspend(block: suspend () -> T): Promise<T> =
    Promise { resolve, reject ->
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) {
                result.fold(resolve, reject)
            }
        })
    }

internal suspend fun <T> Promise<T>.awaitValue(): T =
    suspendCoroutine { continuation ->
        then(
            onFulfilled = { value -> continuation.resume(value) },
            onRejected = { error -> continuation.resumeWithException(error) },
        )
    }
