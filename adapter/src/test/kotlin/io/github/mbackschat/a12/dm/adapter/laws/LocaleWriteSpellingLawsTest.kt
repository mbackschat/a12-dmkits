package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.mgmtp.a12.kernel.md.facade.DocumentModelServiceFactory
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.ModelDocument
import io.github.mbackschat.a12.dm.modelkit.UniquenessCriterion
import io.github.mbackschat.a12.dm.predicates.a12LocaleKey
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.StringReader
import java.io.StringWriter

/**
 * A12 writes every locale it read as the parsed locale's `toString`, the key [a12LocaleKey] computes, in every
 * member that carries one: the header's declared codes and every localized entry, labels, descriptions, helper and
 * required-field texts, a string field's suggestions, an enumeration's value labels and invalid-value text, a rule's
 * error text and a uniqueness criterion's. So an A12 read and write turns `en-US` into `en_US`, and the kits write
 * the same key for a locale they store (KERNEL-FINDINGS KF676).
 *
 * The oracle is A12's model serializer reading and re-writing the model, with nothing else in between: no
 * consistency check, so a spelling that would fail the build (`US` in the header) still shows what the writer does
 * with it. Each row names the spelling given to every member at once, and the cell is the set of spellings A12 wrote
 * back across all of them; one member writing differently would show as a second value. Own domain (insurance).
 */
class LocaleWriteSpellingLawsTest {

    @Test
    fun a12WritesEveryLocaleItReadAsItsKey() {
        // The control: every carrier the kits write is present beside the header code (the model's and a field's
        // label, a string field's suggestions, an enumeration value's label and its invalid-value text, a
        // required-field text, helper text, both descriptions, a rule's error text and a criterion's), so one the
        // writer treated differently cannot be absent.
        val carriers = mutableListOf<String?>()
        collect(MAPPER.readTree(spelledEverywhere("en-US")), carriers)
        assertEquals(List(CARRIERS) { "en-US" }, carriers)
        val spellings = listOf("en_US", "en-US", "en", "eng", "iw", "US", "en_US_POSIX", "en__POSIX", "XX_123", "de-CH")
        val observed = spellings.associateWith { written(reserialized(spelledEverywhere(it))) }
        val expected = spellings.associateWith { setOf(a12LocaleKey(it)) }
        assertEquals(expected, observed)
    }

    /** The policy model with every locale-carrying member spelled [spelling]. */
    private fun spelledEverywhere(spelling: String): String {
        val tree = MAPPER.readTree(POLICY) as ObjectNode
        tree.path("header").path("locales").forEach { (it as ObjectNode).put("code", spelling) }
        respell(tree, spelling)
        return tree.toString()
    }

    private fun respell(node: JsonNode, spelling: String) {
        if (node is ObjectNode && node.has("locale") && node.path("locale").isTextual) node.put("locale", spelling)
        node.forEach { respell(it, spelling) }
    }

    /** Every locale A12 wrote: the header codes and each `locale` member anywhere in the tree. */
    private fun written(json: String): Set<String?> {
        val tree = MAPPER.readTree(json)
        val out = tree.path("header").path("locales").map { it.path("code").asText() }.toMutableSet<String?>()
        collect(tree, out)
        return out
    }

    private fun collect(node: JsonNode, out: MutableCollection<String?>) {
        if (node.isObject && node.path("locale").isTextual) out += node.path("locale").asText()
        node.forEach { collect(it, out) }
    }

    private fun reserialized(modelJson: String): String {
        val serializer = DocumentModelServiceFactory().createDocumentModelSerializer()
        val out = StringWriter()
        serializer.serialize(serializer.deserialize(StringReader(modelJson)), out) { }
        return out.toString()
    }

    private companion object {
        val MAPPER = ObjectMapper()
        const val CARRIERS = 11

        val POLICY: String = run {
            val drafted = DocumentModelDraft.named("policy").locale("en_US").label("en_US", "Policy")
                .rootGroup("Policy") { p ->
                    p.stringField("PolicyNo") { f ->
                        f.required().label("en_US", "Number").suggestions("en_US", "P-1", "P-2")
                    }
                        .enumField("Tier") { e ->
                            e.value("GOLD") { l -> l.label("en_US", "Gold") }.invalidValueMessage("Unknown tier.")
                        }
                }
                .build().toJson()
            val described = ModelDocument.read(drafted)
                .setRequiredErrorMessages("/Policy/PolicyNo", mapOf("en_US" to "Enter a number."))
                .setHelperText("/Policy/PolicyNo", mapOf("en_US" to "As printed on the certificate."))
                .setInternalDescription("/Policy/PolicyNo", "en_US", "The register key.")
                .setExternalDescription("/Policy/PolicyNo", "en_US", "Your policy number.")
                .toJson()
            val edit = EditSession.read(described)
            val policy = edit.group("/Policy")
            policy.addRule { r ->
                r.name("NumberRequired").code("NUMBER").errorField(policy.field("PolicyNo"))
                    .`when`(policy.str("PolicyNo").notFilled()).message(Dsl.en("A number is required.")).build()
            }
            edit.addUniquenessCriterion(UniquenessCriterion("PolicyKey", listOf("/Policy/PolicyNo"),
                mapOf("en_US" to "Policy number taken.")))
            edit.toJson()
        }
    }
}
