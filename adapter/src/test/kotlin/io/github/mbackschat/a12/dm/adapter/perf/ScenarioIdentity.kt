package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.interpreter.gen.PlacementSink
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** The complete semantic result regime a scenario promises. Computations are deliberately outcome-specific. */
enum class ResultRegime {
    VALIDATION_FIRING,
    VALIDATION_LOW_FIRING,
    VALIDATION_MIXED,
    COMPUTE_VALUE,
    COMPUTE_CLEARED,
    COMPUTE_ERRORED,
    COMPUTE_MIXED,
}

/** The declared asymptotic complexity of a scenario's scaled axis. */
enum class Complexity { LINEAR, N_LOG_N, PAIRWISE }

/** Model-independent generator identity. The generated placements are hashed too; this records the inputs. */
data class GeneratorIdentity(
    val name: String,
    val seed: Long = 0,
    val parameters: Map<String, String> = emptyMap(),
    val variants: List<Int> = listOf(0),
) {
    init {
        require(name.isNotBlank()) { "generator name must not be blank" }
        require(variants.isNotEmpty()) { "at least one generator variant must be fingerprinted" }
        require(variants.distinct().size == variants.size) { "generator variants must be unique" }
    }
}

/** Explicit identity fields for a parameterized diagnostic scenario that is not in [PerfInventory]. */
data class DiagnosticScenarioMetadata(
    val resultRegime: ResultRegime,
    val complexity: Complexity,
    val generator: GeneratorIdentity,
    val scaleAxis: String,
    val fairJavaCapable: Boolean,
) {
    init {
        require(scaleAxis.isNotBlank()) { "diagnostic scale axis must not be blank" }
    }
}

/**
 * Reviewed scenario contract. [reviewedFingerprint] is stored beside [version]; changing any bound content
 * without updating both makes [bind] fail before a benchmark can produce a row.
 */
class ScenarioId private constructor(builder: Builder) {
    val id: String = builder.id
    val version: Int = builder.version
    val op: PerfOp = checkNotNull(builder.op) { "$id: operation is required" }
    val resultRegime: ResultRegime = checkNotNull(builder.resultRegime) { "$id: result regime is required" }
    val complexity: Complexity = checkNotNull(builder.complexity) { "$id: complexity is required" }
    val generator: GeneratorIdentity = checkNotNull(builder.generator) { "$id: generator identity is required" }
    val scaleAxis: String = builder.scaleAxis
    val expectedResultCardinality: List<Int> = builder.expectedResultCardinality.toList()
    val reviewedFingerprint: String = builder.reviewedFingerprint
    val zone: ModelTimeZone = builder.zone
    val locale: String = builder.locale
    val fairJavaCapable: Boolean = builder.fairJavaCapable

    init {
        require(id.isNotBlank()) { "scenario id must not be blank" }
        require(version >= 1) { "$id: version must be at least 1" }
        require(scaleAxis.isNotBlank()) { "$id: scale axis must not be blank" }
        require(expectedResultCardinality.all { it >= 0 }) { "$id: expected result cardinality must be non-negative" }
    }

    /** Compute the canonical content hash without accepting it—used only to review/update a scenario version. */
    internal fun fingerprintFor(scenario: PerfScenario, n: Int): String =
        ScenarioFingerprint.compute(this, scenario, n)

    /** Review the current content for [n], returning a new immutable contract with the hash stored beside version. */
    internal fun review(scenario: PerfScenario, n: Int): ScenarioId =
        toBuilder().reviewedFingerprint(fingerprintFor(scenario, n)).build()

    internal fun withResultRegime(regime: ResultRegime): ScenarioId =
        toBuilder().resultRegime(regime).build()

    /** Verify the reviewed content hash and return a scenario that owns the resulting bound identity. */
    internal fun bind(scenario: PerfScenario, n: Int): PerfScenario {
        check(reviewedFingerprint.matches(HEX_256)) {
            "$id@$version: reviewedFingerprint must be a stored lowercase SHA-256 value"
        }
        check(scenario.op == op) { "$id@$version: declared op $op != scenario op ${scenario.op}" }
        check(scenario.interpModel.timeZone == zone) {
            "$id@$version: declared zone $zone != model zone ${scenario.interpModel.timeZone}"
        }
        check(scenario.locale == locale) {
            "$id@$version: declared locale $locale != scenario locale ${scenario.locale}"
        }
        val actual = fingerprintFor(scenario, n)
        check(actual == reviewedFingerprint) {
            "$id@$version: scenario content drifted without a version/fingerprint update: " +
                "reviewed=$reviewedFingerprint actual=$actual"
        }
        return scenario.withIdentity(ScenarioBinding(this, n, actual))
    }

