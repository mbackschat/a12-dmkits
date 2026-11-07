package io.github.mbackschat.a12.dm.adapter

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws
import io.github.mbackschat.a12.dm.core.ConsistencyCheckFailedException
import io.github.mbackschat.a12.dm.core.ModelConsistencyException
import io.github.mbackschat.a12.dm.core.ModelLoader
import io.github.mbackschat.a12.dm.core.UnresolvedReferenceException
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import java.io.IOException
import java.io.UncheckedIOException
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

/**
 * A model the kernel cannot build is a verdict on the caller's bytes wherever in the kernel the build dies
 * (KERNEL-FINDINGS KF259). The kernel's `CombinationModelService.expand` copies the model through its serializer, so a
 * model the serializer cannot write dies inside expansion, before `checkConsistency` is reached: a computation with no
 * `computedFieldRelPath` reaches `ComputationSerializer` with a null reference (measured 2026-11-07 on kernel 31.3.1,
 * which `KernelModelPreparation.expand` throwing shows, since it stops before the consistency check). Kernel 31.1.1's
 * trigger, a computation whose target names a group, is a converter sentence on 31.3.1 instead (KF652).
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
        val withTarget = RuntimeLaws.withComputation(base, "/Policy", "Calc", "../Total", "1")
        val noTarget = withTarget.replace(Regex("\"computedFieldRelPath\"\\s*:\\s*\"\\.\\./Total\"\\s*,"), "")
        assertNotEquals(withTarget, noTarget, "the fixture must lose its computed field")

        assertThrows<ConsistencyCheckFailedException>("the crash is raised by expansion") {
            KernelModelPreparation.expand(noTarget) { null }
        }
        val failure = assertThrows<ModelConsistencyException> { ModelLoader.loadSelfContained(noTarget) }
        assertTrue(failure.message!!.contains("the kernel could not build this model"), failure.message)
        assertDoesNotThrow("the same computation with its computed field is a model the kernel builds") {
            ModelLoader.loadSelfContained(withTarget)
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
