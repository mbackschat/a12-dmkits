package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.MessageParameterProbes.kernel
import io.github.mbackschat.a12.dm.adapter.laws.MessageParameterProbes.UNSUPPORTED_SIGNS
import io.github.mbackschat.a12.dm.adapter.laws.MessageParameterProbes.portable
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Which parameters a COMPUTATION's message admits (a12-kernel-lean `SPEC-2026-10-13-12`, its computation rows):
 * the members its target, operation, common precondition and every alternative read, the group tokens on a
 * root-group carrier, a key, a starred operation, and a computation into a repeatable keyed group. Each row
 * compares the kernel with the portable checker.
 *
 * Own domain (an insurance quote: a root premium computed from its base, quote lines keyed by reference).
 * Message text stays raw, because its tokens are the measured input.
 */
class ComputationMessageCarrierAgreementTest {

    private val quote: String = DocumentModelDraft.named("quote-computation-messages").locale("en_US")
        .rootGroup("Quote") { q -> q
            .numberField("Premium") { it.fractionDigits(0) }.numberField("Base") { it.fractionDigits(0) }
            .stringField("Discount") { }.stringField("Agent") { }.stringField("Note") { }
            .group("Lines") { l -> l.repeatable(5).indexField("Ref")
                .stringField("Ref") { }.numberField("Amount") { it.fractionDigits(0) }
                .numberField("Gross") { it.fractionDigits(0) } } }
        .build().toJson()

    private enum class Shape { PREMIUM, PREMIUM_SECOND_ALTERNATIVE, PREMIUM_FROM_SUM, GROSS }

    /**
     * One computation of the given shape carrying [text] as its message. The raw writer adds it unchecked, as the
     * refused rows need; every reference is still a typed ref off the quote's editors.
     */
    private fun model(shape: Shape, text: String): String {
        val edit = EditSession.read(quote)
        val bound = BoundModel.ofJson(quote)
        val root = edit.group("/Quote")
        val lines = edit.group("/Quote/Lines")
        val base = root.num("Base").asNumber()
        fun premium(body: (Computation.Builder) -> Computation.Builder) =
            body(bound.computation(root.num("Premium")).name("PremiumComp").commonPrecondition(root.str("Agent").filled()))
        val computation = when (shape) {
            Shape.PREMIUM -> premium { it.`when`(root.str("Discount").filled()).calc(base.plus(1)) }
            Shape.PREMIUM_SECOND_ALTERNATIVE -> premium {
                it.`when`(root.str("Discount").filled()).calc(base.plus(1)).`when`(root.str("Note").filled()).calc(base.plus(2))
            }
            Shape.PREMIUM_FROM_SUM -> premium { it.calc(Dsl.sum(root.flatten("Lines*/Amount"))) }
            Shape.GROSS -> bound.computation(lines.num("Gross")).name("GrossComp").calc(lines.num("Amount").asNumber().times(2))
        }
        return ModelWriter.addComputation(quote, computation.message(Dsl.en(text)).build())
    }

    private fun rows(): Map<String, Pair<Shape, String>> = linkedMapOf(
        "premium: no parameter" to (Shape.PREMIUM to "Premium differs."),
        "premium: the target" to (Shape.PREMIUM to "\$Premium\$ is \$Premium.value\$."),
        "premium: the operation's field" to (Shape.PREMIUM to "\$Base.value\$."),
        "premium: the alternative's precondition" to (Shape.PREMIUM to "\$Discount.value\$."),
        "premium: the common precondition" to (Shape.PREMIUM to "\$Agent.value\$."),
        "premium: an unread field" to (Shape.PREMIUM to "\$Note\$."),
        "premium: an unread field beside a supplementary character" to (Shape.PREMIUM to "🛒 \$Note\$."),
        "premium: an unknown name" to (Shape.PREMIUM to "\$Nope\$."),
        "premium: the rule group" to (Shape.PREMIUM to "\$#RuleGroup\$."),
        "premium: the root group" to (Shape.PREMIUM to "\$#RootGroup\$."),
        "premium: a keyed field" to (Shape.PREMIUM to "\$Lines/Amount.value For \"k1\"\$."),
        "second alternative: the field it reads" to (Shape.PREMIUM_SECOND_ALTERNATIVE to "\$Note\$ \$Note.value\$."),
        "sum: the starred field's name" to (Shape.PREMIUM_FROM_SUM to "\$Lines/Amount\$."),
        "sum: the starred field's value" to (Shape.PREMIUM_FROM_SUM to "\$Lines/Amount.value\$."),
        "gross: the group tokens" to (Shape.GROSS to "\$#RuleGroup\$ \$#RootGroup\$."),
        "gross: the index token" to (Shape.GROSS to "\$index(Ref).value\$."),
        "gross: its operation and target" to (Shape.GROSS to "\$Amount.value\$ \$Gross.value\$."),
        "gross: an unread root field" to (Shape.GROSS to "\$/Quote/Base\$."),
    )

    @Test
    fun theCheckerAgreesWithA12() {
        val verdicts = rows().mapValues { (_, row) -> model(row.first, row.second).let { model ->
                // Classes and multiplicity; report order is A12ReportStreamTest's characterized difference.
                kernel(model).sorted() to portable(model).map { if (it == "RK_UNSUPPORTED_CHARACTER") UNSUPPORTED_SIGNS else it }.sorted()
            } }
        assertEquals(emptyMap<String, Any>(), verdicts.filterValues { it.first != it.second }
            .mapValues { "A12 ${it.value.first}, ours ${it.value.second}" })
        // The grid is not agreement on nothing: these are A12's own refusals; every other row is admitted.
        assertEquals(mapOf(
            "premium: an unread field" to listOf("INVALID_FIELD"),
            "premium: an unread field beside a supplementary character" to listOf("INVALID_FIELD", UNSUPPORTED_SIGNS),
            "premium: an unknown name" to listOf("INVALID_ENTITY"),
            "premium: the rule group" to listOf("RULEGROUP_IMMEDIATELY_BELOW_ROOT"),
            "premium: a keyed field" to listOf("MVK_INDEX_FOR_ERROR_TEXT_INVALID"),
            "sum: the starred field's value" to listOf("PARAM_VALUE_WITH_STARREFERENCE"),
            "gross: an unread root field" to listOf("INVALID_FIELD")),
            verdicts.mapValues { it.value.first }.filterValues { it.isNotEmpty() })
    }
}
