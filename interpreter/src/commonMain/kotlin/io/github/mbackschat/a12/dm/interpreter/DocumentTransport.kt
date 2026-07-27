package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.workspace.immutableListSnapshot
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmSynthetic

/** One explicit group row of the V1 Document input: one positive 1-based index per path part. */
data class GroupPlacementInput(
    val path: String,
    val coordinates: List<Int>,
)

/**
 * One raw field cell of the V1 Document input. `raw` preserves exact lexical text: `""` is a present-empty
 * cell (deliberately different from omitting the placement), and malformed text stays present so formal
 * validation can report it. The terminal coordinate names the field rather than a repetition axis and must be
 * 1; Kernel document conversion refuses any other value before validation.
 */
data class FieldPlacementInput(
    val path: String,
    val coordinates: List<Int>,
    val raw: String,
)

/**
 * The versioned, behavior-free Document transport. Omission means absent; explicit group placements preserve
 * empty and sparse rows; coordinates carry one positive 1-based index per path part. Group coordinates above an
 * effective repetition maximum — a non-repeatable group's coordinate 2 included — remain representable and are
 * reported by formal validation. A field placement's terminal coordinate must be 1 because the Kernel document
 * boundary cannot represent a repeated field.
 */
data class DocumentInputV1(
    val groups: List<GroupPlacementInput>,
    val fields: List<FieldPlacementInput>,
    val version: Int = SUPPORTED_VERSION,
) {
    companion object {
        const val SUPPORTED_VERSION = 1
    }
}

/**
 * Resource bounds for Document decoding. Caller values are capped by the hard implementation maxima so an API
 * consumer cannot accidentally disable the browser-safe denial-of-service guards.
 */
