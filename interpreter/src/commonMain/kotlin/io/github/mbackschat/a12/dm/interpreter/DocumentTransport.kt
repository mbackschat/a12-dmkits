package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.workspace.immutableListSnapshot
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmSynthetic

/**
 * Resource bounds for Document decoding. Caller values are capped by the hard implementation maxima so an API
 * consumer cannot accidentally disable the browser-safe denial-of-service guards.
 */
class DocumentDecodeLimits private constructor(
    val maxDocumentCodeUnits: Int,
    val maxGroupPlacements: Int,
    val maxFieldPlacements: Int,
    val maxPathCodeUnits: Int,
    val maxRawCodeUnitsPerField: Int,
    val maxTotalRawCodeUnits: Int,
    val maxModelNestingDepth: Int,
) {
    companion object {
        const val DEFAULT_MAX_DOCUMENT_CODE_UNITS = 32_000_000

        /** Group rows and field cells share one pair of ceilings: in A12 Document JSON **every** row is
         *  explicit (the old placement envelope left ancestors implicit and uncounted), so a document with N
         *  repetitions of one group is N + its ancestors rows — the qualified 10,000-row browser stress
         *  document alone is 10,001. A row ceiling below the cell ceiling would refuse documents whose cells
         *  are admitted. */
        const val DEFAULT_MAX_GROUP_PLACEMENTS = 100_000
        const val DEFAULT_MAX_FIELD_PLACEMENTS = 100_000
        const val DEFAULT_MAX_PATH_CODE_UNITS = 1_024
        const val DEFAULT_MAX_RAW_CODE_UNITS_PER_FIELD = 1_000_000
        const val DEFAULT_MAX_TOTAL_RAW_CODE_UNITS = 16_000_000
        const val DEFAULT_MAX_MODEL_NESTING_DEPTH = 32

        const val HARD_MAX_DOCUMENT_CODE_UNITS = 128_000_000
        const val HARD_MAX_GROUP_PLACEMENTS = 1_000_000
        const val HARD_MAX_FIELD_PLACEMENTS = 1_000_000
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
        private var maxDocumentCodeUnits = DEFAULT_MAX_DOCUMENT_CODE_UNITS
        private var maxGroupPlacements = DEFAULT_MAX_GROUP_PLACEMENTS
        private var maxFieldPlacements = DEFAULT_MAX_FIELD_PLACEMENTS
        private var maxPathCodeUnits = DEFAULT_MAX_PATH_CODE_UNITS
        private var maxRawCodeUnitsPerField = DEFAULT_MAX_RAW_CODE_UNITS_PER_FIELD
        private var maxTotalRawCodeUnits = DEFAULT_MAX_TOTAL_RAW_CODE_UNITS
        private var maxModelNestingDepth = DEFAULT_MAX_MODEL_NESTING_DEPTH

        fun maxDocumentCodeUnits(value: Int) =
            apply { maxDocumentCodeUnits = positive("maxDocumentCodeUnits", value) }
        fun maxGroupPlacements(value: Int) = apply { maxGroupPlacements = positive("maxGroupPlacements", value) }
        fun maxFieldPlacements(value: Int) = apply { maxFieldPlacements = positive("maxFieldPlacements", value) }
        fun maxPathCodeUnits(value: Int) = apply { maxPathCodeUnits = positive("maxPathCodeUnits", value) }
        fun maxRawCodeUnitsPerField(value: Int) =
            apply { maxRawCodeUnitsPerField = positive("maxRawCodeUnitsPerField", value) }
        fun maxTotalRawCodeUnits(value: Int) =
            apply { maxTotalRawCodeUnits = positive("maxTotalRawCodeUnits", value) }
        fun maxModelNestingDepth(value: Int) =
            apply { maxModelNestingDepth = positive("maxModelNestingDepth", value) }

        fun build(): DocumentDecodeLimits = DocumentDecodeLimits(
            maxDocumentCodeUnits = minOf(maxDocumentCodeUnits, HARD_MAX_DOCUMENT_CODE_UNITS),
            maxGroupPlacements = minOf(maxGroupPlacements, HARD_MAX_GROUP_PLACEMENTS),
            maxFieldPlacements = minOf(maxFieldPlacements, HARD_MAX_FIELD_PLACEMENTS),
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

/** What a JSON node at one document position actually is, when the model expected something else. */
enum class DocumentJsonNodeType {
    /** A JSON object — a group instance in A12 Document JSON. */
    OBJECT,

    /** A JSON array — the repetitions of a group in A12 Document JSON. */
    ARRAY,

    /** A JSON string, number, or boolean — a field value in A12 Document JSON. */
    VALUE,

    /** JSON `null` — a present field cell carrying no value. */
    NULL,
}

enum class DocumentInputReasonCode {
    /** The input is not parseable JSON, or its root is not the document's root object. */
    MALFORMED_JSON,

    /** A key names no declared field or group of the prepared model. */
    UNKNOWN_MODEL_PATH,

    /** The JSON node at a declared position cannot carry that model entity — see [DocumentJsonNodeType]. */
    UNEXPECTED_NODE_TYPE,

    /** The serializer's root-level `id` is neither a string nor a number. */
    INVALID_DOCUMENT_ID,

    /** Decoding exceeded one configured resource ceiling. */
    RESOURCE_LIMIT_EXCEEDED,
}

enum class DocumentInputResource {
    /** UTF-16 code units of the whole Document JSON text, checked before parsing. */
    DOCUMENT_CODE_UNITS,

    /** Group instances (rows) the document creates. */
    GROUP_PLACEMENTS,

    /** Field cells the document creates. */
    FIELD_PLACEMENTS,

    /** UTF-16 code units of one model path. */
    PATH_CODE_UNITS,

    /** UTF-16 code units of one field's raw value. */
    RAW_CODE_UNITS_PER_FIELD,

    /** UTF-16 code units across every field's raw value. */
    TOTAL_RAW_CODE_UNITS,

    /** Model path parts of one document position. */
    MODEL_NESTING_DEPTH,
}

/**
 * One structured Document-input failure. Only properties meaningful for [reasonCode] are populated; [message]
 * is presentation text and is never the sole carrier of corrective data. [pointer] is the canonical A12
 * `DocumentPointer` string of the offending position ([A12PointerFormat]), so a caller can address the defect in
 * the same form the document itself uses.
 */
interface DocumentInputDiagnostic {
    val reasonCode: DocumentInputReasonCode
    val pointer: String? get() = null
    val modelPath: String? get() = null
    val entityKind: ModelEntityKind? get() = null
    val nodeType: DocumentJsonNodeType? get() = null
    val resource: DocumentInputResource? get() = null
    val maximum: Long? get() = null
    val actual: Long? get() = null
    val message: String
}

/** Aggregate Document-input failure; thrown before any document state is constructed. */
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
 * The document holds state A12 Document JSON has no shape for, so encoding would silently lose it. The one
 * reachable case is a **non-repeatable group row above 1**: A12's reader accepts an array at any group (so such
 * a row stays readable, and formal validation reports it), while A12's writer emits a non-repeatable group as a
 * single object. `pointer` is the canonical A12 pointer of the offending row.
 */
class DocumentEncodeError private constructor(
    val modelPath: String,
    val pointer: String,
) : RuntimeException(
    "'$pointer' cannot be written as A12 Document JSON: '$modelPath' is not repeatable, " +
        "so the shape addresses only its first row",
) {
    val code: String = "A12_DOCUMENT_NOT_REPRESENTABLE"

    companion object {
        @JvmSynthetic
        internal fun nonRepeatableRow(modelPath: String, pointer: String): DocumentEncodeError =
            DocumentEncodeError(modelPath, pointer)
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
 * verb on its owning [PreparedModel.documents] is the read-back. `documentId` is A12's root-level `id` — a
 * serializer special case rather than a model field, so it takes no part in evaluation.
 */
class DocumentSnapshot private constructor(
    override val owner: ModelOwnerIdentity,
    override val modelVersion: ModelVersionIdentity,
    val documentId: String?,
    private val document: Document,
) : ModelOwned {
    @JvmSynthetic
    internal fun document(): Document = document

    companion object {
        @JvmSynthetic
        internal fun of(
            owner: ModelOwnerIdentity,
            modelVersion: ModelVersionIdentity,
            documentId: String?,
            document: Document,
        ): DocumentSnapshot = DocumentSnapshot(owner, modelVersion, documentId, document)
    }
}

/**
 * The Document transport of one [PreparedModel], speaking **A12's canonical Document JSON** on both sides.
 * [readDocument] validates the complete text — parseability, resource limits, model paths,
 * and node types — and only then constructs the snapshot, so a failing input mutates nothing. [encodeDocument]
 * is the owner-checked read-back: every row, empty cell, and unconverted raw value survives it, and one encode
 * reaches the canonical fixed point.
 */
class PreparedDocumentApi internal constructor(
    private val prepared: PreparedModel,
) {
    /**
     * Read A12 Document JSON into an immutable, owner-bound snapshot. Resource-limit failures are fail-fast;
     * model-aware input diagnostics aggregate so a caller sees every defect at once.
     */
    @JvmOverloads
    fun readDocument(
        json: String,
        limits: DocumentDecodeLimits = DocumentDecodeLimits.defaults(),
    ): DocumentSnapshot {
        val read = A12DocumentJson.read(prepared.definition(), json, limits)
        return DocumentSnapshot.of(prepared.owner, prepared.modelVersion, read.documentId, read.document)
    }

    /** Write a snapshot from this prepared owner back as canonical A12 Document JSON. */
    fun encodeDocument(snapshot: DocumentSnapshot): String {
        if (snapshot.owner !== prepared.owner) {
            throw ModelOwnerMismatchError.of(expected = prepared.owner, actual = snapshot.owner)
        }
        return A12DocumentJson.write(prepared.definition(), snapshot.document(), snapshot.documentId)
    }
}
