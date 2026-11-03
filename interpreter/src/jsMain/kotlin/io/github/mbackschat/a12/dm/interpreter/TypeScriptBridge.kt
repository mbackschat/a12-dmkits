@file:OptIn(ExperimentalJsExport::class)

import io.github.mbackschat.a12.dm.interpreter.A12PartiallyKnownMultiPointer as CorePartiallyKnown
import io.github.mbackschat.a12.dm.interpreter.A12Pointer as CoreA12Pointer
import io.github.mbackschat.a12.dm.interpreter.A12PointerFormat as CoreA12PointerFormat
import io.github.mbackschat.a12.dm.interpreter.ComputationOutcome as CoreComputationOutcome
import io.github.mbackschat.a12.dm.interpreter.ComputationReport as CoreComputationReport
import io.github.mbackschat.a12.dm.interpreter.CustomCondition as CoreCustomCondition
import io.github.mbackschat.a12.dm.interpreter.CustomConditionInvocation as CoreCustomConditionInvocation
import io.github.mbackschat.a12.dm.interpreter.CustomConditionRegistry as CoreCustomConditionRegistry
import io.github.mbackschat.a12.dm.interpreter.CustomFieldTypeRegistry as CoreCustomFieldTypeRegistry
import io.github.mbackschat.a12.dm.interpreter.CustomFieldValidationResult as CoreCustomFieldValidationResult
import io.github.mbackschat.a12.dm.interpreter.CustomFieldValidator as CoreCustomFieldValidator
import io.github.mbackschat.a12.dm.interpreter.DocumentCoordinateError as CoreDocumentCoordinateError
import io.github.mbackschat.a12.dm.interpreter.DocumentCoordinateReason as CoreDocumentCoordinateReason
import io.github.mbackschat.a12.dm.interpreter.DocumentDecodeLimits as CoreDocumentDecodeLimits
import io.github.mbackschat.a12.dm.interpreter.DocumentEncodeError as CoreDocumentEncodeError
import io.github.mbackschat.a12.dm.interpreter.DocumentInputDiagnostic as CoreDocumentInputDiagnostic
import io.github.mbackschat.a12.dm.interpreter.DocumentInputError as CoreDocumentInputError
import io.github.mbackschat.a12.dm.interpreter.DocumentInputReasonCode as CoreDocumentInputReasonCode
import io.github.mbackschat.a12.dm.interpreter.DocumentInputResource as CoreDocumentInputResource
import io.github.mbackschat.a12.dm.interpreter.DocumentJsonNodeType as CoreDocumentJsonNodeType
import io.github.mbackschat.a12.dm.interpreter.DocumentPointer as CoreDocumentPointer
import io.github.mbackschat.a12.dm.interpreter.DocumentSnapshot as CoreDocumentSnapshot
import io.github.mbackschat.a12.dm.interpreter.Interpreter as CoreInterpreter
import io.github.mbackschat.a12.dm.interpreter.InterpreterConfiguration as CoreInterpreterConfiguration
import io.github.mbackschat.a12.dm.interpreter.InterpreterIntegrationError as CoreInterpreterIntegrationError
import io.github.mbackschat.a12.dm.interpreter.ModelCollectionSignal as CoreModelCollectionSignal
import io.github.mbackschat.a12.dm.interpreter.ModelEntityKind as CoreModelEntityKind
import io.github.mbackschat.a12.dm.interpreter.ModelOwnerMismatchError as CoreModelOwnerMismatchError
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationDiagnostic as CoreModelPreparationDiagnostic
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationError as CoreModelPreparationError
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationLimits as CoreModelPreparationLimits
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationResource as CoreModelPreparationResource
import io.github.mbackschat.a12.dm.interpreter.ModelReferencePurpose as CoreModelReferencePurpose
import io.github.mbackschat.a12.dm.interpreter.ModelSourceLoadContext as CoreModelSourceLoadContext
import io.github.mbackschat.a12.dm.interpreter.ModelSourceProvider as CoreModelSourceProvider
import io.github.mbackschat.a12.dm.interpreter.ModelSourceProviderError as CoreModelSourceProviderError
import io.github.mbackschat.a12.dm.interpreter.ModelSourceProviderFailureReason as CoreProviderFailureReason
import io.github.mbackschat.a12.dm.interpreter.ModelWorkspace as CoreModelWorkspace
import io.github.mbackschat.a12.dm.interpreter.PreparedModel as CorePreparedModel
import io.github.mbackschat.a12.dm.interpreter.RelevantCoordinate as CoreRelevantCoordinate
import io.github.mbackschat.a12.dm.interpreter.RelevantEntityPointer as CoreRelevantEntityPointer
import io.github.mbackschat.a12.dm.interpreter.RelevantSelectionPointer as CoreRelevantSelectionPointer
import io.github.mbackschat.a12.dm.interpreter.UnknownModelPathError as CoreUnknownModelPathError
import io.github.mbackschat.a12.dm.interpreter.ValidationFinding as CoreValidationFinding
import io.github.mbackschat.a12.dm.interpreter.UnsupportedProgram as CoreUnsupportedProgram
import io.github.mbackschat.a12.dm.interpreter.UnsupportedProgramSubject as CoreUnsupportedProgramSubject
import io.github.mbackschat.a12.dm.interpreter.ValidationFindingType as CoreValidationFindingType
import io.github.mbackschat.a12.dm.interpreter.ValidationReport as CoreValidationReport
import io.github.mbackschat.a12.dm.interpreter.ValidationSeverity as CoreValidationSeverity
import io.github.mbackschat.a12.dm.interpreter.WorkspaceModelSource as CoreWorkspaceModelSource
import io.github.mbackschat.a12.dm.interpreter.awaitValue
import io.github.mbackschat.a12.dm.interpreter.promiseFromSuspend
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.js.Promise

/**
 * Internal Kotlin/JS transport consumed only by the authored TypeScript façade. Public package consumers never
 * import these bridge declarations directly; keeping handles here prevents Kotlin collections, enums, sealed
 * results, suspension machinery, and ownership tokens from leaking into the package ABI.
 */
