package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * a12-kernel-lean **SPEC-2026-08-24-05** — `NumberOfValueInFields` over an **UNSTARRED, non-repeatable (fixed)**
 * group operand, on the COMPUTATION route.
 *
 * **Why this shape has its own file.** The starred group operand is locked by `GroupStarValueInFieldsDiffTest`
 * and the interpreter expands it. The unstarred one was authorable — `Dsl.numberOfValueInFields` takes any
 * `GroupListRef` and `GroupOperandCarrierAdmissionLawsTest` measures the kernel's ACCEPT — while
 * `ExprEval.numberOfValueInFields` routed its operand tail through `expandGroupStars`, which by contract leaves
 * an unstarred group untouched. The fallback arm then asked `filled` of a group path, where no field is declared,
 * so the count was `0` on every document. That is the worse half of [IF287]'s class: an admitted shape whose
 * evaluator silently disagrees, rather than a loud missing factory.
 *
 * **Both Boolean constants, because their kind gates differ.** `True` admits `Boolean` and `Confirm` declarations;
 * `False` admits `Boolean` only, so the two operand groups cannot be one group. Each target is a direct
 * computation of the count — no arithmetic wrapper that could resolve the operand first.
 *
 * **The seed is what makes `CLEARED` observable at all**, and the malformed row is what makes the fixture
 * non-inert: without it, a stuck `0` reads exactly like a correct count over empty cells. Its genuineness is
 * asserted separately below, in the same document, so the compute row cannot be satisfied by a fixture whose
 * "malformed" token was quietly legal.
 */
class FixedGroupValueInFieldsComputeDiffTest {

    private val base = DocumentModelDraft.named("order-fixed-flag-count").locale("en_US")
        .rootGroup("Order") { order ->
            order.numberField("ApprovalCount") { it.fractionDigits(0) }
            order.numberField("DeclinedCount") { it.fractionDigits(0) }
            order.group("Approvals") { g ->
                g.field("Expedited", FieldKind.BOOLEAN)
                g.field("TermsAccepted", FieldKind.CONFIRM)
            }
            order.group("Declines") { g ->
                g.field("GiftWrap", FieldKind.BOOLEAN)
                g.field("Insurance", FieldKind.BOOLEAN)
            }
        }.build().toJson()

    private val edit = EditSession.read(base)
    private val order = edit.group(ROOT)

    /** `True` counts the Boolean+Confirm group; `False` counts the two-Boolean group. Relative nav off the editor. */
    private val modelJson = listOf(
        order.computation(order.num("ApprovalCount")) {
            it.name("ApprovalTally")
                .calc(Dsl.numberOfValueInFields(true, order.flattenGroup("Approvals")).expression())
                .message(Dsl.en("Approvals confirmed on this order.")).build()
        },
        order.computation(order.num("DeclinedCount")) {
            it.name("DeclineTally")
                .calc(Dsl.numberOfValueInFields(false, order.flattenGroup("Declines")).expression())
                .message(Dsl.en("Options declined on this order.")).build()
        },
    ).fold(base) { json, computation -> TypedRules.inject(json, computation) }

    private val definition = ModelLoader.load(modelJson)
    private val javaEngine by lazy { RuntimeLaws.kernelJavaEngine(modelJson) }

    /** One (Approvals, Declines) content pair on all three engines, which must agree. */
    private fun computed(expedited: String?, terms: String?, giftWrap: String?, insurance: String?): String {
        val kernelDocument = RuntimeLaws.doc().group(ROOT, 1)
            .field(APPROVAL_COUNT, SEED, 1, 1).field(DECLINED_COUNT, SEED, 1, 1)
            .group(APPROVALS, 1, 1).group(DECLINES, 1, 1)
        if (expedited != null) kernelDocument.field(EXPEDITED, expedited, 1, 1, 1)
        if (terms != null) kernelDocument.field(TERMS, terms, 1, 1, 1)
        if (giftWrap != null) kernelDocument.field(GIFT_WRAP, giftWrap, 1, 1, 1)
        if (insurance != null) kernelDocument.field(INSURANCE, insurance, 1, 1, 1)

        val interpreterDocument = definition.newDocument().group(ROOT, 1)
            .field(APPROVAL_COUNT, SEED, 1, 1).field(DECLINED_COUNT, SEED, 1, 1)
            .group(APPROVALS, 1, 1).group(DECLINES, 1, 1)
        if (expedited != null) interpreterDocument.field(EXPEDITED, expedited, 1, 1, 1)
        if (terms != null) interpreterDocument.field(TERMS, terms, 1, 1, 1)
        if (giftWrap != null) interpreterDocument.field(GIFT_WRAP, giftWrap, 1, 1, 1)
        if (insurance != null) interpreterDocument.field(INSURANCE, insurance, 1, 1, 1)

        val dynamic = RuntimeLaws.compute(modelJson, kernelDocument).filter { it.path() in TARGETS }
            .map { outcome(it.path(), it.value().orElse(null), it.cleared(), it.errored()) }.sorted()
        val static = javaEngine.compute(javaEngine.instance(kernelDocument)).filter { it.path() in TARGETS }
            .map { outcome(it.path(), it.value().orElse(null), it.cleared(), it.errored()) }.sorted()
        val interpreter = DmInterpreter(definition).compute(interpreterDocument).filter { it.path in TARGETS }
            .map {
                when (it.outcome) {
                    ComputeOutcome.VALUE -> "${it.path}|VALUE|${it.value}"
                    ComputeOutcome.CLEARED -> "${it.path}|CLEARED"
                    ComputeOutcome.ERRORED -> "${it.path}|ERRORED"
                }
            }.sorted()

        assertEquals(dynamic, static, "dynamic-Groovy vs generated Java")
        assertEquals(dynamic, interpreter, "kernel vs INTERPRETER")
        return dynamic.ifEmpty { listOf("(no outcome)") }.joinToString(",")
    }

