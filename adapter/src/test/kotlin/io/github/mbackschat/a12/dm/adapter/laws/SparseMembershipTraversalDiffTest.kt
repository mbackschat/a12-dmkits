package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.DocumentDifferential.underUtc
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldOperand
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** SPEC02: opposite-side collection, sparse search and prefix stops are independently observable. */
class SparseMembershipTraversalDiffTest {
    private enum class MemberOp { Any, None, NotAll;
        fun condition(values: List<FieldOperand>, subject: FieldOperand): Condition = when (this) {
            Any -> Dsl.atLeastOneFieldValueIncludedInValueList(values, subject)
            None -> Dsl.noFieldValueIncludedInValueList(values, subject)
            NotAll -> Dsl.notAllFieldValuesIncludedInValueList(values, subject)
        }
    }
    private enum class Carrier { Number, String }
    private enum class Side { Subjects, Values, Empty }
    private val targets = Carrier.entries.flatMap { kind -> Side.entries.flatMap { side ->
        MemberOp.entries.map { op -> "${kind.name}${side.name}${op.name}" }
    } } + listOf("BadNone", "BadNotAll")
    private val model = underUtc {
        val draft = DocumentModelDraft.named("dispatch-membership").locale("en_US")
            .rootGroup("Dispatch") { root ->
                for (name in listOf("Anchor", "Approved", "EmptyNumber", "Bad") + targets)
                    root.numberField(name) { it.fractionDigits(0) }
                root.stringField("Token") { }.stringField("EmptyString") { }
                root.group("Runs") { runs -> runs.repeatable(2).group("Parcels") { rows ->
                    rows.repeatable(51).numberField("Permit") { it.fractionDigits(0) }
                        .numberField("Charge") { it.fractionDigits(0) }.stringField("Label") { }
                        .dateTimeField("RecordedAt") { it.format("yyyy-MM-dd'T'HH:mm:ss") }
                } }
            }.build().toJson()
        val edit = EditSession.read(draft).also { it.config().timeZone("Europe/Berlin") }
        val root = edit.group("/Dispatch")
        val rows = edit.group("/Dispatch/Runs/Parcels")
        val having = rows.num("Permit").filled().or(rows.dateTime("RecordedAt").eq(Dsl.dateTimeValue("29.02.1500T10:00:00")))
        var json = edit.toJson()
        fun add(target: String, condition: Condition) {
            json = TypedRules.inject(json, root.rule { it.name(target + "Rule").code(target).errorField(root.num("Anchor"))
                .`when`(root.num("Anchor").filled().and(condition)).message(Dsl.en("Check dispatch members.")).build() })
            json = TypedRules.inject(json, root.computation(root.num(target)) { it.name(target + "Comp")
                .commonPrecondition(condition).calc(NumberLiteral.of("1")).message(Dsl.en("Check dispatch members.")).build() })
        }
        for (kind in Carrier.entries) {
            val selected = root.flatten(if (kind == Carrier.Number) "Runs*/Parcels*/Charge" else "Runs*/Parcels*/Label").having(having)
            val approved: FieldOperand = if (kind == Carrier.Number) root.num("Approved").field() else root.str("Token").field()
            val empty: FieldOperand = if (kind == Carrier.Number) root.num("EmptyNumber").field() else root.str("EmptyString").field()
            for (op in MemberOp.entries) {
                add("${kind.name}Subjects${op.name}", op.condition(listOf(approved), selected))
                add("${kind.name}Values${op.name}", op.condition(listOf(selected), approved))
                add("${kind.name}Empty${op.name}", if (op == MemberOp.NotAll) op.condition(listOf(selected), empty)
                    else op.condition(listOf(empty), selected))
            }
        }
        val selected = root.flatten("Runs*/Parcels*/Charge").having(having)
        for (op in listOf(MemberOp.None, MemberOp.NotAll)) add("Bad${op.name}",
            op.condition(listOf(root.num("Bad").field(), selected), root.num("Approved").field()))
        json
    }

    @Test
    fun membershipPassesKeepTheirPolicyAndSideSpecificStops() = underUtc {
        val subjects = listOf("TFF", "PPP", "TPP", "TFP", "PPP", "PPT")
        val values = listOf("TFF", "PPP", "TPP", "PPP", "PPP", "PPP")
        val empty = listOf("FTF", "FPF", "FPF", "FPF", "FPF", "FPF")
        val differences = mutableListOf<String>()
        for (case in subjects.indices) {
            val width = when (case) { 0 -> 1; 2, 4 -> 51; else -> 50 }
            val first = List(width) { "{\"Permit\":1${if (case == 3 || case == 5) ",\"Charge\":5,\"Label\":\"five\"" else ""}}" }.joinToString(",")
            val seeds = targets.joinToString(",") { "\"$it\":99" }
            val json = """{"Dispatch":{"Anchor":1,"Approved":${if (case == 5) 9 else 5},"Token":"${if (case == 5) "nine" else "five"}","Bad":"bad",$seeds,
                "Runs":[{"Parcels":[$first]},{"Parcels":[{"Permit":1,"Charge":5,"Label":"five"}${if (case == 4) ",{}" else ""}]}]}}"""
            val kernel = DocumentDifferential.kernel(model, json, dynamic = true)
            for (kind in Carrier.entries) for (side in Side.entries) for (op in MemberOp.entries) {
                val name = "${kind.name}${side.name}${op.name}"
                val expected = when (side) { Side.Subjects -> subjects; Side.Values -> values; Side.Empty -> empty }[case][op.ordinal]
                assertEquals(if (expected == 'T') "VALUE|1" else "CLEARED", kernel.outcomes[name], "$case/$name computation")
                assertEquals(when (expected) { 'T' -> listOf("OMISSION_ERROR"); 'P' -> listOf("VALUE_ERROR"); else -> emptyList() },
                    kernel.messages.filter { it.code == name }.map { it.type }, "$case/$name validation")
                assertEquals(expected == 'P', kernel.operandErrors.any { it.code == "berechnungsWertFehler" && it.pointer.endsWith("/$name") }, "$case/$name poison")
            }
            for (name in listOf("BadNone", "BadNotAll")) {
                assertEquals(emptyList<MessageSignature>(), kernel.messages.filter { it.code == name }, "$case/$name validation")
                assertEquals("CLEARED", kernel.outcomes[name], "$case/$name computation")
            }
            assertEquals(kernel, DocumentDifferential.kernel(model, json, dynamic = false), "case=$case strategies")
            val actual = DocumentDifferential.interpreter(model, json)
            if (actual != kernel) differences += "case=$case: expected=$kernel actual=$actual"
        }
        assertEquals(emptyList<String>(), differences)
    }
}
