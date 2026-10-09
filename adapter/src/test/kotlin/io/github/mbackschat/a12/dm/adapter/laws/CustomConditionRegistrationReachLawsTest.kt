package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * When A12 looks up a `CustomCondition` registration (a12-kernel-lean SPEC-2026-10-06-01): only when the callback
 * leaf is REACHED. A rule whose condition names a callback nobody registered validates silently while an empty
 * guard before it decides the conjunction. Once the guard lets the leaf run, the missing registration does not fail
 * the validation: the rule reports its own code as VALUE, the way it reports any failure raised inside it
 * (KERNEL-FINDINGS KF513). The interpreter's
 * answer is a declared policy, not parity: it refuses an unregistered name when it prepares the rule, reached or
 * not, as it refuses an unregistered predefined type name (SPEC-2026-10-04-08).
 *
 * Own domain (an insurance claim review).
 */
class CustomConditionRegistrationReachLawsTest {

    private val base = DocumentModelDraft.named("claim-review-registration").locale("en_US")
        .rootGroup("Claim") { c -> c.stringField("Error") { }.stringField("Note") { } }
        .build().toJson()

    private val model: String = run {
        val bound = BoundModel.ofJson(base)
        val condition = bound.str("/Claim/Note").filled().and(Dsl.customCondition("UnregisteredCheck"))
        RuntimeLaws.withRule(base, "/Claim", "Review", "../Note", "REVIEW", RuntimeLaws.render("/Claim", condition))
    }

    /** The kernel's answer on one strategy: the fired messages, or whether a failure escaped and named the callback. */
    private fun kernel(note: String?, dynamic: Boolean): String {
        val doc = RuntimeLaws.doc().group("/Claim", 1).field("/Claim/Error", "x", 1, 1)
            .let { if (note == null) it else it.field("/Claim/Note", note, 1, 1) }
        return try {
            val codes = if (dynamic) {
                RuntimeLaws.messages(model, doc, emptyMap()).map { "${it.code()}|${it.type()}" }
            } else {
                val java = RuntimeLaws.kernelJavaEngine(model)
                java.messages(java.instance(doc), emptyMap()).map { "${it.code()}|${it.type()}" }
            }
            "fires $codes"
        } catch (failure: RuntimeException) {
            val text = generateSequence<Throwable>(failure) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
            "fails, naming the callback: ${"UnregisteredCheck" in text}"
        }
    }

    private fun interpreterUnsupported(note: String?): List<String> {
        val def = ModelLoader.load(model)
        val doc = def.newDocument().group("/Claim", 1).field("/Claim/Error", "x", 1, 1)
            .let { if (note == null) it else it.field("/Claim/Note", note, 1, 1) }
        return DmInterpreter(def).validate(doc).unsupported.map { it.toString().substringBefore(' ') }
    }

    @Test
    fun aRegistrationIsLookedUpOnlyWhenTheCallbackIsReached() {
        val actual = linkedMapOf(
            "unreached, dynamic Groovy" to kernel(null, dynamic = true),
            "unreached, static Java" to kernel(null, dynamic = false),
            "reached, dynamic Groovy" to kernel("checked", dynamic = true),
            "reached, static Java" to kernel("checked", dynamic = false),
        )
        assertEquals(linkedMapOf(
            "unreached, dynamic Groovy" to "fires []",
            "unreached, static Java" to "fires []",
            "reached, dynamic Groovy" to "fires [REVIEW|VALUE_ERROR]",
            "reached, static Java" to "fires [REVIEW|VALUE_ERROR]",
        ), actual)
    }

    /** The declared policy, locked so a change to it is a decision rather than drift. */
    @Test
    fun theInterpreterRefusesAnUnregisteredCallbackReachedOrNot() {
        assertEquals(
            listOf(true, true),
            listOf(null, "checked").map { note -> interpreterUnsupported(note).isNotEmpty() },
        )
    }
}