@JsExport
class InterpreterBridgeWorkspaceSource(
    val json: String,
    val diagnosticLabel: String?,
    val expectedSha256: String?,
) {
    internal fun toCore(): CoreWorkspaceModelSource {
        val builder = CoreWorkspaceModelSource.builder().json(json)
        diagnosticLabel?.let(builder::diagnosticLabel)
        expectedSha256?.let(builder::expectedSha256)
        return builder.build()
    }
}

@JsExport
class InterpreterBridgeFailure(
    val code: String,
    val message: String,
    val detailsJson: String,
)

@JsExport
class InterpreterBridgeStringResult internal constructor(
    val value: String?,
    val failure: InterpreterBridgeFailure?,
)

@JsExport
class InterpreterBridgeWorkspaceResult internal constructor(
    val value: InterpreterBridgeWorkspace?,
    val failure: InterpreterBridgeFailure?,
)

@JsExport
class InterpreterBridgePreparedModelResult internal constructor(
    val value: InterpreterBridgePreparedModel?,
    val failure: InterpreterBridgeFailure?,
)

@JsExport
class InterpreterBridgeDocumentResult internal constructor(
    val value: InterpreterBridgeDocumentSnapshot?,
    val failure: InterpreterBridgeFailure?,
)

@JsExport
class InterpreterBridgePointerResult internal constructor(
    val value: InterpreterBridgePointer?,
    val failure: InterpreterBridgeFailure?,
)

/** One parsed **exact** pointer — A12's `DocumentPointer` shape: its ordered parts, plus the derived views. */
@JsExport
class InterpreterBridgeParsedPointer internal constructor(
    val pathParts: Array<InterpreterBridgePathPart>,
    val fullName: String,
    val repetitionIndexes: Array<Int>,
)

/** A12's `PathPart`. */
@JsExport
class InterpreterBridgePathPart internal constructor(
    val name: String,
    val repetitionIndex: Int,
)

/** One parsed message pointer — A12's `PartiallyKnownDocumentMultiPointer` shape, `fullName` plus indexes. */
@JsExport
class InterpreterBridgeParsedPartiallyKnownPointer internal constructor(
    val fullName: String,
    val repetitionIndexes: Array<Int>,
)

@JsExport
class InterpreterBridgePointerFormatResult internal constructor(
    val value: String?,
    val rejection: String?,
)

@JsExport
class InterpreterBridgePointerParseResult internal constructor(
    val value: InterpreterBridgeParsedPointer?,
    val rejection: String?,
)

@JsExport
class InterpreterBridgePartiallyKnownPointerResult internal constructor(
    val value: InterpreterBridgeParsedPartiallyKnownPointer?,
    val rejection: String?,
)

@JsExport
class InterpreterBridgeRelevantPointerResult internal constructor(
    val value: InterpreterBridgeRelevantPointer?,
    val failure: InterpreterBridgeFailure?,
)

@JsExport
class InterpreterBridgeInterpreterResult internal constructor(
    val value: InterpreterBridgeInterpreter?,
    val failure: InterpreterBridgeFailure?,
)

@JsExport
class InterpreterBridgeValidationResult internal constructor(
    val value: InterpreterBridgeValidationReport?,
    val failure: InterpreterBridgeFailure?,
)

@JsExport
class InterpreterBridgeComputationResult internal constructor(
    val value: InterpreterBridgeComputationReport?,
    val failure: InterpreterBridgeFailure?,
)

@JsExport
class InterpreterBridgeWorkspace internal constructor(
    internal val core: CoreModelWorkspace,
) {
    val modelIds: Array<String> get() = core.modelIds.toTypedArray()
    val snapshotRevision: String? get() = core.snapshotRevision

    fun expand(entryModelId: String): InterpreterBridgeStringResult =
        try {
            InterpreterBridgeStringResult(core.expand(entryModelId), null)
        } catch (error: CoreModelPreparationError) {
            InterpreterBridgeStringResult(null, preparationFailure(error))
        }

    fun prepare(entryModelId: String): InterpreterBridgePreparedModelResult =
        try {
            InterpreterBridgePreparedModelResult(InterpreterBridgePreparedModel(core.prepare(entryModelId)), null)
        } catch (error: CoreModelPreparationError) {
            InterpreterBridgePreparedModelResult(null, preparationFailure(error))
        }
}

@JsExport
class InterpreterBridgePreparedModel internal constructor(
    internal val core: CorePreparedModel,
) {
    val entryModelId: String get() = core.entryModelId
    val fingerprint: String get() = core.fingerprint
    val dependencies: Array<InterpreterBridgeModelDependency> get() =
        core.dependencies.map { dependency ->
            InterpreterBridgeModelDependency(
                fromModelId = dependency.fromModelId,
                toModelId = dependency.toModelId,
                purpose = dependency.purpose.wireName(),
                diagnosticLabel = dependency.diagnosticLabel,
                sha256 = dependency.sha256,
            )
        }.toTypedArray()
    val documents: InterpreterBridgeDocuments = InterpreterBridgeDocuments(core)
    val model: InterpreterBridgeModelMetadata = InterpreterBridgeModelMetadata(core)

    fun createInterpreter(
        customFieldTypes: Array<InterpreterBridgeCustomFieldTypeRegistration>,
        customConditions: Array<InterpreterBridgeCustomConditionRegistration>,
        evaluationInstant: Double?,
    ): InterpreterBridgeInterpreterResult =
        try {
            InterpreterBridgeInterpreterResult(
                InterpreterBridgeInterpreter(
                    core.createInterpreter(
                        interpreterConfiguration(customFieldTypes, customConditions, evaluationInstant)),
                ),
                null,
            )
        } catch (error: CoreInterpreterIntegrationError) {
            InterpreterBridgeInterpreterResult(null, integrationFailure(error))
        } catch (error: CoreModelPreparationError) { InterpreterBridgeInterpreterResult(null, preparationFailure(error)) }
}

@JsExport
class InterpreterBridgeCustomFieldValidation(
    val accepted: Boolean,
    val errorKey: String?,
    val message: String?,
)

