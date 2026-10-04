package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * SPEC-2026-10-03-04: a computation precondition over value-list membership reads in the kernel operator's own order,
 * and a reached read of an invalid cell poisons the computation. The scalar form reads every member, so a malformed
 * member after a match still poisons; an empty Number subject is no value rather than zero, and an empty member never
 * matches, which makes a `NotIncluded` firing an omission. The plural forms read the members first and then scan the
 * subjects only to the deciding cell, and a filtered list reads one kept successor ahead and no further.
 *
 * Every guarded target `T` has a dependent `AfterT` that holds whenever it is computed at all, so the dependent
 * separates a poisoned target (CLEARED) from a cleanly cleared one (1). Three validation rules over the scalar
 * `NotIncluded` form carry the phase-neutral half: no firing on a malformed member, omission on an empty one.
 *
 * Own domain (e-commerce basket).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MembershipComputationGuardDiffTest {

    private val targets = listOf("QtyIn", "QtyNotIn", "CodeIn", "CodeNotIn", "TierIn", "TierNotIn", "AnyCodes",
        "NoCodes", "NotAllCodes", "AnyKeptMembers", "AnyKeptSubjects", "NoKeptSubjects")
    private val seeds = targets.flatMap { listOf(it to "9", "After$it" to "9") }.toMap()

    private val model: String = run {
        val draft = DocumentModelDraft.named("basket-membership").locale("en_US")
            .rootGroup("Basket") { b ->
                for (name in listOf("Qty", "QtyFirst", "QtyLast")) b.numberField(name) { it.fractionDigits(0) }
                for (name in listOf("Code", "CodeFirst", "CodeLast")) b.stringField(name) { it.maxLength(4) }
                for (name in listOf("Tier", "TierFirst", "TierLast")) b.enumField(name) { it.value("GOLD").value("SILVER") }
                b.group("Lines") { l ->
                    l.repeatable(4)
                    l.numberField("LineQty") { it.fractionDigits(0) }
                    l.numberField("Keep") { it.fractionDigits(0) }
                }
                for (name in seeds.keys) b.numberField(name) { it.fractionDigits(0) }
            }.build().toJson()
        val session = EditSession.read(draft)
        val b = session.group("/Basket")
        val qty = b.num("Qty")
        val code = b.str("Code")
        val tier = b.enumOf("Tier")
        val keptLineQty = b.flatten("Lines*/LineQty").having(session.group("/Basket/Lines").num("Keep").gt(0))
        val guards: List<Pair<String, Condition>> = listOf(
            "QtyIn" to qty.`in`(b.num("QtyFirst"), b.num("QtyLast")),
            "QtyNotIn" to qty.notIn(b.num("QtyFirst"), b.num("QtyLast")),
            "CodeIn" to code.`in`(b.str("CodeFirst"), b.str("CodeLast")),
            "CodeNotIn" to code.notIn(b.str("CodeFirst"), b.str("CodeLast")),
            "TierIn" to tier.`in`(b.enumOf("TierFirst"), b.enumOf("TierLast")),
            "TierNotIn" to tier.notIn(b.enumOf("TierFirst"), b.enumOf("TierLast")),
            "AnyCodes" to Dsl.atLeastOneFieldValueIncludedInValueList(
                listOf(b.str("CodeFirst").field()), code.field(), b.str("CodeLast").field()),
            "NoCodes" to Dsl.noFieldValueIncludedInValueList(
                listOf(b.str("CodeFirst").field()), code.field(), b.str("CodeLast").field()),
            "NotAllCodes" to Dsl.notAllFieldValuesIncludedInValueList(
                listOf(b.str("CodeFirst").field()), code.field(), b.str("CodeLast").field()),
            "AnyKeptMembers" to Dsl.atLeastOneFieldValueIncludedInValueList(listOf(keptLineQty), qty.field()),
            "AnyKeptSubjects" to Dsl.atLeastOneFieldValueIncludedInValueList(listOf(b.num("QtyFirst").field()), keptLineQty),
            "NoKeptSubjects" to Dsl.noFieldValueIncludedInValueList(listOf(b.num("QtyFirst").field()), keptLineQty),
        )
        var json = session.toJson()
        for ((name, guard) in guards) {
            val target = b.num(name)
            val after = b.num("After$name")
            json = TypedRules.inject(json, b.computation(target) {
                it.name("${name}Comp").`when`(guard).calc(NumberLiteral.of("1"))
                    .message(Dsl.en("Check the basket.")).build()
            })
            json = TypedRules.inject(json, b.computation(after) {
                it.name("After${name}Comp").`when`(target.filled().or(target.notFilled())).calc(NumberLiteral.of("1"))
                    .message(Dsl.en("Check the basket.")).build()
            })
        }
        val notListed: List<Triple<String, io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef, Condition>> = listOf(
            Triple("QtyNotListed", qty.field(), qty.notIn(b.num("QtyFirst"), b.num("QtyLast"))),
            Triple("CodeNotListed", code.field(), code.notIn(b.str("CodeFirst"), b.str("CodeLast"))),
            Triple("TierNotListed", tier.field(), tier.notIn(b.enumOf("TierFirst"), b.enumOf("TierLast"))),
        )
        for ((rule, subject, condition) in notListed) {
            json = TypedRules.inject(json, b.rule {
                it.name(rule).code(rule).errorField(subject).`when`(condition)
                    .message(Dsl.en("Check the basket.")).build()
            })
        }
        json
    }

    private fun document(scalars: Map<String, String>, lines: String = "") =
        """{"Basket":{""" + (scalars + seeds).entries.joinToString(",") { (k, v) -> "\"$k\":\"$v\"" } +
            (if (lines.isEmpty()) "" else ""","Lines":[$lines]""") + "}}"

    private fun scalars(qty: List<String>, code: List<String>, tier: List<String>) =
        listOf("Qty", "QtyFirst", "QtyLast").zip(qty).toMap() + listOf("Code", "CodeFirst", "CodeLast").zip(code) +
            listOf("Tier", "TierFirst", "TierLast").zip(tier)

    private fun line(qty: String, keep: String) = """{"LineQty":"$qty","Keep":"$keep"}"""

    private val documents = linkedMapOf(
        "match" to document(scalars(listOf("0", "0", "1"), listOf("ab", "ab", "cd"), listOf("GOLD", "GOLD", "SILVER"))),
        "outside" to document(scalars(listOf("2", "0", "1"), listOf("zz", "ab", "cd"), listOf("SILVER", "GOLD", "GOLD"))),
        "late member malformed after a match" to
            document(scalars(listOf("0", "0", "bad"), listOf("ab", "ab", "toolong"), listOf("GOLD", "GOLD", "BRONZE"))),
        "late member malformed, no match" to
            document(scalars(listOf("5", "0", "bad"), listOf("zz", "ab", "toolong"), listOf("SILVER", "GOLD", "BRONZE"))),
        "empty member before a malformed one" to
            document(scalars(listOf("5", "", "bad"), listOf("zz", "", "toolong"), listOf("SILVER", "", "BRONZE"))),
        "empty subject" to document(scalars(listOf("", "0", "1"), listOf("", "ab", "cd"), listOf("", "GOLD", "SILVER"))),
        "empty members" to document(scalars(listOf("0", "", ""), listOf("ab", "", ""), listOf("GOLD", "", ""))),
        "empty subject, malformed member" to
            document(scalars(listOf("", "0", "bad"), listOf("", "ab", "toolong"), listOf("", "GOLD", "BRONZE"))),
        "outside before a malformed subject" to
            document(scalars(listOf("0", "0", "1"), listOf("zz", "ab", "toolong"), listOf("GOLD", "GOLD", "SILVER"))),
        "kept rows: match, other, malformed" to document(scalars(listOf("0", "0", "1"), listOf("ab", "ab", "cd"),
            listOf("GOLD", "GOLD", "SILVER")), listOf(line("0", "1"), line("1", "1"), line("bad", "1")).joinToString(",")),
        "kept rows: outside, outside, malformed" to document(scalars(listOf("0", "0", "1"), listOf("ab", "ab", "cd"),
            listOf("GOLD", "GOLD", "SILVER")), listOf(line("2", "1"), line("1", "1"), line("bad", "1")).joinToString(",")),
        "third filter malformed after a match" to document(scalars(listOf("0", "0", "1"), listOf("ab", "ab", "cd"),
            listOf("GOLD", "GOLD", "SILVER")), listOf(line("0", "1"), line("1", "1"), line("1", "bad")).joinToString(",")),
    )

    @Test
    fun theModelIsKernelConsistent() {
        assertEquals(emptyList<String>(), MessageParameterProbes.kernel(model))
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        DocumentDifferential.assertAgree(model, documents, seeds)
    }

    /**
     * The separating rows, pinned against the normative kernel so the agreement above cannot pass vacuously. Each
     * value is `target/dependent`, where `CLEARED/CLEARED` is a poisoned target and `CLEARED/1` a clean clear (an
     * unchanged seed reads `9`). Each row names the rival account it rules out.
     */
    @Test
    fun theSeparatingRowsAreTheKernelsOwn() {
        fun outcome(doc: String, target: String): String {
            val outcomes = DocumentDifferential.kernel(model, documents.getValue(doc), dynamic = true).outcomes
            fun of(name: String) = outcomes[name]?.removePrefix("VALUE|") ?: "9"
            return of(target) + "/" + of("After$target")
        }
        fun fired(doc: String) = DocumentDifferential.kernel(model, documents.getValue(doc), dynamic = true)
            .messages.filter { it.contains("NotListed|") }
        val actual = linkedMapOf(
            "a scalar membership reads every member, even after a match" to
                listOf("QtyIn", "CodeIn", "TierIn").map { outcome("late member malformed after a match", it) },
            "an empty subject is no value, not zero" to
                listOf("QtyIn", "QtyNotIn").map { outcome("empty subject", it) },
            "an empty member never matches" to
                listOf("QtyIn", "QtyNotIn").map { outcome("empty members", it) },
            "the members are read even when the subject is empty" to
                listOf("QtyIn", "CodeIn").map { outcome("empty subject, malformed member", it) },
            "a NotIncluded rule over a malformed member does not fire" to fired("late member malformed, no match"),
            "a NotIncluded rule over empty members fires as omission" to fired("empty members"),
            "a text subject decides at its first empty member, a Number at any malformed one" to
                fired("empty member before a malformed one"),
            "the plural subject scan stops at a match before a malformed subject" to
                listOf("AnyCodes", "NoCodes").map { outcome("late member malformed after a match", it) },
            "and at an outside subject" to listOf("NotAllCodes", "AnyCodes").map { outcome("outside before a malformed subject", it) },
            "filtered members are read completely" to listOf(outcome("kept rows: match, other, malformed", "AnyKeptMembers")),
            "a reached malformed filtered subject poisons" to
                listOf("AnyKeptSubjects", "NoKeptSubjects").map { outcome("kept rows: outside, outside, malformed", it) },
            "a filtered scan reads one kept successor ahead and no further" to
                listOf("AnyKeptSubjects", "NoKeptSubjects").map { outcome("third filter malformed after a match", it) },
        )
        assertEquals(linkedMapOf(
            "a scalar membership reads every member, even after a match" to
                listOf("CLEARED/CLEARED", "CLEARED/CLEARED", "CLEARED/CLEARED"),
            "an empty subject is no value, not zero" to listOf("CLEARED/1", "CLEARED/1"),
            "an empty member never matches" to listOf("CLEARED/1", "1/1"),
            "the members are read even when the subject is empty" to listOf("CLEARED/CLEARED", "CLEARED/CLEARED"),
            "a NotIncluded rule over a malformed member does not fire" to emptyList<String>(),
            "a NotIncluded rule over empty members fires as omission" to listOf(
                "CodeNotListed|OMISSION_ERROR|/Basket[1]/Code", "QtyNotListed|OMISSION_ERROR|/Basket[1]/Qty",
                "TierNotListed|OMISSION_ERROR|/Basket[1]/Tier"),
            "a text subject decides at its first empty member, a Number at any malformed one" to listOf(
                "CodeNotListed|OMISSION_ERROR|/Basket[1]/Code", "TierNotListed|OMISSION_ERROR|/Basket[1]/Tier"),
            "the plural subject scan stops at a match before a malformed subject" to listOf("1/1", "CLEARED/1"),
            "and at an outside subject" to listOf("1/1", "CLEARED/CLEARED"),
            "filtered members are read completely" to listOf("CLEARED/CLEARED"),
            "a reached malformed filtered subject poisons" to listOf("CLEARED/CLEARED", "CLEARED/CLEARED"),
            "a filtered scan reads one kept successor ahead and no further" to listOf("1/1", "CLEARED/1"),
        ), actual)
    }
}
