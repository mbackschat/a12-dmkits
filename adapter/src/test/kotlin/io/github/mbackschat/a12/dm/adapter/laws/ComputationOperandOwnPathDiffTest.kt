package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — a computation operand OUTSIDE the target's own repetition level is read **at its own path
 * inside the current target row**, which is what decides the target instances it reaches (SPEC-2026-08-23-06).
 *
 * **This is the half an admission test cannot see, and it fails silently.** The scope gate
 * (`rulekit.validate.laws.ComputationOperandScopeLawsTest`) establishes that an outer operand is *admitted*;
 * an implementation that then addresses that operand at the TARGET's path finds nothing there and reads it as
 * empty on every row — a wrong VALUE, not a refusal, and on a NUMBER operand the substituted zero makes it
 * look like a plausible answer.
 *
 * [twoEnclosingRowsGiveTheirOwnLeavesDifferentResults] is the row that separates the two accounts: with a
 * two-LEVEL target and the operand at the middle level, an own-path read gives the leaves under each
 * enclosing row that row's own value, while a target-path read gives every leaf the same one.
 *
 * Own domain (motor insurance claims).
 */
class ComputationOperandOwnPathDiffTest {

    /** A one-level target reading a FORM-level operand: `Net = Amount - PolicyExcess`. */
    private val singleLevel: String = run {
        val base = DocumentModelDraft.named("claims-single").locale("en_US")
            .rootGroup("Policy") { policy ->
                policy.numberField("PolicyExcess") { it.fractionDigits(0) }
                policy.group("Claims") { claims ->
                    claims.repeatable(20).indexField("ClaimKey")
                        .stringField("ClaimKey") { }
                        .numberField("Amount") { it.fractionDigits(0) }
                        .numberField("Net") { it.fractionDigits(0) }
                }
            }.build().toJson()
        val m = BoundModel.ofJson(base)
        val operation = DslRenderer(ConditionLanguage.EN, CLAIMS).render(
            m.num("$CLAIMS/Amount").asNumber().minus(m.num("/Policy/PolicyExcess").asNumber()).expression(),
        )
        RuntimeLaws.withComputation(base, CLAIMS, "NetComp", "../Net", operation)
    }

    /** A two-LEVEL target reading the MIDDLE level: `ItemNet = ItemAmount + Amount`. */
    private val twoLevel: String = run {
        val base = DocumentModelDraft.named("claims-nested").locale("en_US")
            .rootGroup("Policy") { policy ->
                policy.group("Claims") { claims ->
                    claims.repeatable(20).indexField("ClaimKey")
                        .stringField("ClaimKey") { }
                        .numberField("Amount") { it.fractionDigits(0) }
                        .group("Items") { items ->
                            items.repeatable(20).indexField("ItemKey")
                                .stringField("ItemKey") { }
                                .numberField("ItemAmount") { it.fractionDigits(0) }
                                .numberField("ItemNet") { it.fractionDigits(0) }
                        }
                }
            }.build().toJson()
        val m = BoundModel.ofJson(base)
        val operation = DslRenderer(ConditionLanguage.EN, ITEMS).render(
            m.num("$ITEMS/ItemAmount").asNumber().plus(m.num("$CLAIMS/Amount").asNumber()).expression(),
        )
        RuntimeLaws.withComputation(base, ITEMS, "ItemNetComp", "../ItemNet", operation)
    }

    /** One root cell reaches every target row — as a value. */
    @Test
    fun aFormLevelOperandReachesEveryTargetRow() = agreeSingle(
        excess = "10",
        expected = listOf("/Policy[1]/Claims[1]/Net|VALUE|90", "/Policy[1]/Claims[2]/Net|VALUE|190"),
    )

    /** …as a substituted zero when it is empty: a number operand's empty reading is `0`, not "no value". */
    @Test
    fun anEmptyFormLevelOperandStillComputesOnEveryRow() = agreeSingle(
        excess = null,
        expected = listOf("/Policy[1]/Claims[1]/Net|VALUE|100", "/Policy[1]/Claims[2]/Net|VALUE|200"),
    )

    /** …and as a clear when it is formally invalid: the one bad root cell poisons every row at once. */
    @Test
    fun aFormallyInvalidFormLevelOperandClearsEveryTargetRow() = agreeSingle(
        excess = "not-a-number",
        expected = listOf("/Policy[1]/Claims[1]/Net|CLEARED", "/Policy[1]/Claims[2]/Net|CLEARED"),
    )

    /**
     * The discriminator. Each leaf reads the operand under ITS OWN enclosing row, so two enclosing rows
     * carrying different values give their leaves different results. Addressing the operand at the target's
     * path would give all four leaves the same answer.
     */
    @Test
    fun twoEnclosingRowsGiveTheirOwnLeavesDifferentResults() = agreeTwoLevel(
        firstAmount = "100",
        expected = listOf(
            "/Policy[1]/Claims[1]/Items[1]/ItemNet|VALUE|105",
            "/Policy[1]/Claims[1]/Items[2]/ItemNet|VALUE|107",
            "/Policy[1]/Claims[2]/Items[1]/ItemNet|VALUE|205",
        ),
    )

