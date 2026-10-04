package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.UnsupportedConstructException
import io.github.mbackschat.a12.dm.interpreter.eval.readDocumentJson
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * SPEC-2026-10-04-08: what a computation does when `Valid`/`Invalid` names a predefined type no validator supplies.
 * Validation fires both polarities on a filled value ([KF51](../../../../../../../../../../../docs/KERNEL-FINDINGS.md),
 * `UnregisteredPredefinedTypeObservableDiffTest`); a computation does not transfer that. A filled, well-formed source
 * fails either polarity with a calculation error on the target, which poisons its readers. An empty source clears
 * cleanly, a malformed source poisons before any lookup, and a connective that decides first never reaches the lookup.
 *
 * The interpreter keeps its declared policy for an unknown name, in this phase as in validation: the construct is
 * refused as unsupported rather than given the kernel's degenerate outcome. The last test locks that it stays explicit.
 *
 * Every guarded target `T` has a dependent `AfterT` that holds whenever it is computed at all, separating a poisoned
 * target (CLEARED) from a clean clear (1). Own domain (insurance policy contact).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UnregisteredPredefinedTypeComputationTest {

    private val targets = listOf("Yes", "No", "LazyYes", "LazyNo")
    private val seeds = targets.flatMap { listOf(it to "9", "After$it" to "9") }.toMap()

    private val model: String = run {
        val draft = DocumentModelDraft.named("policy-contact").locale("en_US")
            .rootGroup("Policy") { p ->
                p.stringField("Contact") { it.maxLength(8) }
                for (name in seeds.keys) p.numberField(name) { it.fractionDigits(0) }
            }.build().toJson()
        val session = EditSession.read(draft)
        val p = session.group("/Policy")
        val contact = p.str("Contact")
        val guards: List<Pair<String, Condition>> = listOf(
            "Yes" to Dsl.validType(contact.field(), "Missing"),
            "No" to Dsl.invalidType(contact.field(), "Missing"),
            "LazyYes" to contact.filled().or(Dsl.validType(contact.field(), "Missing")),
            "LazyNo" to contact.notFilled().and(Dsl.invalidType(contact.field(), "Missing")),
        )
        var json = session.toJson()
        for ((name, guard) in guards) {
            val target = p.num(name)
            json = TypedRules.inject(json, p.computation(target) {
                it.name("${name}Comp").`when`(guard).calc(NumberLiteral.of("1")).message(Dsl.en("Check the contact.")).build()
            })
            json = TypedRules.inject(json, p.computation(p.num("After$name")) {
                it.name("After${name}Comp").`when`(target.filled().or(target.notFilled())).calc(NumberLiteral.of("1"))
                    .message(Dsl.en("Check the contact.")).build()
            })
        }
        json
    }

    private fun document(contact: String) =
        """{"Policy":{"Contact":"$contact",""" + seeds.entries.joinToString(",") { (k, v) -> "\"$k\":\"$v\"" } + "}}"

    private val documents = linkedMapOf(
        "filled" to document("a@b.io"),
        "empty" to document(""),
        "malformed" to document("much-too-long"),
    )

    @Test
    fun theModelIsKernelConsistent() {
        assertEquals(emptyList<String>(), MessageParameterProbes.kernel(model))
    }

    /**
     * The kernel's computation outcomes, both strategies, pinned against dynamic Groovy. Each value is
     * `target/dependent`: `CLEARED/CLEARED` is a poisoned target, `CLEARED/1` a clean clear.
     */
    @Test
    fun theKernelFailsTheTargetOnlyWhenTheLookupIsReached() {
        fun row(doc: String): List<String> {
            val dynamic = DocumentDifferential.kernel(model, documents.getValue(doc), dynamic = true)
            assertEquals(dynamic, DocumentDifferential.kernel(model, documents.getValue(doc), dynamic = false),
                "$doc: static Java vs dynamic Groovy")
            fun of(name: String) = dynamic.outcomes[name] ?: "9"
            return targets.map { of(it) + "/" + of("After$it") } + dynamic.operandErrors.map { "${it.code}@${it.pointer}" }
        }
        assertEquals(linkedMapOf(
            "filled" to listOf("CLEARED/CLEARED", "CLEARED/CLEARED", "VALUE|1/VALUE|1", "CLEARED/VALUE|1",
                "berechnungsWertFehler@/Policy[1]/No", "berechnungsWertFehler@/Policy[1]/Yes"),
            "empty" to listOf("CLEARED/VALUE|1", "CLEARED/VALUE|1", "CLEARED/VALUE|1", "CLEARED/VALUE|1"),
            "malformed" to listOf("CLEARED/CLEARED", "CLEARED/CLEARED", "CLEARED/CLEARED", "CLEARED/CLEARED",
                "stringZuLang@/Policy[1]/Contact"),
        ), documents.keys.associateWith(::row))
    }

    /** The interpreter's declared divergence for an unknown name: refused as unsupported, never computed. */
    @Test
    fun theInterpreterRefusesTheUnknownNameExplicitly() {
        val def = ModelLoader.load(model)
        val refusal = assertThrows(UnsupportedConstructException::class.java) {
            DmInterpreter(def).compute(def.readDocumentJson(documents.getValue("filled")))
        }
        assertTrue(refusal.message.orEmpty().contains("\"Missing\""), refusal.message)
    }
}
