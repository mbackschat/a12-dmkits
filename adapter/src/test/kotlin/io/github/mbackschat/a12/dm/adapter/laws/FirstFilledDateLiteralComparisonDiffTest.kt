package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.DateExpr
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Tri-engine differential: a dotted date literal compared with a `FirstFilledValue` of dates is a date, typed by
 * the operands the operator returns one of, as a literal beside a date field is (KERNEL-FINDINGS KF568).
 *
 * **The ladder discriminates a date reading from a lexical one.** `2025-06-30` sorts after `01.01.2026` as text
 * and before it as a date, so a comparison that left the literal as text fires the opposite rule of each pair.
 * Both carriers are measured: two plain date fields, and a keyed shipment date whose unmatched key falls through
 * to the promised date (a12-kernel-lean SPEC-2026-10-14-07, rule `c03`, reported the keyed row).
 *
 * Own domain (e-commerce delivery promises).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FirstFilledDateLiteralComparisonDiffTest {

    private companion object {
        const val ORDER = "/Order"
        const val REFERENCE = "/Order/Reference"
        const val PROMISED = "/Order/PromisedBy"
        const val REVISED = "/Order/RevisedBy"
        const val SHIPMENTS = "/Order/Shipments"
        const val TRACKING = "/Order/Shipments/Tracking"
        const val DELIVERED = "/Order/Shipments/DeliveredOn"
        const val LITERAL = "01.01.2026"
    }

    private val base = DocumentModelDraft.named("delivery-promise-first-date").locale("en_US")
        .rootGroup("Order") { o -> o
            .stringField("Reference") { }
            .dateField("PromisedBy") { it.format("yyyy-MM-dd") }
            .dateField("RevisedBy") { it.format("yyyy-MM-dd") }
            .group("Shipments") { s -> s.repeatable(3).indexField("Tracking")
                .stringField("Tracking") { }
                .dateField("DeliveredOn") { it.format("yyyy-MM-dd") } } }
        .build().toJson()

    private val bound = BoundModel.ofJson(base)

    private val plain = Dsl.firstFilledDate(bound.date(REVISED), bound.date(PROMISED))
    private val keyed = Dsl.firstFilledDate(bound.date(DELIVERED).semanticIndex("T1").index(), bound.field(PROMISED))

    private fun rule(code: String, first: DateExpr, after: Boolean) =
        bound.rule(bound.group(ORDER)).name("${code}Rule").errorField(bound.str(REFERENCE))
            .`when`(Dsl.fieldFilled(bound.field(REFERENCE)).and(
                if (after) first.after(Dsl.dateValue(LITERAL)) else first.before(Dsl.dateValue(LITERAL))))
            .code(code).severity(Severity.ERROR).message(Dsl.en(code)).build()

    private val rules = listOf(
        rule("PLAIN_AFTER", plain, after = true),
        rule("PLAIN_BEFORE", plain, after = false),
        rule("KEYED_AFTER", keyed, after = true),
        rule("KEYED_BEFORE", keyed, after = false),
    )

    private val modelJson = rules.fold(base) { json, r -> TypedRules.inject(json, r) }
    private val definition = ModelLoader.load(modelJson)
    private val staticEngine by lazy { RuntimeLaws.kernelJavaEngine(modelJson) }

    @Test
    fun theProbeModelIsAdmitted() =
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(modelJson))

    @Test
    fun aDottedLiteralBesideAFirstFilledDateIsADate() {
        val observed = linkedMapOf(
            "the key matches a March delivery" to fired(promised = "2025-06-30", delivered = "2026-03-01"),
            "the key matches nothing, promised in June 2025" to fired(promised = "2025-06-30", tracking = "T9"),
            "the key matches nothing, promised on the literal's day" to fired(promised = "2026-01-01", tracking = "T9"),
            "revised to February 2026 ahead of the promise" to fired(promised = "2025-06-30", revised = "2026-02-01"),
        )
        val expected = linkedMapOf(
            "the key matches a March delivery" to setOf("KEYED_AFTER", "PLAIN_BEFORE"),
            "the key matches nothing, promised in June 2025" to setOf("KEYED_BEFORE", "PLAIN_BEFORE"),
            "the key matches nothing, promised on the literal's day" to emptySet(),
            "revised to February 2026 ahead of the promise" to setOf("KEYED_BEFORE", "PLAIN_AFTER"),
        )
        assertEquals(expected, observed)
    }

    // ---- the instrument ----

    private fun fired(
        promised: String,
        delivered: String? = null,
        revised: String? = null,
        tracking: String = "T1",
    ): Set<String> {
        val codes = rules.map { it.code() }.toSet()
        var kernel = RuntimeLaws.doc().group(ORDER, 1).field(REFERENCE, "ORD-7781", 1, 1).field(PROMISED, promised, 1, 1)
            .group(SHIPMENTS, 1, 1).field(TRACKING, tracking, 1, 1, 1)
        var interpreter: Document = definition.newDocument().group(ORDER, 1).field(REFERENCE, "ORD-7781", 1, 1)
            .field(PROMISED, promised, 1, 1).group(SHIPMENTS, 1, 1).field(TRACKING, tracking, 1, 1, 1)
        delivered?.let {
            kernel = kernel.field(DELIVERED, it, 1, 1, 1)
            interpreter = interpreter.field(DELIVERED, it, 1, 1, 1)
        }
        revised?.let {
            kernel = kernel.field(REVISED, it, 1, 1)
            interpreter = interpreter.field(REVISED, it, 1, 1)
        }
        val dynamic = RuntimeLaws.messages(modelJson, kernel).map { it.code() }.filter { it in codes }.toSortedSet()
        val static = staticEngine.messages(staticEngine.instance(kernel)).map { it.code() }
            .filter { it in codes }.toSortedSet()
        val interpreted = DmInterpreter(definition).validateFull(interpreter).map { it.code }
            .filter { it in codes }.toSortedSet()
        assertEquals(dynamic, static, "generated Java vs normative dynamic-Groovy")
        assertEquals(dynamic, interpreted, "interpreter vs normative dynamic-Groovy")
        return dynamic
    }
}
