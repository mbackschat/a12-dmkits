package io.github.mbackschat.a12.dm.interpreter

import java.lang.reflect.Executable
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertTrue

class PublicPreparationApiSurfaceTest {

    @Test
    fun r2_6CallablePublicPreparationSurfaceDoesNotExposeImplementationTypes() {
        val leaks = R2_6_PUBLIC_PREPARATION_TYPES.flatMap { type ->
            type.methods.asList() + type.declaredConstructors.asList()
        }.filter { executable ->
            executable.isCallablePublic() &&
                INTERNAL_PACKAGE_MARKERS.any { marker -> marker in executable.toGenericString() }
        }

        assertTrue(
            leaks.isEmpty(),
            "public preparation API leaks implementation types: " +
                leaks.joinToString { it.toGenericString() },
        )
    }

    @Test
    fun opaqueIdentitiesCannotBeMintedByJavaCallers() {
        for (identityType in listOf(ModelOwnerIdentity::class.java, ModelVersionIdentity::class.java)) {
            assertTrue(
                identityType.declaredConstructors.none { it.isCallablePublic() },
                "${identityType.simpleName} must be created only by PreparedModel",
            )
        }
    }

    @Test
    fun outputOnlyPreparationValuesCannotBeConstructedByJavaCallers() {
        for (outputType in listOf(
            ModelReferenceStep::class.java,
            ModelDependency::class.java,
            ModelPreparationError::class.java,
        )) {
            assertTrue(
                outputType.declaredConstructors.none { it.isCallablePublic() },
                "${outputType.simpleName} is produced by preparation and must not expose a Java constructor",
            )
        }
    }

    private fun Executable.isCallablePublic(): Boolean =
        Modifier.isPublic(modifiers) && !isSynthetic

    private companion object {
        // R3.3 owns the complete packaged Kotlin/TypeScript ABI dump. This focused pre-publication lock covers every
        // type intentionally introduced by R2.6, including the nested builders and result variants.
        val R2_6_PUBLIC_PREPARATION_TYPES = listOf(
            WorkspaceModelSource::class.java,
            WorkspaceModelSource.Companion::class.java,
            WorkspaceModelSource.Builder::class.java,
            ModelPreparationLimits::class.java,
            ModelPreparationLimits.Companion::class.java,
            ModelPreparationLimits.Builder::class.java,
            ModelReferencePurpose::class.java,
            ModelReferencePurpose.Companion::class.java,
            ModelReferenceStep::class.java,
            ModelReferenceStep.Companion::class.java,
            ModelDependency::class.java,
            ModelDependency.Companion::class.java,
            ModelPreparationDiagnosticCode::class.java,
            ModelPreparationResource::class.java,
            ModelPreparationDiagnostic::class.java,
            ModelPreparationError::class.java,
            ModelPreparationError.Companion::class.java,
            ModelPreparationResult::class.java,
            ModelPreparationResult.Success::class.java,
            ModelPreparationResult.Failure::class.java,
            ModelOwnerIdentity::class.java,
            ModelOwnerIdentity.Companion::class.java,
            ModelVersionIdentity::class.java,
            ModelVersionIdentity.Companion::class.java,
            ModelOwned::class.java,
            ModelWorkspace::class.java,
            ModelWorkspace.Companion::class.java,
            PreparedModel::class.java,
            PreparedModel.Companion::class.java,
        )

        val INTERNAL_PACKAGE_MARKERS = listOf(
            ".interpreter.eval.",
            ".interpreter.load.",
            ".interpreter.model.",
            ".interpreter.workspace.",
            "kotlinx.serialization.",
        )
    }
}