@JsExport
class InterpreterBridgeCustomFieldTypeRegistration(
    val name: String,
    internal val validate: (
        value: String,
        locale: String,
        minLength: Int?,
        maxLength: Int?,
        isDisplayValue: Boolean,
    ) -> InterpreterBridgeCustomFieldValidation,
)

@JsExport
class InterpreterBridgeCustomConditionRegistration(
    val name: String,
    internal val check: (InterpreterBridgeCustomConditionInvocation) -> Boolean,
)

@JsExport
class InterpreterBridgeCustomConditionInvocation internal constructor(
    private val core: CoreCustomConditionInvocation,
) {
    val relevantEntities: Array<InterpreterBridgeRelevantPointer>? get() =
        core.relevantEntities?.map(::InterpreterBridgeRelevantPointer)?.toTypedArray()
    val formallyIncorrectEntities: Array<InterpreterBridgePointer> get() =
        core.formallyIncorrectEntities.map(::InterpreterBridgePointer).toTypedArray()
    val errorPointer: InterpreterBridgePointer get() = InterpreterBridgePointer(core.errorPointer)

    fun fields(): Array<InterpreterBridgePointer> =
        core.document.fields().map(::InterpreterBridgePointer).toTypedArray()

    fun value(pointer: InterpreterBridgePointer): String? =
        core.document.value(pointer.core as io.github.mbackschat.a12.dm.interpreter.FieldPointer)
}

@JsExport
class InterpreterBridgeModelDependency internal constructor(
    val fromModelId: String,
    val toModelId: String,
    val purpose: String,
    val diagnosticLabel: String?,
    val sha256: String,
)

@JsExport
class InterpreterBridgeDocuments internal constructor(
    private val prepared: CorePreparedModel,
) {
    fun seedDocument(): InterpreterBridgeDocumentSnapshot =
        InterpreterBridgeDocumentSnapshot(prepared.documents.seedDocument())

    fun readDocument(
        json: String,
        limitsJson: String?,
    ): InterpreterBridgeDocumentResult =
        try {
            InterpreterBridgeDocumentResult(
                InterpreterBridgeDocumentSnapshot(
                    prepared.documents.readDocument(json, documentLimits(limitsJson)),
                ),
                null,
            )
        } catch (error: CoreDocumentInputError) {
            InterpreterBridgeDocumentResult(null, documentInputFailure(error))
        }

    fun encodeDocument(snapshot: InterpreterBridgeDocumentSnapshot): InterpreterBridgeStringResult =
        try {
            InterpreterBridgeStringResult(prepared.documents.encodeDocument(snapshot.core), null)
        } catch (error: CoreDocumentEncodeError) {
            InterpreterBridgeStringResult(null, documentEncodeFailure(error))
        }

    fun isSnapshot(snapshot: InterpreterBridgeDocumentSnapshot?): Boolean =
        snapshot != null && snapshot.core.owner === prepared.owner
}

@JsExport
class InterpreterBridgeDocumentSnapshot internal constructor(
    internal val core: CoreDocumentSnapshot,
)

@JsExport
class InterpreterBridgePointer internal constructor(
    internal val core: CoreDocumentPointer,
) {
    val entityKind: String get() = core.entityKind.wireName()
    val path: String get() = core.path
    val coordinates: Array<Int> get() = core.coordinates.toTypedArray()

    fun asRelevant(): InterpreterBridgeRelevantPointer = InterpreterBridgeRelevantPointer(core)
}

@JsExport
class InterpreterBridgeRelevantPointer internal constructor(
    internal val core: CoreRelevantEntityPointer,
) {
    val entityKind: String get() = core.entityKind.wireName()
    val path: String get() = core.path
    val coordinates: Array<Int> get() =
        when (core) {
            is CoreDocumentPointer -> core.coordinates.toTypedArray()
            is CoreRelevantSelectionPointer ->
                core.coordinates.map { coordinate ->
                    when (coordinate) {
                        is CoreRelevantCoordinate.Exact -> coordinate.value
                        CoreRelevantCoordinate.All -> 0
                    }
                }.toTypedArray()
        }
}

@JsExport
class InterpreterBridgeModelMetadata internal constructor(
    private val prepared: CorePreparedModel,
) {
    val documentModelId: String get() = prepared.model.documentModelId

    fun pointer(path: String, coordinates: Array<Int>): InterpreterBridgePointerResult =
        try {
            InterpreterBridgePointerResult(
                InterpreterBridgePointer(prepared.model.pointer(path, coordinates.toList())),
                null,
            )
        } catch (error: CoreUnknownModelPathError) {
            InterpreterBridgePointerResult(null, unknownPathFailure(error))
        } catch (error: CoreDocumentCoordinateError) {
            InterpreterBridgePointerResult(null, coordinateFailure(error))
        }

    /**
     * `0` is the internal bridge sentinel for the public TypeScript `"ALL"` coordinate. Exact coordinates are
     * positive and have already crossed the public shape/range validator.
     */
    fun relevantPointer(
        path: String,
        coordinates: Array<Int>,
    ): InterpreterBridgeRelevantPointerResult =
        try {
            val coreCoordinates = coordinates.map { coordinate ->
                if (coordinate == 0) CoreRelevantCoordinate.all() else CoreRelevantCoordinate.exact(coordinate)
            }
            InterpreterBridgeRelevantPointerResult(
                InterpreterBridgeRelevantPointer(prepared.model.relevantPointer(path, coreCoordinates)),
                null,
            )
        } catch (error: CoreUnknownModelPathError) {
            InterpreterBridgeRelevantPointerResult(null, unknownPathFailure(error))
        } catch (error: CoreDocumentCoordinateError) {
            InterpreterBridgeRelevantPointerResult(null, coordinateFailure(error))
        }
}

@JsExport
class InterpreterBridgeUnsupportedProgram internal constructor(
    val path: String,
    val reason: String,
    /** `"element"` (a model path) or `"cell"` (a document instance pointer) — see `UnsupportedProgramSubject`. */
    val subject: String,
)