class DocumentDecodeLimits private constructor(
    val maxGroupPlacements: Int,
    val maxFieldPlacements: Int,
    val maxCoordinatesPerPlacement: Int,
    val maxPathCodeUnits: Int,
    val maxRawCodeUnitsPerField: Int,
    val maxTotalRawCodeUnits: Int,
    val maxModelNestingDepth: Int,
) {
    companion object {
        const val DEFAULT_MAX_GROUP_PLACEMENTS = 10_000
        const val DEFAULT_MAX_FIELD_PLACEMENTS = 100_000
        const val DEFAULT_MAX_COORDINATES_PER_PLACEMENT = 16
        const val DEFAULT_MAX_PATH_CODE_UNITS = 1_024
        const val DEFAULT_MAX_RAW_CODE_UNITS_PER_FIELD = 1_000_000
        const val DEFAULT_MAX_TOTAL_RAW_CODE_UNITS = 16_000_000
        const val DEFAULT_MAX_MODEL_NESTING_DEPTH = 32

        const val HARD_MAX_GROUP_PLACEMENTS = 100_000
        const val HARD_MAX_FIELD_PLACEMENTS = 1_000_000
        const val HARD_MAX_COORDINATES_PER_PLACEMENT = 64
        const val HARD_MAX_PATH_CODE_UNITS = 4_096
        const val HARD_MAX_RAW_CODE_UNITS_PER_FIELD = 16_000_000
        const val HARD_MAX_TOTAL_RAW_CODE_UNITS = 64_000_000
        const val HARD_MAX_MODEL_NESTING_DEPTH = 128

        @JvmStatic
        fun defaults(): DocumentDecodeLimits = Builder().build()

        @JvmStatic
        fun builder(): Builder = Builder()
    }

    class Builder internal constructor() {
        private var maxGroupPlacements = DEFAULT_MAX_GROUP_PLACEMENTS
        private var maxFieldPlacements = DEFAULT_MAX_FIELD_PLACEMENTS
        private var maxCoordinatesPerPlacement = DEFAULT_MAX_COORDINATES_PER_PLACEMENT
        private var maxPathCodeUnits = DEFAULT_MAX_PATH_CODE_UNITS
        private var maxRawCodeUnitsPerField = DEFAULT_MAX_RAW_CODE_UNITS_PER_FIELD
        private var maxTotalRawCodeUnits = DEFAULT_MAX_TOTAL_RAW_CODE_UNITS
        private var maxModelNestingDepth = DEFAULT_MAX_MODEL_NESTING_DEPTH

        fun maxGroupPlacements(value: Int) = apply { maxGroupPlacements = positive("maxGroupPlacements", value) }
        fun maxFieldPlacements(value: Int) = apply { maxFieldPlacements = positive("maxFieldPlacements", value) }
        fun maxCoordinatesPerPlacement(value: Int) =
            apply { maxCoordinatesPerPlacement = positive("maxCoordinatesPerPlacement", value) }
        fun maxPathCodeUnits(value: Int) = apply { maxPathCodeUnits = positive("maxPathCodeUnits", value) }
        fun maxRawCodeUnitsPerField(value: Int) =
            apply { maxRawCodeUnitsPerField = positive("maxRawCodeUnitsPerField", value) }
        fun maxTotalRawCodeUnits(value: Int) =
            apply { maxTotalRawCodeUnits = positive("maxTotalRawCodeUnits", value) }
        fun maxModelNestingDepth(value: Int) =
            apply { maxModelNestingDepth = positive("maxModelNestingDepth", value) }

        fun build(): DocumentDecodeLimits = DocumentDecodeLimits(
            maxGroupPlacements = minOf(maxGroupPlacements, HARD_MAX_GROUP_PLACEMENTS),
            maxFieldPlacements = minOf(maxFieldPlacements, HARD_MAX_FIELD_PLACEMENTS),
            maxCoordinatesPerPlacement = minOf(maxCoordinatesPerPlacement, HARD_MAX_COORDINATES_PER_PLACEMENT),
            maxPathCodeUnits = minOf(maxPathCodeUnits, HARD_MAX_PATH_CODE_UNITS),
            maxRawCodeUnitsPerField = minOf(maxRawCodeUnitsPerField, HARD_MAX_RAW_CODE_UNITS_PER_FIELD),
            maxTotalRawCodeUnits = minOf(maxTotalRawCodeUnits, HARD_MAX_TOTAL_RAW_CODE_UNITS),
            maxModelNestingDepth = minOf(maxModelNestingDepth, HARD_MAX_MODEL_NESTING_DEPTH),
        )

        private fun positive(name: String, value: Int): Int {
            require(value > 0) { "$name must be positive" }
            return value
        }
    }
}

enum class DocumentPlacementKind {
    /** An entry of the envelope's `groups` array. */
    GROUP,

    /** An entry of the envelope's `fields` array. */
    FIELD,
}

enum class DocumentInputReasonCode {
    /** The envelope's `version` is not the supported placement-transport version. */
    UNSUPPORTED_VERSION,

    /** A placement path names no declared field or group of the prepared model. */
    UNKNOWN_MODEL_PATH,

    /** A placement does not carry exactly one coordinate per path part. */
    COORDINATE_COUNT_MISMATCH,

    /** A coordinate is not a positive 1-based repetition index. */
    INVALID_COORDINATE,

    /**
     * A field placement's terminal coordinate is positive but not 1. The Kernel's document conversion refuses
     * this class before either evaluation strategy can run; fields have no repeatability declaration.
     */
    INVALID_FIELD_REPETITION,

    /** Decoding exceeded one configured resource ceiling. */
    RESOURCE_LIMIT_EXCEEDED,

    /** The same placement identity appears twice with the same content. */
    DUPLICATE_PLACEMENT,

    /** The same field-cell identity appears twice with different raw values. */
    CONFLICTING_PLACEMENT,
}

enum class DocumentInputResource {
    /** Entries of the envelope's `groups` array. */
    GROUP_PLACEMENTS,

    /** Entries of the envelope's `fields` array. */
    FIELD_PLACEMENTS,

    /** Coordinates carried by one placement. */
    COORDINATES_PER_PLACEMENT,

    /** UTF-16 code units of one placement path. */
    PATH_CODE_UNITS,

