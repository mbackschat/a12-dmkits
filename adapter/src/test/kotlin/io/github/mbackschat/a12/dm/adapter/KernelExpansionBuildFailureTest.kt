package io.github.mbackschat.a12.dm.adapter

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws
import io.github.mbackschat.a12.dm.core.ModelConsistencyException
import io.github.mbackschat.a12.dm.core.ModelLoader
import io.github.mbackschat.a12.dm.core.UnresolvedReferenceException
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import java.io.IOException
import java.io.UncheckedIOException
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

/**
 * A model the kernel cannot build is a verdict on the caller's bytes wherever in the kernel the build dies
 * (KERNEL-FINDINGS KF259). Kernel 31.1.1's `CombinationModelService.expand` copies the model through its serializer,
 * whose basic consistency pass runs INSIDE expansion, so a crash that 30.8.1 raised inside `checkConsistency` now
 * escapes from expand: a computation whose target names a group dies in `ComputationWrapper.getComputedField` with a
 * `ClassCastException` (measured 2026-11-02 by `laws.CheckerBasicSentenceAgreementTest`).
 *
 * The fold must not swallow what our own resolver bridge raises inside that same call, because those belong to an
 * earlier tier that reports itself; the two control rows keep that boundary, one for the bridge's own failure and
 * one for a consumer resolver's.
 */
class KernelExpansionBuildFailureTest {

    private val base = DocumentModelDraft.named("policy").locale("en_US")
        .rootGroup("Policy") { policy ->
            policy.field("Total", FieldKind.NUMBER)
            policy.group("Items") { it.field("Amount", FieldKind.NUMBER) }
        }
        .build().toJson()

    @Test
    fun aKernelCrashDuringExpansionIsTheModelsVerdict() {
        val groupTarget = RuntimeLaws.withComputation(base, "/Policy", "Calc", "../Items", "1")

        val failure = assertThrows<ModelConsistencyException> { ModelLoader.loadSelfContained(groupTarget) }

        assertTrue(failure.message!!.contains("the kernel could not build this model"), failure.message)
        assertDoesNotThrow("the same computation on a field is a model the kernel builds") {
            ModelLoader.loadSelfContained(RuntimeLaws.withComputation(base, "/Policy", "Calc", "../Total", "1"))
        }
    }

    @Test
    fun aResolverFailureInsideExpansionStillReportsItself() {
        val entry = DocumentModelDraft.named("permit").locale("en_US")
            .rootGroup("Permit") { it.mount("Details", "component") }
            .build().toJson()

        assertThrows<UnresolvedReferenceException>("the bridge's own refusal") {
            ModelLoader.loadAndCheck(entry) { null }
        }
        assertThrows<UncheckedIOException>("a consumer resolver's failure") {
            ModelLoader.loadAndCheck(entry) { throw UncheckedIOException(IOException("disk gone")) }
        }
    }
}
