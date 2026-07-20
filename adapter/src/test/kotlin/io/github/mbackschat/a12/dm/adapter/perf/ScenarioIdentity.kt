package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.interpreter.gen.PlacementSink
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone
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
    val spec = ScenarioId.builder("diagnostic/${name.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-')}")
        .operation(op)
        .resultRegime(if (op == PerfOp.VALIDATE) ResultRegime.VALIDATION_MIXED else ResultRegime.COMPUTE_MIXED)
        .complexity(Complexity.LINEAR)
        .generator(GeneratorIdentity("$name/runtime", parameters = mapOf("status" to "unreviewed"), variants = variants))
        .scaleAxis("n")
        .expectedResultCardinality(observed)
        .reviewedFingerprint("0".repeat(64))
        .zone(interpModel.timeZone)
        .locale(locale)
        .fairJavaCapable(false)
        .build()
    return ScenarioIdentity(spec, n, ScenarioFingerprint.compute(spec, this, n), observed)
}

/** SHA-256 over one unambiguous, ordered canonical stream. */
private object ScenarioFingerprint {
    fun compute(id: ScenarioId, scenario: PerfScenario, n: Int): String {
        require(n > 0) { "${id.id}: benchmark scale must be positive" }
        val digest = CanonicalDigest()
        digest.put("format", "a12-perf-scenario-v1")
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
        model.fields.forEach { put("interp-field", it.toString()) }
        model.rules.forEach { rule ->
            put("interp-rule", rule.copy(messages = rule.messages.toSortedMap()).toString())
        }
        model.computations.forEach { put("interp-computation", it.toString()) }
        put("interp-repeatable", model.repeatableGroups.sorted().joinToString(","))
        model.declaredReps.toSortedMap().forEach { (path, count) -> put("interp-declared-reps:$path", count.toString()) }
        put("interp-index-fields", model.indexFields.sorted().joinToString(","))
        put("interp-base-year", model.baseYear?.toString() ?: "<absent>")
        put("interp-short-names", model.fieldRefByShortNameAllowed.toString())
        put("interp-legal-charset", model.legalCharset?.toString() ?: "<absent>")
        put("interp-time-zone", model.timeZone.name)
    }

    fun hex(): String = digest.digest().joinToString("") { "%02x".format(it) }

    private fun update(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
        digest.update(':'.code.toByte())
        digest.update(bytes)
    }
}
