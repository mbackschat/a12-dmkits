package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.StringExpr
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * What an enumeration contributes to a text concatenation, authored through the typed `EnumRef.plus` (KF396 admits
 * a braced enumeration read beside a text whatever its labels). One enumeration's labels remap its values and the
 * other's repeat them, so the stored value and the label are told apart. Each concatenation is computed into a text
 * target and also compared in a rule, the immediate spelling `[Plan] + [Note]` beside its braced twin; all three
 * engines agree on every document and the computed texts and fired rules are pinned. A key-selected cell and a
 * first-filled value of a remapped enumeration join through the typed `EnumExpr.plus` the same way, and two category
 * projections join by their category values through `CategoryRef.plus`.
 *
 * Own domain (an insurance policy summary line).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EnumConcatenationDiffTest {

    private val model: String = run {
        val draft = DocumentModelDraft.named("policy-summary").locale("en_US")
            .rootGroup("Policy") { p ->
                p.stringField("Ref") { }
                p.stringField("Note") { }
                p.stringField("Key") { }
                p.enumField("Tier") { e ->
                    e.value("GOLD") { it.label("en_US", "Gold tier") }.value("SILVER") { it.label("en_US", "Silver tier") }
                        .category("Band", "TOP", "MID")
                }
                p.enumField("Plan") { e -> e.value("BASIC") { it.label("en_US", "BASIC") }.category("Band", "LOW") }
                p.group("Claims") { g -> g.repeatable(3).indexField("Code")
                    .stringField("Code") { }
                    .enumField("Cover") { e ->
                        e.value("FULL") { it.label("en_US", "Full cover") }.value("PART") { it.label("en_US", "Part cover") }
                    } }
                for (name in TARGETS) p.stringField(name) { }
            }.build().toJson()
        val session = EditSession.read(draft)
        val p = session.group("/Policy")
        val note = p.str("Note")
        val tier = p.enumOf("Tier")
        val lines: Map<String, StringExpr> = linkedMapOf(
            "TierFirst" to tier.plus(" / ").plus(note.asString()),
            "TierLast" to note.plus(" / ").plus(tier),
            "PlanFirst" to p.enumOf("Plan").plus(note.asString()),
            "TierPlan" to tier.plus(p.enumOf("Plan")),
            "KeyedCover" to p.enumOf("Claims/Cover").forIndex(p.field("Key")).plus(" / ").plus(note.asString()),
            "FirstCover" to Dsl.firstFilledEnum(p.flatten("Claims*/Cover")).plus(note.asString()),
            "BandPair" to tier.category("Band").plus(p.enumOf("Plan").category("Band")),
        )
        val ref = p.str("Ref")
        var json = session.toJson()
        for ((name, line) in lines) {
            json = TypedRules.inject(json, p.computation(p.str(name)) {
                it.name(name + "Comp").calc(line).message(Dsl.en("Check the summary.")).build()
            })
        }
        json = TypedRules.inject(json, p.rule {
            it.name("TierRefRule").code("TierRefRule").errorField(ref.field())
                .`when`(ref.filled().and(ref.eq(tier.plus("-1")))).message(Dsl.en("Check the summary.")).build()
        })
        json = TypedRules.inject(json, p.rule {
            it.name("PlanBracedRule").code("PlanBracedRule").errorField(note.field())
                .`when`(note.filled().and(note.ne(p.enumOf("Plan").plus(note.asString()))))
                .message(Dsl.en("Check the summary.")).build()
        })
        // The immediate spelling has no typed form, since the typed surface always braces; Plan's labels repeat
        // its values, so A12 admits it (KF396).
        json = RuntimeLaws.withRule(json, "/Policy", "PlanImmediateRule", "../Note", "PlanImmediateRule",
            "FieldFilled(Note) And [Note] != [Plan] + [Note]")
        json
    }

    private fun policy(vararg values: Pair<String, String>) =
        """{"Policy":{"Ref":"GOLD-1",""" + values.joinToString(",") { (k, v) -> "\"$k\":\"$v\"" } +
            (if (values.isEmpty()) "" else ",") + TARGETS.joinToString(",") { "\"$it\":\"x\"" } +
            ""","Key":"C1","Claims":[{"Code":"C1","Cover":"FULL"},{"Code":"C2"}]}}"""

    private val documents = linkedMapOf(
        "tier, plan and note" to policy("Tier" to "GOLD", "Plan" to "BASIC", "Note" to "n"),
        "tier without note" to policy("Tier" to "GOLD"),
        "note without tier" to policy("Note" to "n"),
        "nothing filled" to policy(),
    )

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        DocumentDifferential.assertAgree(model, documents)
    }

    /**
     * The computed texts on the kernel's dynamic strategy, and whether the comparison rule fired. `Tier`'s labels
     * remap its values, so `GOLD` rather than `Gold tier` is what tells the stored value from the label; an empty
     * enumeration contributes nothing, and a concatenation that is empty as a whole clears its target.
     */
    @Test
    fun anEnumerationJoinsATextAsItsStoredValue() {
        val observed = documents.mapValues { (_, doc) ->
            DocumentDifferential.kernel(model, doc, dynamic = true).let { o ->
                TARGETS.associateWith { o.outcomes[it] } to o.messages.map { it.code }.filter { it.endsWith("Rule") }.sorted()
            }
        }
        assertEquals(linkedMapOf(
            "tier, plan and note" to (texts("GOLD / n", "n / GOLD", "BASICn", "GOLDBASIC", "FULL / n", "FULLn",
                "TOPLOW") to listOf("PlanBracedRule", "PlanImmediateRule", "TierRefRule")),
            "tier without note" to (texts("GOLD / ", " / GOLD", null, "GOLD", "FULL / ", "FULL", "TOP") to
                listOf("TierRefRule")),
            "note without tier" to (texts(" / n", "n / ", "n", null, "FULL / n", "FULLn", null) to emptyList()),
            "nothing filled" to (texts(" / ", " / ", null, null, "FULL / ", "FULL", null) to emptyList()),
        ), observed)
    }

    private fun texts(vararg values: String?) =
        TARGETS.zip(values.map { if (it == null) "CLEARED" else "VALUE|$it" }).toMap()

    @Test
    fun theModelIsKernelConsistent() {
        assertEquals(emptyList<String>(), MessageParameterProbes.kernel(model))
    }

    private companion object {
        val TARGETS = listOf("TierFirst", "TierLast", "PlanFirst", "TierPlan", "KeyedCover", "FirstCover", "BandPair")
    }
}
