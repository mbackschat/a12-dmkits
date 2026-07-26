package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome as EngineComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.CustomCondition as EngineCustomCondition
import io.github.mbackschat.a12.dm.interpreter.model.CustomConditionData as EngineCustomConditionData
import io.github.mbackschat.a12.dm.interpreter.model.CustomConditionFieldPointer
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldType as EngineCustomFieldType
import io.github.mbackschat.a12.dm.interpreter.model.CustomTypeRejection
import io.github.mbackschat.a12.dm.interpreter.model.Message as EngineMessage
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.interpreter.model.Severity
import io.github.mbackschat.a12.dm.interpreter.model.ValidationOutcome
import io.github.mbackschat.a12.dm.interpreter.workspace.immutableListSnapshot
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmSynthetic

/** Whether a model-owned pointer addresses a declared group row or field cell. */
enum class ModelEntityKind {
    /** A declared group row. */
    GROUP,

    /** A declared field cell. */
    FIELD,
}

/** Stable reason why a concrete model address cannot be constructed. */
enum class DocumentCoordinateReason {
    /** The address does not carry one coordinate per model-path part. */
    COORDINATE_COUNT_MISMATCH,

    /** A concrete coordinate is not a positive 1-based index. */
    INVALID_COORDINATE,

    /** The terminal coordinate of a field address is not 1. */
    INVALID_FIELD_REPETITION,
}

/** A lookup requested a path that names no declared field or group. */
class UnknownModelPathError private constructor(
    val path: String,
) : RuntimeException("no field or group exists at model path '$path'") {
    val code: String = "A12_UNKNOWN_MODEL_PATH"

    companion object {
        @JvmSynthetic
        internal fun of(path: String): UnknownModelPathError = UnknownModelPathError(path)
    }
}

/**
 * A concrete or relevance pointer carries invalid coordinates. Group coordinates above declared bounds remain
 * representable for formal validation; this error covers address shape, not repetition-limit validation.
 */
class DocumentCoordinateError private constructor(
    val reason: DocumentCoordinateReason,
    val path: String,
    val coordinateIndex: Int?,
    val expectedCoordinateCount: Int?,
    val actualCoordinateCount: Int?,
) : RuntimeException(messageFor(reason, path, coordinateIndex, expectedCoordinateCount, actualCoordinateCount)) {
    val code: String = "A12_DOCUMENT_COORDINATE_INVALID"

    companion object {
        @JvmSynthetic
        internal fun of(
            reason: DocumentCoordinateReason,
            path: String,
            coordinateIndex: Int? = null,
            expectedCoordinateCount: Int? = null,
            actualCoordinateCount: Int? = null,
        ): DocumentCoordinateError =
            DocumentCoordinateError(reason, path, coordinateIndex, expectedCoordinateCount, actualCoordinateCount)

        private fun messageFor(
            reason: DocumentCoordinateReason,
            path: String,
            coordinateIndex: Int?,
            expectedCoordinateCount: Int?,
            actualCoordinateCount: Int?,
        ): String = when (reason) {
            DocumentCoordinateReason.COORDINATE_COUNT_MISMATCH ->
                "model path '$path' needs $expectedCoordinateCount coordinates, got $actualCoordinateCount"
            DocumentCoordinateReason.INVALID_COORDINATE ->
                "coordinate $coordinateIndex of model path '$path' must be a positive 1-based index"
            DocumentCoordinateReason.INVALID_FIELD_REPETITION ->
                "the terminal field coordinate of model path '$path' must be 1"
        }
    }
}

/** One coordinate of a partial-validation selection: one exact row or every repetition at that level. */
sealed interface RelevantCoordinate {
    /** One positive 1-based repetition coordinate. */
    data class Exact(val value: Int) : RelevantCoordinate

    /** Every repetition at this path level, equivalent to the Kernel's `ALL_SEMANTIC`. */
    data object All : RelevantCoordinate

    companion object {
        @JvmStatic
        fun exact(value: Int): RelevantCoordinate = Exact(value)

        @JvmStatic
        fun all(): RelevantCoordinate = All
    }
}

