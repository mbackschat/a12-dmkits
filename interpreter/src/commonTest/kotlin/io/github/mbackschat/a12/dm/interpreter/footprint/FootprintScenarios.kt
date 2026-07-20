package io.github.mbackschat.a12.dm.interpreter.footprint

import io.github.mbackschat.a12.dm.interpreter.CacheObserver
import io.github.mbackschat.a12.dm.interpreter.CacheOwnerKind
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader

internal data class FootprintSemanticResult(
    val supported: Boolean,
    val outcome: String,
    val cardinality: Int,
)

internal class FootprintFixture(
    val model: ModelDef,
    val document: Document,
    val contentDefinition: String,
    private val evaluation: (DmInterpreter, Document) -> FootprintSemanticResult,
) {
    fun evaluate(
        interpreter: DmInterpreter,
        evaluatedDocument: Document = document,
    ): FootprintSemanticResult = evaluation(interpreter, evaluatedDocument)
}

internal object FootprintScenarios {

    fun fixture(scenario: FootprintScenario, scale: Int): FootprintFixture {
        require(scale >= 2) { "footprint scenarios need at least two rows" }
        val modelJson = FootprintModelFixtures.jsonFor(scenario)
        val model = ModelLoader.load(modelJson)
        val contentDefinition = "$modelJson\nworkload=${scenario.generatorIdentity}"
        return when (scenario) {
            FootprintScenario.SIMPLE_VALIDATION ->
                validationFixture(model, contentDefinition, scale)
            FootprintScenario.EQUALITY_CORRELATION ->
                correlationFixture(model, contentDefinition, scale)
            FootprintScenario.COMPUTE_OVERLAY ->
                computeFixture(model, contentDefinition, scale)
        }
    }

    private fun validationFixture(
        model: ModelDef,
        contentDefinition: String,
        scale: Int,
    ): FootprintFixture {
        val document = model.newDocument()
        repeat(scale) { row -> document.field(SIMPLE_VALUE, "present", 1, row + 1, 1) }
        return FootprintFixture(model, document, contentDefinition) { interpreter, doc ->
            validationResult(interpreter, doc, SIMPLE_CODE, FootprintScenario.SIMPLE_VALIDATION)
        }
    }

    private fun correlationFixture(
        model: ModelDef,
        contentDefinition: String,
        scale: Int,
    ): FootprintFixture {
        val document = model.newDocument()
        repeat(scale) { row -> document.field(CORRELATION_KEY, "same-key", 1, row + 1, 1) }
        return FootprintFixture(model, document, contentDefinition) { interpreter, doc ->
            validationResult(interpreter, doc, CORRELATION_CODE, FootprintScenario.EQUALITY_CORRELATION)
        }
    }

    private fun computeFixture(
        model: ModelDef,
        contentDefinition: String,
        scale: Int,
    ): FootprintFixture {
        val document = model.newDocument()
        repeat(scale) { row ->
            val key = if (row == 1) "kb" else "k$row"
            document.field(COMPUTE_KEY, key, 1, row + 1, 1)
                .field(COMPUTE_SOURCE, "10", 1, row + 1, 1)
        }
        return FootprintFixture(model, document, contentDefinition) { interpreter, doc ->
            val audit = interpreter.supportAudit()
            val computed = interpreter.compute(doc)
            val actualSignature = computed.map(::computedSignature).sorted()
            val expectedSignature = expectedComputeSignature(scale).sorted()
            FootprintSemanticResult(
                supported = audit.isEmpty(),
                outcome = if (actualSignature == expectedSignature) {
                    FootprintScenario.COMPUTE_OVERLAY.expectedOutcome
                } else {
                    "computed=SIGNATURE_MISMATCH:" +
                        firstMismatch(expectedSignature, actualSignature)
                },
                cardinality = computed.size,
            )
        }
    }

    private fun computedSignature(computed: io.github.mbackschat.a12.dm.interpreter.model.Computed): String =
        "${computed.path}@${computed.indices.joinToString(",")}|${computed.outcome}|${computed.value.orEmpty()}"

