package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What a text field compared with an enumeration decides at runtime, the comparison kernel 31.0 admitted for a
 * labelled enumeration (A12K-3969; admission in [KF655]). `Plan` labels its values (`PRO` → `Pro plan`) and `Channel`
 * labels none, so a text equal to the stored value and a text equal to the label are told apart, and the textless
 * enumeration is the control for whether labels take part at all. Each rule is authored through the typed
 * `EnumRef.eq(StringRef)` / `StringRef.ne(EnumRef)`; dynamic Groovy, generated Java and the interpreter are compared
 * on every document. Own domain (a subscription request).
 */
class TextEnumComparisonDiffTest {

    private companion object {
        const val SUB = "/Subscription"
        const val REQUESTED = "/Subscription/RequestedPlan"
        const val PLAN = "/Subscription/Plan"
        const val CHANNEL = "/Subscription/Channel"
        val CODES = listOf("PLAN_MATCH", "PLAN_MISMATCH", "CHANNEL_MATCH")
    }

    private val model: String = run {
        val base = DocumentModelDraft.named("subscription-request").locale("en_US")
            .rootGroup("Subscription") { s ->
                s.stringField("RequestedPlan") { }
                    .enumField("Plan") { e ->
                        e.value("BASIC") { it.label("en_US", "Basic plan") }.value("PRO") { it.label("en_US", "Pro plan") }
                    }
                    .enumField("Channel") { e -> e.value("WEB").value("STORE") }
            }.build().toJson()
        EditSession.read(base).also { edit ->
            edit.addRule { b ->
                val requested = b.str(REQUESTED)
                b.rule(b.group(SUB)).name("PlanMatch").code("PLAN_MATCH").errorField(requested)
                    .`when`(requested.filled().and(b.enumOf(PLAN).eq(requested))).severity(Severity.ERROR)
                    .message(Dsl.en("The requested plan is the plan.")).build()
            }
            edit.addRule { b ->
                val requested = b.str(REQUESTED)
                b.rule(b.group(SUB)).name("PlanMismatch").code("PLAN_MISMATCH").errorField(requested)
                    .`when`(requested.filled().and(requested.ne(b.enumOf(PLAN)))).severity(Severity.ERROR)
                    .message(Dsl.en("The requested plan is not the plan.")).build()
            }
            edit.addRule { b ->
                val requested = b.str(REQUESTED)
                b.rule(b.group(SUB)).name("ChannelMatch").code("CHANNEL_MATCH").errorField(requested)
                    .`when`(requested.filled().and(b.enumOf(CHANNEL).eq(requested))).severity(Severity.ERROR)
                    .message(Dsl.en("The requested plan names the channel.")).build()
            }
            edit.validate()
        }.toJson()
    }

    private fun fired(requested: String, plan: String?, channel: String?): List<String> {
        val definition = ModelLoader.load(model)
        val d = Both(RuntimeLaws.doc(), definition.newDocument()).group(SUB, 1).field(REQUESTED, requested, 1, 1)
        plan?.let { d.field(PLAN, it, 1, 1) }
        channel?.let { d.field(CHANNEL, it, 1, 1) }
        val dynamic = RuntimeLaws.messages(model, d.kDoc).map { it.code() }.filter { it in CODES }.sorted()
        val java = RuntimeLaws.kernelJavaEngine(model)
        val generated = java.messages(java.instance(d.kDoc)).map { it.code() }.filter { it in CODES }.sorted()
        val interpreted = DmInterpreter(definition).validateFull(d.iDoc).map { it.code }.filter { it in CODES }.sorted()
        assertEquals(dynamic, generated, "generated Java")
        assertEquals(dynamic, interpreted, "interpreter")
        return dynamic
    }

    @Test
    fun theModelIsAdmitted() = assertEquals(listOf<String>(), RuntimeLaws.consistencyMessages(model))

    @Test
    fun aTextComparesWithTheStoredValueNeverTheLabel() = assertEquals(
        linkedMapOf(
            "the stored value" to listOf("PLAN_MATCH"),
            "the label" to listOf("PLAN_MISMATCH"),
            "another value" to listOf("PLAN_MISMATCH"),
            // An empty enumeration decides neither `==` nor `!=` against a filled text.
            "no plan" to listOf(),
            "a textless enumeration's stored value" to listOf("CHANNEL_MATCH", "PLAN_MISMATCH"),
        ),
        linkedMapOf(
            "the stored value" to fired("PRO", "PRO", "STORE"),
            "the label" to fired("Pro plan", "PRO", "STORE"),
            "another value" to fired("BASIC", "PRO", "STORE"),
            "no plan" to fired("PRO", null, "STORE"),
            "a textless enumeration's stored value" to fired("WEB", "PRO", "WEB"),
        ),
    )
}