/** Model-owned field/group selection accepted by partial validation. */
sealed interface RelevantEntityPointer : ModelOwned {
    val entityKind: ModelEntityKind
    val path: String
}

/** One exact model-owned group row or field cell. */
sealed interface DocumentPointer : RelevantEntityPointer {
    val coordinates: List<Int>
}

/** Exact model-owned group-row pointer produced by [ModelMetadata.pointer]. */
class GroupPointer private constructor(
    override val path: String,
    coordinates: List<Int>,
    override val owner: ModelOwnerIdentity,
    override val modelVersion: ModelVersionIdentity,
) : DocumentPointer {
    override val entityKind = ModelEntityKind.GROUP
    override val coordinates: List<Int> = immutableListSnapshot(coordinates)

    companion object {
        @JvmSynthetic
        internal fun create(
            path: String,
            coordinates: List<Int>,
            owner: ModelOwnerIdentity,
            modelVersion: ModelVersionIdentity,
        ): GroupPointer = GroupPointer(path, coordinates, owner, modelVersion)
    }
}

/** Exact model-owned field-cell pointer produced by [ModelMetadata.pointer]. */
class FieldPointer private constructor(
    override val path: String,
    coordinates: List<Int>,
    override val owner: ModelOwnerIdentity,
    override val modelVersion: ModelVersionIdentity,
) : DocumentPointer {
    override val entityKind = ModelEntityKind.FIELD
    override val coordinates: List<Int> = immutableListSnapshot(coordinates)

    companion object {
        @JvmSynthetic
        internal fun create(
            path: String,
            coordinates: List<Int>,
            owner: ModelOwnerIdentity,
            modelVersion: ModelVersionIdentity,
        ): FieldPointer = FieldPointer(path, coordinates, owner, modelVersion)
    }
}

/** Partial-validation selection containing at least one [RelevantCoordinate.All] coordinate. */
class RelevantSelectionPointer private constructor(
    override val entityKind: ModelEntityKind,
    override val path: String,
    coordinates: List<RelevantCoordinate>,
    override val owner: ModelOwnerIdentity,
    override val modelVersion: ModelVersionIdentity,
) : RelevantEntityPointer {
    val coordinates: List<RelevantCoordinate> = immutableListSnapshot(coordinates)

    companion object {
        @JvmSynthetic
        internal fun create(
            entityKind: ModelEntityKind,
            path: String,
            coordinates: List<RelevantCoordinate>,
            owner: ModelOwnerIdentity,
            modelVersion: ModelVersionIdentity,
        ): RelevantSelectionPointer =
            RelevantSelectionPointer(entityKind, path, coordinates, owner, modelVersion)
    }
}

/**
 * Minimal model-owned metadata boundary for exact and partial-relevance pointer construction. Rich declaration
 * navigation remains outside the first interpreter release increment.
 */
