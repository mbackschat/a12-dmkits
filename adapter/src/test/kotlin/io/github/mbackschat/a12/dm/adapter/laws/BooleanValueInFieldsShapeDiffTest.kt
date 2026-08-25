package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * a12-kernel-lean **SPEC-2026-08-24-04**, the shape half — `NumberOfValueInFields` over a Boolean-valued group
 * operand across **one to three declarations, both admitted kinds, and both kind orders**, with the exact count
 * asserted for each.
 *
 * **What varies and what is held fixed.** Every group is fully filled with matching cells, so the only variable is
 * the group's DECLARATION SHAPE. That is what makes the counts `1`, `2`, `3` load-bearing: an evaluator that read
 * the operand as one cell, or expanded only the first declaration, or expanded recursively past the group, gives a
 * different number on at least one row while a same-arity fixture would hide it.
 *
 * **The kind orders are separate rows because the kernel's gate is constant-specific.** `True` admits Boolean and
 * Confirm declarations, so `Boolean, Confirm` and `Confirm, Boolean` are both legal and are both measured — an
 * order effect would show up as a disagreement between those two rows alone. `False` admits Boolean declarations
 * only, so its rows vary arity rather than kind; the refusal of a Confirm under `False` is a static admission fact
 * and lives with the kernel's consistency oracle in `validate.laws.ValueInFieldsBooleanKindGateLawsTest` rather
 * than here.
 *
 * Groups are non-repeatable and hold no deeper repeatable group, which is the boundary the peer entry measured.
 */
class BooleanValueInFieldsShapeDiffTest {

    private val base = DocumentModelDraft.named("order-boolean-shapes").locale("en_US")
        .rootGroup("Order") { order ->
            for (shape in SHAPES) {
                order.numberField(shape.target) { it.fractionDigits(0) }
                order.group(shape.group) { g -> shape.kinds.forEachIndexed { i, k -> g.field("F${i + 1}", k) } }
            }
        }.build().toJson()

    private val bound = BoundModel.ofJson(base)

    private val modelJson = SHAPES.fold(base) { json, shape ->
        TypedRules.inject(
            json,
            bound.computation(bound.num("$ROOT/${shape.target}")).name("${shape.target}Tally")
                .calc(
                    Dsl.numberOfValueInFields(shape.constant, bound.flattenGroup("$ROOT/${shape.group}"))
                        .expression(),
                )
                .message(Dsl.en("Flags matching ${shape.constant} in ${shape.group}.")).build(),
        )
    }

    private val definition = ModelLoader.load(modelJson)
    private val javaEngine by lazy { RuntimeLaws.kernelJavaEngine(modelJson) }

    /** Fill every declaration of every shape with its matching token, then read the four-engine agreement. */
    @Test fun theCountIsTheGroupsDeclarationCountOnEveryShape() {
        fun kernelDoc(): RuntimeLaws.Doc {
            val d = RuntimeLaws.doc().group(ROOT, 1)
            for (shape in SHAPES) {
                d.field("$ROOT/${shape.target}", SEED, 1, 1)
                d.group("$ROOT/${shape.group}", 1, 1)
                shape.kinds.indices.forEach { i ->
                    d.field("$ROOT/${shape.group}/F${i + 1}", shape.constant.toString(), 1, 1, 1)
                }
            }
            return d
        }
        val interpreterDoc = definition.newDocument().group(ROOT, 1)
        for (shape in SHAPES) {
            interpreterDoc.field("$ROOT/${shape.target}", SEED, 1, 1)
            interpreterDoc.group("$ROOT/${shape.group}", 1, 1)
            shape.kinds.indices.forEach { i ->
                interpreterDoc.field("$ROOT/${shape.group}/F${i + 1}", shape.constant.toString(), 1, 1, 1)
            }
        }

        val dynamic = RuntimeLaws.compute(modelJson, kernelDoc()).map { render(it.path(), it.value().orElse(null)) }
            .sorted()
        val static = javaEngine.compute(javaEngine.instance(kernelDoc()))
            .map { render(it.path(), it.value().orElse(null)) }.sorted()
        val interpreter = DmInterpreter(definition).compute(interpreterDoc)
            .map { render(it.path, if (it.outcome == ComputeOutcome.VALUE) it.value else it.outcome.name) }.sorted()

        assertEquals(dynamic, static, "dynamic-Groovy vs generated Java")
        assertEquals(dynamic, interpreter, "kernel vs INTERPRETER")
        assertEquals(EXPECTED, dynamic, "the exact count per declaration shape, on BOTH kernel strategies")
    }

    private fun render(path: String, value: String?) = "${path.removePrefix("$ROOT/")}=$value"

    /** One measured declaration shape: the counted constant, the operand group's declared kinds, and its target. */
    private data class Shape(val target: String, val group: String, val constant: Boolean, val kinds: List<FieldKind>)

    private companion object {
        const val ROOT = "/Order"
        const val SEED = "9"
        val SHAPES = listOf(
            Shape("TrueOne", "TrueOneFlags", true, listOf(FieldKind.BOOLEAN)),
            Shape("TrueBoolConfirm", "TrueBoolConfirmFlags", true, listOf(FieldKind.BOOLEAN, FieldKind.CONFIRM)),
            Shape("TrueConfirmBool", "TrueConfirmBoolFlags", true, listOf(FieldKind.CONFIRM, FieldKind.BOOLEAN)),
            Shape(
                "TrueThree", "TrueThreeFlags", true,
                listOf(FieldKind.BOOLEAN, FieldKind.CONFIRM, FieldKind.BOOLEAN),
            ),
            Shape("FalseOne", "FalseOneFlags", false, listOf(FieldKind.BOOLEAN)),
            Shape("FalseTwo", "FalseTwoFlags", false, listOf(FieldKind.BOOLEAN, FieldKind.BOOLEAN)),
            Shape(
                "FalseThree", "FalseThreeFlags", false,
                listOf(FieldKind.BOOLEAN, FieldKind.BOOLEAN, FieldKind.BOOLEAN),
            ),
        )
        val EXPECTED = listOf(
            "FalseOne=1", "FalseThree=3", "FalseTwo=2",
            "TrueBoolConfirm=2", "TrueConfirmBool=2", "TrueOne=1", "TrueThree=3",
        )
    }
}