@JsExport
class InterpreterBridgeSupportReport internal constructor(
    val unsupported: Array<InterpreterBridgeUnsupportedProgram>,
    val fullySupported: Boolean,
)

@JsExport
class InterpreterBridgeValidationFinding internal constructor(
    val code: String,
    val rulePath: String,
    val errorPath: InterpreterBridgeParsedPartiallyKnownPointer,
    val severity: String,
    val type: String,
    val text: String,
    val referenced: Array<InterpreterBridgeParsedPartiallyKnownPointer>,
    val fillToFix: Array<InterpreterBridgeParsedPartiallyKnownPointer>,
)

@JsExport
class InterpreterBridgeValidationReport internal constructor(
    val findings: Array<InterpreterBridgeValidationFinding>,
    val unsupported: Array<InterpreterBridgeUnsupportedProgram>,
    val noErrorOccurred: Boolean,
    val fullySupported: Boolean,
) {
    companion object {
        internal fun fromCore(report: CoreValidationReport): InterpreterBridgeValidationReport =
            InterpreterBridgeValidationReport(
                findings = report.findings.map(::validationFinding).toTypedArray(),
                unsupported = report.unsupported.map { unsupported ->
                    unsupportedProgram(unsupported)
                }.toTypedArray(),
                noErrorOccurred = report.noErrorOccurred,
                fullySupported = report.fullySupported,
            )
    }
}

@JsExport
class InterpreterBridgeComputedField internal constructor(
    val pointer: InterpreterBridgePointer,
    val outcome: String,
    val value: String?,
    val diagnostic: InterpreterBridgeValidationFinding?,
)

@JsExport
class InterpreterBridgeComputationReport internal constructor(
    private val core: CoreComputationReport,
) {
    val results: Array<InterpreterBridgeComputedField> = core.results.map(::computedField).toTypedArray()
    val actions: Array<InterpreterBridgeComputedField> = core.actions.map(::computedField).toTypedArray()
    val formalErrorsInOperands: Array<InterpreterBridgeValidationFinding> =
        core.formalErrorsInOperands.map(::validationFinding).toTypedArray()
    val unsupported: Array<InterpreterBridgeUnsupportedProgram> = core.unsupported.map { unsupported ->
        unsupportedProgram(unsupported)
    }.toTypedArray()
    val noErrorOccurred: Boolean = core.noErrorOccurred
    val fullySupported: Boolean = core.fullySupported

    fun applyTo(destination: InterpreterBridgeDocumentSnapshot): InterpreterBridgeDocumentResult =
        try {
            InterpreterBridgeDocumentResult(
                InterpreterBridgeDocumentSnapshot(core.applyTo(destination.core)),
                null,
            )
        } catch (error: CoreModelOwnerMismatchError) {
            InterpreterBridgeDocumentResult(null, ownerMismatchFailure(error))
        }

    companion object {
        internal fun fromCore(report: CoreComputationReport): InterpreterBridgeComputationReport =
            InterpreterBridgeComputationReport(report)

        private fun computedField(result: io.github.mbackschat.a12.dm.interpreter.ComputationResult):
            InterpreterBridgeComputedField =
            InterpreterBridgeComputedField(
                pointer = InterpreterBridgePointer(result.pointer),
                outcome = result.outcome.wireName(),
                value = result.value,
                diagnostic = result.diagnostic?.let(::validationFinding),
            )
    }
}

@JsExport
class InterpreterBridgeInterpreter internal constructor(
    private val core: CoreInterpreter,
) {
    fun supportReport(): InterpreterBridgeSupportReport {
        val report = core.supportReport()
        return InterpreterBridgeSupportReport(
            unsupported = report.unsupported.map { unsupported ->
                unsupportedProgram(unsupported)
            }.toTypedArray(),
            fullySupported = report.fullySupported,
        )
    }

    fun validateFull(
        snapshot: InterpreterBridgeDocumentSnapshot,
        locale: String,
    ): InterpreterBridgeValidationResult =
        try {
            InterpreterBridgeValidationResult(
                InterpreterBridgeValidationReport.fromCore(core.validateFull(snapshot.core, locale)),
                null,
            )
        } catch (error: CoreModelOwnerMismatchError) {
            InterpreterBridgeValidationResult(null, ownerMismatchFailure(error))
        } catch (error: CoreInterpreterIntegrationError) {
            InterpreterBridgeValidationResult(null, integrationFailure(error))
        }

    fun validatePart(
        snapshot: InterpreterBridgeDocumentSnapshot,
        relevant: Array<InterpreterBridgeRelevantPointer>,
        locale: String,
    ): InterpreterBridgeValidationResult =
        try {
            InterpreterBridgeValidationResult(
                InterpreterBridgeValidationReport.fromCore(
                    core.validatePart(snapshot.core, relevant.map { it.core }, locale),
                ),
                null,
            )
        } catch (error: CoreModelOwnerMismatchError) {
            InterpreterBridgeValidationResult(null, ownerMismatchFailure(error))
        } catch (error: CoreInterpreterIntegrationError) {
            InterpreterBridgeValidationResult(null, integrationFailure(error))
        }

    fun compute(
        snapshot: InterpreterBridgeDocumentSnapshot,
        locale: String,
    ): InterpreterBridgeComputationResult =
        try {
            InterpreterBridgeComputationResult(
                InterpreterBridgeComputationReport.fromCore(core.compute(snapshot.core, locale)),
                null,
            )
        } catch (error: CoreModelOwnerMismatchError) {
            InterpreterBridgeComputationResult(null, ownerMismatchFailure(error))
        } catch (error: CoreInterpreterIntegrationError) {
            InterpreterBridgeComputationResult(null, integrationFailure(error))
        }
}

@JsExport
fun interpreterBridgeWorkspaceFromSources(
    sources: Array<InterpreterBridgeWorkspaceSource>,
    limitsJson: String?,
): InterpreterBridgeWorkspaceResult =
    try {
        InterpreterBridgeWorkspaceResult(
            InterpreterBridgeWorkspace(
                CoreModelWorkspace.fromSources(sources.map(InterpreterBridgeWorkspaceSource::toCore), preparationLimits(limitsJson)),
            ),
            null,
        )
    } catch (error: CoreModelPreparationError) {
        InterpreterBridgeWorkspaceResult(null, preparationFailure(error))
    }