class ModelMetadata private constructor(
    private val prepared: PreparedModel,
) : ModelOwned by prepared {
    val documentModelId: String get() = prepared.entryModelId

    fun pointer(path: String, coordinates: List<Int>): DocumentPointer {
        val kind = entityKind(path)
        requireCoordinates(path, kind, coordinates)
        return when (kind) {
            ModelEntityKind.GROUP ->
                GroupPointer.create(path, coordinates, owner, modelVersion)
            ModelEntityKind.FIELD ->
                FieldPointer.create(path, coordinates, owner, modelVersion)
        }
    }

    fun relevantPointer(path: String, coordinates: List<RelevantCoordinate>): RelevantEntityPointer {
        val kind = entityKind(path)
        requireCoordinateCount(path, coordinates.size)
        coordinates.forEachIndexed { index, coordinate ->
            if (coordinate is RelevantCoordinate.Exact && coordinate.value < 1) {
                throw DocumentCoordinateError.of(
                    reason = DocumentCoordinateReason.INVALID_COORDINATE,
                    path = path,
                    coordinateIndex = index,
                    expectedCoordinateCount = null,
                    actualCoordinateCount = null,
                )
            }
        }
        val exact = coordinates.mapNotNull { (it as? RelevantCoordinate.Exact)?.value }
        if (exact.size == coordinates.size) {
            return pointer(path, exact)
        }
        return RelevantSelectionPointer.create(kind, path, coordinates, owner, modelVersion)
    }

    @JvmSynthetic
    internal fun requireOwned(pointer: ModelOwned) {
        if (pointer.owner !== owner) {
            throw ModelOwnerMismatchError.of(expected = owner, actual = pointer.owner)
        }
    }

    @JvmSynthetic
    internal fun fieldPointer(pointer: CustomConditionFieldPointer): FieldPointer =
        FieldPointer.create(pointer.path, pointer.indices, owner, modelVersion)

    @JvmSynthetic
    internal fun relevantPointer(pointer: CustomConditionFieldPointer): RelevantEntityPointer {
        val coordinates = pointer.indices.map { coordinate ->
            if (coordinate == RelevantEntity.ALL) RelevantCoordinate.All else RelevantCoordinate.Exact(coordinate)
        }
        return relevantPointer(pointer.path, coordinates)
    }

    private fun entityKind(path: String): ModelEntityKind {
        val model = prepared.definition()
        return when (ModelAddressValidation.entityKind(model, path)) {
            DocumentPlacementKind.GROUP -> ModelEntityKind.GROUP
            DocumentPlacementKind.FIELD -> ModelEntityKind.FIELD
            null -> throw UnknownModelPathError.of(path)
        }
    }

    private fun requireCoordinates(path: String, kind: ModelEntityKind, coordinates: List<Int>) {
        val placementKind = when (kind) {
            ModelEntityKind.GROUP -> DocumentPlacementKind.GROUP
            ModelEntityKind.FIELD -> DocumentPlacementKind.FIELD
        }
        ModelAddressValidation.violations(placementKind, path, coordinates)
            .firstOrNull()
            ?.let { throw coordinateError(path, it) }
    }

    private fun coordinateError(path: String, violation: ModelAddressViolation): DocumentCoordinateError =
        when (violation.reason) {
            ModelAddressViolationReason.COORDINATE_COUNT_MISMATCH ->
                DocumentCoordinateError.of(
                    reason = DocumentCoordinateReason.COORDINATE_COUNT_MISMATCH,
                    path = path,
                    expectedCoordinateCount = violation.expectedCoordinateCount,
                    actualCoordinateCount = violation.actualCoordinateCount,
                )
            ModelAddressViolationReason.INVALID_COORDINATE ->
                DocumentCoordinateError.of(
                    reason = DocumentCoordinateReason.INVALID_COORDINATE,
                    path = path,
                    coordinateIndex = violation.coordinateIndex,
                )
            ModelAddressViolationReason.INVALID_FIELD_REPETITION ->
                DocumentCoordinateError.of(
                    reason = DocumentCoordinateReason.INVALID_FIELD_REPETITION,
                    path = path,
                    coordinateIndex = violation.coordinateIndex,
                )
        }

    private fun requireCoordinateCount(path: String, actual: Int) {
        val expected = ModelAddressValidation.pathPartCount(path)
        if (actual != expected) {
            throw DocumentCoordinateError.of(
                reason = DocumentCoordinateReason.COORDINATE_COUNT_MISMATCH,
                path = path,
                coordinateIndex = null,
                expectedCoordinateCount = expected,
                actualCoordinateCount = actual,
            )
        }
    }

    companion object {
        @JvmSynthetic
        internal fun create(prepared: PreparedModel): ModelMetadata = ModelMetadata(prepared)
    }
}

/** UI severity of one validation finding; only [ERROR] makes `noErrorOccurred` false. */
enum class ValidationSeverity {
    /** Error-severity finding. */
    ERROR,

    /** Warning-severity finding. */
    WARNING,

    /** Informational finding. */
    INFO,
}

/** Whether a firing reports an invalid supplied value or a required omission. */
enum class ValidationFindingType {
    /** A supplied value violates a rule or formal constraint. */
    VALUE,

