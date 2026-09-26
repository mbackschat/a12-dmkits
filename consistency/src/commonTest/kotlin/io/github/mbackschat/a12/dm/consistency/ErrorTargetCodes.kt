package io.github.mbackschat.a12.dm.consistency

/**
 * The two codes A12 draws for an error target that names no field, in report order: two distinct codes from
 * two enums for one defect, so a test naming either alone asserts an under-report ([KERNEL-FINDINGS KF293]).
 */
internal val TARGET_PAIR = listOf("MVK_ERROR_FIELD_MISSING", "MVK_MISSING_ERROR_FIELD")