@JsExport
fun interpreterBridgeCollectWorkspace(
    entryModelId: String,
    snapshotRevision: String?,
    limitsJson: String?,
    load: (String, Int) -> Promise<InterpreterBridgeWorkspaceSource?>,
    isCancelled: () -> Boolean,
): Promise<InterpreterBridgeWorkspaceResult> =
    promiseFromSuspend {
        try {
            val signal = object : CoreModelCollectionSignal {
                override val isCancelled: Boolean get() = isCancelled()
            }
            val provider = object : CoreModelSourceProvider {
                override val snapshotRevision: String? = snapshotRevision

                override suspend fun load(
                    modelId: String,
                    context: CoreModelSourceLoadContext,
                ): CoreWorkspaceModelSource? =
                    try {
                        load(modelId, context.maximumBytes).awaitValue()?.toCore()
                    } catch (error: Throwable) {
                        throw translateProviderFailure(error, modelId)
                    }
            }
            InterpreterBridgeWorkspaceResult(
                InterpreterBridgeWorkspace(
                    CoreModelWorkspace.collect(
                        entryModelId = entryModelId,
                        provider = provider,
                        limits = preparationLimits(limitsJson),
                        signal = signal,
                    ),
                ),
                null,
            )
        } catch (error: CoreModelPreparationError) {
            InterpreterBridgeWorkspaceResult(null, preparationFailure(error))
        }
    }

@JsExport
fun interpreterBridgeLoadExpandedJson(source: String): InterpreterBridgePreparedModelResult =
    try {
        InterpreterBridgePreparedModelResult(
            InterpreterBridgePreparedModel(CorePreparedModel.loadExpandedJson(source)),
            null,
        )
    } catch (error: CoreModelPreparationError) {
        InterpreterBridgePreparedModelResult(null, preparationFailure(error))
    }

/**
 * Kotlin/JS **erases `Int`**, so a packed consumer really can hand `1.5`, `NaN`, `Infinity` or `2^31` to a
 * parameter typed `Array<Int>` and the compiler never sees it. This is the boundary where that erasure happens,
 * so this is where it is refused — the shared domain checks in `commonMain` are about A12's domain and would
 * happily read `1.5 >= 1` as satisfied.
 *
 * The test is lexical on purpose: `toString().toIntOrNull()` is the one integrality-and-range predicate whose
 * behaviour does not depend on how Kotlin/JS lowers `Int` arithmetic (`%`, `|0`, truncating conversions), and it
 * rejects a value outside the 32-bit range for free. `TypeScriptBridgePointerFormatTest` measures each case.
 */
private fun Array<Int>.asPortableIntegers(): List<Int> = map { value ->
    require(value.toString().toIntOrNull() == value) {
        "each repetition index must be a 32-bit integer, but got '$value'"
    }
    value
}

/**
 * Renders A12's canonical **exact** `DocumentPointer` for [path] and its positional [repetitions].
 *
 * A malformed request comes back as [InterpreterBridgePointerFormatResult.rejection] rather than as a thrown
 * `IllegalArgumentException`: it is a caller contract violation, not a model diagnostic, so it carries no code
 * and no structured details and must not borrow [InterpreterBridgeFailure]'s diagnostic shape.
 */
@JsExport
fun interpreterBridgeFormatA12Pointer(
    path: String,
    repetitions: Array<Int>,
): InterpreterBridgePointerFormatResult =
    try {
        InterpreterBridgePointerFormatResult(
            CoreA12PointerFormat.format(path, repetitions.asPortableIntegers()),
            null,
        )
    } catch (rejected: IllegalArgumentException) {
        InterpreterBridgePointerFormatResult(null, rejected.message.orEmpty())
    }

/** Parses a canonical or leading-slash pointer; see [interpreterBridgeFormatA12Pointer] on the rejection shape. */
@JsExport
fun interpreterBridgeParseA12Pointer(pointer: String): InterpreterBridgePointerParseResult =
    try {
        InterpreterBridgePointerParseResult(bridgePointer(CoreA12PointerFormat.parse(pointer)), null)
    } catch (rejected: IllegalArgumentException) {
        InterpreterBridgePointerParseResult(null, rejected.message.orEmpty())
    }

private fun bridgePointer(parsed: CoreA12Pointer) = InterpreterBridgeParsedPointer(
    parsed.pathParts.map { InterpreterBridgePathPart(it.name, it.repetitionIndex) }.toTypedArray(),
    parsed.fullName,
    parsed.repetitionIndexes.toTypedArray(),
)

/** A12's `RepetitionsV2` slot repetition, legal only on an exact pointer's last part. */
@JsExport
fun interpreterBridgeA12LastPartZero(): Int = CoreA12PointerFormat.LAST_PART_ZERO

/**
 * Builds a **message** pointer — `PartiallyKnownDocumentMultiPointer.of(fullName, repetitions)`.
 *
 * There is deliberately no `format`/`parse` beside it: A12 defines no pointer string for this type, so the
 * bridge carries the owner's value and the owner's operations only.
 */
@JsExport
fun interpreterBridgeA12PartiallyKnownOf(
    fullName: String,
    repetitions: Array<Int>,
): InterpreterBridgePartiallyKnownPointerResult =
    try {
        val built = CorePartiallyKnown.of(fullName, repetitions.asPortableIntegers())
        InterpreterBridgePartiallyKnownPointerResult(bridgePartiallyKnown(built), null)
    } catch (rejected: IllegalArgumentException) {
        InterpreterBridgePartiallyKnownPointerResult(null, rejected.message.orEmpty())
    }

/** The one crossing of a message pointer, so a built one and a finding's one cannot take different shapes. */
private fun bridgePartiallyKnown(pointer: CorePartiallyKnown) = InterpreterBridgeParsedPartiallyKnownPointer(
    pointer.fullName,
    pointer.repetitionIndexes.toTypedArray(),
)