    /** Required content is absent. */
    OMISSION,
}

/** Detached, kernel-free projection of one validation finding. */
data class ValidationFinding(
    val code: String,
    val rulePath: String,
    val errorPath: String,
    val severity: ValidationSeverity,
    val type: ValidationFindingType,
    val text: String,
    val referenced: List<String>,
    val fillToFix: List<String>,
)

/** One stored rule, implicit rule, or computation the configured interpreter cannot evaluate. */
data class UnsupportedProgram(
    val path: String,
    val reason: String,
)

/** Document-independent support audit for one prepared model and one bound customization configuration. */
class InterpreterSupportReport private constructor(
    unsupported: List<UnsupportedProgram>,
) {
    val unsupported: List<UnsupportedProgram> = immutableListSnapshot(unsupported)
    val fullySupported: Boolean get() = unsupported.isEmpty()

    companion object {
        @JvmSynthetic
        internal fun create(unsupported: List<UnsupportedProgram>): InterpreterSupportReport =
            InterpreterSupportReport(unsupported)
    }
}

/** Detached full/partial-validation report with independent finding and support axes. */
class ValidationReport private constructor(
    findings: List<ValidationFinding>,
    unsupported: List<UnsupportedProgram>,
) {
    val findings: List<ValidationFinding> = immutableListSnapshot(findings)
    val unsupported: List<UnsupportedProgram> = immutableListSnapshot(unsupported)
    val noErrorOccurred: Boolean get() = findings.none { it.severity == ValidationSeverity.ERROR }
    val fullySupported: Boolean get() = unsupported.isEmpty()

    companion object {
        @JvmSynthetic
        internal fun create(
            findings: List<ValidationFinding>,
            unsupported: List<UnsupportedProgram>,
        ): ValidationReport = ValidationReport(findings, unsupported)
    }
}

/** Result state of one computed field cell. */
enum class ComputationOutcome {
    /** The computation produced [ComputationResult.value]. */
    VALUE,

    /** The computation produced no value and clears the target. */
    CLEARED,

    /** The produced text is formally invalid for the target field. */
    ERRORED,
}

/** Detached outcome of one computed field cell. */
data class ComputationResult(
    val pointer: FieldPointer,
    val outcome: ComputationOutcome,
    val value: String?,
)

/** Detached report for the model's computations; unsupported work never fabricates a computed value. */
class ComputationReport private constructor(
    results: List<ComputationResult>,
    unsupported: List<UnsupportedProgram>,
) {
    val results: List<ComputationResult> = immutableListSnapshot(results)
    val unsupported: List<UnsupportedProgram> = immutableListSnapshot(unsupported)
    val fullySupported: Boolean get() = unsupported.isEmpty()

    companion object {
        @JvmSynthetic
        internal fun create(
            results: List<ComputationResult>,
            unsupported: List<UnsupportedProgram>,
        ): ComputationReport = ComputationReport(results, unsupported)
    }
}

/** Locale and effective declaration bounds supplied to one custom-field validation call. */
data class CustomFieldValidationParameters(
    val locale: String,
    val minLength: Int?,
    val maxLength: Int?,
    val isDisplayValue: Boolean,
)

/** Allocation-free accepting or structured rejecting result of a custom field validator. */
sealed interface CustomFieldValidationResult {
    /** The stored text is accepted. */
    data object Accepted : CustomFieldValidationResult

    /** The stored text is rejected with the project-owned error key and optional message template. */
    data class Rejected(
        val errorKey: String,
        val message: String?,
    ) : CustomFieldValidationResult
}

/** Synchronous code-based custom-field validation SPI. */
fun interface CustomFieldValidator {
    fun validate(value: String, parameters: CustomFieldValidationParameters): CustomFieldValidationResult
}

/** Name-resolving source of custom field validators; `null` means unsupported. */
fun interface CustomFieldTypeFactory {
    fun create(customFieldTypeName: String): CustomFieldValidator?
}

/** Synchronous document-aware error-condition SPI; `true` means the containing rule fires. */
fun interface CustomCondition {
    fun check(invocation: CustomConditionInvocation): Boolean
}

