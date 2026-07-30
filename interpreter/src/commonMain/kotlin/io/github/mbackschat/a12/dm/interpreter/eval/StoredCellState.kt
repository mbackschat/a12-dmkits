package io.github.mbackschat.a12.dm.interpreter.eval

/**
 * The stored state of one field cell — the [Document.cellStateAt] result. ABSENT, PRESENT-EMPTY, and
 * PRESENT-VALUE are kept distinct (never collapsed), mirroring the three states the kernel's
 * application routes leave per cell (`IDocumentComputationResult.applyTo`;
 * KERNEL-FINDINGS §11 "post-application cell state"):
 *
 * - a computed VALUE is set (creating the cell — missing ancestor rows included — when absent);
 * - a source-classified CLEARED action creates a present-empty destination cell and any addressed missing
 *   ancestry, while ERRORED clears only an existing destination cell;
 * - on same-document compute-and-apply, CLEARED can only come from an input-filled target, so that route
 *   empties a cell already present in the source;
 * - an untouched cell keeps its placement state.
 *
 * [PresentValue.raw] is the raw **stored** text exactly as placed or computed — the eval-side CRLF→LF
 * normalization (IF124) does not leak into this read (the kernel's document store keeps CRLF; only its
 * eval caches normalize). A `""` value is NO value and reads [PresentEmpty] — the kernel's
 * `DocumentUtils.valueExists` notion (null or empty string), which every supported ingestion path
 * already applies when it maps an empty value to an empty placement.
 */
sealed interface StoredCellState {

    /** No placement exists for the cell (a missing row or a never-placed field). */
    data object Absent : StoredCellState

    /** The cell is materialized but carries no value — an empty placement, a cleared/errored computed
     *  cell, or a `""` value (no value under the kernel's `valueExists` notion). */
    data object PresentEmpty : StoredCellState

    /** The cell carries [raw] — the stored text exactly as placed or computed. */
    data class PresentValue(val raw: String) : StoredCellState
}
