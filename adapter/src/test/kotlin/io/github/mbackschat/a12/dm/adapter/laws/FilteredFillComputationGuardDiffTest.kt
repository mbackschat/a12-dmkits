package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldOperand
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * SPEC-2026-10-04-05: a computation precondition over a filtered field-fill list selects its candidates as validation
 * does ([KF512](../../../../../../../../../../docs/KERNEL-FINDINGS.md)). The declared-range quantifiers (`All`,
 * `NotAll`, the second scan of `FieldsNotCollectivelyFilled`) evaluate the filter at every declared coordinate, stored
 * or missing, while the thresholds search the stored rows. Both read one kept successor ahead of the cell they
 * consume, so a malformed filter behind the deciding row poisons, and a computation target in a row past the declared
 * capacity clears.
 *
 * Each of the seven quantifiers guards a target for three lists: `Items*` filtered by a positive coordinate (`CA`), by
 * a positive gate (`F`), and that gated list behind a direct field (`AF`). Every target `T` has a dependent `AfterT`
 * that holds whenever it is computed at all, separating a poisoned target (CLEARED) from a clean clear (1).
 *
 * Own domain (insurance claim items).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FilteredFillComputationGuardDiffTest {

    private enum class Quantifier(val build: (List<FieldOperand>) -> Condition) {
        ALL(Dsl::allFieldsFilled), NO(Dsl::noFieldFilled), ANY(Dsl::atLeastOneFieldFilled),
        MORE_THAN_ONE(Dsl::moreThanOneFieldFilled), NOT_ALL(Dsl::notAllFieldsFilled),
        NOT_EXACTLY_ONE(Dsl::notExactlyOneFieldFilled), NOT_COLLECTIVELY(Dsl::fieldsNotCollectivelyFilled),
    }

    private val forms = listOf("CA", "F", "AF")
    private val targets = forms.flatMap { form -> Quantifier.entries.indices.map { "$form$it" } }
    private val seeds = targets.flatMap { listOf(it to "99", "After$it" to "99") }.toMap()

    private val model: String = run {
        val draft = DocumentModelDraft.named("claim-filtered-fill").locale("en_US")
            .rootGroup("Claim") { c ->
                c.numberField("Excess") { it.fractionDigits(0) }
                c.group("Items") { i ->
                    i.repeatable(3)
                    i.numberField("Amount") { it.fractionDigits(0) }
                    i.numberField("Gate") { it.fractionDigits(0) }
                    i.numberField("Stamp") { it.fractionDigits(0) }
                    i.numberField("Sealed") { it.fractionDigits(0) }
                    i.group("Seal") { seal -> seal.numberField("Mark") { it.fractionDigits(0) } }
                }
                for (name in seeds.keys) c.numberField(name) { it.fractionDigits(0) }
            }.build().toJson()
        val session = EditSession.read(draft)
        val c = session.group("/Claim")
        val items = session.group("/Claim/Items")
        val positiveCoordinate = c.flatten("Items*/Amount").having(Dsl.currentRepetition(c.group("Items")).gt(0))
        val gated = c.flatten("Items*/Amount").having(items.num("Gate").gt(0))
        val lists = mapOf(
            "CA" to listOf<FieldOperand>(positiveCoordinate),
            "F" to listOf<FieldOperand>(gated),
            "AF" to listOf(c.num("Excess").field(), gated),
        )
        var json = session.toJson()
        json = TypedRules.inject(json, items.computation(items.num("Stamp")) {
            it.name("StampComp").calc(NumberLiteral.of("1")).message(Dsl.en("Check the item.")).build()
        })
        val seal = session.group("/Claim/Items/Seal")
        json = TypedRules.inject(json, seal.computation(seal.num("Mark")) {
            it.name("MarkComp").calc(NumberLiteral.of("1")).message(Dsl.en("Check the item.")).build()
        })
        json = TypedRules.inject(json, items.computation(items.num("Sealed")) {
            it.name("SealedComp").`when`(items.group("Seal").filled()).calc(NumberLiteral.of("1"))
                .message(Dsl.en("Check the item.")).build()
        })
        for (form in forms) for (q in Quantifier.entries) {
            val name = "$form${q.ordinal}"
            val target = c.num(name)
            json = TypedRules.inject(json, c.computation(target) {
                it.name("${name}Comp").`when`(q.build(lists.getValue(form))).calc(NumberLiteral.of("1"))
                    .message(Dsl.en("Check the claim.")).build()
            })
            json = TypedRules.inject(json, c.computation(c.num("After$name")) {
                it.name("After${name}Comp").`when`(target.filled().or(target.notFilled())).calc(NumberLiteral.of("1"))
                    .message(Dsl.en("Check the claim.")).build()
            })
        }
        json
    }

    private fun document(excess: String, items: List<Triple<String, String, String>>) =
        """{"Claim":{"Excess":"$excess",""" + seeds.entries.joinToString(",") { (k, v) -> "\"$k\":\"$v\"" } +
            ""","Items":[""" + items.joinToString(",") { (a, g, s) ->
                """{"Amount":"$a","Gate":"$g","Stamp":"$s","Seal":{"Mark":"bad"}}"""
            } + "]}}"

    private fun item(amount: String, gate: String = "1") = Triple(amount, gate, "bad")

    private val documents = linkedMapOf(
        "zero rows" to document("", emptyList()),
        "one filled row" to document("", listOf(item("1"))),
        "two filled rows" to document("", listOf(item("1"), item("2"))),
        "all filled" to document("", listOf(item("1"), item("2"), item("3"))),
        "malformed third filter" to document("", listOf(item("1"), item("2"), item("3", gate = "bad"))),
        "malformed second filter" to document("", listOf(item("1"), item("2", gate = "bad"), item("3"))),
        "malformed third amount" to document("", listOf(item("1"), item("2"), item("bad"))),
        "direct field before the filter" to document("1", listOf(item("1", gate = "bad"))),
        "a filled row past the capacity" to
            document("", listOf(item(""), item(""), item(""), item("1", gate = "bad"))),
    )

    @Test
    fun theModelIsKernelConsistent() {
        assertEquals(emptyList<String>(), MessageParameterProbes.kernel(model))
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        DocumentDifferential.assertAgree(model, documents)
    }

    /**
     * The separating rows, pinned against the normative kernel so the agreement above cannot pass vacuously. Each
     * value is the seven quantifiers of one list in declaration order (`All`, `No`, `AtLeastOne`, `MoreThanOne`,
     * `NotAll`, `NotExactlyOne`, `FieldsNotCollectively`): `T` a target computed, `F` a clean clear, `P` a poisoned one.
     * Each row names the rival account it rules out.
     */
    @Test
    fun theSeparatingRowsAreTheKernelsOwn() {
        fun signature(doc: String, form: String): String {
            val outcomes = DocumentDifferential.kernel(model, documents.getValue(doc), dynamic = true).outcomes
            return Quantifier.entries.indices.joinToString("") { i ->
                when {
                    outcomes["$form$i"] == "VALUE|1" -> "T"
                    outcomes["After$form$i"] == "CLEARED" -> "P"
                    else -> "F"
                }
            }
        }
        val actual = linkedMapOf(
            "a coordinate filter selects missing declared slots, a value filter nothing" to
                listOf(signature("zero rows", "CA"), signature("zero rows", "F")),
            "the declared quantifiers see the missing slots beside a stored row" to listOf(signature("one filled row", "CA")),
            "a filter behind the deciding row is read ahead" to listOf(signature("malformed third filter", "F")),
            "a malformed second filter is reached by every scan" to listOf(signature("malformed second filter", "F")),
            "a malformed amount behind the decision is not" to listOf(signature("malformed third amount", "F")),
            "a direct field decides before the filtered list is read" to
                listOf(signature("direct field before the filter", "AF")),
            "a row past the capacity contributes nothing" to
                listOf(signature("a filled row past the capacity", "F"), signature("a filled row past the capacity", "CA")),
            "and a computation target in that row clears" to
                DocumentDifferential.kernel(model, documents.getValue("a filled row past the capacity"), dynamic = true)
                    .outcomes.let { listOf(it["Items/Stamp"], it["Items[4]/Stamp"]) },
            "a group reached only through a computed field still reports the over-capacity error" to
                DocumentDifferential.kernel(model, documents.getValue("a filled row past the capacity"), dynamic = true)
                    .operandErrors.filter { "Seal" in it },
        )
        assertEquals(linkedMapOf(
            "a coordinate filter selects missing declared slots, a value filter nothing" to listOf("FTFFTTF", "TTFFFTF"),
            "the declared quantifiers see the missing slots beside a stored row" to listOf("FFTFTFT"),
            "a filter behind the deciding row is read ahead" to listOf("PFTPPPP"),
            "a malformed second filter is reached by every scan" to listOf("PPPPPPP"),
            "a malformed amount behind the decision is not" to listOf("PFTTPTP"),
            "a direct field decides before the filtered list is read" to listOf("PFTPPPP"),
            "a row past the capacity contributes nothing" to listOf("FTFFTTF", "FTFFTTF"),
            "and a computation target in that row clears" to listOf("VALUE|1", "CLEARED"),
            "a group reached only through a computed field still reports the over-capacity error" to listOf("zuGrosseKontextnummer@/Claim[1]/Items[4]/Seal"),
        ), actual)
    }
}