/** Name-resolving source of custom conditions; `null` means unsupported. */
fun interface CustomConditionFactory {
    fun create(customConditionName: String): CustomCondition?
}

/** Read-only, model-owned document view supplied only while a custom condition is executing. */
class CustomConditionDocumentView private constructor(
    val snapshot: DocumentSnapshot,
    val model: ModelMetadata,
    private val data: EngineCustomConditionData,
) : ModelOwned by snapshot {
    fun value(pointer: FieldPointer): String? {
        model.requireOwned(pointer)
        return data.getValue(CustomConditionFieldPointer(pointer.path, pointer.coordinates))
    }

    fun fields(): List<FieldPointer> =
        immutableListSnapshot(data.fields().map(model::fieldPointer))

    companion object {
        @JvmSynthetic
        internal fun create(
            snapshot: DocumentSnapshot,
            model: ModelMetadata,
            data: EngineCustomConditionData,
        ): CustomConditionDocumentView = CustomConditionDocumentView(snapshot, model, data)
    }
}

/** Exact document, relevance, formal-error, and error-pointer inputs of one custom-condition call. */
class CustomConditionInvocation private constructor(
    val document: CustomConditionDocumentView,
    relevantEntities: List<RelevantEntityPointer>?,
    formallyIncorrectEntities: List<FieldPointer>,
    val errorPointer: FieldPointer,
) {
    val relevantEntities: List<RelevantEntityPointer>? = relevantEntities?.let(::immutableListSnapshot)
    val formallyIncorrectEntities: List<FieldPointer> = immutableListSnapshot(formallyIncorrectEntities)

    companion object {
        @JvmSynthetic
        internal fun create(
            document: CustomConditionDocumentView,
            relevantEntities: List<RelevantEntityPointer>?,
            formallyIncorrectEntities: List<FieldPointer>,
            errorPointer: FieldPointer,
        ): CustomConditionInvocation =
            CustomConditionInvocation(document, relevantEntities, formallyIncorrectEntities, errorPointer)
    }
}

/** Customization registry involved in a registration failure. */
enum class CustomizationKind {
    /** Code-based custom field types. */
    CUSTOM_FIELD_TYPE,

    /** Document-aware custom conditions. */
    CUSTOM_CONDITION,
}

/** Stable reason why an explicit customization registration was refused. */
enum class CustomizationRegistrationReason {
    /** The registration name is empty. */
    EMPTY_NAME,

    /** The same exact name was already registered. */
    DUPLICATE_NAME,
}

/** An explicit customization registry contains an empty or duplicate name. */
class CustomizationRegistrationError private constructor(
    val customizationKind: CustomizationKind,
    val reason: CustomizationRegistrationReason,
    val name: String,
) : IllegalArgumentException("$customizationKind registration '$name' failed: $reason") {
    val code: String = "A12_CUSTOMIZATION_REGISTRATION"

    companion object {
        @JvmSynthetic
        internal fun of(
            customizationKind: CustomizationKind,
            reason: CustomizationRegistrationReason,
            name: String,
        ): CustomizationRegistrationError = CustomizationRegistrationError(customizationKind, reason, name)
    }
}

