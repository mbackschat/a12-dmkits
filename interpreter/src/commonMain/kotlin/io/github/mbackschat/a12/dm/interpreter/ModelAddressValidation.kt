package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.model.ModelDef

internal enum class ModelAddressViolationReason {
    COORDINATE_COUNT_MISMATCH,
    INVALID_COORDINATE,
    INVALID_FIELD_REPETITION,
}

internal data class ModelAddressViolation(
    val reason: ModelAddressViolationReason,
    val coordinateIndex: Int? = null,
    val expectedCoordinateCount: Int? = null,
    val actualCoordinateCount: Int? = null,
)

/**
 * The one exact-address classifier: [entityKind] tells the A12 Document JSON reader and public pointer
 * construction what the model declares at a path, and [violations] validates caller-supplied coordinates. Group
 * coordinates may exceed declared bounds because the Kernel represents them for formal validation; only
 * coordinate shape and the measured field-terminal invariant belong here.
 */
internal object ModelAddressValidation {
    fun entityKind(model: ModelDef, path: String): ModelEntityKind? =
        when {
            path in model.kinds -> ModelEntityKind.FIELD
            path in model.groupPaths -> ModelEntityKind.GROUP
            else -> null
        }

    fun violations(
        kind: ModelEntityKind,
        path: String,
        coordinates: List<Int>,
    ): List<ModelAddressViolation> = buildList {
        val expected = pathPartCount(path)
        if (coordinates.size != expected) {
            add(
                ModelAddressViolation(
                    reason = ModelAddressViolationReason.COORDINATE_COUNT_MISMATCH,
                    expectedCoordinateCount = expected,
                    actualCoordinateCount = coordinates.size,
                ),
            )
        }
        coordinates.forEachIndexed { index, coordinate ->
            if (coordinate < 1) {
                add(
                    ModelAddressViolation(
                        reason = ModelAddressViolationReason.INVALID_COORDINATE,
                        coordinateIndex = index,
                    ),
                )
            }
        }
        if (
            kind == ModelEntityKind.FIELD &&
            coordinates.size == expected &&
            coordinates.last() > 1
        ) {
            add(
                ModelAddressViolation(
                    reason = ModelAddressViolationReason.INVALID_FIELD_REPETITION,
                    coordinateIndex = coordinates.lastIndex,
                ),
            )
        }
    }

    fun pathPartCount(path: String): Int =
        path.trim('/').split('/').size
}
