package io.github.mbackschat.a12.dm.adapter.perf

import java.io.File
import java.util.Locale

/** One row that moved further than NFR #1 allows, carrying both numbers so the report needs no second lookup. */
internal data class Nfr1Breach(
    val row: String,
    val baseline: Long,
    val observed: Long,
    val percent: Double,
) {
    override fun toString(): String =
        "$row: $baseline → $observed (+${"%.3f".format(Locale.ROOT, percent)}%)"
}

/**
 * NFR #1's standing anchor: H4's deterministic byte rows as the baseline commit measured them, held at a
 * per-row allowance with no compensation between rows.
 *
 * **Per row, never in aggregate** — the proposal's wording, and the reason this class returns every breach
 * rather than a verdict. A mean over 66 rows lets a collapsed scenario hide behind 65 unchanged ones, which is
 * the failure a single "total bytes" comparison would have shipped: the delivered-size half moved +0.108 % in
 * total at the checkpoint while its worst single asset moved +0.407 %, and only the second number is the one
 * the bar is written against.
 *
 * **Absence is a failure, not a skip** ([missingRows]) **and so is a row the anchor never saw** ([unknownRows]).
 * NFR #1 says missing or inconclusive evidence fails rather than passes, and both directions are how a bar goes
 * quiet without anyone deciding to switch it off: a renamed scenario stops matching its baseline and starts
 * matching nothing, so a comparison that silently intersects the two key sets reports green on an empty overlap.
 */
internal class Nfr1StandingAnchor(
    val baselineCommit: String,
    val maxRegressionPercent: Double,
    val environment: String,
    val coveredCollectors: List<String>,
    val rows: Map<String, Long>,
    val capture: Map<String, Nfr1MeasurementIdentity>,
) {

    /** Every row of [measured] that exceeds its baseline by more than the allowance. Unknown rows are not judged here. */
    fun breaches(measured: Map<String, Long>): List<Nfr1Breach> =
        measured.mapNotNull { (row, observed) ->
            val baseline = rows[row] ?: return@mapNotNull null
            // A zero baseline has no percentage; treat any growth off it as a breach rather than dividing.
            val percent =
                if (baseline == 0L) if (observed > 0L) Double.POSITIVE_INFINITY else 0.0
                else (observed - baseline) * 100.0 / baseline
            if (percent > maxRegressionPercent) Nfr1Breach(row, baseline, observed, percent) else null
        }.sortedByDescending { it.percent }

    /** Rows [measured] carries that the anchor has no baseline for — a rename or an addition, never a pass. */
    fun unknownRows(measured: Map<String, Long>): List<String> =
        measured.keys.filter { it !in rows }.sorted()

    /** Rows the anchor holds for [collector] that [measured] did not produce — the evidence-absent arm. */
    fun missingRows(collector: String, measured: Map<String, Long>): List<String> =
        rowsFor(collector).keys.filter { it !in measured }.sorted()

    /** The anchor's rows belonging to one collector, keyed by the leading segment of every row id. */
    fun rowsFor(collector: String): Map<String, Long> =
        rows.filterKeys { it.startsWith("$collector/") }

    companion object {

        internal const val RESOURCE = "/perf/nfr1-standing-anchor.json"

        fun load(): Nfr1StandingAnchor {
            val stream = Nfr1StandingAnchor::class.java.getResourceAsStream(RESOURCE)
                ?: error("the committed standing anchor $RESOURCE is missing from the test resources")
            val root = stream.use { PerfObservations.MAPPER.readTree(it) }
            return Nfr1StandingAnchor(
                baselineCommit = root.path("baselineCommit").asText(),
                maxRegressionPercent = root.path("maxRegressionPercent").asDouble(),
                environment = root.path("environment").asText(),
                coveredCollectors = root.path("coveredCollectors").map { it.asText() },
                rows = root.path("rows").properties().associate { (key, value) -> key to value.asLong() },
                capture = root.path("capture").properties().associate { (collector, fields) ->
                    collector to Nfr1MeasurementIdentity(
                        fields.properties().associate { (field, value) -> field to value.asText() },
                    )
                },
            )
        }
    }
}

