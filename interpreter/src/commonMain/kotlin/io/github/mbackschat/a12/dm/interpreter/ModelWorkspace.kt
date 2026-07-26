package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.load.InvalidPreparedModelSourceException
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.load.UnpreparedModelResidueException
import io.github.mbackschat.a12.dm.interpreter.load.UnsupportedDatePrecisionException
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.workspace.AdmittedWorkspace
import io.github.mbackschat.a12.dm.interpreter.workspace.FingerprintSource
import io.github.mbackschat.a12.dm.interpreter.workspace.PreparedModelFingerprint
import io.github.mbackschat.a12.dm.interpreter.workspace.ReferenceAssembly
import io.github.mbackschat.a12.dm.interpreter.workspace.WorkspaceReferenceAssembler
import io.github.mbackschat.a12.dm.interpreter.workspace.WorkspaceSourceAdmission
import io.github.mbackschat.a12.dm.interpreter.workspace.immutableListSnapshot
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmSynthetic

/**
 * Immutable exact-id workspace produced from a finite set of caller-supplied sources. Construction admits and
 * freezes every supplied source; [prepare] resolves only the requested entry's transitive closure.
 */
class ModelWorkspace private constructor(
    private val admitted: AdmittedWorkspace,
    private val limits: ModelPreparationLimits,
) {
    val modelIds: List<String> = immutableListSnapshot(admitted.modelIds)

    fun prepare(entryModelId: String): PreparedModel {
        val assembly = WorkspaceReferenceAssembler.assemble(admitted, entryModelId, limits)
        return PreparedModel.fromWorkspace(admitted, assembly)
    }

    fun tryPrepare(entryModelId: String): ModelPreparationResult =
        try {
            ModelPreparationResult.Success(prepare(entryModelId))
        } catch (error: ModelPreparationError) {
            ModelPreparationResult.Failure(error)
        }

    companion object {
        @JvmStatic
        @JvmOverloads
        fun fromSources(
            sources: List<WorkspaceModelSource>,
            limits: ModelPreparationLimits = ModelPreparationLimits.defaults(),
        ): ModelWorkspace =
            ModelWorkspace(
                admitted = WorkspaceSourceAdmission.admit(sources, limits),
                limits = limits,
            )
    }
}

/**
 * One model-static prepared owner domain. It contains no request document, clock, callback, registry, provider,
 * filesystem handle, or mutable resolver state.
 */
class PreparedModel private constructor(
    val entryModelId: String,
    val fingerprint: String,
    dependencies: List<ModelDependency>,
    private val definition: ModelDef,
    override val owner: ModelOwnerIdentity = ModelOwnerIdentity.create(),
    override val modelVersion: ModelVersionIdentity = ModelVersionIdentity.create(),
) : ModelOwned {
    val dependencies: List<ModelDependency> = immutableListSnapshot(dependencies)

    @JvmSynthetic
    internal fun definition(): ModelDef = definition

    companion object {
        @JvmStatic
        fun loadExpandedJson(source: String): PreparedModel {
            val limits = ModelPreparationLimits.defaults()
            val admitted = WorkspaceSourceAdmission.admit(
                listOf(WorkspaceModelSource.builder().json(source).build()),
                limits,
            )
            val modelId = admitted.modelIds.single()
            val admittedSource = checkNotNull(admitted.source(modelId))
            return create(
                entryModelId = modelId,
                fingerprint = PreparedModelFingerprint.expanded(admittedSource.sha256),
                dependencies = emptyList(),
                json = admittedSource.json,
            )
        }

        @JvmStatic
        fun tryLoadExpandedJson(source: String): ModelPreparationResult =
            try {
                ModelPreparationResult.Success(loadExpandedJson(source))
            } catch (error: ModelPreparationError) {
                ModelPreparationResult.Failure(error)
            }

        @JvmSynthetic
        internal fun fromWorkspace(
            workspace: AdmittedWorkspace,
            assembly: ReferenceAssembly,
        ): PreparedModel {
            val closureIds = linkedSetOf(assembly.entryModelId)
            assembly.dependencies.forEach { closureIds += it.step.toModelId }
            val fingerprintSources = closureIds.map { modelId ->
                val source = checkNotNull(workspace.source(modelId)) {
                    "resolved workspace closure lost source '$modelId'"
                }
                FingerprintSource(modelId, source.sha256)
            }
            val dependencies = assembly.dependencies.map { resolved ->
                val step = resolved.step
                ModelDependency.resolved(
                    step = step,
                    diagnosticLabel = resolved.diagnosticLabel,
                    sha256 = resolved.sha256,
                )
            }
            return create(
                entryModelId = assembly.entryModelId,
                fingerprint = PreparedModelFingerprint.workspace(assembly.entryModelId, fingerprintSources),
                dependencies = dependencies,
                json = assembly.json,
            )
        }

        private fun create(
            entryModelId: String,
            fingerprint: String,
            dependencies: List<ModelDependency>,
            json: String,
        ): PreparedModel =
            PreparedModel(
                entryModelId = entryModelId,
                fingerprint = fingerprint,
                dependencies = dependencies,
                definition = loadDefinition(json),
            )

        private fun loadDefinition(json: String): ModelDef =
            try {
                ModelLoader.load(json)
            } catch (error: UnpreparedModelResidueException) {
                throw ModelPreparationError.from(
                    listOf(
                        PreparedInputDiagnostic(
                            code = ModelPreparationDiagnosticCode.UNPREPARED_MODEL_RESIDUE,
                            path = error.path,
                            reason = error.residue,
                            message = error.message.orEmpty(),
                        ),
                    ),
                )
            } catch (error: UnsupportedDatePrecisionException) {
                throw ModelPreparationError.from(
                    listOf(
                        PreparedInputDiagnostic(
                            code = ModelPreparationDiagnosticCode.UNSUPPORTED_DATE_PRECISION,
                            path = error.path,
                            reference = error.precision,
                            message = error.message.orEmpty(),
                        ),
                    ),
                )
            } catch (error: InvalidPreparedModelSourceException) {
                throw invalidModelSource(
                    message = error.message.orEmpty(),
                    path = error.path,
                )
            } catch (error: UnsupportedModelTimeZoneException) {
                throw ModelPreparationError.from(
                    listOf(
                        PreparedInputDiagnostic(
                            code = ModelPreparationDiagnosticCode.UNSUPPORTED_TIME_ZONE,
                            reference = error.timeZoneId,
                            message = error.message.orEmpty(),
                        ),
                    ),
                )
            } catch (error: InvalidModelTimeZoneException) {
                throw invalidModelSource(error.message.orEmpty(), reference = error.timeZoneId)
            } catch (error: IllegalArgumentException) {
                throw invalidModelSource(error.message ?: "prepared model source is invalid")
            }

        private fun invalidModelSource(
            message: String,
            path: String? = null,
            reference: String? = null,
        ): ModelPreparationError =
            ModelPreparationError.from(
                listOf(
                    PreparedInputDiagnostic(
                        code = ModelPreparationDiagnosticCode.INVALID_MODEL_SOURCE,
                        path = path,
                        reference = reference,
                        message = message,
                    ),
                ),
            )
    }
}

private data class PreparedInputDiagnostic(
    override val code: ModelPreparationDiagnosticCode,
    override val path: String? = null,
    override val reference: String? = null,
    override val reason: String? = null,
    override val message: String,
) : ModelPreparationDiagnostic
