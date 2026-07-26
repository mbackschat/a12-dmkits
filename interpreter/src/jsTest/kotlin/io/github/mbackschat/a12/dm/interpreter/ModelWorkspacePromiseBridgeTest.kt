package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.workspace.WorkspacePreparationFixtureJson
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.fail

class ModelWorkspacePromiseBridgeTest {

    @Test
    fun promiseProviderAndAbortSignalReachTheCommonCollector(): Promise<Unit> {
        val controller: dynamic = js("new AbortController()")
        val signal = controller.signal.unsafeCast<JsAbortSignal>()
        val caseId = "self-contained"
        val entryModelId = WorkspacePreparationFixtureJson.entryModelId(caseId)
        val source = WorkspaceModelSource.builder()
            .json(WorkspacePreparationFixtureJson.sources(caseId).getValue(entryModelId))
            .build()
        val requests = mutableListOf<String>()
        val receivedSignals = mutableListOf<JsAbortSignal>()
        val loader = PromiseModelSourceLoader { modelId, maximumBytes, receivedSignal ->
            requests += modelId
            receivedSignals += receivedSignal
            assertEquals(ModelPreparationLimits.DEFAULT_MAX_MODEL_BYTES, maximumBytes)
            controller.abort()
            Promise.resolve(source)
        }

        return collectWorkspacePromise(
            entryModelId = entryModelId,
            provider = PromiseModelSourceProvider(
                snapshotRevision = "manifest:promise-test",
                loader = loader,
                signal = signal,
            ),
            limits = ModelPreparationLimits.defaults(),
        ).then(
            onFulfilled = {
                fail("aborted collection must reject its Promise")
            },
            onRejected = { failure ->
                val error = failure as ModelPreparationError
                assertEquals(listOf(entryModelId), requests)
                assertEquals(1, receivedSignals.size)
                assertSame(signal, receivedSignals.single())
                assertEquals(
                    ModelSourceProviderFailureReason.CANCELLED,
                    error.diagnostics.single().providerFailureReason,
                )
                Unit
            },
        )
    }
}
