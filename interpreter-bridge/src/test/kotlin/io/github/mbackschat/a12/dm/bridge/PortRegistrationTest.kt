package io.github.mbackschat.a12.dm.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult
import io.github.mbackschat.a12.dm.rulekit.spi.ReadBackends

/**
 * The module's contract with a consumer is the PORT, not any type in here: a classpath carrying this module
 * and no kernel must resolve `ConditionReadBack` to [InterpreterConditionReadBack] through `META-INF/services`.
 *
 * Nothing else proved that. Every other test in this module drives [ConditionBridge] directly, which is the
 * mapping's own surface and reaches the provider not at all — so the service registration, the one thing a
 * consumer depends on, was asserted only by the file existing. A renamed class or a dropped resource would
 * have left the whole suite green.
 */
class PortRegistrationTest {

    @Test
    fun theServiceLoaderResolvesReadBackToThisModulesProvider() {
        assertEquals(
            InterpreterConditionReadBack::class.java,
            ReadBackends.require().javaClass,
        ) { "the kernel-free provider is what a bridge-carrying, kernel-free classpath resolves" }
    }

    @Test
    fun theResolvedPortReadsAModelsRulesWithNoKernel() {
        val model = shipmentModel("")
        readsTheRule(model)
    }

    /**
     * A commented DM-JSON model is a legal A12 artifact — A12 enables `ALLOW_COMMENTS` on both of its own
     * mappers, so a file its tooling round-trips must read here too. A plain `ObjectMapper` refuses one, and
     * this provider walks the model tree itself rather than going through `core`'s reader, so it needs the
     * same configuration to agree with the rest of the estate.
     */
    @Test
    fun aCommentedModelStillReads() {
        readsTheRule(shipmentModel("// the carrier a shipment was booked with\n"))
    }

    private fun readsTheRule(model: String) {
        val read = ReadBackends.require().readAll(model, ReferenceResolvers.selfContained())
        val rule = read["/Shipment/CarrierNamed"]
        assertTrue(rule is ReadResult.Converted) { "the rule reads back as a typed condition, got: $rule" }
    }

    private fun shipmentModel(comment: String): String = """
            {
              "header": {"id": "port", "modelType": "document", "modelVersion": "28.4.0",
                         "locales": [{"code": "en_US"}], "labels": [], "annotations": [],
                         "modelReferences": []},
              "content": {
                "modelInfo": {"name": "port", "variant": "cockpit", "revision": "r", "baseYear": "2020",
                              "immutable": false},
                "modelConfig": {"decimalSeparator": ".", "timeZone": "Europe/Berlin",
                                "conditionLanguage": {"code": "en_US"}, "fieldRefByShortNameAllowed": false},
                "modelRoot": {"rootGroups": [{
                  "type": "Group", "id": "/Shipment", "name": "Shipment",
                  "Group": {"repeatability": 1, "elements": [
                    $comment{"type": "Field", "id": "/Shipment/Carrier", "name": "Carrier",
                     "Field": {"fieldType": {"type": "StringType"}}},
                    {"type": "Rule", "id": "/Shipment/CarrierNamed", "name": "CarrierNamed",
                     "Rule": {"errorCondition": "FieldNotFilled(Carrier)", "errorField": "../Carrier",
                              "code": "C", "severity": "ERROR"}}
                  ]}
                }]}
              }
            }
        """.trimIndent()
}