    fun toBuilder(): Builder = Builder(id)
        .version(version)
        .operation(op)
        .resultRegime(resultRegime)
        .complexity(complexity)
        .generator(generator)
        .scaleAxis(scaleAxis)
        .expectedResultCardinality(expectedResultCardinality)
        .reviewedFingerprint(reviewedFingerprint)
        .zone(zone)
        .locale(locale)
        .fairJavaCapable(fairJavaCapable)

    class Builder internal constructor(internal val id: String) {
        internal var version: Int = 1
        internal var op: PerfOp? = null
        internal var resultRegime: ResultRegime? = null
        internal var complexity: Complexity? = null
        internal var generator: GeneratorIdentity? = null
        internal var scaleAxis: String = "rows"
        internal var expectedResultCardinality: List<Int> = emptyList()
        internal var reviewedFingerprint: String = ""
        internal var zone: ModelTimeZone = ModelTimeZone.UTC
        internal var locale: String = "en_US"
        internal var fairJavaCapable: Boolean = true

        fun version(value: Int) = apply { version = value }
        fun operation(value: PerfOp) = apply { op = value }
        fun resultRegime(value: ResultRegime) = apply { resultRegime = value }
        fun complexity(value: Complexity) = apply { complexity = value }
        fun generator(value: GeneratorIdentity) = apply { generator = value }
        fun scaleAxis(value: String) = apply { scaleAxis = value }
        fun expectedResultCardinality(value: List<Int>) = apply { expectedResultCardinality = value.toList() }
        fun reviewedFingerprint(value: String) = apply { reviewedFingerprint = value }
        fun zone(value: ModelTimeZone) = apply { zone = value }
        fun locale(value: String) = apply { locale = value }
        fun fairJavaCapable(value: Boolean) = apply { fairJavaCapable = value }
        fun build(): ScenarioId = ScenarioId(this)
    }

    companion object {
        val HEX_256 = Regex("[0-9a-f]{64}")

        fun builder(id: String): Builder = Builder(id)
    }
}

/** A reviewed identity bound to one concrete benchmark scale. */
data class ScenarioBinding(
    val id: ScenarioId,
    val n: Int,
    val fingerprint: String,
) {
    init {
        require(n > 0) { "${id.id}: benchmark scale must be positive" }
    }

    fun complete(observedResultCardinality: List<Int>): ScenarioIdentity {
        check(observedResultCardinality == id.expectedResultCardinality) {
            "${id.id}@${id.version}: result cardinality drifted: " +
                "expected=${id.expectedResultCardinality} observed=$observedResultCardinality"
        }
        return ScenarioIdentity(id, n, fingerprint, observedResultCardinality)
    }
}

/** Complete identity retained by every structured result row and emitted verbatim in every formatted row. */
data class ScenarioIdentity(
    val id: ScenarioId,
    val n: Int,
    val fingerprint: String,
    val observedResultCardinality: List<Int>,
) {
    val label: String get() = "${id.id}@${id.version}"
}

/**
 * Non-certifying diagnostic identity for parameterized/ad-hoc scenarios. It is still content-bound and emitted;
 * the `diagnostic/` prefix and generator status make its unreviewed posture explicit.
 */