    /** UTF-16 code units of one field's raw value. */
    RAW_CODE_UNITS_PER_FIELD,

    /** UTF-16 code units across every field's raw value. */
    TOTAL_RAW_CODE_UNITS,

    /** Model path parts of one placement. */
    MODEL_NESTING_DEPTH,
}

/**
 * One structured placement-transport failure. Only properties meaningful for [reasonCode] are populated;
 * [message] is presentation text and is never the sole carrier of corrective data. [placementIndex] and
 * [firstPlacementIndex] are zero-based into the envelope array named by [placementKind].
 */
interface DocumentInputDiagnostic {
    val reasonCode: DocumentInputReasonCode
    val placementKind: DocumentPlacementKind? get() = null
    val placementIndex: Int? get() = null
    val coordinateIndex: Int? get() = null
    val modelPath: String? get() = null
    val expectedVersion: Int? get() = null
    val actualVersion: Int? get() = null
    val expectedCoordinateCount: Int? get() = null
    val actualCoordinateCount: Int? get() = null
    val resource: DocumentInputResource? get() = null
    val maximum: Long? get() = null
    val actual: Long? get() = null
    val firstPlacementIndex: Int? get() = null
    val message: String
}

/** Aggregate placement-transport failure; thrown before any document state is constructed. */
class DocumentInputError private constructor(
    diagnostics: List<DocumentInputDiagnostic>,
) : RuntimeException(diagnostics.joinToString("; ") { it.message }) {
    val code: String = "A12_DOCUMENT_INPUT_INVALID"
    val diagnostics: List<DocumentInputDiagnostic> = immutableListSnapshot(diagnostics)

    companion object {
        @JvmSynthetic
        internal fun from(diagnostics: List<DocumentInputDiagnostic>): DocumentInputError =
            DocumentInputError(diagnostics)
    }
}

/**
 * A model-owned runtime object reached an API belonging to a different prepared owner domain — a foreign model,
 * or a stale snapshot of an earlier preparation of the same sources. `expected` and `actual` are opaque
 * correlation identities, never model content.
 */
class ModelOwnerMismatchError private constructor(
    val expected: ModelOwnerIdentity,
    val actual: ModelOwnerIdentity,
) : RuntimeException("the supplied model-owned object belongs to a different prepared model owner") {
    val code: String = "A12_MODEL_OWNER_MISMATCH"

    companion object {
        @JvmSynthetic
        internal fun of(expected: ModelOwnerIdentity, actual: ModelOwnerIdentity): ModelOwnerMismatchError =
            ModelOwnerMismatchError(expected, actual)
    }
}

/**
 * One frozen, model-owned document instance. It exposes no storage mutation or evaluation surface; the encode
 * verb on its owning [PreparedModel.documents] is the lossless read-back.
 */
class DocumentSnapshot private constructor(
    override val owner: ModelOwnerIdentity,
    override val modelVersion: ModelVersionIdentity,
    private val document: Document,
) : ModelOwned {
    @JvmSynthetic
    internal fun document(): Document = document

    companion object {
        @JvmSynthetic
        internal fun of(
            owner: ModelOwnerIdentity,
            modelVersion: ModelVersionIdentity,
            document: Document,
        ): DocumentSnapshot = DocumentSnapshot(owner, modelVersion, document)
    }
}

/**
 * The Document transport of one [PreparedModel]. [readDocument] is the typed ingress: it validates the
 * complete envelope — version, resource limits, model paths, coordinate arity and positivity, duplicates, and
 * conflicts — and only then constructs the snapshot, so a failing input mutates nothing. [encodeDocument] is
 * the lossless owner-checked read-back and the transport's round-trip oracle.
 */