/** Immutable explicit or factory-backed custom-field resolver bound once to an [Interpreter]. */
class CustomFieldTypeRegistry private constructor(
    private val factory: CustomFieldTypeFactory,
) {
    @JvmSynthetic
    internal fun engineMap(): Map<String, EngineCustomFieldType> =
        ResolvingMap { name ->
            factory.create(name)?.let { validator ->
                EngineCustomFieldType { value, context ->
                    val result = try {
                        validator.validate(
                            value,
                            CustomFieldValidationParameters(
                                locale = context.locale,
                                minLength = context.minLength,
                                maxLength = context.maxLength,
                                isDisplayValue = context.isDisplayValue,
                            ),
                        )
                    } catch (error: InterpreterIntegrationError) {
                        throw error
                    } catch (error: Throwable) {
                        throw CallbackFailure(error)
                    }
                    when (result) {
                        CustomFieldValidationResult.Accepted -> null
                        is CustomFieldValidationResult.Rejected ->
                            CustomTypeRejection(result.errorKey, result.message)
                    }
                }
            }
        }

    companion object {
        @JvmStatic
        fun empty(): CustomFieldTypeRegistry = CustomFieldTypeRegistry(CustomFieldTypeFactory { null })

        @JvmStatic
        fun builder(): Builder = Builder()

        @JvmStatic
        fun fromFactory(factory: CustomFieldTypeFactory): CustomFieldTypeRegistry =
            CustomFieldTypeRegistry(factory)
    }

    class Builder internal constructor() {
        private val validators = linkedMapOf<String, CustomFieldValidator>()

        fun register(name: String, validator: CustomFieldValidator): Builder = apply {
            register(validators, CustomizationKind.CUSTOM_FIELD_TYPE, name, validator)
        }

        fun build(): CustomFieldTypeRegistry {
            val frozen = validators.toMap()
            return CustomFieldTypeRegistry(CustomFieldTypeFactory(frozen::get))
        }
    }
}

/** Immutable explicit or factory-backed custom-condition resolver bound once to an [Interpreter]. */
class CustomConditionRegistry private constructor(
    private val factory: CustomConditionFactory,
) {
    @JvmSynthetic
    internal fun engineMap(
        adapt: (CustomCondition) -> EngineCustomCondition,
    ): Map<String, EngineCustomCondition> =
        ResolvingMap { name -> factory.create(name)?.let(adapt) }

    companion object {
        @JvmStatic
        fun empty(): CustomConditionRegistry = CustomConditionRegistry(CustomConditionFactory { null })

        @JvmStatic
        fun builder(): Builder = Builder()

        @JvmStatic
        fun fromFactory(factory: CustomConditionFactory): CustomConditionRegistry =
            CustomConditionRegistry(factory)
    }

    class Builder internal constructor() {
        private val conditions = linkedMapOf<String, CustomCondition>()

        fun register(name: String, condition: CustomCondition): Builder = apply {
            register(conditions, CustomizationKind.CUSTOM_CONDITION, name, condition)
        }

        fun build(): CustomConditionRegistry {
            val frozen = conditions.toMap()
            return CustomConditionRegistry(CustomConditionFactory(frozen::get))
        }
    }
}

/** Customization inputs used to create one active [Interpreter]. */
class InterpreterConfiguration private constructor(
    internal val customFieldTypes: CustomFieldTypeRegistry,
    internal val customConditions: CustomConditionRegistry,
) {
    companion object {
        @JvmStatic
        fun defaults(): InterpreterConfiguration = Builder().build()

        @JvmStatic
        fun builder(): Builder = Builder()
    }

    class Builder internal constructor() {
        private var customFieldTypes = CustomFieldTypeRegistry.empty()
        private var customConditions = CustomConditionRegistry.empty()

        fun customFieldTypes(value: CustomFieldTypeRegistry) = apply { customFieldTypes = value }
        fun customConditions(value: CustomConditionRegistry) = apply { customConditions = value }

        fun build(): InterpreterConfiguration =
            InterpreterConfiguration(customFieldTypes, customConditions)
    }
}

/** Lifecycle phase in which a host factory or callback failed. */
enum class InterpreterIntegrationPhase {
    /** Customizations are being resolved and support-audited. */
    BIND,

    /** A document is being validated or computed. */
    EVALUATE,
}

/** Stable classification of a host integration failure. */
enum class InterpreterIntegrationReason {
    /** A customization factory threw while the active interpreter was bound. */
    FACTORY_THREW,

    /** A bound custom validator or condition threw during evaluation. */
    CALLBACK_THREW,

    /** A callback attempted to enter the same active interpreter recursively. */
    REENTRANT_EVALUATION,
}

