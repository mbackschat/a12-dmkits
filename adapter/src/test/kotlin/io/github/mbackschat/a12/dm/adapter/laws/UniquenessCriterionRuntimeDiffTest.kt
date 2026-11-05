package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.UniquenessCriterion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A document uniqueness criterion constrains values ACROSS stored documents, so validating one document evaluates
 * nothing of it ([KF671]: no validation path reads a criterion). The same model is read with and without its
 * criterion, one builder call apart, and every engine's messages must match between the two on each document —
 * dynamic Groovy, generated Java and the interpreter alike. The empty-number document is the liveness control: it
 * draws the required-field message, so the comparison is not between two silences. Own domain (insurance policies).
 */
class UniquenessCriterionRuntimeDiffTest {

    private fun model(withCriterion: Boolean): String {
        val draft = DocumentModelDraft.named("policy-runtime").locale("en_US")
            .rootGroup("Policy") { p -> p.stringField("PolicyNo") { it.required() }.stringField("Holder") { } }
        if (withCriterion) {
            draft.uniquenessCriterion(UniquenessCriterion("PolicyKey", listOf("/Policy/PolicyNo"),
                mapOf("en_US" to "Policy \$/Policy/PolicyNo.value\$ exists")))
        }
        return draft.build().toJson()
    }

    /** Every engine's message codes for one document, asserted equal across engines; the Groovy list returned. */
    private fun codes(model: String, policyNo: String?): List<String> {
        val definition = ModelLoader.load(model)
        val d = Both(RuntimeLaws.doc(), definition.newDocument()).group("/Policy", 1).field("/Policy/Holder", "Ada", 1, 1)
        policyNo?.let { d.field("/Policy/PolicyNo", it, 1, 1) }
        val dynamic = RuntimeLaws.messages(model, d.kDoc).map { it.code() }.sorted()
        val java = RuntimeLaws.kernelJavaEngine(model)
        assertEquals(dynamic, java.messages(java.instance(d.kDoc)).map { it.code() }.sorted(), "generated Java")
        assertEquals(dynamic, DmInterpreter(definition).validateFull(d.iDoc).map { it.code }.sorted(), "interpreter")
        return dynamic
    }

    @Test
    fun theCriterionModelIsAdmitted() = assertEquals(listOf<String>(), RuntimeLaws.consistencyMessages(model(true)))

    @Test
    fun aCriterionChangesNoMessageOnAnyEngine() {
        val documents = linkedMapOf("a policy number" to "P-1", "no policy number" to null)
        val without = documents.mapValues { (_, no) -> codes(model(false), no) }
        assertEquals(without, documents.mapValues { (_, no) -> codes(model(true), no) })
        assertEquals(listOf<String>(), without.getValue("a policy number"))
        assertEquals(1, without.getValue("no policy number").size, "the required-field control fires: $without")
    }
}