class PreparedDocumentApi internal constructor(
    private val prepared: PreparedModel,
) {
    /**
     * Validate the complete V1 transport before constructing an immutable, owner-bound Document snapshot.
     * Resource-limit failures are fail-fast; model-aware input diagnostics aggregate.
     */
    @JvmOverloads
    fun readDocument(
        input: DocumentInputV1,
        limits: DocumentDecodeLimits = DocumentDecodeLimits.defaults(),
    ): DocumentSnapshot {
        val document = DocumentReader.read(prepared.definition(), input, limits)
        return DocumentSnapshot.of(prepared.owner, prepared.modelVersion, document)
    }

    /** Encode a snapshot from this prepared owner without losing empty cells, raw text, or explicit group rows. */
    fun encodeDocument(snapshot: DocumentSnapshot): DocumentInputV1 {
        if (snapshot.owner !== prepared.owner) {
            throw ModelOwnerMismatchError.of(expected = prepared.owner, actual = snapshot.owner)
        }
        val groups = mutableListOf<GroupPlacementInput>()
        val fields = mutableListOf<FieldPlacementInput>()
        for (record in snapshot.document().placementsSnapshot()) {
            val coordinates = immutableListSnapshot(record.reps)
            if (record.isGroup) {
                groups += GroupPlacementInput(record.path, coordinates)
            } else {
                fields += FieldPlacementInput(record.path, coordinates, record.raw.orEmpty())
            }
        }
        return DocumentInputV1(
            groups = immutableListSnapshot(groups),
            fields = immutableListSnapshot(fields),
        )
    }
}

/**
 * The one shared Document validation engine (both the typed ingress and the later unknown-shape decoder land
 * here). Resource ceilings fail fast; model-level defects aggregate so a caller sees every defect at once; the
 * document is constructed only after the complete envelope passed.
 */
private object DocumentReader {

    fun read(model: ModelDef, input: DocumentInputV1, limits: DocumentDecodeLimits): Document {
        requireSupportedVersion(input.version)
        val groups = input.groups.toList()
        val fields = input.fields.toList()
        requireCount(groups.size, limits.maxGroupPlacements, DocumentInputResource.GROUP_PLACEMENTS)
        requireCount(fields.size, limits.maxFieldPlacements, DocumentInputResource.FIELD_PLACEMENTS)

        val diagnostics = mutableListOf<DocumentInputDiagnostic>()
        val firstByIdentity = mutableMapOf<PlacementIdentity, FirstOccurrence>()
        var totalRawCodeUnits = 0L
        groups.forEachIndexed { index, group ->
            validatePlacement(
                model = model,
                limits = limits,
                kind = DocumentPlacementKind.GROUP,
                index = index,
                path = group.path,
                coordinates = group.coordinates,
                raw = null,
                diagnostics = diagnostics,
                firstByIdentity = firstByIdentity,
            )
        }
        fields.forEachIndexed { index, field ->
            totalRawCodeUnits += field.raw.length
            requireWithin(
                actual = field.raw.length.toLong(),
                maximum = limits.maxRawCodeUnitsPerField.toLong(),
                resource = DocumentInputResource.RAW_CODE_UNITS_PER_FIELD,
                kind = DocumentPlacementKind.FIELD,
                index = index,
            )
            requireWithin(
                actual = totalRawCodeUnits,
                maximum = limits.maxTotalRawCodeUnits.toLong(),
                resource = DocumentInputResource.TOTAL_RAW_CODE_UNITS,
                kind = DocumentPlacementKind.FIELD,
                index = index,
            )
            validatePlacement(
                model = model,
                limits = limits,
                kind = DocumentPlacementKind.FIELD,
                index = index,
                path = field.path,
                coordinates = field.coordinates,
                raw = field.raw,
                diagnostics = diagnostics,
                firstByIdentity = firstByIdentity,
            )
        }
        if (diagnostics.isNotEmpty()) {
            throw DocumentInputError.from(diagnostics)
        }

        val document = model.newDocument()
        groups.forEach { document.group(it.path, *it.coordinates.toIntArray()) }
        fields.forEach { document.field(it.path, it.raw, *it.coordinates.toIntArray()) }
        return document
    }