/**
 * The conditions a footprint median was taken under — the answer to "is this the same quantity?".
 *
 * **Why the bar needs one at all.** A retained-heap median is not a property of the code alone: it moves with
 * warmup, repetition count, scale set, heap size, GC method, allocation instrument and runtime major. Comparing a
 * run taken under different conditions against the baseline is comparing two different quantities while reporting
 * a percentage, which is worse than not comparing at all — it produces a number, and a number gets believed.
 *
 * **The carrier that makes this concrete is the Node major, and it is not the operator's to choose.** The
 * Kotlin/JS toolchain downloads its OWN runtime into the isolated Gradle home (`node-v22.0.0-darwin-arm64` there
 * on 2026-09-19, which is what the baseline recorded), so a Kotlin plugin upgrade moves every Node heap row
 * without a line of interpreter source changing — and nothing else in the estate would attribute that move
 * correctly. A regression hunt would start in the interpreter and find nothing.
 *
 * **Major version, not patch** — the estate's existing reasoning for the JDK pin, applied here for the same
 * reason: `java.version` cannot separate the vendors while a patch-exact rule would redden on every Temurin bump
 * for a difference that moves nothing. Held as named fields rather than a record of ten accessors because the only
 * operation is a field-wise diff, and a diff wants names.
 */
internal data class Nfr1MeasurementIdentity(val fields: Map<String, String>) {

    /** Field-by-field disagreement with [other], as `field: expected ≠ actual`; empty when the quantities match. */
    fun diff(other: Nfr1MeasurementIdentity): List<String> =
        (fields.keys + other.fields.keys).sorted().mapNotNull { field ->
            val mine = fields[field]
            val theirs = other.fields[field]
            if (mine == theirs) null else "$field: $mine ≠ $theirs"
        }

    companion object {

        /** Derive the identity a fork record was produced under, keyed exactly as the committed anchor keys it. */
        fun of(record: com.fasterxml.jackson.databind.JsonNode): Nfr1MeasurementIdentity {
            val runtime = record.path("runtime")
            return Nfr1MeasurementIdentity(
                linkedMapOf(
                    "warmup" to record.path("warmup").asText(),
                    "repetitions" to record.path("repetitions").asText(),
                    "scales" to record.path("scales").joinToString(",") { it.asText() },
                    "runtimeName" to runtime.path("name").asText(),
                    "runtimeMajor" to runtime.path("version").asText().removePrefix("v").substringBefore('.'),
                    "os" to runtime.path("os").asText(),
                    "arch" to runtime.path("arch").asText(),
                    "maxHeapBytes" to runtime.path("maxHeapBytes").asText(),
                    "gcMethod" to record.path("gc").path("method").asText(),
                    "allocationMethod" to record.path("allocationMethod").asText(),
                ),
            )
        }
    }
}

/**
 * Reads the quantities the anchor holds, from the artifacts the measuring lanes already write.
 *
 * **The raw fork records, not the aggregate report.** Both carry the same numbers, and the aggregate additionally
 * carries curve fits this bar has no use for; reading the raws keeps the gate's input the same file the anchor
 * itself was generated from, so a change to the aggregate's presentation cannot move the bar. The median is the
 * lower median of the forks — `sorted[n / 2]`, which is exact at the odd fork counts the lane requires (≥5) and
 * is the convention the committed anchor was captured with.
 */
internal enum class Nfr1Collector(val id: String, val inputVariable: String) {

    /** The `footprintJvmFork*` raws — retained and allocated heap per scenario and scale. */
    JVM_FOOTPRINT("jvm-footprint", "NFR1_FOOTPRINT_RAW_DIR") {
        override fun read(input: File, anchor: Nfr1StandingAnchor, version: String?) =
            footprintMedians(input, "jvm-", id)

        override fun identityOf(input: File) = identityAcross(input, "jvm-")
    },

    /** The `jsNodeFootprintNodeFork*` raws, written into the same directory by the same lane. */
    NODE_FOOTPRINT("node-footprint", "NFR1_FOOTPRINT_RAW_DIR") {
        override fun read(input: File, anchor: Nfr1StandingAnchor, version: String?) =
            footprintMedians(input, "node-", id)

        override fun identityOf(input: File) = identityAcross(input, "node-")
    },