/**
 * `PartiallyKnownDocumentMultiPointer.toDocumentPointer()` — the exact pointer when the message pointer is one,
 * and a `null` [InterpreterBridgePointerParseResult.value] with no rejection when it legitimately is not.
 *
 * The ROOT raises rather than answering, which is the owner's measured behaviour, so it arrives as a rejection.
 */
@JsExport
fun interpreterBridgeA12PartiallyKnownPointerToExact(
    fullName: String,
    repetitions: Array<Int>,
): InterpreterBridgePointerParseResult =
    try {
        val exact = CorePartiallyKnown.of(fullName, repetitions.asPortableIntegers()).toExactPointer()
        InterpreterBridgePointerParseResult(exact?.let(::bridgePointer), null)
    } catch (rejected: IllegalArgumentException) {
        InterpreterBridgePointerParseResult(null, rejected.message.orEmpty())
    }

/** `DocumentMultiPointer.WILDCARD` — all repetitions of a level, legal at any part of a message pointer. */
@JsExport
fun interpreterBridgeA12PartiallyKnownWildcard(): Int = CorePartiallyKnown.WILDCARD

/** `PartiallyKnownDocumentMultiPointer.UNKNOWN` (`IIdentifier.UNKNOWN`) — a level that resolved to no row. */
@JsExport
fun interpreterBridgeA12PartiallyKnownUnknown(): Int = CorePartiallyKnown.UNKNOWN

/** `IIdentifier.PARALLEL_ITERATION` — a parallel level A12's runtime can leave on a message pointer's references. */
@JsExport
fun interpreterBridgeA12PartiallyKnownParallelIteration(): Int = CorePartiallyKnown.PARALLEL_ITERATION

private fun interpreterConfiguration(
    customFieldTypes: Array<InterpreterBridgeCustomFieldTypeRegistration>,
    customConditions: Array<InterpreterBridgeCustomConditionRegistration>,
    evaluationInstant: Double?,
): CoreInterpreterConfiguration {
    val fieldTypes = CoreCustomFieldTypeRegistry.builder()
    customFieldTypes.forEach { registration ->
        fieldTypes.register(
            registration.name,
            CoreCustomFieldValidator { value, parameters ->
                registration.validate(
                    value,
                    parameters.locale,
                    parameters.minLength,
                    parameters.maxLength,
                    parameters.isDisplayValue,
                ).toCore()
            },
        )
    }
    val conditions = CoreCustomConditionRegistry.builder()
    customConditions.forEach { registration ->
        conditions.register(
            registration.name,
            CoreCustomCondition { invocation ->
                registration.check(InterpreterBridgeCustomConditionInvocation(invocation))
            },
        )
    }
    // A JavaScript Date's time value is an integral millisecond count, so the conversion is exact.
    return CoreInterpreterConfiguration.builder()
        .customFieldTypes(fieldTypes.build())
        .customConditions(conditions.build())
        .apply { evaluationInstant?.let { evaluationInstant(it.toLong()) } }
        .build()
}

private fun InterpreterBridgeCustomFieldValidation.toCore(): CoreCustomFieldValidationResult =
    if (accepted) {
        CoreCustomFieldValidationResult.Accepted
    } else {
        CoreCustomFieldValidationResult.Rejected(
            errorKey = requireNotNull(errorKey) {
                "a rejecting custom-field validation must provide errorKey"
            },
            message = message,
        )
    }

private fun preparationLimits(json: String?): CoreModelPreparationLimits {
    if (json.isNullOrEmpty()) return CoreModelPreparationLimits.defaults()
    val values = Json.parseToJsonElement(json).jsonObject
    val builder = CoreModelPreparationLimits.builder()
    values.intOrNull("maxModelCount")?.let(builder::maxModelCount)
    values.intOrNull("maxModelBytes")?.let(builder::maxModelBytes)
    values.intOrNull("maxTotalBytes")?.let(builder::maxTotalBytes)
    values.intOrNull("maxPreparedBytes")?.let(builder::maxPreparedBytes)
    values.intOrNull("maxReferenceDepth")?.let(builder::maxReferenceDepth)
    values.intOrNull("maxReferenceEdges")?.let(builder::maxReferenceEdges)
    values.intOrNull("maxStructureDepth")?.let(builder::maxStructureDepth)
    values.intOrNull("maxPreparedElements")?.let(builder::maxPreparedElements)
    return builder.build()
}

private fun documentLimits(json: String?): CoreDocumentDecodeLimits {
    if (json.isNullOrEmpty()) return CoreDocumentDecodeLimits.defaults()
    val values = Json.parseToJsonElement(json).jsonObject
    val builder = CoreDocumentDecodeLimits.builder()
    values.intOrNull("maxDocumentCodeUnits")?.let(builder::maxDocumentCodeUnits)
    values.intOrNull("maxGroupPlacements")?.let(builder::maxGroupPlacements)
    values.intOrNull("maxFieldPlacements")?.let(builder::maxFieldPlacements)
    values.intOrNull("maxPathCodeUnits")?.let(builder::maxPathCodeUnits)
    values.intOrNull("maxRawCodeUnitsPerField")?.let(builder::maxRawCodeUnitsPerField)
    values.intOrNull("maxTotalRawCodeUnits")?.let(builder::maxTotalRawCodeUnits)
    values.intOrNull("maxModelNestingDepth")?.let(builder::maxModelNestingDepth)
    return builder.build()
}

private fun JsonObject.intOrNull(name: String): Int? = get(name)?.jsonPrimitive?.int

private fun translateProviderFailure(error: Throwable, requestedModelId: String): Throwable {
    val dynamicError = error.asDynamic()
    if (dynamicError.code as? String != "A12_MODEL_SOURCE_PROVIDER_FAILED") return error
    val reason = (dynamicError.reason as? String)?.let(::providerFailureReason)
        ?: CoreProviderFailureReason.PROVIDER_FAILED
    val modelId = (dynamicError.modelId as? String).orEmpty().ifEmpty { requestedModelId }
    return CoreModelSourceProviderError.builder()
        .reason(reason)
        .modelId(modelId)
        .message("provider could not load model '$modelId'")
        .cause(error)
        .build()
}