    private fun validatePlacement(
        model: ModelDef,
        limits: DocumentDecodeLimits,
        kind: DocumentPlacementKind,
        index: Int,
        path: String,
        coordinates: List<Int>,
        raw: String?,
        diagnostics: MutableList<DocumentInputDiagnostic>,
        firstByIdentity: MutableMap<PlacementIdentity, FirstOccurrence>,
    ) {
        requireWithin(
            actual = path.length.toLong(),
            maximum = limits.maxPathCodeUnits.toLong(),
            resource = DocumentInputResource.PATH_CODE_UNITS,
            kind = kind,
            index = index,
        )
        val parts = ModelAddressValidation.pathPartCount(path)
        requireWithin(
            actual = parts.toLong(),
            maximum = limits.maxModelNestingDepth.toLong(),
            resource = DocumentInputResource.MODEL_NESTING_DEPTH,
            kind = kind,
            index = index,
        )
        requireWithin(
            actual = coordinates.size.toLong(),
            maximum = limits.maxCoordinatesPerPlacement.toLong(),
            resource = DocumentInputResource.COORDINATES_PER_PLACEMENT,
            kind = kind,
            index = index,
        )

        if (ModelAddressValidation.entityKind(model, path) != kind) {
            diagnostics += UnknownModelPathDiagnostic(kind, index, path)
        }
        for (violation in ModelAddressValidation.violations(kind, path, coordinates)) {
            when (violation.reason) {
                ModelAddressViolationReason.COORDINATE_COUNT_MISMATCH ->
                    diagnostics += CoordinateCountMismatchDiagnostic(
                        placementKind = kind,
                        placementIndex = index,
                        modelPath = path,
                        expectedCoordinateCount = checkNotNull(violation.expectedCoordinateCount),
                        actualCoordinateCount = checkNotNull(violation.actualCoordinateCount),
                    )
                ModelAddressViolationReason.INVALID_COORDINATE -> {
                    val coordinateIndex = checkNotNull(violation.coordinateIndex)
                    diagnostics += InvalidCoordinateDiagnostic(
                        placementKind = kind,
                        placementIndex = index,
                        coordinateIndex = coordinateIndex,
                        modelPath = path,
                        coordinate = coordinates[coordinateIndex],
                    )
                }
                ModelAddressViolationReason.INVALID_FIELD_REPETITION -> {
                    val coordinateIndex = checkNotNull(violation.coordinateIndex)
                    diagnostics += InvalidFieldRepetitionDiagnostic(
                        placementIndex = index,
                        coordinateIndex = coordinateIndex,
                        modelPath = path,
                        coordinate = coordinates[coordinateIndex],
                    )
                }
            }
        }

        val identity = PlacementIdentity(kind, path, coordinates.toList())
        val first = firstByIdentity[identity]
        when {
            first == null -> firstByIdentity[identity] = FirstOccurrence(index, raw)
            kind == DocumentPlacementKind.FIELD && first.raw != raw ->
                diagnostics += ConflictingPlacementDiagnostic(kind, index, first.index, path)
            else -> diagnostics += DuplicatePlacementDiagnostic(kind, index, first.index, path)
        }
    }

    private fun requireSupportedVersion(version: Int) {
        if (version != DocumentInputV1.SUPPORTED_VERSION) {
            throw DocumentInputError.from(
                listOf(
                    UnsupportedVersionDiagnostic(
                        expectedVersion = DocumentInputV1.SUPPORTED_VERSION,
                        actualVersion = version,
                    ),
                ),
            )
        }
    }

    private fun requireCount(actual: Int, maximum: Int, resource: DocumentInputResource) {
        requireWithin(actual.toLong(), maximum.toLong(), resource, kind = null, index = null)
    }

    private fun requireWithin(
        actual: Long,
        maximum: Long,
        resource: DocumentInputResource,
        kind: DocumentPlacementKind?,
        index: Int?,
    ) {
        if (actual > maximum) {
            throw DocumentInputError.from(
                listOf(ResourceLimitExceededDiagnostic(resource, maximum, actual, kind, index)),
            )
        }
    }

    private data class PlacementIdentity(
        val kind: DocumentPlacementKind,
        val path: String,
        val coordinates: List<Int>,
    )

