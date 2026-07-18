package io.github.mbackschat.a12.dm.interpreter.model

/**
 * The model's configured time zone (`content.modelConfig.timeZone`) — the axis the kernel applies to every
 * DATE/DATE_TIME parse at evaluation time (a zone-set, non-lenient `SimpleDateFormat`; the codegen copies the
 * slot into its internal `"TimeZone"` meta key). Only the kernel's two documented values are modeled
 * (`IMetaKeysInternal`: "Currently, only `UTC` and `Europe/Berlin` are supported"); the kernel itself passes
 * any string to `TimeZone.getTimeZone(...)` with a silent GMT fallback for unknown ids, so `"GMT"` maps to
 * [UTC] here and any OTHER id is refused loud — silently evaluating a zone the interpreter does not model
 * would be wrong (KERNEL-FINDINGS §6).
 *
 * Under [UTC] (the DM-JSON default) wall-clock time equals instant time and every datetime computation is
 * plain 86 400 s/day arithmetic — the interpreter's historical behavior, unchanged. Under [EUROPE_BERLIN]
 * the DST transitions make wall clock and instants diverge; the zone math lives in
 * [io.github.mbackschat.a12.dm.interpreter.eval.DateMath].
 */
enum class ModelTimeZone {
    UTC, EUROPE_BERLIN;

    companion object {
        /** Map a `modelConfig.timeZone` string: absent/blank/`UTC`/`GMT` → [UTC] (the kernel default + its
         *  GMT alias), `Europe/Berlin` → [EUROPE_BERLIN]; any other id is refused loud. */
        fun of(id: String?): ModelTimeZone = when {
            id.isNullOrBlank() || id == "UTC" || id == "GMT" -> UTC
            id == "Europe/Berlin" -> EUROPE_BERLIN
            else -> throw IllegalArgumentException(
                "unsupported model timeZone '$id' — the interpreter models the kernel's two documented values, UTC and Europe/Berlin")
        }
    }
}
