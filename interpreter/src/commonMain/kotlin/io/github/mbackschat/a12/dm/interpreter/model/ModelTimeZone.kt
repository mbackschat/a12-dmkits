package io.github.mbackschat.a12.dm.interpreter.model

import io.github.mbackschat.a12.dm.interpreter.UnsupportedModelTimeZoneException
import io.github.mbackschat.a12.dm.interpreter.InvalidModelTimeZoneException

/**
 * The model's configured time zone (`content.modelConfig.timeZone`) — the axis the kernel applies to every
 * DATE/DATE_TIME parse at evaluation time (a zone-set, non-lenient `SimpleDateFormat`; the codegen copies the
 * slot into its internal `"TimeZone"` meta key). Only the kernel's two documented values are modeled
 * (`IMetaKeysInternal`: "Currently, only `UTC` and `Europe/Berlin` are supported"); the kernel itself passes
 * any string to `TimeZone.getTimeZone(...)` with a silent GMT fallback for unknown ids. `"GMT"` maps to
 * [UTC] here; an explicit blank is invalid, and any other id is refused without common code guessing whether
 * the host's Java registry accepts it. The JVM consumer supplies that legal-versus-invalid classification
 * before this neutral supported-set boundary (KERNEL-FINDINGS §6).
 *
 * Under [UTC] (the DM-JSON default) wall-clock time equals instant time. Under [EUROPE_BERLIN] the DST
 * transitions make wall clock and instants diverge. Sub-day operations use instants; DifferenceInDays uses
 * stateful model-zone calendar steps; date construction uses the model-zone legacy hybrid calendar. The math lives in
 * [io.github.mbackschat.a12.dm.interpreter.eval.DateMath].
 */
enum class ModelTimeZone {
    UTC, EUROPE_BERLIN;

    companion object {
        /** Map an explicitly configured `modelConfig.timeZone`: `UTC`/`GMT` → [UTC], `Europe/Berlin` →
         *  [EUROPE_BERLIN], blank/null → invalid configuration, and any other id → a neutral supported-set
         *  refusal. A genuinely absent slot is defaulted by the loader before this method is called. */
        fun of(id: String?): ModelTimeZone = when {
            id.isNullOrBlank() -> throw InvalidModelTimeZoneException(
                id,
                "invalid modelConfig.timeZone '${id ?: "null"}' — an explicitly present value must be a nonblank " +
                    "Java time-zone id; omit the slot to use the UTC default")
            id == "UTC" || id == "GMT" -> UTC
            id == "Europe/Berlin" -> EUROPE_BERLIN
            else -> throw UnsupportedModelTimeZoneException(
                id,
                "unsupported model timeZone '$id' — the interpreter-backed evaluator supports only UTC, GMT, and " +
                    "Europe/Berlin. This common boundary does not classify the host Java time-zone id domain; " +
                    "evaluate this model under a supported zone or validate the id at the JVM authoring boundary.")
        }
    }
}
