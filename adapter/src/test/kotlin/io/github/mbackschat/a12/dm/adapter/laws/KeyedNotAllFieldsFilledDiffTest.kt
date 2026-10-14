package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Tri-engine differential: `NotAllFieldsFilled` over a keyed selection is undecided on a key two rows carry, and
 * fires OMISSION on a key no row carries, on both keyed carriers — a starred list below the key and a fixed group
 * below it (KERNEL-FINDINGS KF475, extended 2026-10-14 from a12-kernel-lean SPEC-2026-10-14-07).
 *
 * **What separates "undecided" from "decided false".** Alone, an undecided quantifier and a false one are both
 * silent; beside a plain operand they part: an empty sibling settles `NotAllFieldsFilled` whatever the keyed
 * part holds, so the rule fires on a duplicated key too, while a filled sibling leaves the keyed part to decide
 * and the duplicated key decides nothing. `NoFieldFilled` is KF475's measured control on the same documents.
 *
 * Own domain (e-commerce packages, each holding parcels and its dimensions).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KeyedNotAllFieldsFilledDiffTest {

    private companion object {
        const val ORDER = "/Order"
        const val REFERENCE = "/Order/Reference"
        const val NOTE = "/Order/Note"
        const val PACKAGES = "/Order/Packages"
        const val CODE = "/Order/Packages/Code"
        const val PARCELS = "/Order/Packages/Parcels"
        const val WEIGHT = "/Order/Packages/Parcels/Weight"
        const val DIMS = "/Order/Packages/Dims"
        const val LENGTH = "/Order/Packages/Dims/Length"
        const val WIDTH = "/Order/Packages/Dims/Width"
    }

    private val base = DocumentModelDraft.named("keyed-not-all-filled").locale("en_US")
        .rootGroup("Order") { o -> o
            .stringField("Reference") { }
            .stringField("Note") { }
            .group("Packages") { p -> p.repeatable(3).indexField("Code")
                .stringField("Code") { }
                .group("Parcels") { c -> c.repeatable(2).numberField("Weight") { it.fractionDigits(0) } }
                .group("Dims") { d -> d
                    .numberField("Length") { it.fractionDigits(0) }
                    .numberField("Width") { it.fractionDigits(0) } } } }
        .build().toJson()

    private val bound = BoundModel.ofJson(base)
    private val weights = bound.flatten("/Order/Packages/Parcels*/Weight").forIndex("P1")
    private val dims = bound.flattenGroup(DIMS).forIndex("P1")

    private fun rule(code: String, condition: Condition) =
        bound.rule(bound.group(ORDER)).name("${code}Rule").errorField(bound.str(REFERENCE))
            .`when`(Dsl.fieldFilled(bound.field(REFERENCE)).and(condition))
            .code(code).severity(Severity.ERROR).message(Dsl.en(code)).build()

    private val rules = listOf(
        rule("STAR", Dsl.notAllFieldsFilled(weights)),
        rule("GROUP", Dsl.notAllFieldsFilled(dims)),
        rule("BESIDE", Dsl.notAllFieldsFilled(bound.field(NOTE), weights)),
        rule("NONE", Dsl.noFieldFilled(weights)),
    )

    private val modelJson = rules.fold(base) { json, r -> TypedRules.inject(json, r) }
    private val definition = ModelLoader.load(modelJson)
    private val staticEngine by lazy { RuntimeLaws.kernelJavaEngine(modelJson) }

    /** One package row: its key, its parcel weights in order, and its dimensions when filled. */
    private class Pkg(val code: String, val weights: List<String>, val dims: Boolean = true)

    private val full = Pkg("P1", listOf("4", "6"))

    @Test
    fun theProbeModelIsAdmitted() =
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(modelJson))

    @Test
    fun aDuplicatedKeyLeavesNotAllFieldsFilledUndecided() {
        val observed = linkedMapOf(
            "P1 complete, note filled" to fired(listOf(full, Pkg("P2", emptyList())), note = true),
            "P1 missing its second parcel" to fired(listOf(Pkg("P1", listOf("4")), Pkg("P2", emptyList())), note = true),
            "P1 twice, both complete, note filled" to fired(listOf(full, full), note = true),
            "P1 twice, both complete, note empty" to fired(listOf(full, full), note = false),
            "no P1, note filled" to fired(listOf(Pkg("P2", listOf("4", "6"))), note = true),
        )
        val expected = linkedMapOf(
            "P1 complete, note filled" to emptyMap(),
            "P1 missing its second parcel" to mapOf("STAR" to "OMISSION", "BESIDE" to "OMISSION"),
            "P1 twice, both complete, note filled" to emptyMap(),
            "P1 twice, both complete, note empty" to mapOf("BESIDE" to "OMISSION"),
            "no P1, note filled" to mapOf("STAR" to "OMISSION", "GROUP" to "OMISSION", "BESIDE" to "OMISSION",
                "NONE" to "OMISSION"),
        )
        assertEquals(expected, observed)
    }

    // ---- the instrument ----

    private fun fired(packages: List<Pkg>, note: Boolean): Map<String, String> {
        val codes = rules.map { it.code() }.toSet()
        var kernel = RuntimeLaws.doc().group(ORDER, 1).field(REFERENCE, "ORD-4410", 1, 1)
        var interpreter: Document = definition.newDocument().group(ORDER, 1).field(REFERENCE, "ORD-4410", 1, 1)
        fun put(path: String, value: String, vararg reps: Int) {
            kernel = kernel.field(path, value, *reps)
            interpreter = interpreter.field(path, value, *reps)
        }
        fun open(path: String, vararg reps: Int) {
            kernel = kernel.group(path, *reps)
            interpreter = interpreter.group(path, *reps)
        }
        if (note) put(NOTE, "gift wrap", 1, 1)
        packages.forEachIndexed { i, pkg ->
            val row = i + 1
            open(PACKAGES, 1, row)
            put(CODE, pkg.code, 1, row, 1)
            pkg.weights.forEachIndexed { j, weight ->
                open(PARCELS, 1, row, j + 1)
                put(WEIGHT, weight, 1, row, j + 1, 1)
            }
            if (pkg.dims) {
                open(DIMS, 1, row, 1)
                put(LENGTH, "30", 1, row, 1, 1)
                put(WIDTH, "20", 1, row, 1, 1)
            }
        }
        fun label(omission: Boolean) = if (omission) "OMISSION" else "VALUE"
        val dynamic = RuntimeLaws.messages(modelJson, kernel).filter { it.code() in codes }
            .associate { it.code() to label(it.type().name.startsWith("OMISSION")) }
        val static = staticEngine.messages(staticEngine.instance(kernel)).filter { it.code() in codes }
            .associate { it.code() to label(it.type().name.startsWith("OMISSION")) }
        val interpreted = DmInterpreter(definition).validateFull(interpreter).filter { it.code in codes }
            .associate { it.code to label(it.type == MsgType.OMISSION) }
        assertEquals(dynamic, static, "generated Java vs normative dynamic-Groovy")
        assertEquals(dynamic, interpreted, "interpreter vs normative dynamic-Groovy")
        return dynamic
    }
}
