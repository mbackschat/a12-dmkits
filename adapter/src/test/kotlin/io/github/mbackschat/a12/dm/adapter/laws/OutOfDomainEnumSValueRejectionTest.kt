package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The kernel's stance on a RAW hand-authored enum `sValue` OUTSIDE the declared value domain, and the
 * interpreter loader's matching refusal (graduated from IG104(b); IF221). The typed builders fail-fast on it
 * (`FieldTypeSpec.EnumSpec` — the S-value must be a declared value), so a well-formed dmkit model can never
 * carry it; but a FOREIGN raw DM-JSON model could. This pins what the kernel does with such a model and locks
 * `ModelLoader` to the same verdict.
 *
 * **Kernel stance (probed 2026-07-24):** an out-of-domain s-value is a MODEL-consistency error,
 * `MVK_S_VALUE_INVALID` — reported by `checkConsistency`, and BOTH kernel strategies REFUSE to generate
 * validation code (dynamic-Groovy `DocumentValidationException`, static-Java `generateValidationCode` returns
 * null). There is no runtime "staged-formal-check" path: the model is simply not kernel-consistent. So the
 * interpreter must REJECT it loud on load — mirroring an ungeneratable model would mean fabricating a runtime
 * error the kernel never produces (its previous silent behavior: it staged the token and raised a spurious
 * `stringFalschesMuster`).
 *
 * E-commerce domain (own fixtures): an order's Lines are keyed by a ServiceLevel enum whose declared values are
 * STD / EXPRESS; the foreign model declares the out-of-domain s-value OVERNIGHT.
 */
class OutOfDomainEnumSValueRejectionTest {

    /** Build a valid model (in-domain s-value), then hand-edit the raw JSON to the out-of-domain token —
     *  exactly the foreign shape the typed builders refuse to author. */
    private fun foreignModel(): String {
        val inDomain = DocumentModelDraft.named("order").locale("en_US")
            .rootGroup("Order") { o ->
                o.group("Lines") { lines: GroupBuilder ->
                    lines.repeatable(10).indexField("ServiceLevel")
                        .enumField("ServiceLevel") { e -> e.value("STD").value("EXPRESS").sValue("STD") }
                        .numberField("Qty") { }
                }
            }
            .build().toJson()
        val foreign = inDomain
            .replace("\"sValue\":\"STD\"", "\"sValue\":\"OVERNIGHT\"")
            .replace("\"sValue\" : \"STD\"", "\"sValue\" : \"OVERNIGHT\"")
        check(foreign != inDomain) { "sValue substitution failed — DM-JSON shape changed:\n$inDomain" }
        return foreign
    }

    @Test
    fun kernelRejectsAnOutOfDomainSValueAtModelConsistency() {
        val notifications = RuntimeLaws.consistencyMessages(foreignModel())
        assertTrue(notifications.any { it.contains("MVK_S_VALUE_INVALID") },
            "the kernel flags an out-of-domain s-value at MVK: $notifications")
    }

    @Test
    fun interpreterModelLoaderRejectsAnOutOfDomainSValueLoud() {
        val ex = assertThrows(IllegalArgumentException::class.java) { ModelLoader.load(foreignModel()) }
        val message = ex.message ?: ""
        assertTrue("OVERNIGHT" in message && "/Order/Lines/ServiceLevel" in message,
            "the rejection names the offending field and value: $message")
        assertTrue("MVK_S_VALUE_INVALID" in message,
            "the rejection cites the kernel's model-consistency code: $message")
    }

    @Test
    fun interpreterModelLoaderStillLoadsAnInDomainSValue() {
        val inDomain = DocumentModelDraft.named("order").locale("en_US")
            .rootGroup("Order") { o ->
                o.group("Lines") { lines: GroupBuilder ->
                    lines.repeatable(10).indexField("ServiceLevel")
                        .enumField("ServiceLevel") { e -> e.value("STD").value("EXPRESS").sValue("STD") }
                        .numberField("Qty") { }
                }
            }
            .build().toJson()
        // The valid twin loads and carries the S-value as an index default (control against over-rejection).
        assertEquals(mapOf("/Order/Lines/ServiceLevel" to "STD"), ModelLoader.load(inDomain).indexDefaults)
    }
}
