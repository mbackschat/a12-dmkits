package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Where and how often A12 reports a reached `CustomCondition` nobody registered (a12-kernel-lean SPEC-2026-10-22-03,
 * SPEC-2026-10-22-04; KERNEL-FINDINGS KF52): once per rule, as VALUE, at the iterator's first error address whichever
 * row reached the name, after the earlier rows' own firings, with a partial call starting where its relevant
 * error-field selectors start it, and with a name behind a holding `Or` reached by the classification pass.
 *
 * Both Kernel strategies are measured through [KernelRuntimeProbe] on both channels and must agree. The interpreter
 * does not emulate this: it refuses an unregistered name when it prepares the rule, reached or not (KF52's declared
 * policy), and [theInterpreterRefusesEveryRuleNamingTheMissingCallback] pins that beside the Kernel's answer.
 *
 * Own domain: an order's lines, and the stock and reservations two warehouses hold for it.
 */
class CustomConditionUnregisteredReportLawsTest {

    @Test
    fun a12ReportsEachRuleOnceAtItsIteratorsFirstAddress() {
        assertEquals(EXPECTED, artifact.path("rows").associate { row ->
            row.path("name").asText() to listOf("messages", "partialMessages").associateWith { channel ->
                row.path("engines").path(0).path(channel).map { m ->
                    m.path("code").asText() + "@" + m.path("field").asText() + "|" + m.path("type").asText() +
                        if (m.path("referenced").isEmpty) "" else "|referenced"
                }
            }
        })
        artifact.path("rows").forEach { row ->
            assertTrue(row.path("enginesAgree").asBoolean(), "both strategies on ${row.path("name").asText()}")
        }
    }

    /** KF52's declared policy, on every rule of this model: each names the unregistered callback, so each is refused. */
    @Test
    fun theInterpreterRefusesEveryRuleNamingTheMissingCallback() {
        val def = ModelLoader.load(model)
        val unsupported = DmInterpreter(def).validate(def.newDocument()).unsupported.map { it.rulePath }
        assertEquals(RULES.map { "/Order/${it.group}${it.name}" }.sorted(), unsupported.sorted())
    }

    private class RuleSpec(val group: String, val name: String, val errorField: String)

