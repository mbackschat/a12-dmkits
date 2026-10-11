package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Empty authored patterns fail when reached (SPEC-2026-10-10-13). */
class EmptyAuthoredPatternDiffTest {
    private fun model(pattern: String, matched: Boolean): String {
        val base = DocumentModelDraft.named("parcel-pattern-execution").locale("en_US")
            .rootGroup("Parcel") { p ->
                p.stringField("Ref") { }
                p.stringField("Label") { it.pattern("", null) }
                p.group("Legs") { l -> l.repeatable(2).stringField("Label") { it.pattern("", null) }
                    .numberField("Weight") { it.fractionDigits(0) } }
                for (name in listOf("Reached", "Hidden", "FollowingFalse", "TruePrefix", "Filtered", "Independent")) {
                    p.numberField(name) { it.fractionDigits(0) }
                }
            }.build().toJson()
        val session = EditSession.read(base)
        val p = session.group("/Parcel")
        val label = p.str("Label")
        val ref = p.str("Ref")
        val failing = if (matched) label.matches(pattern) else label.violates(pattern)
        val conditions = linkedMapOf(
            "Reached" to failing,
            "Hidden" to ref.eq("hide").and(failing),
            "FollowingFalse" to failing.and(ref.eq("hide")),
            "TruePrefix" to ref.filled().or(failing),
        )
        var json = session.toJson()
        for ((name, condition) in conditions) {
            json = TypedRules.inject(json, p.rule { it.name(name + "Rule").code(name)
                .errorField(ref.field()).`when`(ref.filled().and(condition)).message(Dsl.en("Review the parcel label.")).build() })
            json = TypedRules.inject(json, p.computation(p.num(name)) { it.name(name + "Comp")
                .`when`(condition).calc(NumberLiteral.of(2)).message(Dsl.en("Review the label result.")).build() })
        }
        val legs = session.group("/Parcel/Legs")
        val filter = if (matched) legs.str("Label").matches(pattern) else legs.str("Label").violates(pattern)
        json = TypedRules.inject(json, p.computation(p.num("Filtered")) { it.name("FilteredComp")
            .calc(Dsl.sum(p.flatten("Legs*/Weight").having(filter))).message(Dsl.en("Review the leg weights.")).build() })
        return TypedRules.inject(json, p.computation(p.num("Independent")) { it.name("IndependentComp")
            .calc(NumberLiteral.of(7)).message(Dsl.en("Review the independent value.")).build() })
    }

    @Test
    fun everyReachedAndHiddenCarrierAgreesWithBothStrategies() {
        val documents = linkedMapOf(
            "filled" to """{"Parcel":{"Ref":"parcel","Label":"ready","Legs":[{"Weight":3,"Label":"ready"}],"Reached":99,"Filtered":99}}""",
            "empty" to """{"Parcel":{"Ref":"parcel","Label":"","Legs":[{"Weight":3,"Label":""}],"Reached":99,"Filtered":99}}""",
            "absent" to """{"Parcel":{"Ref":"parcel","Reached":99,"Filtered":99}}""",
        )
        for (pattern in listOf("", ".*")) for (matched in listOf(false, true)) {
            val json = model(pattern, matched)
            assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(json))
            for ((name, document) in documents) {
                val oracle = DocumentDifferential.kernel(json, document, dynamic = true)
                assertEquals("VALUE|7", oracle.outcomes["Independent"], "$name independent continuation")
                assertEquals(pattern.isEmpty(), oracle.operandErrors.any {
                    it.code == "berechnungsWertFehler" && it.pointer.endsWith("/Reached")
                }, "$name reached execution failure")
                assertEquals(false, oracle.operandErrors.any { it.pointer.endsWith("/Hidden") },
                    "$name false prefix hides execution failure")
                assertEquals(oracle, DocumentDifferential.kernel(json, document, dynamic = false), name)
                assertEquals(oracle, DocumentDifferential.interpreter(json, document), name)
            }
        }
    }
}
