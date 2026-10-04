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
 * SPEC-2026-10-04-07: a computation precondition over `FieldValuesNotUnique` scans its values in order and stops at
 * the first duplicate, and a reached malformed value poisons the computation where validation skips it. An earlier
 * duplicate keeps a later malformed value unread, in a plain field list and down a starred column alike, and a filtered
 * column reads one kept successor ahead and no further.
 *
 * Every guarded target `T` has a dependent `AfterT` that holds whenever it is computed at all, so the dependent
 * separates a poisoned target (CLEARED) from a cleanly cleared one (1).
 *
 * Own domain (e-commerce catalogue).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UniquenessComputationGuardDiffTest {

    private val targets = listOf("RepeatedSku", "RepeatedKeptSku", "EarlyRepeat", "LateRepeat")
    private val seeds = targets.flatMap { listOf(it to "9", "After$it" to "9") }.toMap()

    private val model: String = run {
        val draft = DocumentModelDraft.named("catalogue-uniqueness").locale("en_US")
            .rootGroup("Catalogue") { c ->
                for (name in listOf("FirstSku", "SecondSku", "BrokenSku")) c.numberField(name) { it.fractionDigits(0) }
                c.group("Lines") { l ->
                    l.repeatable(5)
                    l.numberField("Sku") { it.fractionDigits(0) }
                    l.numberField("Keep") { it.fractionDigits(0) }
                }
                for (name in seeds.keys) c.numberField(name) { it.fractionDigits(0) }
            }.build().toJson()
        val session = EditSession.read(draft)
        val c = session.group("/Catalogue")
        val first = c.num("FirstSku").field()
        val second = c.num("SecondSku").field()
        val broken = c.num("BrokenSku").field()
        val guards: List<Pair<String, Condition>> = listOf(
            "RepeatedSku" to Dsl.fieldValuesNotUnique(c.flatten("Lines*/Sku")),
            "RepeatedKeptSku" to Dsl.fieldValuesNotUnique(
                c.flatten("Lines*/Sku").having(session.group("/Catalogue/Lines").num("Keep").gt(0))),
            "EarlyRepeat" to Dsl.fieldValuesNotUnique(first, second, broken),
            "LateRepeat" to Dsl.fieldValuesNotUnique(broken, first, second),
        )
        var json = session.toJson()
        for ((name, guard) in guards) {
            val target = c.num(name)
            json = TypedRules.inject(json, c.computation(target) {
                it.name("${name}Comp").`when`(guard).calc(NumberLiteral.of("1")).message(Dsl.en("Check the SKUs.")).build()
            })
            json = TypedRules.inject(json, c.computation(c.num("After$name")) {
                it.name("After${name}Comp").`when`(target.filled().or(target.notFilled())).calc(NumberLiteral.of("1"))
                    .message(Dsl.en("Check the SKUs.")).build()
            })
        }
        json
    }

    private fun document(lines: List<Pair<String, String>>, broken: String = "3") =
        """{"Catalogue":{"FirstSku":"1","SecondSku":"1","BrokenSku":"$broken",""" +
            seeds.entries.joinToString(",") { (k, v) -> "\"$k\":\"$v\"" } +
            ""","Lines":[""" + lines.joinToString(",") { (sku, keep) -> """{"Sku":"$sku","Keep":"$keep"}""" } + "]}}"

    private val documents = linkedMapOf(
        "duplicates" to document(listOf("1" to "1", "1" to "1", "2" to "1")),
        "distinct" to document(listOf("1" to "1", "2" to "1")),
        "malformed scalar" to document(listOf("1" to "1", "2" to "1"), broken = "bad"),
        "malformed row before a duplicate" to document(listOf("bad" to "1", "1" to "1", "1" to "1")),
        "malformed row after a duplicate" to document(listOf("1" to "1", "1" to "1", "bad" to "1")),
        "malformed third filter" to document(listOf("1" to "1", "1" to "1", "2" to "bad", "3" to "1")),
        "malformed fourth filter" to document(listOf("1" to "1", "1" to "1", "2" to "1", "3" to "bad")),
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
     * value is `target/dependent`: `CLEARED/CLEARED` is a poisoned target, `CLEARED/1` a clean clear. Each row names
     * the rival account it rules out.
     */
    @Test
    fun theSeparatingRowsAreTheKernelsOwn() {
        fun outcomes(doc: String, vararg names: String): List<String> {
            val outcomes = DocumentDifferential.kernel(model, documents.getValue(doc), dynamic = true).outcomes
            fun of(name: String) = outcomes[name]?.removePrefix("VALUE|") ?: "9"
            return names.map { of(it) + "/" + of("After$it") }
        }
        val actual = linkedMapOf(
            "an earlier duplicate keeps a later malformed field unread" to
                outcomes("malformed scalar", "EarlyRepeat", "LateRepeat"),
            "a reached malformed row poisons rather than being skipped" to
                outcomes("malformed row before a duplicate", "RepeatedSku"),
            "an earlier duplicate keeps a later malformed row unread" to
                outcomes("malformed row after a duplicate", "RepeatedSku"),
            "a filtered column reads one kept successor ahead" to outcomes("malformed third filter", "RepeatedKeptSku"),
            "and no further" to outcomes("malformed fourth filter", "RepeatedKeptSku"),
        )
        assertEquals(linkedMapOf(
            "an earlier duplicate keeps a later malformed field unread" to listOf("1/1", "CLEARED/CLEARED"),
            "a reached malformed row poisons rather than being skipped" to listOf("CLEARED/CLEARED"),
            "an earlier duplicate keeps a later malformed row unread" to listOf("1/1"),
            "a filtered column reads one kept successor ahead" to listOf("CLEARED/CLEARED"),
            "and no further" to listOf("1/1"),
        ), actual)
    }
}