internal fun PerfScenario.diagnosticIdentity(n: Int, observed: List<Int>, variants: List<Int>): ScenarioIdentity {
    val metadata = diagnosticMetadata ?: DiagnosticScenarioMetadata(
        resultRegime = if (op == PerfOp.VALIDATE) ResultRegime.VALIDATION_MIXED else ResultRegime.COMPUTE_MIXED,
        complexity = Complexity.LINEAR,
        generator = GeneratorIdentity(
            "$name/runtime",
            parameters = mapOf("status" to "unreviewed"),
            variants = variants,
        ),
        scaleAxis = "n",
        fairJavaCapable = false,
    )
    check(metadata.generator.variants == variants) {
        "$name: diagnostic generator variants=${metadata.generator.variants}, run requested variants=$variants"
    }
    val spec = ScenarioId.builder("diagnostic/${name.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-')}")
        .operation(op)
        .resultRegime(metadata.resultRegime)
        .complexity(metadata.complexity)
        .generator(metadata.generator)
        .scaleAxis(metadata.scaleAxis)
        .expectedResultCardinality(observed)
        .reviewedFingerprint("0".repeat(64))
        .zone(interpModel.timeZone)
        .locale(locale)
        .fairJavaCapable(metadata.fairJavaCapable)
        .build()
    return ScenarioIdentity(spec, n, ScenarioFingerprint.compute(spec, this, n), observed)
}

/** SHA-256 over one unambiguous, ordered canonical stream. */
private object ScenarioFingerprint {
    fun compute(id: ScenarioId, scenario: PerfScenario, n: Int): String {
        require(n > 0) { "${id.id}: benchmark scale must be positive" }
        val digest = CanonicalDigest()
        digest.put("format", "a12-perf-scenario-v2")
        digest.put("id", id.id)
        digest.put("op", id.op.name)
        digest.put("result-regime", id.resultRegime.name)
        digest.put("complexity", id.complexity.name)
        digest.put("locale", id.locale)
        digest.put("zone", id.zone.name)
        digest.put("fair-java-capable", id.fairJavaCapable.toString())
        digest.put("scale-axis", id.scaleAxis)
        digest.put("scale", n.toString())
        digest.put("expected-cardinality", id.expectedResultCardinality.joinToString(","))
        digest.put("generator-name", id.generator.name)
        digest.put("generator-seed", id.generator.seed.toString())
        id.generator.parameters.toSortedMap().forEach { (key, value) ->
            digest.put("generator-parameter:$key", value)
        }
        digest.put("generator-variants", id.generator.variants.joinToString(","))
        digest.put("scenario-name", scenario.name)
        digest.put("kernel-model", scenario.kernelModel)
        digest.putModel(scenario.interpModel)
        scenario.runtimeIdentity.toSortedMap().forEach { (key, value) ->
            digest.put("runtime-binding:$key", value)
        }
        for (variant in id.generator.variants) {
            digest.put("instance-variant", variant.toString())
            scenario.instance(DigestPlacementSink(digest), n, variant)
        }
        return digest.hex()
    }
}

private class DigestPlacementSink(private val digest: CanonicalDigest) : PlacementSink {
    override fun group(path: String, vararg reps: Int) {
        digest.put("group", path)
        digest.put("group-reps", reps.joinToString(","))
    }

    override fun field(path: String, value: String, vararg reps: Int) {
        digest.put("field", path)
        digest.put("field-reps", reps.joinToString(","))
        digest.put("field-value", value)
    }

    override fun emptyField(path: String, vararg reps: Int) {
        digest.put("empty-field", path)
        digest.put("empty-field-reps", reps.joinToString(","))
    }
}

private class CanonicalDigest {
    private val digest = MessageDigest.getInstance("SHA-256")

    fun put(label: String, value: String) {
        update(label)
        update(value)
    }

    fun putModel(model: ModelDef) {
        model.fields.forEach(::putField)
        model.rules.forEach(::putRule)
        model.computations.forEach(::putComputation)
        put("interp-repeatable", model.repeatableGroups.sorted().joinToString(","))
        model.declaredReps.toSortedMap().forEach { (path, count) -> put("interp-declared-reps:$path", count.toString()) }
        put("interp-index-fields", model.indexFields.sorted().joinToString(","))
        put("interp-group-paths", model.groupPaths.sorted().joinToString(","))
        put("interp-base-year", model.baseYear?.toString() ?: "<absent>")
        put("interp-short-names", model.fieldRefByShortNameAllowed.toString())
        put("interp-legal-charset", model.legalCharset?.toString() ?: "<absent>")
        put("interp-time-zone", model.timeZone.name)
        put("interp-condition-language", model.conditionLanguage.name)
    }