    private data class FirstOccurrence(val index: Int, val raw: String?)
}

private data class UnsupportedVersionDiagnostic(
    override val expectedVersion: Int,
    override val actualVersion: Int,
) : DocumentInputDiagnostic {
    override val reasonCode = DocumentInputReasonCode.UNSUPPORTED_VERSION
    override val message =
        "Document version $actualVersion is not supported; this boundary reads version $expectedVersion"
}

private data class UnknownModelPathDiagnostic(
    override val placementKind: DocumentPlacementKind,
    override val placementIndex: Int,
    override val modelPath: String,
) : DocumentInputDiagnostic {
    override val reasonCode = DocumentInputReasonCode.UNKNOWN_MODEL_PATH
    override val message =
        "${placementKind.name.lowercase()} placement $placementIndex names unknown model path '$modelPath'"
}

private data class CoordinateCountMismatchDiagnostic(
    override val placementKind: DocumentPlacementKind,
    override val placementIndex: Int,
    override val modelPath: String,
    override val expectedCoordinateCount: Int,
    override val actualCoordinateCount: Int,
) : DocumentInputDiagnostic {
    override val reasonCode = DocumentInputReasonCode.COORDINATE_COUNT_MISMATCH
    override val message = "${placementKind.name.lowercase()} placement $placementIndex at '$modelPath' carries " +
        "$actualCoordinateCount coordinates; the path has $expectedCoordinateCount parts"
}

private data class InvalidCoordinateDiagnostic(
    override val placementKind: DocumentPlacementKind,
    override val placementIndex: Int,
    override val coordinateIndex: Int,
    override val modelPath: String,
    private val coordinate: Int,
) : DocumentInputDiagnostic {
    override val reasonCode = DocumentInputReasonCode.INVALID_COORDINATE
    override val message = "${placementKind.name.lowercase()} placement $placementIndex at '$modelPath' carries " +
        "coordinate $coordinate at position $coordinateIndex; repetition indices are positive and 1-based"
}

private data class InvalidFieldRepetitionDiagnostic(
    override val placementIndex: Int,
    override val coordinateIndex: Int,
    override val modelPath: String,
    private val coordinate: Int,
) : DocumentInputDiagnostic {
    override val reasonCode = DocumentInputReasonCode.INVALID_FIELD_REPETITION
    override val placementKind = DocumentPlacementKind.FIELD
    override val message = "field placement $placementIndex at '$modelPath' carries terminal coordinate " +
        "$coordinate; a field's terminal coordinate must be 1"
}

private data class ResourceLimitExceededDiagnostic(
    override val resource: DocumentInputResource,
    override val maximum: Long,
    override val actual: Long,
    override val placementKind: DocumentPlacementKind?,
    override val placementIndex: Int?,
) : DocumentInputDiagnostic {
    override val reasonCode = DocumentInputReasonCode.RESOURCE_LIMIT_EXCEEDED
    override val message =
        "placement decoding exceeded ${resource.name.lowercase()} limit $maximum (actual $actual)"
}

private data class DuplicatePlacementDiagnostic(
    override val placementKind: DocumentPlacementKind,
    override val placementIndex: Int,
    override val firstPlacementIndex: Int,
    override val modelPath: String,
) : DocumentInputDiagnostic {
    override val reasonCode = DocumentInputReasonCode.DUPLICATE_PLACEMENT
    override val message = "${placementKind.name.lowercase()} placement $placementIndex repeats " +
        "placement $firstPlacementIndex at '$modelPath'"
}

private data class ConflictingPlacementDiagnostic(
    override val placementKind: DocumentPlacementKind,
    override val placementIndex: Int,
    override val firstPlacementIndex: Int,
    override val modelPath: String,
) : DocumentInputDiagnostic {
    override val reasonCode = DocumentInputReasonCode.CONFLICTING_PLACEMENT
    override val message = "field placement $placementIndex stores a different raw value than " +
        "placement $firstPlacementIndex at '$modelPath'"
}
