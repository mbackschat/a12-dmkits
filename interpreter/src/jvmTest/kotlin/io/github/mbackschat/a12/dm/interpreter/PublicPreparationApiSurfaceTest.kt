package io.github.mbackschat.a12.dm.interpreter

import java.lang.reflect.Executable
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PublicPreparationApiSurfaceTest {

    @Test
    fun r2_6CallablePublicPreparationSurfaceDoesNotExposeImplementationTypes() {
        val leaks = implementationLeaksFrom(R2_6_PUBLIC_PREPARATION_TYPES)

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

    @Test
    fun leakDetectorCoversDirectAndGenericTypesFromThePublicRootPackage() {
        val leaks = implementationLeaksFrom(listOf(RootPackageLeak::class.java))

        assertTrue(
            leaks.map(Executable::getName).containsAll(listOf("direct", "generic")),
            "the focused guard must recognize root-package implementation types in direct and generic signatures",
        )
    }

    @Test
    fun preparationFailuresUseTheirNamedRuntimeTypeRatherThanArgumentClassification() {
        assertTrue(RuntimeException::class.java.isAssignableFrom(ModelPreparationError::class.java))
        assertFalse(IllegalArgumentException::class.java.isAssignableFrom(ModelPreparationError::class.java))
    }

    @Test
    fun timeZoneFailuresRequireTheStructuredOffendingId() {
        for (errorType in listOf(
            UnsupportedModelTimeZoneException::class.java,
            InvalidModelTimeZoneException::class.java,
        )) {
            val publicConstructors = errorType.declaredConstructors.filter { it.isCallablePublic() }

            assertTrue(
                publicConstructors.all { it.parameterTypes.contentEquals(arrayOf(String::class.java, String::class.java)) },
                "${errorType.simpleName} must not expose a message-only compatibility constructor",
            )
        }
    }

    private fun implementationLeaksFrom(types: List<Class<*>>): List<Executable> =
        types.flatMap { type ->
            type.methods.asList() + type.declaredConstructors.asList()
        }.filter { executable ->
            executable.isCallablePublic() &&
                executable.referencedClasses().any { referenced ->
                    referenced.name.startsWith(PROJECT_PACKAGE_PREFIX) &&
                        referenced !in R2_6_PUBLIC_PREPARATION_TYPE_SET
                }
        }

    private fun Executable.isCallablePublic(): Boolean =
        Modifier.isPublic(modifiers) && !isSynthetic

    private fun Executable.referencedClasses(): Set<Class<*>> =
        buildSet {
            val visited = mutableSetOf<Type>()
            if (this@referencedClasses is java.lang.reflect.Method) {
                addClasses(genericReturnType, visited)
            }
            genericParameterTypes.forEach { addClasses(it, visited) }
            genericExceptionTypes.forEach { addClasses(it, visited) }
            typeParameters.forEach { addClasses(it, visited) }
        }

    private fun MutableSet<Class<*>>.addClasses(type: Type, visited: MutableSet<Type>) {
        if (!visited.add(type)) {
            return
        }
        when (type) {
            is Class<*> -> if (type.isArray) addClasses(type.componentType, visited) else add(type)
            is ParameterizedType -> {
                addClasses(type.rawType, visited)
                type.ownerType?.let { addClasses(it, visited) }
                type.actualTypeArguments.forEach { addClasses(it, visited) }
            }
            is GenericArrayType -> addClasses(type.genericComponentType, visited)
            is WildcardType -> {
                type.lowerBounds.forEach { addClasses(it, visited) }
                type.upperBounds.forEach { addClasses(it, visited) }
            }
            is TypeVariable<*> -> type.bounds.forEach { addClasses(it, visited) }
        }
    }

    private class RootPackageLeak {
        fun direct(): PreparedEvaluationPlan? = null
        fun generic(): List<PreparedEvaluationPlan> = emptyList()
    }

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

        val R2_6_PUBLIC_PREPARATION_TYPE_SET = R2_6_PUBLIC_PREPARATION_TYPES.toSet()
        const val PROJECT_PACKAGE_PREFIX = "io.github.mbackschat.a12.dm.interpreter"
    }
}