    private fun outcome(path: String, value: String?, cleared: Boolean, errored: Boolean): String = when {
        cleared -> "$path|CLEARED"
        errored -> "$path|ERRORED"
        else -> "$path|VALUE|$value"
    }

    /**
     * THE CONTROL THAT DECIDES WHETHER THE MALFORMED ROW MEANS ANYTHING. `"maybe"` in a Boolean/Confirm cell must
     * draw the kernel's own token formal error in the very document the compute row uses; otherwise a fixture with
     * a quietly legal token would report the same channel and the finding would be worthless.
     */
    @Test fun theMalformedTokensAreGenuinelyFormallyInvalidInTheSameDocument() {
        val kernelDocument = RuntimeLaws.doc().group(ROOT, 1)
            .field(APPROVAL_COUNT, SEED, 1, 1).field(DECLINED_COUNT, SEED, 1, 1)
            .group(APPROVALS, 1, 1).group(DECLINES, 1, 1)
            .field(EXPEDITED, "true", 1, 1, 1).field(TERMS, "maybe", 1, 1, 1)
            .field(GIFT_WRAP, "false", 1, 1, 1).field(INSURANCE, "maybe", 1, 1, 1)
        assertEquals(
            listOf(
                "feldJaFalsch@/Order[1]/Approvals[1]/TermsAccepted",
                "feldJaNeinFalsch@/Order[1]/Declines[1]/Insurance",
            ),
            RuntimeLaws.messages(modelJson, kernelDocument).map { "${it.code()}@${it.field()}" }.sorted(),
            "both malformed tokens must be formally invalid — a Confirm cell draws the yes-only code",
        )
    }

    @Test fun aFixedGroupOperandExpandsItsDirectFieldsOnEveryEngine() {
        assertEquals(
            EXPECTED,
            listOf(
                "two matches   => ${computed("true", "true", "false", "false")}",
                "one match     => ${computed("true", null, "false", null)}",
                "empty cells   => ${computed(null, null, null, null)}",
                "malformed     => ${computed("true", "maybe", "false", "maybe")}",
            ),
            "the seeded targets as BOTH kernel strategies report them",
        )
    }

    private companion object {
        val EXPECTED = listOf(
            "two matches   => /Order/ApprovalCount|VALUE|2,/Order/DeclinedCount|VALUE|2",
            "one match     => /Order/ApprovalCount|VALUE|1,/Order/DeclinedCount|VALUE|1",
            "empty cells   => /Order/ApprovalCount|VALUE|0,/Order/DeclinedCount|VALUE|0",
            "malformed     => /Order/ApprovalCount|CLEARED,/Order/DeclinedCount|CLEARED",
        )
        const val ROOT = "/Order"
        const val APPROVAL_COUNT = "/Order/ApprovalCount"
        const val DECLINED_COUNT = "/Order/DeclinedCount"
        const val APPROVALS = "/Order/Approvals"
        const val DECLINES = "/Order/Declines"
        const val EXPEDITED = "/Order/Approvals/Expedited"
        const val TERMS = "/Order/Approvals/TermsAccepted"
        const val GIFT_WRAP = "/Order/Declines/GiftWrap"
        const val INSURANCE = "/Order/Declines/Insurance"
        val TARGETS = setOf(APPROVAL_COUNT, DECLINED_COUNT)
        const val SEED = "9"
    }
}