    /**
     * The assembled release mirror, read as file lengths rather than from `SHA256SUMS`.
     *
     * A checksum file answers whether bytes CHANGED, which every ordinary release does; this bar asks how many
     * bytes there are, so it measures the artifacts themselves.
     */
    DELIVERED_SIZE("delivered-size", "NFR1_DELIVERED_SIZE_DIR") {
        override fun read(input: File, anchor: Nfr1StandingAnchor, version: String?): Map<String, Long> {
            // The row ADDRESSES the asset by placeholder, so a release does not read as 18 vanished rows. Keying
            // by the literal version would make every bump fire both the missing and the unknown arm at once, and
            // the only way out would be re-measuring — which is a re-base, and the owner's to make. Re-keying is
            // not: the bytes a jar contains do not depend on what the file is called.
            val resolved = version?.takeIf { it.isNotBlank() }
                ?: error(
                    "the delivered-size rows address assets by $VERSION_PLACEHOLDER, so \$$VERSION_VARIABLE must " +
                        "name the version under $input",
                )
            return anchor.rowsFor(id).keys.mapNotNull { row ->
                val asset = File(input, row.removePrefix("$id/").replace(VERSION_PLACEHOLDER, resolved))
                // Absent is left out rather than recorded as zero: `missingRows` is what reports it, and a zero
                // would read as a 100% improvement — the one direction this bar does not check.
                if (asset.isFile) row to asset.length() else null
            }.toMap()
        }
    };

    /**
     * Read this collector's rows from [input]. [version] expands [VERSION_PLACEHOLDER] and is a parameter rather
     * than an environment read so that the refusal below it can be driven by a test — an advertised refusal that
     * only the build can reach is one nothing proves fires.
     */
    abstract fun read(input: File, anchor: Nfr1StandingAnchor, version: String?): Map<String, Long>

    /**
     * The conditions this run's records were produced under, or `null` for a collector whose rows carry no such
     * identity — delivered size is file lengths, which no warmup or heap setting can move.
     */
    open fun identityOf(input: File): Nfr1MeasurementIdentity? = null

    companion object {

        const val VERSION_PLACEHOLDER = "<version>"
        const val VERSION_VARIABLE = "NFR1_DELIVERED_VERSION"

        private fun forkRecords(rawDir: File, forkPrefix: String): List<File> =
            rawDir.listFiles()
                ?.filter { it.isFile && it.name.startsWith(forkPrefix) && it.name.endsWith(".json") }
                ?.sortedBy { it.name }
                .orEmpty()

        /**
         * One identity for the whole set of forks, or `null` when there are none.
         *
         * Forks that disagree with EACH OTHER get a synthetic identity that can match no anchor, so the gate
         * reports it rather than silently picking the first: a set of forks measured under two different
         * conditions is not a sample of one quantity, whatever the anchor says.
         */
        private fun identityAcross(rawDir: File, forkPrefix: String): Nfr1MeasurementIdentity? {
            val identities = forkRecords(rawDir, forkPrefix)
                .map { Nfr1MeasurementIdentity.of(PerfObservations.MAPPER.readTree(it)) }
                .distinct()
            return when (identities.size) {
                0 -> null
                1 -> identities.single()
                else -> Nfr1MeasurementIdentity(
                    mapOf("forksDisagree" to identities.joinToString(" | ") { it.fields.toString() }),
                )
            }
        }

        fun of(id: String): Nfr1Collector = entries.firstOrNull { it.id == id }
            ?: error("$id is not a standing-anchor collector; known: ${entries.joinToString { it.id }}")

        private fun footprintMedians(rawDir: File, forkPrefix: String, collector: String): Map<String, Long> {
            val samples = mutableMapOf<String, MutableList<Long>>()
            rawDir.listFiles()
                ?.filter { it.isFile && it.name.startsWith(forkPrefix) && it.name.endsWith(".json") }
                ?.sortedBy { it.name }
                .orEmpty()
                .forEach { fork ->
                    PerfObservations.MAPPER.readTree(fork).path("samples").forEach { sample ->
                        val scenario = sample.path("scenario").asText()
                        val scale = sample.path("scale").asLong()
                        listOf("retainedBytes", "allocatedBytes").forEach { metric ->
                            val value = sample.path(metric)
                            if (value.isNumber) {
                                samples.getOrPut("$collector/$scenario/$scale/$metric") { mutableListOf() }
                                    .add(value.asLong())
                            }
                        }
                    }
                }
            return samples.mapValues { (_, values) -> values.sorted()[values.size / 2] }
        }
    }
}
