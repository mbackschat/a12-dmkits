package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * An unquoted exponent reaches the kernel as written through either probe ingress (a12-kernel-lean
 * SPEC-2026-10-09-11). A12 reads a native decimal as `BigDecimal.toString()`, so a String field stores `1E+3` for
 * `1e3` and `1E-7` for `0.0000001` (KERNEL-FINDINGS KF1). An inline row written back in plain notation handed the
 * kernel `1000`, losing the negative scale; `0.0000001` has a positive one and is the control that plain notation
 * keeps. `KernelProbeDocumentFidelityTest` covers the trailing-zero arm of the same defect; this covers the
 * exponent arm, read through a computation that echoes the stored text.
 */
class KernelProbeExponentFidelityTest {

    @Test
    fun anInlineExponentReachesTheKernelAsAFileOneDoes() {
        DOCUMENTS.keys.forEach { name ->
            assertEquals(echo("$name-by-path"), echo("$name-inline"), "$name: the two ingresses must agree")
        }
    }

    /** The agreement is on A12's reading, not merely on a shared rewrite. */
    @Test
    fun theStringFieldStoresTheDecimalsOwnText() {
        assertEquals(listOf("Order/Echo=1E+3!"), echo("thousand-inline"))
        assertEquals(listOf("Order/Echo=1E-7!"), echo("tiny-inline"))
    }

    private fun echo(row: String): List<String> =
        artifact.path("rows").first { it.path("name").asText() == row }
            .path("engines").flatMap { engine ->
                engine.path("computations").path("outcomes").map {
                    "${it.path("field").asText()}=${it.path("value").asText()}"
                }
            }.distinct()

    companion object {

        private val MAPPER = ObjectMapper()

        @JvmStatic
        @TempDir
        lateinit var dir: Path

        private val DOCUMENTS = linkedMapOf(
            "thousand" to """{ "Order": { "Code": 1e3 } }""",
            "tiny" to """{ "Order": { "Code": 0.0000001 } }""",
        )

        private val model: String by lazy {
            val base = BoundModel.ofJson(
                DocumentModelDraft.named("probe-exponent").locale("en_US")
                    .rootGroup("Order") { order ->
                        order.stringField("Code") { }.stringField("Echo") { }
                    }.build().toJson(),
            )
            base.addAll(
                emptyList(),
                listOf(base.computation(base.str("/Order/Echo")).name("EchoComputation")
                    .calc(base.str("/Order/Code").asString().plus("!")).message(Dsl.en("Echo the code.")).build()),
            ).toJson()
        }

        private fun write(name: String, content: String): Path = Files.writeString(dir.resolve(name), content)

        private val artifact: JsonNode by lazy {
            val rows = DOCUMENTS.entries.joinToString(",\n") { (name, document) ->
                val file = write("$name.document.json", document)
                """{ "name": "$name-by-path", "document": "${file.fileName}" },
                   { "name": "$name-inline", "documentInline": $document }"""
            }
            val request = write("exponent.request.json", """{
                "model": "${write("exponent.model.json", model).fileName}",
                "observe": ["validateFull", "compute"],
                "rows": [ $rows ] }""")
            val out = dir.resolve("exponent.json")
            KernelRuntimeProbe.main(arrayOf(request.toString(), out.toString()))
            MAPPER.readTree(Files.readString(out))
        }
    }
}
