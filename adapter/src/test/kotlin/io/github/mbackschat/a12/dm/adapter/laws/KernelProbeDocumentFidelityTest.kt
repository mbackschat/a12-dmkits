package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A row's document reaches the kernel as the request WROTE it, whichever ingress it came through.
 *
 * **The defect this locks, measured 2026-09-22.** A `documentInline` row is re-serialized out of the parsed
 * request before the kernel sees it, and the request was parsed by a DEFAULT Jackson mapper — so `5.00` became
 * a `DoubleNode`, was written back `5.0`, and the kernel stored a value the request never wrote. A row given by
 * PATH never had it, because the file's bytes reach the kernel unparsed. Every inline row with a scaled decimal
 * was affected, not only the one that exposed this.
 *
 * **Why a scale is not cosmetic.** A12 stores `String | Boolean | BigDecimal | Instant | InstantRange` and
 * re-renders every later text from the stored value, so the scale IS the value (KERNEL-FINDINGS KF1). Here it
 * decides a computation's change detection:
 * a document seeded with the total the computation will produce is NOT a change, but a document seeded with a
 * differently-scaled one is — so the defect silently manufactured a change out of an equality.
 *
 * **The two ingresses are the control pair**, which is what makes this a measurement rather than a pin: the
 * same document text through both routes must produce the same observation, and only one route was ever broken.
 */
class KernelProbeDocumentFidelityTest {

    /** Same bytes, two ingresses, one observation — the equality the defect broke. */
    @Test
    fun anInlineDocumentAndAFileDocumentAreTheSameObservation() {
        assertEquals(
            observationOf("by-path"), observationOf("inline"),
            "a row's document must reach the kernel as written, whichever key carried it",
        )
    }

    /**
     * …and the observation is the RIGHT one, not merely a consistent one. Without this, two ingresses that
     * mangled the scale identically would satisfy the equality above. A total already equal to what the
     * computation produces is no change, so the change projection reports nothing at all.
     */
    @Test
    fun aSeededTotalEqualToItsComputedValueIsNotAChange() {
        assertEquals(
            emptyList<String>(), observationOf("inline"),
            "the seeded 10.00 IS what the computation yields, so nothing changed — a reported change here "
                + "means the stored value was re-scaled on the way in",
        )
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private fun observationOf(row: String): List<String> =
        artifact.path("rows").first { it.path("name").asText() == row }
            .path("engines").flatMap { engine ->
                engine.path("computations").path("outcomes").map {
                    "${it.path("field").asText()}=${it.path("value").asText()}"
                }
            }

    companion object {

        private val MAPPER = ObjectMapper()

        @JvmStatic
        @TempDir
        lateinit var dir: Path

        /** Two items at 5.00 with the line total already 10.00 — the equality the scale decides. */
        private const val DOCUMENT =
            """{ "Order": { "UnitPrice": 5.00, "Quantity": 2, "LineTotal": 10.00 } }"""

        private val model: String by lazy {
            val base = BoundModel.ofJson(
                DocumentModelDraft.named("probe-fidelity").locale("en_US")
                    .rootGroup("Order") { order ->
                        order.numberField("UnitPrice") { it.fractionDigits(2, 2) }
                            .numberField("Quantity") { it.fractionDigits(0, 0) }
                            .numberField("LineTotal") { it.fractionDigits(2, 2) }
                    }.build().toJson(),
            )
            base.addAll(
                emptyList(),
                listOf(base.computation(base.num("/Order/LineTotal")).name("LineTotalComputation")
                    .calc(Dsl.roundAccounting(
                        base.num("/Order/UnitPrice").asNumber().times(base.num("/Order/Quantity").asNumber()),
                        2).expression())
                    .message(Dsl.en("Line total.")).build()),
            ).toJson()
        }

        private fun write(name: String, content: String): Path = Files.writeString(dir.resolve(name), content)

        private val artifact: JsonNode by lazy {
            val documentFile = write("order.document.json", DOCUMENT)
            val request = write("fidelity.request.json", """{
                "model": "${write("fidelity.model.json", model).fileName}",
                "observe": ["validateFull", "compute"],
                "rows": [
                  { "name": "by-path", "document": "${documentFile.fileName}" },
                  { "name": "inline", "documentInline": $DOCUMENT }
                ] }""")
            val out = dir.resolve("fidelity.json")
            KernelRuntimeProbe.main(arrayOf(request.toString(), out.toString()))
            MAPPER.readTree(Files.readString(out))
        }
    }
}
