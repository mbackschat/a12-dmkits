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
    fun callablePublicPreparationSurfaceDoesNotExposeImplementationTypes() {
        val leaks = implementationLeaksFrom(PUBLIC_PREPARATION_TYPES)

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
            ModelSourceLoadContext::class.java,
            DocumentInputError::class.java,
            ModelOwnerMismatchError::class.java,
            DocumentSnapshot::class.java,
            UnknownModelPathError::class.java,
            DocumentCoordinateError::class.java,
            GroupPointer::class.java,
            FieldPointer::class.java,
            RelevantSelectionPointer::class.java,
            ModelMetadata::class.java,
            InterpreterSupportReport::class.java,
            ValidationReport::class.java,
            ComputationReport::class.java,
            CustomConditionDocumentView::class.java,
            CustomConditionInvocation::class.java,
            Interpreter::class.java,
        )) {
            assertTrue(
                outputType.declaredConstructors.none { it.isCallablePublic() },
                "${outputType.simpleName} is produced by preparation and must not expose a Java constructor",
            )
        }
    }

    @Test
    fun suspendingCollectorStaysOutsideTheJavaSurface() {
        assertTrue(
            ModelWorkspace::class.java.methods.none { method ->
                method.name == "collect" && method.isCallablePublic()
            },
            "Java callers use ModelWorkspaceJava.collect rather than a Continuation-shaped common method",
        )
    }

    @Test
    fun javaCompletionStageConsumerFixtureCompilesAndExecutes() {
        Class.forName("io.github.mbackschat.a12.dm.interpreter.ModelWorkspaceJavaConsumerFixture")
            .getMethod("verify")
            .invoke(null)
    }

    @Test
    fun providerLoadContextHasNoJavaCallableFactory() {
        assertTrue(
            ModelSourceLoadContext.Companion::class.java.methods.none { method ->
                method.returnType == ModelSourceLoadContext::class.java && method.isCallablePublic()
            },
            "ModelSourceLoadContext must be created only by the collector",
        )
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
                        referenced !in PUBLIC_PREPARATION_TYPE_SET
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
        val PUBLIC_PREPARATION_TYPES = listOf(
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
            ModelSourceProviderFailureReason::class.java,
            ModelCollectionSignal::class.java,
            ModelCollectionSignal.Companion::class.java,
            ModelSourceLoadContext::class.java,
            ModelSourceLoadContext.Companion::class.java,
            ModelSourceProvider::class.java,
            ModelSourceProviderError::class.java,
            ModelSourceProviderError.Companion::class.java,
            ModelSourceProviderError.Builder::class.java,
            ModelPreparationDiagnostic::class.java,
            ModelPreparationError::class.java,
            ModelPreparationError.Companion::class.java,
            ModelPreparationResult::class.java,
            ModelPreparationResult.Success::class.java,
            ModelPreparationResult.Failure::class.java,
            ModelExpansionResult::class.java,
            ModelExpansionResult.Success::class.java,
            ModelExpansionResult.Failure::class.java,
            ModelOwnerIdentity::class.java,
            ModelOwnerIdentity.Companion::class.java,
            ModelVersionIdentity::class.java,
            ModelVersionIdentity.Companion::class.java,
            ModelOwned::class.java,
            ModelWorkspace::class.java,
            ModelWorkspace.Companion::class.java,
            PreparedModel::class.java,
            PreparedModel.Companion::class.java,
            DocumentDecodeLimits::class.java,
            DocumentDecodeLimits.Companion::class.java,
            DocumentDecodeLimits.Builder::class.java,
            DocumentJsonNodeType::class.java,
            DocumentInputReasonCode::class.java,
            DocumentInputResource::class.java,
            DocumentInputDiagnostic::class.java,
            DocumentInputError::class.java,
            DocumentInputError.Companion::class.java,
            DocumentEncodeError::class.java,
            DocumentEncodeError.Companion::class.java,
            ModelOwnerMismatchError::class.java,
            ModelOwnerMismatchError.Companion::class.java,
            DocumentSnapshot::class.java,
            DocumentSnapshot.Companion::class.java,
            PreparedDocumentApi::class.java,
            ModelEntityKind::class.java,
            DocumentCoordinateReason::class.java,
            UnknownModelPathError::class.java,
            DocumentCoordinateError::class.java,
            RelevantCoordinate::class.java,
            RelevantCoordinate.Companion::class.java,
            RelevantCoordinate.Exact::class.java,
            RelevantCoordinate.All::class.java,
            RelevantEntityPointer::class.java,
            DocumentPointer::class.java,
            GroupPointer::class.java,
            FieldPointer::class.java,
            RelevantSelectionPointer::class.java,
            ModelMetadata::class.java,
            ValidationSeverity::class.java,
            ValidationFindingType::class.java,
            ValidationFinding::class.java,
            UnsupportedProgramSubject::class.java,
            UnsupportedProgram::class.java,
            InterpreterSupportReport::class.java,
            ValidationReport::class.java,
            ComputationOutcome::class.java,
            ComputationResult::class.java,
            ComputationReport::class.java,
            CustomFieldValidationParameters::class.java,
            CustomFieldValidationResult::class.java,
            CustomFieldValidationResult.Accepted::class.java,
            CustomFieldValidationResult.Rejected::class.java,
            CustomFieldValidator::class.java,
            CustomFieldTypeFactory::class.java,
            CustomCondition::class.java,
            CustomConditionFactory::class.java,
            CustomConditionDocumentView::class.java,
            CustomConditionInvocation::class.java,
            CustomizationKind::class.java,
            CustomizationRegistrationReason::class.java,
            CustomizationRegistrationError::class.java,
            CustomFieldTypeRegistry::class.java,
            CustomFieldTypeRegistry.Companion::class.java,
            CustomFieldTypeRegistry.Builder::class.java,
            CustomConditionRegistry::class.java,
            CustomConditionRegistry.Companion::class.java,
            CustomConditionRegistry.Builder::class.java,
            InterpreterConfiguration::class.java,
            InterpreterConfiguration.Companion::class.java,
            InterpreterConfiguration.Builder::class.java,
            InterpreterIntegrationPhase::class.java,
            InterpreterIntegrationReason::class.java,
            InterpreterIntegrationError::class.java,
            Interpreter::class.java,
            JavaModelSourceProvider::class.java,
            ModelWorkspaceJava::class.java,
        )

        val PUBLIC_PREPARATION_TYPE_SET = PUBLIC_PREPARATION_TYPES.toSet()
        const val PROJECT_PACKAGE_PREFIX = "io.github.mbackschat.a12.dm.interpreter"
    }
}
