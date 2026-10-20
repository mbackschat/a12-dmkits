package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome as EngineComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.eval.ComputationApplication
import io.github.mbackschat.a12.dm.interpreter.model.CustomCondition as EngineCustomCondition
import io.github.mbackschat.a12.dm.interpreter.model.CustomConditionData as EngineCustomConditionData
import io.github.mbackschat.a12.dm.interpreter.model.CustomConditionFieldPointer
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldType as EngineCustomFieldType
import io.github.mbackschat.a12.dm.interpreter.model.EvalClock
import io.github.mbackschat.a12.dm.interpreter.model.CustomTypeRejection
import io.github.mbackschat.a12.dm.interpreter.model.Message as EngineMessage
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.interpreter.model.Severity
import io.github.mbackschat.a12.dm.interpreter.model.LegalCharset
import io.github.mbackschat.a12.dm.interpreter.model.MessageLabelProvider
import io.github.mbackschat.a12.dm.interpreter.model.PredefinedTypeRegistry
import io.github.mbackschat.a12.dm.interpreter.model.UnsupportedRule as EngineUnsupportedRule
import io.github.mbackschat.a12.dm.interpreter.model.UnsupportedSubject as EngineUnsupportedSubject
import io.github.mbackschat.a12.dm.interpreter.model.ValidationOutcome
import io.github.mbackschat.a12.dm.interpreter.workspace.immutableListSnapshot
import io.github.mbackschat.a12.dm.interpreter.workspace.isImmutableProjection
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmSynthetic
import kotlin.concurrent.Volatile

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

    /** Create an exact group-row or field-cell pointer after path, arity, and coordinate validation. */
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

    /** Create a partial-validation pointer whose coordinates may select exact or all repetitions. */
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

    private fun entityKind(path: String): ModelEntityKind =
        ModelAddressValidation.entityKind(prepared.definition(), path) ?: throw UnknownModelPathError.of(path)

    private fun requireCoordinates(path: String, kind: ModelEntityKind, coordinates: List<Int>) {
        ModelAddressValidation.violations(kind, path, coordinates)
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

/**
 * Detached, kernel-free projection of one validation finding.
 *
 * <b>Its three addresses are A12's message pointer.</b> `IMessage` types `getErrorFieldPointer()`,
 * `getReferencedFieldsPointers()` and `getRefOmissionErrorResponsiblePointers()` as
 * `PartiallyKnownDocumentMultiPointer`, so `errorPath`, `referenced` and `fillToFix` carry
 * [A12PartiallyKnownMultiPointer] — the value the engine retains, not a string it was rendered into. The
 * distinction is observable: an unresolved level ([A12PartiallyKnownMultiPointer.UNKNOWN]) and a terminal
 * wildcard are exactly what a rendered path drops, so a consumer that needs them can only get them from here.
 *
 * Rendering is the consumer's call: [A12PartiallyKnownMultiPointer.toExactPointer] plus
 * [A12PointerFormat.render] gives A12's canonical pointer string for an address that names one cell, and
 * `null` for one that does not.
 */
data class ValidationFinding(
    val code: String,
    val rulePath: String,
    val errorPath: A12PartiallyKnownMultiPointer,
    val severity: ValidationSeverity,
    val type: ValidationFindingType,
    val text: String,
    val referenced: List<A12PartiallyKnownMultiPointer>,
    val fillToFix: List<A12PartiallyKnownMultiPointer>,
)

/** What kind of address an [UnsupportedProgram.path] carries — the two are not interchangeable. */
enum class UnsupportedProgramSubject {
    /** A model ELEMENT path: a rule, a computation, or a field declaration. No repetition, no instance. */
    ELEMENT,

    /** A document CELL: an instance pointer carrying repetitions, e.g. an unregistered custom-typed value. */
    CELL,
}

/**
 * One construct the configured interpreter cannot evaluate — a stored rule, an implicit rule, a computation, a
 * field declaration whose custom type is unregistered, or a single document cell.
 *
 * <p>[subject] says which of those [path] addresses, and it is load-bearing rather than descriptive: an
 * [UnsupportedProgramSubject.ELEMENT] path is a model address a consumer can look up in the model, while a
 * [UnsupportedProgramSubject.CELL] path is an instance pointer into the document being evaluated. Both kinds
 * arrive in the same list — a support audit reports only elements, but validation and computation report both —
 * and before this field a consumer had no way to tell them apart, so it could render neither correctly.
 */
data class UnsupportedProgram(
    val path: String,
    val reason: String,
    val subject: UnsupportedProgramSubject,
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
    /** Target-owned formal diagnostic; present exactly when [ComputationResult.outcome] is [ComputationOutcome.ERRORED]. */
    val diagnostic: ValidationFinding? = null,
)

/**
 * Immutable retained result for one source computation. `results` remains the report-all observation; `actions`
 * is its source-relative application subset. Applying to another snapshot never recomputes against that
 * destination.
 *
 * Compatibility is the interpreter's existing model-ownership safety contract: [applyTo] accepts only a
 * snapshot from the same [PreparedModel] owner. The Kernel treats model compatibility as a caller precondition;
 * this explicit refusal is a stronger library boundary, not a claimed Kernel rejection.
 */
class ComputationReport private constructor(
    results: List<ComputationResult>,
    actions: List<ComputationResult>,
    formalErrorsInOperands: List<ValidationFinding>,
    unsupported: List<UnsupportedProgram>,
    override val owner: ModelOwnerIdentity,
    override val modelVersion: ModelVersionIdentity,
) : ModelOwned {
    val results: List<ComputationResult> = immutableListSnapshot(results)
    val actions: List<ComputationResult> = immutableListSnapshot(actions)
    val formalErrorsInOperands: List<ValidationFinding> = immutableListSnapshot(formalErrorsInOperands)
    val unsupported: List<UnsupportedProgram> = immutableListSnapshot(unsupported)
    val noErrorOccurred: Boolean
        get() = results.none { it.outcome == ComputationOutcome.ERRORED } && formalErrorsInOperands.isEmpty()
    val fullySupported: Boolean get() = unsupported.isEmpty()

    /**
     * Apply the actions already classified against the computation source to [destination]. VALUE and CLEARED
     * materialize a missing addressed target and ancestry; ERRORED clears only an existing destination target.
     * Unsupported computation is atomic: with no complete action set, the destination snapshot is returned
     * unchanged.
     *
     * @throws ModelOwnerMismatchError when [destination] belongs to another prepared owner.
     */
    fun applyTo(destination: DocumentSnapshot): DocumentSnapshot {
        if (destination.owner !== owner) {
            throw ModelOwnerMismatchError.of(expected = owner, actual = destination.owner)
        }
        if (!fullySupported || actions.isEmpty()) return destination
        val applications = actions.map { action ->
            ComputationApplication(
                path = action.pointer.path,
                reps = action.pointer.coordinates,
                outcome = when (action.outcome) {
                    ComputationOutcome.VALUE -> EngineComputeOutcome.VALUE
                    ComputationOutcome.CLEARED -> EngineComputeOutcome.CLEARED
                    ComputationOutcome.ERRORED -> EngineComputeOutcome.ERRORED
                },
                value = action.value,
            )
        }
        return DocumentSnapshot.of(
            owner = owner,
            modelVersion = modelVersion,
            documentId = destination.documentId,
            document = destination.document().withComputationApplications(applications),
        )
    }

    companion object {
        @JvmSynthetic
        internal fun create(
            results: List<ComputationResult>,
            actions: List<ComputationResult>,
            formalErrorsInOperands: List<ValidationFinding>,
            unsupported: List<UnsupportedProgram>,
            owner: ModelOwnerIdentity,
            modelVersion: ModelVersionIdentity,
        ): ComputationReport =
            ComputationReport(results, actions, formalErrorsInOperands, unsupported, owner, modelVersion)
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
    internal val evaluationInstant: Long?,
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
        private var evaluationInstant: Long? = null

        fun customFieldTypes(value: CustomFieldTypeRegistry) = apply { customFieldTypes = value }
        fun customConditions(value: CustomConditionRegistry) = apply { customConditions = value }
        /**
         * Pin the instant `Today` and `Now` read, as A12's `currentDateForTest` does; it is read as wall time in the
         * model's zone. Unset, every validation or computation call reads the current instant, as A12 does.
         */
        fun evaluationInstant(epochMillis: Long) = apply { evaluationInstant = epochMillis }

        fun build(): InterpreterConfiguration =
            InterpreterConfiguration(customFieldTypes, customConditions, evaluationInstant)
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

    /**
     * A second execution lane entered the same interpreter while one was already evaluating. An [Interpreter] is
     * per request: a `PreparedModel` is what may be shared. Detection is best-effort — this refuses the misuse
     * loudly instead of letting a custom condition observe another lane's Document, and does not make the
     * interpreter thread-safe.
     */
    CONCURRENT_USE,
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
    @Volatile
    private var activeSnapshot: DocumentSnapshot? = null

    /** The lane currently inside [evaluate], or [NO_LANE]. Volatile so a second lane can observe the first. */
    @Volatile
    private var evaluatingLane: Long = NO_LANE
    private val engine: DmInterpreter
    private val support: InterpreterSupportReport

    init {
        try {
            val definition = prepared.definition()
            val zone = definition.timeZone
            val pinned = configuration.evaluationInstant?.let { EvalClock.at(it, zone) }
            engine = DmInterpreter.sharing(
                model = definition,
                clock = if (pinned != null) ({ pinned }) else ({ EvalClock.at(currentEpochMillis(), zone) }),
                predefinedTypes = PredefinedTypeRegistry.DEFAULT,
                customConditions = configuration.customConditions.engineMap(::adaptCondition),
                customFieldTypes = configuration.customFieldTypes.engineMap(),
                legalCharset = LegalCharset.BMP,
                messageLabelProvider = MessageLabelProvider.MODEL_LABEL_OR_NAME,
                // The sharing boundary: every interpreter over this prepared model reads ONE model-static
                // preparation, so a per-request interpreter costs its configuration binding and audit, not a
                // rebuild of every AST and plan (NFR-F3).
                plans = prepared.plans(),
            )
            support = InterpreterSupportReport.create(
                engine.supportAudit().map(::unsupportedProgram),
            )
        } catch (error: Throwable) {
            throw InterpreterIntegrationError.of(
                phase = InterpreterIntegrationPhase.BIND,
                reason = InterpreterIntegrationReason.FACTORY_THREW,
                cause = error,
            )
        }
    }

    /** Report unsupported rules and computations once, independently of any Document. */
    fun supportReport(): InterpreterSupportReport = support

    /** Validate every applicable rule against one owned Document snapshot. */
    @JvmOverloads
    fun validateFull(snapshot: DocumentSnapshot, locale: String = "en_US"): ValidationReport =
        evaluate(snapshot) {
            validationReport(engine.validate(snapshot.document(), locale))
        }

    /** Validate only rules relevant to the supplied exact or all-repetitions model pointers. */
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

    /** Evaluate every model computation against one owned Document without mutating the snapshot. */
    @JvmOverloads
    fun compute(snapshot: DocumentSnapshot, locale: String = "en_US"): ComputationReport =
        evaluate(snapshot) {
            try {
                val retained = engine.retainedComputations(snapshot.document(), locale)
                val results = retained.results.map { computed ->
                    ComputationResult(
                        pointer = metadata.pointer(computed.path, computed.indices) as FieldPointer,
                        outcome = when (computed.outcome) {
                            EngineComputeOutcome.VALUE -> ComputationOutcome.VALUE
                            EngineComputeOutcome.CLEARED -> ComputationOutcome.CLEARED
                            EngineComputeOutcome.ERRORED -> ComputationOutcome.ERRORED
                        },
                        value = computed.value,
                        diagnostic = retained.computedErrors[computed.path to computed.indices]
                            ?.let(::validationFinding),
                    )
                }
                val byCell = results.associateBy { it.pointer.path to it.pointer.coordinates }
                val actions = retained.applications.map { action ->
                    checkNotNull(byCell[action.path to action.reps]) {
                        "retained computation action has no report-all result at ${action.path}"
                    }
                }
                ComputationReport.create(
                    results = results,
                    actions = actions,
                    formalErrorsInOperands = retained.formalErrorsInOperands.map(::validationFinding),
                    unsupported = retained.unsupported.map(::unsupportedProgram),
                    owner = owner,
                    modelVersion = modelVersion,
                )
            } catch (error: UnsupportedConstructException) {
                ComputationReport.create(
                    results = emptyList(),
                    actions = emptyList(),
                    formalErrorsInOperands = emptyList(),
                    unsupported = listOf(
                        // A whole-compute failure names the operation, not a document cell.
                        UnsupportedProgram("<compute>", error.message.orEmpty(),
                            UnsupportedProgramSubject.ELEMENT),
                    ),
                    owner = owner,
                    modelVersion = modelVersion,
                )
            }
        }

    /** Same-document compute-and-apply convenience, defined as one retained [compute] followed by [ComputationReport.applyTo]. */
    @JvmOverloads
    fun applyComputations(snapshot: DocumentSnapshot, locale: String = "en_US"): DocumentSnapshot =
        compute(snapshot, locale).applyTo(snapshot)

    private fun validationReport(outcome: ValidationOutcome): ValidationReport =
        ValidationReport.create(
            findings = outcome.messages.map(::validationFinding),
            unsupported = outcome.unsupported.map(::unsupportedProgram),
        )

    /** The one engine → facade projection for an unsupported entry, so the subject cannot be dropped at one site. */
    private fun unsupportedProgram(rule: EngineUnsupportedRule): UnsupportedProgram =
        UnsupportedProgram(
            path = rule.rulePath,
            reason = rule.reason,
            subject = when (rule.subject) {
                EngineUnsupportedSubject.ELEMENT -> UnsupportedProgramSubject.ELEMENT
                EngineUnsupportedSubject.CELL -> UnsupportedProgramSubject.CELL
            },
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
        val lane = currentExecutionLane()
        val occupant = evaluatingLane
        if (occupant != NO_LANE) {
            throw InterpreterIntegrationError.of(
                phase = InterpreterIntegrationPhase.EVALUATE,
                reason = if (occupant == lane) {
                    InterpreterIntegrationReason.REENTRANT_EVALUATION
                } else {
                    InterpreterIntegrationReason.CONCURRENT_USE
                },
                cause = null,
            )
        }
        evaluatingLane = lane
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
            evaluatingLane = NO_LANE
        }
    }

    companion object {
        private const val NO_LANE = -1L

        @JvmSynthetic
        internal fun create(
            prepared: PreparedModel,
            configuration: InterpreterConfiguration,
        ): Interpreter = Interpreter(prepared, configuration)
    }
}

/**
 * Publish a pointer list across the public boundary — **without copying one that is already immutable**.
 *
 * The engine hands every message of a duplicate cluster the *same* pointer-list instance rather than a private
 * copy per message (IF191). Snapshotting defensively per message therefore copies one N-element list N times:
 * quadratic in both time and memory, and it cancels that optimization at exactly the boundary a library consumer
 * calls. Measured, the façade exhausted the default test heap on 8,000 all-duplicate rows — a document the
 * engine itself handles.
 *
 * The first fix cached the last snapshot per channel, which held for ONE cluster and missed on every message of
 * two interleaved ones — the copy, and the O(N²), came straight back (IF261). Since nothing bounds how many
 * duplicate clusters a document may interleave, no fixed number of slots is defensible. So the engine marks every list it
 * builds immutably (`ImmutableProjection`) and this boundary recognizes the marker: no copy, no cache, no scale
 * at which it degrades. A list from anywhere else is still copied, so the published list is immutable either way.
 */
private fun publishedPointers(values: List<A12PartiallyKnownMultiPointer>): List<A12PartiallyKnownMultiPointer> =
    when {
        values.isEmpty() -> emptyList()
        isImmutableProjection(values) -> values
        else -> immutableListSnapshot(values)
    }

/** The single engine-to-public message projection; internal so common tests can lock the channel boundary. */
internal fun validationFinding(message: EngineMessage): ValidationFinding =
    ValidationFinding(
        code = message.code,
        rulePath = message.rulePath,
        errorPath = message.errorPointer,
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
        referenced = publishedPointers(message.referencedPointers),
        fillToFix = publishedPointers(message.fillToFixPointers),
    )

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
