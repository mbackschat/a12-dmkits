package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Where the wildcard sits in a message's `referenced` set when a starred group holds a NON-REPEATABLE group
 * (KERNEL-FINDINGS KF183): the starred level carries `0`, and the fixed group beneath it carries `1`, exactly as it
 * would above the star. Tri-checked dynamic Groovy == generated Java == interpreter on complete sorted sets.
 *
 * The rival account, that every group context beneath the star is wildcarded, predicts `Adjuster[0]`; it was a
 * clause of a12-kernel-lean's spec, withdrawn under their SPEC-2026-10-29-03, and no row here can satisfy both. The
 * starred level's own `Claims[0]` is the positive control that the wildcard is present at all. Both presence
 * predicates, over the fixed group reached through the star and over the starred group itself, so the expansion of
 * a whole starred subtree is read as well as a path through it. Own domain: an insurance policy whose claims each
 * name one adjuster.
 */
class StarredGroupFixedDescendantReferenceDiffTest {

    private val base: String = DocumentModelDraft.named("policy-claims").locale("en_US")
        .rootGroup("Policy") { p ->
            p.stringField("Ref") { }
            p.group("Claims") { c ->
                c.repeatable(2).numberField("Amount") { it.fractionDigits(0, 0) }
                c.group("Adjuster") { a ->
                    a.stringField("Name") { }
                    a.stringField("Phone") { }
                }
            }
        }.build().toJson()

    /** Each rule reports on `Ref`, which the kernel requires the condition to name; only the group operand differs. */
    private val rules: Map<String, String> = BoundModel.ofJson(base).let { m ->
        val ref = m.str("/Policy/Ref").filled()
        val throughStar = m.flattenGroup("/Policy/Claims*/Adjuster")
        val starred = m.flattenGroup("/Policy/Claims*")
        mapOf(
            NONE_THROUGH to ref.and(Dsl.noGroupFilled(throughStar)),
            NONE_STARRED to ref.and(Dsl.noGroupFilled(starred)),
            ANY_THROUGH to ref.and(Dsl.atLeastOneGroupFilled(throughStar)),
            ANY_STARRED to ref.and(Dsl.atLeastOneGroupFilled(starred)),
        ).mapValues { (_, condition) -> RuntimeLaws.render(condition) }
    }

    private val model: String = rules.entries.fold(base) { json, (code, condition) ->
        RuntimeLaws.withRule(json, "/Policy", "R$code", "../Ref", code, condition)
    }

    private val definition = ModelLoader.load(model)

    /** The negative predicates fire on a policy with no claim, the positive ones on a claim with a named adjuster. */
    private fun placements(withClaim: Boolean, kernel: RuntimeLaws.Doc, interpreter: Document) {
        fun group(path: String, vararg reps: Int) { kernel.group(path, *reps); interpreter.group(path, *reps) }
        fun field(path: String, value: String, vararg reps: Int) {
            kernel.field(path, value, *reps); interpreter.field(path, value, *reps)
        }
        group("/Policy", 1)
        field("/Policy/Ref", "POL-11", 1, 1)
        if (withClaim) {
            group("/Policy/Claims", 1, 1)
            group("/Policy/Claims/Adjuster", 1, 1, 1)
            field("/Policy/Claims/Adjuster/Name", "R. Okafor", 1, 1, 1, 1)
        }
    }

    /** `code → (dynamic Groovy, generated Java, interpreter)` over both documents, each message kept. */
    private fun observed(): Map<String, Triple<List<List<String>>, List<List<String>>, List<List<String>>>> {
        val dynamic = mutableListOf<Pair<String, List<String>>>()
        val javaStatic = mutableListOf<Pair<String, List<String>>>()
        val interpreter = mutableListOf<Pair<String, List<String>>>()
        for (withClaim in listOf(false, true)) {
            val kernelDoc = RuntimeLaws.doc().also { placements(withClaim, it, definition.newDocument()) }
            val interpreterDoc = definition.newDocument().also { placements(withClaim, RuntimeLaws.doc(), it) }
            RuntimeLaws.messages(model, kernelDoc).forEach { dynamic += it.code() to it.referenced().sorted() }
            RuntimeLaws.kernelJavaEngine(model).messagesModern(kernelDoc, null, java.util.Locale.US)
                .forEach { javaStatic += it.code() to it.referenced().sorted() }
            DmInterpreter(definition).validateFull(interpreterDoc)
                .forEach { interpreter += it.code to it.referenced.sorted() }
        }
        fun List<Pair<String, List<String>>>.byCode(code: String) = filter { it.first == code }.map { it.second }
        return rules.keys.associateWith { code ->
            Triple(dynamic.byCode(code), javaStatic.byCode(code), interpreter.byCode(code))
        }
    }

    @Test
    fun aFixedGroupBeneathTheStarCarriesOneWhileTheStarredLevelCarriesZero() {
        val adjuster = listOf("/Policy[1]/Claims[0]/Adjuster[1]/Name", "/Policy[1]/Claims[0]/Adjuster[1]/Phone", "/Policy[1]/Ref")
        val subtree = listOf("/Policy[1]/Claims[0]/Adjuster[1]/Name", "/Policy[1]/Claims[0]/Adjuster[1]/Phone",
            "/Policy[1]/Claims[0]/Amount", "/Policy[1]/Ref")
        val predicted = mapOf(NONE_THROUGH to adjuster, NONE_STARRED to subtree, ANY_THROUGH to adjuster, ANY_STARRED to subtree)
        assertEquals(
            predicted.mapValues { (_, set) -> Triple(listOf(set), listOf(set), listOf(set)) },
            observed(),
            "one message per rule, as (dynamic Groovy, generated Java, interpreter)",
        )
    }

    private companion object {
        const val NONE_THROUGH = "NONETHROUGH"
        const val NONE_STARRED = "NONESTARRED"
        const val ANY_THROUGH = "ANYTHROUGH"
        const val ANY_STARRED = "ANYSTARRED"
    }
}
