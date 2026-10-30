package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.load.InvalidPreparedModelSourceException
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.load.UnpreparedModelResidueException
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
 * Immutable exact-id workspace produced from a finite or provider-collected source set. Construction admits and
 * freezes every supplied source; [prepare] resolves only the requested entry's transitive closure.
 *
 * `snapshotRevision` is sanitized provenance captured from a collection provider. It is null for finite
 * [fromSources] workspaces and never contributes to a prepared-model fingerprint.
 */
class ModelWorkspace private constructor(
    private val admitted: AdmittedWorkspace,
    private val limits: ModelPreparationLimits,
    val snapshotRevision: String?,
) {
    val modelIds: List<String> = immutableListSnapshot(admitted.modelIds)

    /**
     * Expand one transitive closure into compact self-contained DM-JSON without invoking the internal model loader.
     * Interpreter-specific evaluability checks therefore belong to [prepare], not this conversion boundary.
     */
    fun expand(entryModelId: String): String = assemble(entryModelId).json

    /** Return either the expanded DM-JSON or the same typed preparation error thrown by [expand]. */
    fun tryExpand(entryModelId: String): ModelExpansionResult =
        try {
            ModelExpansionResult.Success(expand(entryModelId))
        } catch (error: ModelPreparationError) {
            ModelExpansionResult.Failure(error)
        }

    /**
     * Prepare one transitive closure. Each call performs a fresh deterministic assembly and creates a new owner
     * domain; this finite workspace deliberately holds no hidden preparation cache.
     */
    fun prepare(entryModelId: String): PreparedModel {
        val assembly = assemble(entryModelId)
        return PreparedModel.fromWorkspace(admitted, assembly)
    }

    /** Return either a new prepared owner domain or the same typed preparation error thrown by [prepare]. */
    fun tryPrepare(entryModelId: String): ModelPreparationResult =
        try {
            ModelPreparationResult.Success(prepare(entryModelId))
        } catch (error: ModelPreparationError) {
            ModelPreparationResult.Failure(error)
        }

    private fun assemble(entryModelId: String): ReferenceAssembly =
        WorkspaceReferenceAssembler.assemble(admitted, entryModelId, limits)

    companion object {
        /**
         * Admit and freeze a caller-supplied finite source map. References are resolved only when [prepare] or
         * [expand] selects an entry model.
         */
        @JvmStatic
        @JvmOverloads
        fun fromSources(
            sources: List<WorkspaceModelSource>,
            limits: ModelPreparationLimits = ModelPreparationLimits.defaults(),
        ): ModelWorkspace =
            ModelWorkspace(
                admitted = WorkspaceSourceAdmission.admit(sources, limits),
                limits = limits,
                snapshotRevision = null,
            )

        /**
         * Collect one exact-id transitive closure through a snapshot-scoped provider. Calls are sequential and
         * deterministic; each exact id is loaded at most once, and collection never retries implicitly.
         *
         * This suspending Kotlin boundary uses no coroutine-library type. [signal] is therefore also supplied to
         * the provider so applications can connect their own task or request cancellation mechanism.
         */
        @JvmSynthetic
        suspend fun collect(
            entryModelId: String,
            provider: ModelSourceProvider,
            limits: ModelPreparationLimits = ModelPreparationLimits.defaults(),
            signal: ModelCollectionSignal = ModelCollectionSignal.NONE,
        ): ModelWorkspace {
            val collected = ModelWorkspaceCollector.collect(entryModelId, provider, limits, signal)
            return ModelWorkspace(
                admitted = WorkspaceSourceAdmission.admit(collected.sources, limits),
                limits = limits,
                snapshotRevision = collected.snapshotRevision,
            )
        }
    }
}

/**
 * One model-static prepared owner domain. It contains no request document, clock, callback, registry, provider,
 * filesystem handle, or request-scoped state.
 *
 * **It does hold model-static preparation, memoized lazily and thread-safely**: condition and operation ASTs,
 * iteration scopes, `RepetitionNotUnique` plans, the model path index, and the computation dependency order. That
 * state is derived from the model alone and never from an [Interpreter]'s configuration, so every interpreter
 * created here shares one build of it — which is what lets a service cache one entry per model instead of one per
 * (model × configuration). See the P-SERVICE profile in `docs/INTERPRETER-SPEC.md` ("The two consumer profiles").
 * Configuration-dependent work — the support-audit verdict, predefined-type pattern compilation, the clock — stays
 * on each interpreter.
 *
 * Concurrency: the memos are SYNCHRONIZED `lazy`, so concurrent JVM request threads sharing one instance each
 * observe a single build. Node reaches this one isolate at a time; a multi-isolate JS host duplicates the instance
 * per isolate rather than sharing it.
 *
 * *(Corrected 2026-08-07: this note previously claimed "no mutable resolver state", which the shared preparation
 * memo makes false as written. The guarantee that matters — nothing request-scoped is retained — is unchanged.)*
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

    /** The placement-transport verbs bound to this prepared owner domain. */
    val documents: PreparedDocumentApi = PreparedDocumentApi(this)

    /** Minimal owner-bound model lookup and pointer factory. */
    val model: ModelMetadata = ModelMetadata.create(this)

    /** Create a reusable active evaluator bound to this prepared model and customization configuration. */
    @JvmOverloads
    fun createInterpreter(
        configuration: InterpreterConfiguration = InterpreterConfiguration.defaults(),
    ): Interpreter =
        Interpreter.create(this, configuration)

    @JvmSynthetic
    internal fun definition(): ModelDef = definition

    /**
     * The model-static preparation every interpreter of this prepared model shares. Lazy so constructing a
     * prepared model stays cheap, and built exactly once so a second interpreter does none of it.
     */
    private val staticPlans: Lazy<ModelStaticPlans> = lazy { ModelStaticPlans.of(definition) }

    @JvmSynthetic
    internal fun plans(): ModelStaticPlans = staticPlans.value

    companion object {
        /**
         * Load already self-contained DM-JSON into a new prepared owner domain without workspace expansion.
         * Interpreter-specific evaluability checks still apply.
         */
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

        /** Return either the loaded prepared model or the typed error thrown by [loadExpandedJson]. */
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
                ModelLoader.loadForEvaluation(json)
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

    }
}

/** A model source the interpreter cannot prepare, at the model member or element [path] that makes it so. */
internal fun invalidModelSource(
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

private data class PreparedInputDiagnostic(
    override val code: ModelPreparationDiagnosticCode,
    override val path: String? = null,
    override val reference: String? = null,
    override val reason: String? = null,
    override val message: String,
) : ModelPreparationDiagnostic