private fun providerFailureReason(value: String): CoreProviderFailureReason? = when (value) {
    "access-denied" -> CoreProviderFailureReason.ACCESS_DENIED
    "unavailable" -> CoreProviderFailureReason.UNAVAILABLE
    "cancelled" -> CoreProviderFailureReason.CANCELLED
    "source-too-large" -> CoreProviderFailureReason.SOURCE_TOO_LARGE
    "integrity-mismatch" -> CoreProviderFailureReason.INTEGRITY_MISMATCH
    "provider-failed" -> CoreProviderFailureReason.PROVIDER_FAILED
    else -> null
}

private fun preparationFailure(error: CoreModelPreparationError): InterpreterBridgeFailure =
    InterpreterBridgeFailure(
        code = error.code,
        message = error.message.orEmpty(),
        detailsJson = buildJsonObject {
            put("diagnostics", JsonArray(error.diagnostics.map(::preparationDiagnostic)))
        }.toString(),
    )

private fun preparationDiagnostic(diagnostic: CoreModelPreparationDiagnostic): JsonObject =
    buildJsonObject {
        put("code", diagnostic.code.name)
        putOptional("diagnosticLabel", diagnostic.diagnosticLabel)
        putOptional("requestedModelId", diagnostic.requestedModelId)
        putOptional("actualModelId", diagnostic.actualModelId)
        putOptional("providerFailureReason", diagnostic.providerFailureReason?.wireName())
        putOptional("expectedSha256", diagnostic.expectedSha256)
        putOptional("actualSha256", diagnostic.actualSha256)
        putOptional("modelId", diagnostic.modelId)
        putOptional("path", diagnostic.path)
        putOptional("reference", diagnostic.reference)
        if (diagnostic.referenceChain.isNotEmpty()) {
            put("referenceChain", JsonArray(diagnostic.referenceChain.map { step ->
                buildJsonObject {
                    put("fromModelId", step.fromModelId)
                    put("toModelId", step.toModelId)
                    put("purpose", step.purpose.wireName())
                }
            }))
        }
        if (diagnostic.conflictingDiagnosticLabels.isNotEmpty()) {
            put(
                "conflictingDiagnosticLabels",
                JsonArray(diagnostic.conflictingDiagnosticLabels.map(::JsonPrimitive)),
            )
        }
        putOptional("resource", diagnostic.resource?.wireName())
        putOptional("maximum", diagnostic.maximum)
        putOptional("actual", diagnostic.actual)
        putOptional("firstSourceModelId", diagnostic.firstSourceModelId)
        putOptional("conflictingSourceModelId", diagnostic.conflictingSourceModelId)
        putOptional("sourceLanguage", diagnostic.sourceLanguage)
        putOptional("entryLanguage", diagnostic.entryLanguage)
        putOptional("reason", diagnostic.reason)
        putOptional("cyclic", diagnostic.cyclic)
        put("message", diagnostic.message)
    }

private fun documentInputFailure(error: CoreDocumentInputError): InterpreterBridgeFailure =
    InterpreterBridgeFailure(
        code = error.code,
        message = error.message.orEmpty(),
        detailsJson = buildJsonObject {
            put("diagnostics", JsonArray(error.diagnostics.map(::documentInputDiagnostic)))
        }.toString(),
    )

private fun documentInputDiagnostic(diagnostic: CoreDocumentInputDiagnostic): JsonObject =
    buildJsonObject {
        put("reasonCode", diagnostic.reasonCode.wireName())
        putOptional("pointer", diagnostic.pointer)
        putOptional("modelPath", diagnostic.modelPath)
        putOptional("entityKind", diagnostic.entityKind?.wireName())
        putOptional("nodeType", diagnostic.nodeType?.wireName())
        putOptional("resource", diagnostic.resource?.wireName())
        putOptional("maximum", diagnostic.maximum)
        putOptional("actual", diagnostic.actual)
        put("message", diagnostic.message)
    }

private fun documentEncodeFailure(error: CoreDocumentEncodeError): InterpreterBridgeFailure =
    InterpreterBridgeFailure(
        code = error.code,
        message = error.message.orEmpty(),
        detailsJson = buildJsonObject {
            put("modelPath", error.modelPath)
            put("pointer", error.pointer)
        }.toString(),
    )

private fun ownerMismatchFailure(error: CoreModelOwnerMismatchError): InterpreterBridgeFailure =
    InterpreterBridgeFailure(error.code, error.message.orEmpty(), "{}")

private fun unknownPathFailure(error: CoreUnknownModelPathError): InterpreterBridgeFailure =
    InterpreterBridgeFailure(
        error.code,
        error.message.orEmpty(),
        buildJsonObject { put("path", error.path) }.toString(),
    )

private fun coordinateFailure(error: CoreDocumentCoordinateError): InterpreterBridgeFailure =
    InterpreterBridgeFailure(
        error.code,
        error.message.orEmpty(),
        buildJsonObject {
            put("reason", error.reason.wireName())
            put("path", error.path)
            putOptional("coordinateIndex", error.coordinateIndex)
            putOptional("expectedCoordinateCount", error.expectedCoordinateCount)
            putOptional("actualCoordinateCount", error.actualCoordinateCount)
        }.toString(),
    )

private fun integrationFailure(error: CoreInterpreterIntegrationError): InterpreterBridgeFailure {
    val hostError = error.cause?.asDynamic()
    val hostReason =
        if (hostError != null &&
            hostError.code == "A12_INTERPRETER_INTEGRATION_FAILED" &&
            hostError.reason == "thenable-returned"
        ) {
            "thenable-returned"
        } else {
            null
        }
    return InterpreterBridgeFailure(
        error.code,
        error.message.orEmpty(),
        buildJsonObject {
            put("phase", error.phase.name.lowercase())
            put("reason", hostReason ?: error.reason.name.lowercase().replace('_', '-'))
        }.toString(),
    )
}

