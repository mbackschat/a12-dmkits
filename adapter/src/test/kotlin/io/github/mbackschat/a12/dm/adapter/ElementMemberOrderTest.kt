package io.github.mbackschat.a12.dm.adapter

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.mgmtp.a12.kernel.md.facade.DocumentModelServiceFactory
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText
import io.github.mbackschat.a12.dm.rulekit.dsl.RoundingMode
import io.github.mbackschat.a12.dm.rulekit.dsl.ToleranceBand
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.StringReader
import java.io.StringWriter

/**
 * Every path that writes a rule or computation element emits its members in the order A12's own serializer
 * writes them, so a model we write reads back and re-serializes byte-stable.
 *
 * The oracle is the kernel's model serializer run over our output: whatever order it re-emits is A12's
 * (`ComputationSerializer`, `RuleSerializer`, source-read). Each element sets every optional member, because
 * an absent one cannot be out of place.
 */
class ElementMemberOrderTest {

    private val model = TestResources.text("models/subscription.dm.json")
    private val messages = listOf(LocalizedText("en_US", "Fee follows the tiers."), LocalizedText("de_DE", "Gebühr nach Stufen."))

    private val computation = DraftComputation.builder()
        .groupPath("/Subscription/Billing").name("EffectiveFeeComp").computedFieldRelPath("EffectiveFee")
        .commonPrecondition("FieldFilled(BaseFee)")
        .alternatives(listOf(
            DraftComputation.Alternative.builder("[BaseFee]").precondition("[BaseFee] >= 100")
                .toleranceRangeOp(ToleranceBand.TR5).build(),
            DraftComputation.Alternative.builder("0").precondition("[BaseFee] < 100").build()))
        .errorCodesToSuppress(listOf("MVK_INVALID_COMPARE_DEC_PLACES"))
        .roundingMode(RoundingMode.ROUND_UP)
        .messages(messages)
        .build()

    private val rule = DraftRule.builder()
        .groupPath("/Subscription/Billing").name("BaseFeeRule").errorEntityRelPath("BaseFee")
        .errorCondition("[BaseFee] < 0").errorCode("FEE").severity("ERROR").messages(messages)
        .build()

    @Test
    fun aWrittenComputationKeepsA12sMemberOrder() {
        assertA12Order(ModelWriter.addComputation(model, computation, messages), "/Subscription/Billing/EffectiveFeeComp")
    }

    @Test
    fun anInjectedComputationKeepsA12sMemberOrder() {
        assertA12Order(RuleValidator().inject(model, computation), "/Subscription/Billing/EffectiveFeeComp")
    }

    @Test
    fun aWrittenRuleKeepsA12sMemberOrder() {
        assertA12Order(ModelWriter.addRule(model, rule.groupPath(), rule.name(), rule.errorEntityRelPath(),
            rule.errorCode(), rule.errorCondition(), rule.severity(), messages), "/Subscription/Billing/BaseFeeRule")
    }

    @Test
    fun aWrittenElementWithoutMessagesOmitsTheMemberAsA12Does() {
        val bare = ModelWriter.addRule(ModelWriter.addComputation(model, computation, listOf()), rule.groupPath(),
            rule.name(), rule.errorEntityRelPath(), rule.errorCode(), rule.errorCondition(), rule.severity(), listOf())
        assertA12Order(bare, "/Subscription/Billing/EffectiveFeeComp")
        assertA12Order(bare, "/Subscription/Billing/BaseFeeRule")
    }

    @Test
    fun anInjectedRuleKeepsA12sMemberOrder() {
        assertA12Order(RuleValidator().inject(model, rule), "/Subscription/Billing/BaseFeeRule")
    }

    private fun assertA12Order(written: String, id: String) {
        val ours = element(MAPPER.readTree(written), id)
        val a12 = element(MAPPER.readTree(kernelReserialized(written)), id)
        assertEquals(memberOrder(a12), memberOrder(ours))
    }

    /** Each object's member names, depth-first, so a nested alternative's order is compared too. */
    private fun memberOrder(node: JsonNode): List<String> = buildList {
        node.properties().forEach { (name, value) ->
            add(name)
            when {
                value.isObject -> addAll(memberOrder(value).map { "$name.$it" })
                value.isArray -> value.forEachIndexed { i, item ->
                    if (item.isObject) addAll(memberOrder(item).map { "$name[$i].$it" })
                }
            }
        }
    }

    private fun element(root: JsonNode, id: String): JsonNode =
        checkNotNull(find(root.path("content").path("modelRoot").path("rootGroups"), id)) { "no element $id" }

    private fun find(elements: JsonNode, id: String): JsonNode? {
        for (node in elements) {
            if (node.path("id").asText() == id) return node
            find(node.path("Group").path("elements"), id)?.let { return it }
        }
        return null
    }

    private fun kernelReserialized(modelJson: String): String {
        val serializer = DocumentModelServiceFactory().createDocumentModelSerializer()
        val out = StringWriter()
        serializer.serialize(serializer.deserialize(StringReader(modelJson)), out) { }
        return out.toString()
    }

    private companion object {
        val MAPPER = ObjectMapper()
    }
}