    /** …and the poison is scoped the same way: an invalid enclosing operand clears only its own leaves. */
    @Test
    fun anInvalidEnclosingOperandClearsOnlyItsOwnLeaves() = agreeTwoLevel(
        firstAmount = "not-a-number",
        expected = listOf(
            "/Policy[1]/Claims[1]/Items[1]/ItemNet|CLEARED",
            "/Policy[1]/Claims[1]/Items[2]/ItemNet|CLEARED",
            "/Policy[1]/Claims[2]/Items[1]/ItemNet|VALUE|205",
        ),
    )

    /** Two claim rows of 100 and 200; [excess] is the single form-level cell every row reads. */
    private fun agreeSingle(excess: String?, expected: List<String>) {
        val kernelDocument = RuntimeLaws.doc().group("/Policy", 1)
        excess?.let { kernelDocument.field("/Policy/PolicyExcess", it, 1, 1) }
        val interpreterDocument = ModelLoader.load(singleLevel).newDocument().group("/Policy", 1)
        excess?.let { interpreterDocument.field("/Policy/PolicyExcess", it, 1, 1) }
        listOf("100", "200").forEachIndexed { i, amount ->
            kernelDocument.group(CLAIMS, 1, i + 1)
                .field("$CLAIMS/ClaimKey", "C${i + 1}", 1, i + 1, 1)
                .field("$CLAIMS/Amount", amount, 1, i + 1, 1)
                .field("$CLAIMS/Net", SEED, 1, i + 1, 1)
            interpreterDocument.group(CLAIMS, 1, i + 1)
                .field("$CLAIMS/ClaimKey", "C${i + 1}", 1, i + 1, 1)
                .field("$CLAIMS/Amount", amount, 1, i + 1, 1)
                .field("$CLAIMS/Net", SEED, 1, i + 1, 1)
        }
        agree(singleLevel, "$CLAIMS/Net", kernelDocument, interpreterDocument, expected)
    }

    /** Claim 1 carries [firstAmount] and TWO items (5, 7); claim 2 carries 200 and one item (5). */
    private fun agreeTwoLevel(firstAmount: String, expected: List<String>) {
        val kernelDocument = RuntimeLaws.doc().group("/Policy", 1)
        val interpreterDocument = ModelLoader.load(twoLevel).newDocument().group("/Policy", 1)
        val claims = listOf(firstAmount to listOf("5", "7"), "200" to listOf("5"))
        claims.forEachIndexed { c, (amount, items) ->
            kernelDocument.group(CLAIMS, 1, c + 1)
                .field("$CLAIMS/ClaimKey", "C${c + 1}", 1, c + 1, 1)
                .field("$CLAIMS/Amount", amount, 1, c + 1, 1)
            interpreterDocument.group(CLAIMS, 1, c + 1)
                .field("$CLAIMS/ClaimKey", "C${c + 1}", 1, c + 1, 1)
                .field("$CLAIMS/Amount", amount, 1, c + 1, 1)
            items.forEachIndexed { i, itemAmount ->
                kernelDocument.group(ITEMS, 1, c + 1, i + 1)
                    .field("$ITEMS/ItemKey", "I${i + 1}", 1, c + 1, i + 1, 1)
                    .field("$ITEMS/ItemAmount", itemAmount, 1, c + 1, i + 1, 1)
                    .field("$ITEMS/ItemNet", SEED, 1, c + 1, i + 1, 1)
                interpreterDocument.group(ITEMS, 1, c + 1, i + 1)
                    .field("$ITEMS/ItemKey", "I${i + 1}", 1, c + 1, i + 1, 1)
                    .field("$ITEMS/ItemAmount", itemAmount, 1, c + 1, i + 1, 1)
                    .field("$ITEMS/ItemNet", SEED, 1, c + 1, i + 1, 1)
            }
        }
        agree(twoLevel, "$ITEMS/ItemNet", kernelDocument, interpreterDocument, expected)
    }

    private fun agree(
        json: String,
        target: String,
        kernelDocument: RuntimeLaws.Doc,
        interpreterDocument: io.github.mbackschat.a12.dm.interpreter.eval.Document,
        expected: List<String>,
    ) {
        val dynamic = RuntimeLaws.compute(json, kernelDocument).filter { it.path() == target }
            .map { signature(it.pointer(), it.cleared(), it.errored(), it.value().orElse("")) }.sorted()
        val engine = RuntimeLaws.kernelJavaEngine(json)
        val generated = engine.compute(engine.instance(kernelDocument)).filter { it.path() == target }
            .map { signature(it.pointer(), it.cleared(), it.errored(), it.value().orElse("")) }.sorted()
        val interpreted = DmInterpreter(ModelLoader.load(json)).compute(interpreterDocument)
            .filter { it.path == target }
            .map {
                signature(
                    formatPointer(it.path, it.indices),
                    it.outcome == ComputeOutcome.CLEARED,
                    it.outcome == ComputeOutcome.ERRORED,
                    it.value.orEmpty(),
                )
            }.sorted()

        assertEquals(expected, dynamic, "normative dynamic Groovy")
        assertEquals(dynamic, generated, "generated Java")
        assertEquals(dynamic, interpreted, "interpreter")
    }

    private fun signature(pointer: String, cleared: Boolean, errored: Boolean, value: String): String = when {
        cleared -> "$pointer|CLEARED"
        errored -> "$pointer|ERRORED|$value"
        else -> "$pointer|VALUE|$value"
    }

    private companion object {
        /** Every target row is seeded, so a CLEAR is observable — an untouched target reports no change. */
        const val SEED = "999"
        const val CLAIMS = "/Policy/Claims"
        const val ITEMS = "/Policy/Claims/Items"
    }
}
