import io.github.mbackschat.a12.dm.interpreter.ModelOwnerMismatchError
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * A backend service serving concurrent requests from a bounded cache of hot document models.
 *
 * The point of the cache is not only reuse over time: several concurrent requests routinely want the SAME model,
 * and they must share its preparation without leaking anything into each other. What the numbers at the end show
 * is that many requests cost few preparations.
 *
 * Five rules are demonstrated, and each one is a real failure mode if broken:
 *
 * 1. Resolve the model ONCE per request and hold that reference for the whole request.
 * 2. Key the cache by a CHEAP identity the service already has — the database's `(modelId, version)` or an
 *    etag — and fetch the model body only on a miss. Neither a digest of the body nor the prepared model's
 *    `fingerprint` can serve as the lookup key: both require the work a hit exists to skip. The fingerprint's
 *    job is to VERIFY an entry's content identity, not to find it.
 * 3. SINGLE-FLIGHT the miss, or concurrent misses each build a full prepared model.
 * 4. Give every request its OWN interpreter. A prepared model is shareable; an interpreter is not.
 * 5. Eviction only drops the cache's reference. An in-flight request keeps its own entry alive.
 */
// tag::interpreter-kotlin-service-cache[]
/** A bounded, content-keyed cache of prepared models with single-flight loading. */
class PreparedModelCache(private val capacity: Int) {
    private val entries = ConcurrentHashMap<String, PreparedModel>()
    private val order = ArrayDeque<String>()
    val preparations = AtomicInteger()
    val evictions = AtomicInteger()

    /**
     * Rule 2 + rule 3: one entry per model version, built at most once even under a concurrent miss.
     *
     * [key] is the cheap content identity the service ALREADY has before touching the model body — the
     * database's `(modelId, version)` or an etag. [loadExpandedJson] runs only on a miss, so a hit costs no
     * database read, no digest, no parse and no preparation. Keying on a digest of the body would defeat that:
     * you cannot hash what you have not fetched. The prepared model's own `fingerprint` is unusable as a
     * lookup key for the same reason, one layer worse — obtaining it requires the preparation being avoided.
     *
     * `computeIfAbsent` is the single-flight: competing threads await the winner instead of each preparing.
     */
    fun resolve(key: String, loadExpandedJson: () -> String): PreparedModel {
        val prepared = entries.computeIfAbsent(key) {
            preparations.incrementAndGet()
            PreparedModel.loadExpandedJson(loadExpandedJson())
        }
        remember(key)
        return prepared
    }

    /** Rule 5: evicting removes only this reference; a request holding the entry keeps using it safely. */
    private fun remember(key: String) {
        synchronized(order) {
            order.remove(key)
            order.addLast(key)
            while (order.size > capacity) {
                entries.remove(order.removeFirst())
                evictions.incrementAndGet()
            }
        }
    }
}

/** One request: resolve once, take an OWN interpreter, answer. Rules 1 and 4. */
fun serveRequest(
    cache: PreparedModelCache,
    modelKey: String,
    loadExpandedJson: () -> String,
    documentJson: String,
): Int {
    // rule 1: resolved once, and the instance is held for the rest of the request
    val prepared = cache.resolve(modelKey, loadExpandedJson)
    val document = prepared.documents.readDocument(documentJson)
    val interpreter = prepared.createInterpreter()        // rule 4: never shared across threads
    return interpreter.validateFull(document).findings.size
}
// end::interpreter-kotlin-service-cache[]

/**
 * Run the service shape and report it. The work is genuinely concurrent; the OUTPUT is aggregated so it stays
 * reproducible rather than exposing thread interleaving.
 */
fun runServiceCacheExample(baseModelJson: String): String {
    // Three distinct models, differing only by model id, standing in for a service's model estate. The point
    // under test is the cache, not model authoring, so one committed fixture supplies all three.
    val models = (1..3).map { baseModelJson.replace("\"permit-basic\"", "\"permit-basic-$it\"") }
    val documentJson = """{"Permit":{"ApplicationNo":"P-42","RequestedArea":120}}"""

    // Run 1 — CONCURRENCY, with the cache large enough for the working set. 12 concurrent requests over 3
    // models cost 3 preparations, which is the whole value proposition in one number. Deterministic because
    // no entry is ever evicted.
    val fits = PreparedModelCache(capacity = 3)
    val findingCounts = ConcurrentHashMap<Int, AtomicInteger>()
    val threads = 4
    val requestsPerThread = 3
    val pool = Executors.newFixedThreadPool(threads)
    val start = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>(null)
    repeat(threads) { worker ->
        pool.execute {
            try {
            start.await()
            repeat(requestsPerThread) { round ->
                // Every worker hits the SAME small set of models, so concurrent requests genuinely contend.
                val index = (worker + round) % models.size
                val count = serveRequest(fits, "permit-basic-${index + 1}@v1", { models[index] }, documentJson)
                findingCounts.computeIfAbsent(count) { AtomicInteger() }.incrementAndGet()
            }
            } catch (error: Throwable) {
                // Without this a worker failure would reach only the uncaught handler, and the sole symptom
                // would be a short request count.
                failure.compareAndSet(null, error)
            }
        }
    }
    start.countDown()
    pool.shutdown()
    check(pool.awaitTermination(60, TimeUnit.SECONDS)) { "concurrent requests did not finish" }
    failure.get()?.let { throw IllegalStateException("a concurrent request failed", it) }
    val served = findingCounts.values.sumOf { it.get() }

    // Run 2 — SIZING, sequential so LRU order is fully determined. The same 12 requests through a cache one
    // entry too small thrash: every request evicts the entry the next one wants.
    val tooSmall = PreparedModelCache(capacity = 2)
    repeat(threads * requestsPerThread) { i ->
        val index = i % models.size
        serveRequest(tooSmall, "permit-basic-${index + 1}@v1", { models[index] }, documentJson)
    }

    // Content identity is NOT instance interchangeability. Two prepared models over identical bytes share a
    // fingerprint, yet each owns its own objects, so mixing them is refused rather than silently misbehaving.
    // That is why a request must resolve once and keep the instance it got (rule 1).
    val a = PreparedModel.loadExpandedJson(models[0])
    val b = PreparedModel.loadExpandedJson(models[0])
    val crossEntry = try {
        b.createInterpreter().validateFull(a.documents.readDocument(documentJson))
        "NOT REJECTED"
    } catch (expected: ModelOwnerMismatchError) {
        "rejected (${expected::class.simpleName})"
    }

    return buildString {
        appendLine("cache fits working set (concurrent, 4 threads)")
        appendLine("  requests served    : $served")
        appendLine("  models prepared    : ${fits.preparations.get()}")
        appendLine("  evictions          : ${fits.evictions.get()}")
        appendLine("  findings/request   : ${findingCounts.keys.sorted()}")
        appendLine("cache one entry too small (sequential)")
        appendLine("  requests served    : ${threads * requestsPerThread}")
        appendLine("  models prepared    : ${tooSmall.preparations.get()}")
        appendLine("  evictions          : ${tooSmall.evictions.get()}")
        appendLine("same content, two entries")
        appendLine("  same fingerprint   : ${a.fingerprint == b.fingerprint}")
        append("  cross-entry document : $crossEntry")
    }
}