    private fun expectedComputeSignature(scale: Int): List<String> = buildList {
        (1..scale).forEach { row ->
            val indices = "1,$row,1"
            add("$COMPUTE_INTERMEDIATE@$indices|${ComputeOutcome.VALUE}|110")
            add("$COMPUTE_CORRELATED@$indices|${ComputeOutcome.VALUE}|110")
            add("$COMPUTE_INDEXED@$indices|${ComputeOutcome.VALUE}|10")
        }
    }

    private fun firstMismatch(expected: List<String>, actual: List<String>): String {
        val index = (0 until maxOf(expected.size, actual.size))
            .firstOrNull { expected.getOrNull(it) != actual.getOrNull(it) }
        return if (index == null) {
            "unknown"
        } else {
            "expected=${expected.getOrNull(index)};actual=${actual.getOrNull(index)}"
        }
    }

    private fun validationResult(
        interpreter: DmInterpreter,
        document: Document,
        expectedCode: String,
        scenario: FootprintScenario,
    ): FootprintSemanticResult {
        val result = interpreter.validate(document)
        val codes = result.messages.map { it.code }.distinct()
        return FootprintSemanticResult(
            supported = result.unsupported.isEmpty(),
            outcome = if (codes == listOf(expectedCode)) scenario.expectedOutcome else "diagnostics=${codes.joinToString(",")}",
            cardinality = result.messages.size,
        )
    }

    private const val SIMPLE_ROOT = "/FootprintSimple"
    private const val SIMPLE_ROW = "$SIMPLE_ROOT/Row"
    private const val SIMPLE_VALUE = "$SIMPLE_ROW/Value"
    private const val SIMPLE_CODE = "SIMPLE_REQUIRED"

    private const val CORRELATION_ROOT = "/FootprintCorrelation"
    private const val CORRELATION_ROW = "$CORRELATION_ROOT/Row"
    private const val CORRELATION_KEY = "$CORRELATION_ROW/Key"
    private const val CORRELATION_CODE = "EQUAL_PAIR"

    private const val COMPUTE_ROOT = "/FootprintCompute"
    private const val COMPUTE_ROW = "$COMPUTE_ROOT/Row"
    private const val COMPUTE_KEY = "$COMPUTE_ROW/Key"
    private const val COMPUTE_SOURCE = "$COMPUTE_ROW/Source"
    private const val COMPUTE_INTERMEDIATE = "$COMPUTE_ROW/Intermediate"
    private const val COMPUTE_CORRELATED = "$COMPUTE_ROW/Correlated"
    private const val COMPUTE_INDEXED = "$COMPUTE_ROW/Indexed"
}

internal class FootprintCacheObserver(
    private val referenceFactory: ((Any) -> FootprintOwnerReference)? = null,
) : CacheObserver {
    private var documentPeak = 0
    private var parsedModelPeak = 0
    private var overlayPeak = 0
    private var overlayCurrent = 0
    private val logicalEntries = mutableListOf<ObservedOwner>()

    override fun cacheEntries(owner: Any, kind: CacheOwnerKind, entries: Int) {
        when (kind) {
            CacheOwnerKind.DOCUMENT -> documentPeak = maxOf(documentPeak, entries)
            CacheOwnerKind.PARSED_MODEL -> parsedModelPeak = maxOf(parsedModelPeak, entries)
            CacheOwnerKind.OVERLAY_VIEWS -> {
                overlayPeak = maxOf(overlayPeak, entries)
                overlayCurrent = entries
            }
        }
        val factory = referenceFactory ?: return
        val observed = logicalEntries.firstOrNull { it.kind == kind && it.reference.refersTo(owner) }
            ?: ObservedOwner(factory(owner), kind, 0).also(logicalEntries::add)
        observed.entries = entries
    }

    fun sweepReleasedOwners() {
        logicalEntries.removeAll { it.reference.isReleased() }
    }

    fun logicalEntries(): Int = logicalEntries.sumOf { it.entries }

    fun snapshot(afterOwnerRelease: Int = 0): FootprintCacheEntries = FootprintCacheEntries(
        document = documentPeak,
        parsedModel = parsedModelPeak,
        overlayPeak = overlayPeak,
        overlayAfterRelease = overlayCurrent,
        afterOwnerRelease = afterOwnerRelease,
    )

    private data class ObservedOwner(
        val reference: FootprintOwnerReference,
        val kind: CacheOwnerKind,
        var entries: Int,
    )
}