private fun validationFinding(finding: CoreValidationFinding): InterpreterBridgeValidationFinding =
    InterpreterBridgeValidationFinding(
        code = finding.code,
        rulePath = finding.rulePath,
        errorPath = bridgePartiallyKnown(finding.errorPath),
        severity = finding.severity.wireName(),
        type = finding.type.wireName(),
        text = finding.text,
        referenced = finding.referenced.map(::bridgePartiallyKnown).toTypedArray(),
        fillToFix = finding.fillToFix.map(::bridgePartiallyKnown).toTypedArray(),
    )

private fun CoreProviderFailureReason.wireName(): String = when (this) {
    CoreProviderFailureReason.ACCESS_DENIED -> "access-denied"
    CoreProviderFailureReason.UNAVAILABLE -> "unavailable"
    CoreProviderFailureReason.CANCELLED -> "cancelled"
    CoreProviderFailureReason.SOURCE_TOO_LARGE -> "source-too-large"
    CoreProviderFailureReason.INTEGRITY_MISMATCH -> "integrity-mismatch"
    CoreProviderFailureReason.PROVIDER_FAILED -> "provider-failed"
}

private fun CoreModelReferencePurpose.wireName(): String = when (this) {
    CoreModelReferencePurpose.INCLUDE -> "include"
    CoreModelReferencePurpose.TYPE_DEFINITIONS -> "type-definitions"
}

private fun CoreModelPreparationResource.wireName(): String = when (this) {
    CoreModelPreparationResource.MODEL_COUNT -> "model-count"
    CoreModelPreparationResource.MODEL_BYTES -> "model-bytes"
    CoreModelPreparationResource.TOTAL_BYTES -> "total-bytes"
    CoreModelPreparationResource.PREPARED_BYTES -> "prepared-bytes"
    CoreModelPreparationResource.REFERENCE_DEPTH -> "reference-depth"
    CoreModelPreparationResource.REFERENCE_EDGES -> "reference-edges"
    CoreModelPreparationResource.STRUCTURE_DEPTH -> "structure-depth"
    CoreModelPreparationResource.PREPARED_ELEMENTS -> "prepared-elements"
}

private fun CoreDocumentInputReasonCode.wireName(): String = when (this) {
    CoreDocumentInputReasonCode.MALFORMED_JSON -> "malformed-json"
    CoreDocumentInputReasonCode.UNKNOWN_MODEL_PATH -> "unknown-model-path"
    CoreDocumentInputReasonCode.UNEXPECTED_NODE_TYPE -> "unexpected-node-type"
    CoreDocumentInputReasonCode.INVALID_DOCUMENT_ID -> "invalid-document-id"
    CoreDocumentInputReasonCode.RESOURCE_LIMIT_EXCEEDED -> "resource-limit-exceeded"
}

private fun CoreDocumentJsonNodeType.wireName(): String = when (this) {
    CoreDocumentJsonNodeType.OBJECT -> "object"
    CoreDocumentJsonNodeType.ARRAY -> "array"
    CoreDocumentJsonNodeType.VALUE -> "value"
    CoreDocumentJsonNodeType.NULL -> "null"
}

private fun CoreDocumentInputResource.wireName(): String = when (this) {
    CoreDocumentInputResource.DOCUMENT_CODE_UNITS -> "document-code-units"
    CoreDocumentInputResource.GROUP_PLACEMENTS -> "group-placements"
    CoreDocumentInputResource.FIELD_PLACEMENTS -> "field-placements"
    CoreDocumentInputResource.PATH_CODE_UNITS -> "path-code-units"
    CoreDocumentInputResource.RAW_CODE_UNITS_PER_FIELD -> "raw-code-units-per-field"
    CoreDocumentInputResource.TOTAL_RAW_CODE_UNITS -> "total-raw-code-units"
    CoreDocumentInputResource.MODEL_NESTING_DEPTH -> "model-nesting-depth"
}

private fun CoreDocumentCoordinateReason.wireName(): String = when (this) {
    CoreDocumentCoordinateReason.COORDINATE_COUNT_MISMATCH -> "coordinate-count-mismatch"
    CoreDocumentCoordinateReason.INVALID_COORDINATE -> "coordinate-not-positive"
    CoreDocumentCoordinateReason.INVALID_FIELD_REPETITION -> "field-terminal-not-one"
}

private fun CoreModelEntityKind.wireName(): String = when (this) {
    CoreModelEntityKind.GROUP -> "group"
    CoreModelEntityKind.FIELD -> "field"
}

private fun CoreValidationSeverity.wireName(): String = when (this) {
    CoreValidationSeverity.ERROR -> "error"
    CoreValidationSeverity.WARNING -> "warning"
    CoreValidationSeverity.INFO -> "info"
}

/** The one core → bridge projection for an unsupported entry, so no site can drop the subject. */
private fun unsupportedProgram(program: CoreUnsupportedProgram): InterpreterBridgeUnsupportedProgram =
    InterpreterBridgeUnsupportedProgram(program.path, program.reason, program.subject.wireName())

private fun CoreUnsupportedProgramSubject.wireName(): String = when (this) {
    CoreUnsupportedProgramSubject.ELEMENT -> "element"
    CoreUnsupportedProgramSubject.CELL -> "cell"
}

private fun CoreValidationFindingType.wireName(): String = when (this) {
    CoreValidationFindingType.VALUE -> "value"
    CoreValidationFindingType.OMISSION -> "omission"
}

private fun CoreComputationOutcome.wireName(): String = when (this) {
    CoreComputationOutcome.VALUE -> "value"
    CoreComputationOutcome.CLEARED -> "cleared"
    CoreComputationOutcome.ERRORED -> "errored"
}

private fun JsonObjectBuilder.putOptional(name: String, value: String?) {
    if (value != null) put(name, value)
}

private fun JsonObjectBuilder.putOptional(name: String, value: Int?) {
    if (value != null) put(name, value)
}

private fun JsonObjectBuilder.putOptional(name: String, value: Long?) {
    if (value != null) put(name, value)
}

private fun JsonObjectBuilder.putOptional(name: String, value: Boolean?) {
    if (value != null) put(name, value)
}