/** Typed boundary error for host factory/callback failures and same-lane reentry. */
class InterpreterIntegrationError private constructor(
    val phase: InterpreterIntegrationPhase,
    val reason: InterpreterIntegrationReason,
    cause: Throwable?,
) : RuntimeException("interpreter integration failed during ${phase.name.lowercase()}: ${reason.name.lowercase()}", cause) {
    val code: String = "A12_INTERPRETER_INTEGRATION_FAILED"

    companion object {
        @JvmSynthetic
        internal fun of(
            phase: InterpreterIntegrationPhase,
            reason: InterpreterIntegrationReason,
            cause: Throwable?,
        ): InterpreterIntegrationError = InterpreterIntegrationError(phase, reason, cause)
    }
}

/**
 * One owner-bound active evaluator. It reuses one prepared engine, accepts only snapshots/pointers from its
 * [PreparedModel], and returns detached support, validation, and computation records.
 */
class Interpreter private constructor(
    private val prepared: PreparedModel,
    configuration: InterpreterConfiguration,
) : ModelOwned by prepared {
    private val metadata = prepared.model
    private var activeSnapshot: DocumentSnapshot? = null
    private var evaluating = false
    private val engine: DmInterpreter
    private val support: InterpreterSupportReport

    init {
        try {
            engine = DmInterpreter(
                model = prepared.definition(),
                customConditions = configuration.customConditions.engineMap(::adaptCondition),
                customFieldTypes = configuration.customFieldTypes.engineMap(),
            )
            support = InterpreterSupportReport.create(
                engine.supportAudit().map { UnsupportedProgram(it.rulePath, it.reason) },
            )
        } catch (error: Throwable) {
            throw InterpreterIntegrationError.of(
                phase = InterpreterIntegrationPhase.BIND,
                reason = InterpreterIntegrationReason.FACTORY_THREW,
                cause = error,
            )
        }
    }

    fun supportReport(): InterpreterSupportReport = support

    @JvmOverloads
    fun validateFull(snapshot: DocumentSnapshot, locale: String = "en_US"): ValidationReport =
        evaluate(snapshot) {
            validationReport(engine.validate(snapshot.document(), locale))
        }

    @JvmOverloads
    fun validatePart(
        snapshot: DocumentSnapshot,
        relevant: List<RelevantEntityPointer>,
        locale: String = "en_US",
    ): ValidationReport =
        evaluate(snapshot) {
            val entities = relevant.mapTo(linkedSetOf(), ::relevantEntity)
            validationReport(engine.validatePart(snapshot.document(), entities, locale))
        }

    @JvmOverloads
    fun compute(snapshot: DocumentSnapshot, locale: String = "en_US"): ComputationReport =
        evaluate(snapshot) {
            try {
                val results = engine.compute(snapshot.document(), locale).map { computed ->
                    ComputationResult(
                        pointer = metadata.pointer(computed.path, computed.indices) as FieldPointer,
                        outcome = when (computed.outcome) {
                            EngineComputeOutcome.VALUE -> ComputationOutcome.VALUE
                            EngineComputeOutcome.CLEARED -> ComputationOutcome.CLEARED
                            EngineComputeOutcome.ERRORED -> ComputationOutcome.ERRORED
                        },
                        value = computed.value,
                    )
                }
                ComputationReport.create(results, emptyList())
            } catch (error: UnsupportedConstructException) {
                ComputationReport.create(
                    results = emptyList(),
                    unsupported = listOf(UnsupportedProgram("<compute>", error.message.orEmpty())),
                )
            }
        }

    private fun validationReport(outcome: ValidationOutcome): ValidationReport =
        ValidationReport.create(
            findings = outcome.messages.map(::validationFinding),
            unsupported = outcome.unsupported.map { UnsupportedProgram(it.rulePath, it.reason) },
        )

    private fun validationFinding(message: EngineMessage): ValidationFinding =
        ValidationFinding(
            code = message.code,
            rulePath = message.rulePath,
            errorPath = message.errorPath,
            severity = when (message.severity) {
                Severity.ERROR -> ValidationSeverity.ERROR
                Severity.WARNING -> ValidationSeverity.WARNING
                Severity.INFO -> ValidationSeverity.INFO
            },
            type = when (message.type) {
                MsgType.VALUE -> ValidationFindingType.VALUE
                MsgType.OMISSION -> ValidationFindingType.OMISSION
            },
            text = message.text,
            referenced = immutableListSnapshot(message.referenced),
            fillToFix = immutableListSnapshot(message.fillToFix),
        )

    private fun relevantEntity(pointer: RelevantEntityPointer): RelevantEntity {
        metadata.requireOwned(pointer)
        val coordinates = when (pointer) {
            is DocumentPointer -> pointer.coordinates
            is RelevantSelectionPointer -> pointer.coordinates.map { coordinate ->
                when (coordinate) {
                    is RelevantCoordinate.Exact -> coordinate.value
                    RelevantCoordinate.All -> RelevantEntity.ALL
                }
            }
        }
        return RelevantEntity(pointer.path, coordinates)
    }

    private fun adaptCondition(condition: CustomCondition): EngineCustomCondition =
        EngineCustomCondition { data, relevant, formallyIncorrect, errorField ->
            val snapshot = checkNotNull(activeSnapshot) {
                "custom condition invoked outside an evaluation transaction"
            }
            val invocation = CustomConditionInvocation.create(
                document = CustomConditionDocumentView.create(snapshot, metadata, data),
                relevantEntities = relevant?.map(metadata::relevantPointer),
                formallyIncorrectEntities = formallyIncorrect.map(metadata::fieldPointer),
                errorPointer = metadata.fieldPointer(errorField),
            )
            try {
                condition.check(invocation)
            } catch (error: InterpreterIntegrationError) {
                throw error
            } catch (error: Throwable) {
                throw CallbackFailure(error)
            }
        }

    private inline fun <T> evaluate(snapshot: DocumentSnapshot, action: () -> T): T {
        metadata.requireOwned(snapshot)
        if (evaluating) {
            throw InterpreterIntegrationError.of(
                phase = InterpreterIntegrationPhase.EVALUATE,
                reason = InterpreterIntegrationReason.REENTRANT_EVALUATION,
                cause = null,
            )
        }
        evaluating = true
        activeSnapshot = snapshot
        return try {
            action()
        } catch (error: CallbackFailure) {
            throw InterpreterIntegrationError.of(
                phase = InterpreterIntegrationPhase.EVALUATE,
                reason = InterpreterIntegrationReason.CALLBACK_THREW,
                cause = error.cause,
            )
        } finally {
            activeSnapshot = null
            evaluating = false
        }
    }

    companion object {
        @JvmSynthetic
        internal fun create(
            prepared: PreparedModel,
            configuration: InterpreterConfiguration,
        ): Interpreter = Interpreter(prepared, configuration)
    }
}

