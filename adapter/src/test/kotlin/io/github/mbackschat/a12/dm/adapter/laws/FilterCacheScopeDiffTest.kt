package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.DocumentDifferential.underUtc
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.eval.readDocumentJson
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Which groups' stored rows bound a computation's `Having` walk (KERNEL-FINDINGS KF528, a12-kernel-lean
 * SPEC-2026-10-09-10). The filter is [FilterMissingSlotDiffTest]'s: `[Weight] > 0 Or [Stamp] == <gap label>`, so the
 * computed `Total` fails exactly when the walk reaches a leg no row stores. Every model is the same base plus at most
 * one more computation, so each row varies one thing: a computation that reads the `Returns` rows, one whose only
 * contact is a target inside `Returns`, one that reads them behind a precondition that never holds, and one that reads
 * `Spares`, whose rows sit one fixed group deeper than the legs. One more model declares capacity 150 but keeps actual cache width three; the sparse threshold is
 * measured separately by [SparseFilteredAggregateDiffTest].
 *
 * Own domain (parcel delivery legs).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FilterCacheScopeDiffTest {
    private enum class Variant { NONE, READS_RETURNS, RETURNS_TARGET, UNREACHED_READ, READS_SPARES }

    private companion object {
        const val ISO_DT = "yyyy-MM-dd'T'HH:mm:ss"

        /** Europe/Berlin skips 02:00–03:00 on 2024-03-31. */
        const val GAP = "31.03.2024T02:30:00"
        const val LEG = """{"Weight":"7","Stamp":"2024-03-31T01:30:00"}"""
    }

    private val models = Variant.entries.associateWith { build(it) }

    /** The returns reader over legs of capacity 150, the only row that varies the legs' capacity. */
    private val wideLegs = build(Variant.READS_RETURNS, legCapacity = 150)

    private fun build(variant: Variant, legCapacity: Int = 3): String = underUtc {
        val draft = DocumentModelDraft.named("parcel-filter-cache").locale("en_US")
            .rootGroup("Parcel") { p ->
                p.stringField("Ref") { }
                p.numberField("Base") { n -> n.fractionDigits(0) }
                p.group("Legs") { l -> l.repeatable(legCapacity)
                    .numberField("Weight") { n -> n.fractionDigits(0) }
                    .dateTimeField("Stamp") { it.format(ISO_DT) } }
                p.group("Returns") { r -> r.repeatable(3).stringField("Note") { }
                    .numberField("Tag") { n -> n.fractionDigits(0) } }
                p.group("Box") { b -> b.group("Spares") { s -> s.repeatable(3).stringField("Note") { } } }
                p.numberField("Total") { n -> n.fractionDigits(0) }
                p.numberField("Count") { n -> n.fractionDigits(0) }
            }.build().toJson()
        val session = EditSession.read(draft).also { it.config().timeZone("Europe/Berlin") }
        val p = session.group("/Parcel")
        val legs = session.group("/Parcel/Legs")
        val total = Dsl.sum(p.flatten("Legs*/Weight")
            .having(legs.num("Weight").gt(0).or(legs.dateTime("Stamp").eq(Dsl.dateTimeValue(GAP)))))
        val ref = p.str("Ref")
        var json = session.toJson()
        json = TypedRules.inject(json, p.computation(p.num("Total")) {
            it.name("TotalComp").calc(total).message(Dsl.en("Check the legs.")).build()
        })
        json = TypedRules.inject(json, p.rule {
            it.name("TotalRule").code("TotalRule").errorField(ref.field())
                .`when`(ref.filled().and(total.gt(0))).message(Dsl.en("Check the legs.")).build()
        })
        val count = p.num("Count")
        json = when (variant) {
            Variant.NONE -> json
            Variant.READS_RETURNS -> TypedRules.inject(json, p.computation(count) {
                it.name("CountComp").calc(Dsl.numberOfFilledFields(p.flatten("Returns*/Note")))
                    .message(Dsl.en("Check the returns.")).build()
            })
            Variant.RETURNS_TARGET -> {
                val returns = session.group("/Parcel/Returns")
                TypedRules.inject(json, returns.computation(returns.num("Tag")) {
                    it.name("TagComp").calc(p.num("Base").plus(1)).message(Dsl.en("Check the returns.")).build()
                })
            }
            Variant.UNREACHED_READ -> TypedRules.inject(json, p.computation(count) {
                it.name("CountComp").commonPrecondition(ref.notFilled())
                    .calc(Dsl.numberOfFilledFields(p.flatten("Returns*/Note")))
                    .message(Dsl.en("Check the returns.")).build()
            })
            Variant.READS_SPARES -> TypedRules.inject(json, p.computation(count) {
                it.name("CountComp").calc(Dsl.numberOfFilledFields(p.flatten("Box/Spares*/Note")))
                    .message(Dsl.en("Check the spares.")).build()
            })
        }
        json
    }

    private fun parcel(returns: Int = 0, spares: Int = 0) =
        """{"Parcel":{"Ref":"P-1","Base":"4","Legs":[$LEG],""" +
            """"Returns":[${List(returns) { """{"Note":"r"}""" }.joinToString(",")}],""" +
            """"Box":{"Spares":[${List(spares) { """{"Note":"s"}""" }.joinToString(",")}]},"Total":"99"}}"""

    private val documents = linkedMapOf(
        "one leg" to parcel(),
        "one leg, three returns" to parcel(returns = 3),
        "one leg, three spares" to parcel(spares = 3),
    )

    /** A fourth `Returns` row past the capacity of three, holding a note or only a tag. */
    private val overLimit = linkedMapOf(
        "a note" to parcel(returns = 4),
        "only a tag" to parcel(returns = 3).replace("""{"Note":"r"}]""", """{"Note":"r"},{"Tag":"1"}]"""),
    )

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        underUtc { (models.values + wideLegs).forEach { DocumentDifferential.assertAgree(it, documents + overLimit) } }
    }

    /**
     * Whether the walk reaches the missing legs, read as the computed `Total` failing at its target (the computation)
     * and the rule over the same filter reporting VALUE (validation), pinned against dynamic Groovy.
     */
    @Test
    fun theComputationWalkCountsEveryGroupTheProgrammeNamesAtTheSameDepth() {
        fun reached(model: String, doc: String): Pair<Boolean, Boolean> {
            val observed = underUtc { DocumentDifferential.kernel(model, documents.getValue(doc), dynamic = true) }
            return observed.operandErrors.any { it.code == "berechnungsWertFehler" && it.pointer.endsWith("/Total") } to
                observed.messages.any { it.code == "TotalRule" && it.type == "VALUE_ERROR" }
        }
        fun reached(variant: Variant, doc: String) = reached(models.getValue(variant), doc)
        val actual = linkedMapOf(
            "one leg, no reader" to reached(Variant.NONE, "one leg"),
            "three returns, no reader" to reached(Variant.NONE, "one leg, three returns"),
            "three returns, a computation reads them" to reached(Variant.READS_RETURNS, "one leg, three returns"),
            "three returns, a computation targets them" to reached(Variant.RETURNS_TARGET, "one leg, three returns"),
            "three returns, read only behind a precondition that fails" to
                reached(Variant.UNREACHED_READ, "one leg, three returns"),
            "three spares a fixed group deeper, a computation reads them" to
                reached(Variant.READS_SPARES, "one leg, three spares"),
            "three spares a fixed group deeper, no reader" to reached(Variant.NONE, "one leg, three spares"),
            "three returns read, legs of capacity 150" to reached(wideLegs, "one leg, three returns"),
        )
        assertEquals(linkedMapOf(
            "one leg, no reader" to (false to false),
            "three returns, no reader" to (false to true),
            "three returns, a computation reads them" to (true to true),
            "three returns, a computation targets them" to (true to true),
            "three returns, read only behind a precondition that fails" to (true to true),
            "three spares a fixed group deeper, a computation reads them" to (false to false),
            "three spares a fixed group deeper, no reader" to (false to false),
            "three returns read, legs of capacity 150" to (true to true),
        ), actual)
    }

    /**
     * Computing one field must see the walk the whole programme sees: `computeField` runs only the target's dependency
     * closure, and a computation outside it still widens the target's walk.
     */
    @Test
    fun computingOneFieldMatchesTheWholeComputation() {
        for ((variant, model) in models) for ((name, json) in documents) {
            val def = ModelLoader.load(model)
            val engine = DmInterpreter(def)
            val whole = underUtc { engine.compute(def.readDocumentJson(json)).filter { it.path == "/Parcel/Total" } }
            val one = underUtc { engine.computeField("/Parcel/Total", def.readDocumentJson(json)) }
            assertEquals(whole, one, "$variant, $name")
        }
    }

    /** The compute report's over-limit findings for the fourth `Returns` row, per model, against dynamic Groovy. */
    @Test
    fun anOverLimitRowIsReportedUnderComputeWhereTheProgrammeKeepsItsGroup() {
        fun reported(variant: Variant, row: String): List<String> =
            underUtc { DocumentDifferential.kernel(models.getValue(variant), overLimit.getValue(row), dynamic = true) }
                .operandErrors.filter { it.code.startsWith("zuGrosse") }.map { it.code + " " + it.pointer }.sorted()
        val actual = linkedMapOf(
            "no computation names Returns, a note" to reported(Variant.NONE, "a note"),
            "a computation reads the notes, a note" to reported(Variant.READS_RETURNS, "a note"),
            "a computation reads the notes, only a tag" to reported(Variant.READS_RETURNS, "only a tag"),
            "a computation targets the tags, a note" to reported(Variant.RETURNS_TARGET, "a note"),
            "a computation targets the tags, only a tag" to reported(Variant.RETURNS_TARGET, "only a tag"),
        )
        val row = "zuGrosseZeile /Parcel[1]/Returns"
        val note = "zuGrosseKontextnummer /Parcel[1]/Returns[4]/Note"
        // The row is reported wherever the programme keeps its group, and a field in it where the field is read or is
        // an uncomputed field of a computed target's group; the computed tag itself never is (KERNEL-FINDINGS KF538).
        assertEquals(linkedMapOf(
            "no computation names Returns, a note" to emptyList(),
            "a computation reads the notes, a note" to listOf(note, row),
            "a computation reads the notes, only a tag" to listOf(row),
            "a computation targets the tags, a note" to listOf(note, row),
            "a computation targets the tags, only a tag" to listOf(row),
        ), actual)
    }

    @Test
    fun everyModelIsKernelConsistentUnderTheAdmittingDefault() {
        assertEquals(emptyList<String>(), underUtc { (models.values + wideLegs).flatMap { MessageParameterProbes.kernel(it) } })
    }
}
