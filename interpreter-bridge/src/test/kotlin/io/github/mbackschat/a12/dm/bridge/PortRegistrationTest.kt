package io.github.mbackschat.a12.dm.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
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
        readsTheRule(BridgeModels.shipmentJson())
    }

    /**
     * A commented DM-JSON model is a legal A12 artifact — A12 enables `ALLOW_COMMENTS` on both of its own
     * mappers, so a file its tooling round-trips must read here too. A plain `ObjectMapper` refuses one, and
     * both the provider's configuration read and `core`'s expanded-model walk must therefore accept it.
     */
    @Test
    fun aCommentedModelStillReads() {
        readsTheRule(BridgeModels.withLineComment(BridgeModels.shipmentJson()))
    }

    /**
     * The provider reads the model's OWN authoring language out of `modelConfig.conditionLanguage`, and German
     * is a supported target rather than an exotic one. Nothing else covers that mapping: every other model here
     * is `en_US`, and the German cases elsewhere in this module hand [ConditionLanguage.DE] to
     * [ConditionBridge] directly, so they never reach the code→language step. Get it wrong and a German model
     * does not fail — every rule comes back [ReadResult.Opaque] with a parse message, which reads to a consumer
     * as "this model uses constructs you do not support" rather than "you parsed it as the wrong language".
     */
    @Test
    fun aGermanModelReadsThroughItsOwnConditionLanguage() {
        val read = ReadBackends.require().readAll(BridgeModels.germanBasketJson(), ReferenceResolvers.selfContained())
        val rule = read["/Bestellung/KundennummerAngegeben"]
        assertTrue(rule is ReadResult.Converted) { "the German rule reads back as a typed condition, got: $rule" }
    }

    /**
     * A12 expansion parses mounted rules in the HOST's condition language, not the provider's declaration. The
     * kernel rejects this mixed-language model (`WorkspacePreparationFixtureGuardTest`); this tolerant read port
     * keeps the mounted rule visible but must report it opaque rather than silently parsing it as German.
     */
    @Test
    fun anIncludedRuleIsParsedInTheHostModelsConditionLanguage() {
        val included = BridgeModels.germanBasketJson()
        val direct = InterpreterConditionReadBack().readAll(included, ReferenceResolvers.selfContained())
        assertTrue(direct["/Bestellung/KundennummerAngegeben"] is ReadResult.Converted) {
            "the condition itself must be bridge-supported in its declared German language: $direct"
        }
        val host = DocumentModelDraft.named("english-host").locale("en_US")
            .rootGroup("Order") { it.mount("Basket", "german-basket") }
            .build().toJson()

        val mounted = InterpreterConditionReadBack().readAll(host) { reference ->
            if (reference == "german-basket") included else null
        }

        assertTrue(mounted["/Order/Basket/KundennummerAngegeben"] is ReadResult.Opaque) {
            "the German provider rule must remain visible but fail under its English host language: $mounted"
        }
    }

    private fun readsTheRule(model: String) {
        val read = ReadBackends.require().readAll(model, ReferenceResolvers.selfContained())
        val rule = read["/Shipment/CarrierNamed"]
        assertTrue(rule is ReadResult.Converted) { "the rule reads back as a typed condition, got: $rule" }
    }

}