private class CallbackFailure(cause: Throwable) : RuntimeException(cause)

private class ResolvingMap<V>(
    private val resolver: (String) -> V?,
) : Map<String, V> {
    private val resolved = linkedMapOf<String, V>()
    private val missing = mutableSetOf<String>()

    override val entries: Set<Map.Entry<String, V>> get() = resolved.entries
    override val keys: Set<String> get() = resolved.keys
    override val size: Int get() = resolved.size
    override val values: Collection<V> get() = resolved.values

    override fun containsKey(key: String): Boolean = resolve(key) != null
    override fun containsValue(value: V): Boolean = resolved.containsValue(value)
    override fun get(key: String): V? = resolve(key)
    override fun isEmpty(): Boolean = resolved.isEmpty()

    private fun resolve(name: String): V? {
        resolved[name]?.let { return it }
        if (name in missing) return null
        return resolver(name)?.also { resolved[name] = it } ?: run {
            missing += name
            null
        }
    }
}

private fun <V> register(
    entries: MutableMap<String, V>,
    kind: CustomizationKind,
    name: String,
    value: V,
) {
    if (name.isEmpty()) {
        throw CustomizationRegistrationError.of(kind, CustomizationRegistrationReason.EMPTY_NAME, name)
    }
    if (name in entries) {
        throw CustomizationRegistrationError.of(kind, CustomizationRegistrationReason.DUPLICATE_NAME, name)
    }
    entries[name] = value
}