    /**
     * Fingerprint semantic fields explicitly. Data-class `toString()` is not a stable format: adding a defaulted
     * property used to invalidate every reviewed scenario even when its content stayed absent.
     */
    private fun putField(field: FieldDef) {
        put("interp-field-path", field.path)
        put("interp-field-kind", field.kind.name)
        putMap("interp-field-label", field.labels)
        putNullable("interp-field-scale", field.scale)
        put("interp-field-signed", field.signed.toString())
        put("interp-field-required", field.required.name)
        putNullable("interp-field-format", field.format)
        putNullable("interp-field-range-separator", field.rangeSeparator)
        put("interp-field-date-precision", field.datePrecision.name)
        putList("interp-field-enum-value", field.enumValues)
        putNestedMap("interp-field-enum-category", field.enumCategories)
        putNullable("interp-field-enum-s-value", field.enumSValue)
        putNullable("interp-field-custom-type-name", field.customType?.name)
        putNullable("interp-field-custom-type-min-length", field.customType?.minLength)
        putNullable("interp-field-custom-type-max-length", field.customType?.maxLength)
        putNullable("interp-field-min-length", field.minLength)
        putNullable("interp-field-max-length", field.maxLength)
        putNullable("interp-field-pattern", field.pattern)
        put("interp-field-no-value-validation", field.noValueValidation.toString())
        putNullable("interp-field-min-value", field.minValue)
        putNullable("interp-field-max-value", field.maxValue)
        putNullable("interp-field-min-fractional-digits", field.minFractionalDigits)
        put("interp-field-line-breaks-permitted", field.lineBreaksPermitted.toString())
        put("interp-field-leading-zeros-allowed", field.leadingZerosAllowed.toString())
        put("interp-field-zero-not-allowed", field.zeroNotAllowed.toString())
        putNullable("interp-field-max-integer-digits", field.maxIntegerDigits)
        put("interp-field-amount-trait", field.amountTrait.toString())
        putNullable("interp-field-number-min-length", field.numberMinLength)
        putNullable("interp-field-number-max-length", field.numberMaxLength)
        put("interp-field-global", field.global.toString())
        putMap("interp-field-pattern-message", field.patternMessages)
        putMap("interp-field-required-message", field.requiredMessages)
    }

    private fun putRule(rule: RuleDef) {
        put("interp-rule-path", rule.path)
        put("interp-rule-group", rule.group)
        put("interp-rule-condition", rule.conditionText)
        put("interp-rule-code", rule.errorCode)
        put("interp-rule-error-path", rule.errorEntityRelPath)
        put("interp-rule-severity", rule.severity.name)
        putMap("interp-rule-message", rule.messages)
    }

    private fun putComputation(computation: ComputationDef) {
        put("interp-computation-target", computation.targetPath)
        put("interp-computation-group", computation.group)
        putNullable("interp-computation-common-precondition", computation.commonPrecondition)
        putNullable("interp-computation-name", computation.name)
        put("interp-computation-alternative-count", computation.alternatives.size.toString())
        computation.alternatives.forEach { alternative ->
            putNullable("interp-computation-precondition", alternative.precondition)
            put("interp-computation-operation", alternative.operation)
            putNullable("interp-computation-tolerance", alternative.tolerance?.name)
        }
    }

    private fun putMap(label: String, values: Map<String, String>) {
        put("$label-count", values.size.toString())
        values.toSortedMap().forEach { (key, value) ->
            put("$label-key", key)
            put("$label-value", value)
        }
    }

    private fun putNestedMap(label: String, values: Map<String, List<String>>) {
        put("$label-count", values.size.toString())
        values.toSortedMap().forEach { (key, members) ->
            put("$label-key", key)
            putList("$label-value", members)
        }
    }

    private fun putList(label: String, values: List<String>) {
        put("$label-count", values.size.toString())
        values.forEach { put(label, it) }
    }

    private fun putNullable(label: String, value: Any?) {
        put(label, value?.toString() ?: "<absent>")
    }

    fun hex(): String = digest.digest().joinToString("") { "%02x".format(it) }

    private fun update(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
        digest.update(':'.code.toByte())
        digest.update(bytes)
    }
}