    companion object {
        private val MAPPER = ObjectMapper()

        @JvmStatic
        @TempDir
        lateinit var dir: Path

        private val RULES = listOf(
            RuleSpec("Lines/", "SkuReach", "Sku"), RuleSpec("Lines/", "QtyReach", "Qty"),
            RuleSpec("Lines/", "EitherReach", "Qty"), RuleSpec("", "LineQtyReach", "Lines/Qty"),
            RuleSpec("", "StockReach", "Stock/Count"), RuleSpec("", "StageReach", "Stage"),
        )

        private val model: String by lazy {
            val base = DocumentModelDraft.named("order-unregistered-callback").locale("en_US")
                .rootGroup("Order") { o -> o
                    .stringField("Note") { }
                    .stringField("Stage") { }
                    .group("Lines") { l -> l.repeatable(5)
                        .stringField("Sku") { }
                        .numberField("Qty") { n -> n.fractionDigits(0) } }
                    .group("Stock") { s -> s.repeatable(3).indexField("Warehouse")
                        .stringField("Warehouse") { }
                        .numberField("Count") { n -> n.fractionDigits(0) } }
                    .group("Reserve") { r -> r.repeatable(3).indexField("Warehouse")
                        .stringField("Warehouse") { }
                        .numberField("Count") { n -> n.fractionDigits(0) } } }
                .build().toJson()
            val m = BoundModel.ofJson(base)
            val ghost = Dsl.customCondition("Ghost")
            val sku = m.str("/Order/Lines/Sku")
            val qty = m.num("/Order/Lines/Qty")
            val conditions = mapOf(
                "SkuReach" to sku.filled().and(ghost),
                "QtyReach" to qty.gt(1).and(ghost),
                "EitherReach" to sku.filled().or(qty.filled().and(ghost)),
                "LineQtyReach" to qty.gt(1).and(ghost),
                "StockReach" to Dsl.fieldsNotCollectivelyFilled(m.field("/Order/Stock/Count"), m.field("/Order/Reserve/Count"))
                    .and(ghost),
                "StageReach" to m.str("/Order/Stage").notFilled().or(ghost),
            )
            RULES.fold(base) { json, rule ->
                val group = "/Order" + if (rule.group.isEmpty()) "" else "/" + rule.group.removeSuffix("/")
                RuntimeLaws.withRule(json, group, rule.name, "../${rule.errorField}", rule.name,
                    RuntimeLaws.render(group, conditions.getValue(rule.name)))
            }
        }

        private const val LINES_FULL = """[{"Sku":"a","Qty":"1"},{"Sku":"b","Qty":"2"},{"Sku":"c","Qty":"3"}]"""

        private val DOCUMENTS = mapOf(
            "full" to """{"Order":{"Note":"n","Stage":"s","Lines":$LINES_FULL,""" +
                """"Stock":[{"Warehouse":"x","Count":"1"},{"Warehouse":"y"}],""" +
                """"Reserve":[{"Warehouse":"x","Count":"2"},{"Warehouse":"y","Count":"3"}]}}""",
            "keep" to """{"Order":{"Note":"n","Lines":[{"Sku":"a","Qty":"1"},{"Qty":"2"},{"Sku":"c","Qty":"3"}]}}""",
            "rows" to """{"Order":{"Note":"n","Lines":[{"Sku":"a","Qty":"1"},{"Sku":"b","Qty":"1"},{"Sku":"c","Qty":"3"}],""" +
                """"Stock":[{"Warehouse":"x","Count":"1"},{"Warehouse":"y","Count":"4"}],""" +
                """"Reserve":[{"Warehouse":"x","Count":"2"},{"Warehouse":"y","Count":"3"}]}}""",
            "join-order" to """{"Order":{"Note":"n","Stage":"s","Stock":[{"Warehouse":"y"},{"Warehouse":"x","Count":"1"}],""" +
                """"Reserve":[{"Warehouse":"x","Count":"2"},{"Warehouse":"y","Count":"3"}]}}""",
            "reserve-first" to """{"Order":{"Note":"n","Stage":"s","Stock":[{"Warehouse":"B"}],""" +
                """"Reserve":[{"Warehouse":"A"},{"Warehouse":"B","Count":"2"}]}}""",
        )

        /** Row name, document, and the relevant set's entries. */
        private val ROWS = listOf(
            Triple("whole", "full", """[{"path":"/Order"}]"""),
            Triple("third-line", "full", """[{"path":"/Order/Lines/Qty","reps":[1,3,1]}]"""),
            Triple("keep", "keep", """[{"path":"/Order"}]"""),
            Triple("second-and-third-line", "rows",
                """[{"path":"/Order/Lines/Qty","reps":[1,2,1]},{"path":"/Order/Lines/Qty","reps":[1,3,1]}]"""),
            Triple("join-order", "join-order", """[{"path":"/Order"}]"""),
            Triple("reserve-first", "reserve-first", """[{"path":"/Order"}]"""),
        )

        private val artifact: JsonNode by lazy {
            val modelFile = Files.writeString(dir.resolve("order.model.json"), model)
            DOCUMENTS.forEach { (name, json) -> Files.writeString(dir.resolve("$name.document.json"), json) }
            val rows = ROWS.joinToString(",") { (name, document, relevant) ->
                """{"name":"$name","document":"$document.document.json","relevant":$relevant}"""
            }
            val request = Files.writeString(dir.resolve("request.json"),
                """{"model":"${modelFile.fileName}","observe":["validateFull","validatePart"],"rows":[$rows]}""")
            val out = dir.resolve("artifact.json")
            KernelRuntimeProbe.main(arrayOf(request.toString(), out.toString()))
            MAPPER.readTree(Files.readString(out))
        }

        private val EXPECTED: Map<String, Map<String, List<String>>> = mapOf(
            "whole" to mapOf(
                "messages" to listOf(
                    "EitherReach@Order/Lines/Qty|VALUE_ERROR|referenced",
                    "EitherReach@Order/Lines[2]/Qty|VALUE_ERROR|referenced",
                    "EitherReach@Order/Lines[3]/Qty|VALUE_ERROR|referenced",
                    "LineQtyReach@Order/Lines/Qty|VALUE_ERROR",
                    "QtyReach@Order/Lines/Qty|VALUE_ERROR",
                    "SkuReach@Order/Lines/Sku|VALUE_ERROR",
                    "StageReach@Order/Stage|VALUE_ERROR",
                    "StockReach@Order/Stock/Count|VALUE_ERROR",
                ),
                "partialMessages" to listOf(
                    "EitherReach@Order/Lines/Qty|VALUE_ERROR|referenced",
                    "EitherReach@Order/Lines[2]/Qty|VALUE_ERROR|referenced",
                    "EitherReach@Order/Lines[3]/Qty|VALUE_ERROR|referenced",
                    "LineQtyReach@Order/Lines/Qty|VALUE_ERROR",
                    "QtyReach@Order/Lines/Qty|VALUE_ERROR",
                    "SkuReach@Order/Lines/Sku|VALUE_ERROR",
                    "StageReach@Order/Stage|VALUE_ERROR",
                    "StockReach@Order/Stock/Count|VALUE_ERROR",
                ),
            ),
            "third-line" to mapOf(
                "messages" to listOf(
                    "EitherReach@Order/Lines/Qty|VALUE_ERROR|referenced",
                    "EitherReach@Order/Lines[2]/Qty|VALUE_ERROR|referenced",
                    "EitherReach@Order/Lines[3]/Qty|VALUE_ERROR|referenced",
                    "LineQtyReach@Order/Lines/Qty|VALUE_ERROR",
                    "QtyReach@Order/Lines/Qty|VALUE_ERROR",
                    "SkuReach@Order/Lines/Sku|VALUE_ERROR",
                    "StageReach@Order/Stage|VALUE_ERROR",
                    "StockReach@Order/Stock/Count|VALUE_ERROR",
                ),
                "partialMessages" to listOf(
                    "EitherReach@Order/Lines[3]/Qty|VALUE_ERROR",
                    "LineQtyReach@Order/Lines[3]/Qty|VALUE_ERROR",
                    "QtyReach@Order/Lines[3]/Qty|VALUE_ERROR",
                ),
            ),
            "keep" to mapOf(
                "messages" to listOf(
                    "EitherReach@Order/Lines/Qty|VALUE_ERROR",
                    "EitherReach@Order/Lines/Qty|VALUE_ERROR|referenced",
                    "LineQtyReach@Order/Lines/Qty|VALUE_ERROR",
                    "QtyReach@Order/Lines/Qty|VALUE_ERROR",
                    "SkuReach@Order/Lines/Sku|VALUE_ERROR",
                    "StageReach@Order/Stage|VALUE_ERROR",
                ),
                "partialMessages" to listOf(
                    "EitherReach@Order/Lines/Qty|VALUE_ERROR",
                    "EitherReach@Order/Lines/Qty|VALUE_ERROR|referenced",
                    "LineQtyReach@Order/Lines/Qty|VALUE_ERROR",
                    "QtyReach@Order/Lines/Qty|VALUE_ERROR",
                    "SkuReach@Order/Lines/Sku|VALUE_ERROR",
                    "StageReach@Order/Stage|VALUE_ERROR",
                ),
            ),
            "second-and-third-line" to mapOf(
                "messages" to listOf(
                    "EitherReach@Order/Lines/Qty|VALUE_ERROR|referenced",
                    "EitherReach@Order/Lines[2]/Qty|VALUE_ERROR|referenced",
                    "EitherReach@Order/Lines[3]/Qty|VALUE_ERROR|referenced",
                    "LineQtyReach@Order/Lines/Qty|VALUE_ERROR",
                    "QtyReach@Order/Lines/Qty|VALUE_ERROR",
                    "SkuReach@Order/Lines/Sku|VALUE_ERROR",
                    "StageReach@Order/Stage|VALUE_ERROR",
                ),
                "partialMessages" to listOf(
                    "EitherReach@Order/Lines/Qty|VALUE_ERROR",
                    "LineQtyReach@Order/Lines/Qty|VALUE_ERROR",
                    "QtyReach@Order/Lines/Qty|VALUE_ERROR",
                ),
            ),
            "join-order" to mapOf(
                "messages" to listOf(
                    "StageReach@Order/Stage|VALUE_ERROR",
                    "StockReach@Order/Stock[2]/Count|VALUE_ERROR",
                ),
                "partialMessages" to listOf(
                    "StageReach@Order/Stage|VALUE_ERROR",
                    "StockReach@Order/Stock[2]/Count|VALUE_ERROR",
                ),
            ),
            "reserve-first" to mapOf(
                "messages" to listOf(
                    "StageReach@Order/Stage|VALUE_ERROR",
                    "StockReach@/Order[1]/Stock[-5]/Count|VALUE_ERROR",
                ),
                "partialMessages" to listOf(
                    "StageReach@Order/Stage|VALUE_ERROR",
                    "StockReach@/Order[1]/Stock[-5]/Count|VALUE_ERROR",
                ),
            ),
        )
    }
}
