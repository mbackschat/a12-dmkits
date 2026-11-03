package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.DocumentDifferential.underUtc
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * What a computed target's group puts in scope when that group holds a REPEATABLE subgroup (KERNEL-FINDINGS KF538,
 * KF528). `Returns` (capacity 3) holds `Marks` (capacity 3); `Legs` sits under the fixed `Route`, so its rows are at
 * the depth of `Marks`' rows. Every model computes the root `Total` over the legs with [FilterCacheScopeDiffTest]'s
 * filter, which fails exactly when the walk reaches a leg no row stores, and at most one more computation reads the
 * marks, targets the `Returns` tags, or targets the grades inside `Marks`, declared there or in `Returns`.
 *
 * Each row discriminates two accounts: a target names only its own ancestors, or it names everything its innermost
 * repeatable group contains, nested repeatable groups included; and the innermost repeatable group alone, or every
 * repeatable ancestor of the target.
 *
 * Own domain (parcel returns with inspection marks).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ComputeTargetSubgroupScopeDiffTest {
    private enum class Variant { ROOT_ONLY, READS_MARKS, TARGETS_TAGS, TARGETS_GRADES, TARGETS_GRADES_FROM_RETURNS }

    private companion object {
        const val ISO_DT = "yyyy-MM-dd'T'HH:mm:ss"

        /** A Julian leap day, which Europe/Berlin cannot read: the label fails when reached (KERNEL-FINDINGS KF665). */
        const val GAP = "29.02.1500T10:00:00"
        const val LEG = """{"Weight":"7","Stamp":"2024-03-31T01:30:00"}"""
    }

    private val models = Variant.entries.associateWith(::build)

    private fun build(variant: Variant): String = underUtc {
        val draft = DocumentModelDraft.named("parcel-target-subgroup").locale("en_US")
            .rootGroup("Parcel") { p ->
                p.stringField("Ref") { }
                p.numberField("Base") { n -> n.fractionDigits(0) }
                p.group("Route") { r -> r.group("Legs") { l -> l.repeatable(3)
                    .numberField("Weight") { n -> n.fractionDigits(0) }
                    .dateTimeField("Stamp") { it.format(ISO_DT) } } }
                p.group("Returns") { r -> r.repeatable(3).stringField("Note") { }
                    .numberField("Qty") { n -> n.fractionDigits(0) }
                    .numberField("Tag") { n -> n.fractionDigits(0) }
                    .group("Marks") { m -> m.repeatable(3).numberField("Mark") { n -> n.fractionDigits(0) }
                        .numberField("Grade") { n -> n.fractionDigits(0) } } }
                p.numberField("Total") { n -> n.fractionDigits(0) }
                p.numberField("Count") { n -> n.fractionDigits(0) }
            }.build().toJson()
        val session = EditSession.read(draft).also { it.config().timeZone("Europe/Berlin") }
        val p = session.group("/Parcel")
        val legs = session.group("/Parcel/Route/Legs")
        val returns = session.group("/Parcel/Returns")
        val marks = session.group("/Parcel/Returns/Marks")
        val total = Dsl.sum(p.flatten("Route/Legs*/Weight")
            .having(legs.num("Weight").gt(0).or(legs.dateTime("Stamp").eq(Dsl.dateTimeValue(GAP)))))
        var json = TypedRules.inject(session.toJson(), p.computation(p.num("Total")) {
            it.name("TotalComp").calc(total).message(Dsl.en("Check the legs.")).build()
        })
        val base = p.num("Base").plus(1)
        json = when (variant) {
            Variant.ROOT_ONLY -> json
            Variant.READS_MARKS -> TypedRules.inject(json, p.computation(p.num("Count")) {
                it.name("CountComp").calc(Dsl.numberOfFilledFields(p.flatten("Returns*/Marks*/Mark")))
                    .message(Dsl.en("Check the marks.")).build()
            })
            Variant.TARGETS_TAGS -> TypedRules.inject(json, returns.computation(returns.num("Tag")) {
                it.name("TagComp").calc(base).message(Dsl.en("Check the returns.")).build()
            })
            Variant.TARGETS_GRADES -> TypedRules.inject(json, marks.computation(marks.num("Grade")) {
                it.name("GradeComp").calc(base).message(Dsl.en("Check the marks.")).build()
            })
            Variant.TARGETS_GRADES_FROM_RETURNS -> TypedRules.inject(json, returns.computation(marks.num("Grade")) {
                it.name("GradeComp").calc(base).message(Dsl.en("Check the marks.")).build()
            })
        }
        json
    }

    private fun parcel(vararg returns: String) =
        """{"Parcel":{"Ref":"P-1","Base":"4","Route":{"Legs":[$LEG]},"Returns":[${returns.joinToString(",")}]}}"""

    private fun marks(count: Int) = List(count) { """{"Mark":"1"}""" }.joinToString(",")

    private val documents = linkedMapOf(
        "an invalid quantity and mark" to parcel("""{"Note":"r","Qty":"1.5","Marks":[{"Mark":"2.5"}]}"""),
        "a fourth mark" to parcel("""{"Note":"r","Marks":[${marks(4)}]}"""),
        "three marks, one leg" to parcel("""{"Note":"r","Marks":[${marks(3)}]}"""),
    )

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        underUtc { models.values.forEach { DocumentDifferential.assertAgree(it, documents) } }
    }

    /** The compute report's formal and over-limit findings, and whether the walk reached a missing leg, against dynamic Groovy. */
    @Test
    fun aTargetsGroupScopeAgainstItsNestedRepeatableGroup() {
        fun observed(variant: Variant, doc: String): List<String> {
            val errors = underUtc { DocumentDifferential.kernel(models.getValue(variant), documents.getValue(doc), dynamic = true) }
                .operandErrors
            return errors.filter { it.code != "berechnungsWertFehler" }.map { it.code + " " + it.pointer }.sorted() +
                (if (errors.any { it.code == "berechnungsWertFehler" && it.pointer.endsWith("/Total") }) listOf("walk reached") else emptyList())
        }
        val actual = LinkedHashMap<String, List<String>>()
        for (variant in Variant.entries) for (doc in documents.keys) actual["$variant, $doc"] = observed(variant, doc)

        val qty = "zahlHatDezimalTrenner /Parcel[1]/Returns[1]/Qty"
        val mark = "zahlHatDezimalTrenner /Parcel[1]/Returns[1]/Marks[1]/Mark"
        val marksRow = "zuGrosseZeile /Parcel[1]/Returns[1]/Marks"
        val fourthMark = "zuGrosseKontextnummer /Parcel[1]/Returns[1]/Marks[4]/Mark"
        val walk = "walk reached"
        assertEquals(linkedMapOf(
            "ROOT_ONLY, an invalid quantity and mark" to emptyList(),
            "ROOT_ONLY, a fourth mark" to emptyList(),
            "ROOT_ONLY, three marks, one leg" to emptyList(),
            "READS_MARKS, an invalid quantity and mark" to listOf(mark),
            "READS_MARKS, a fourth mark" to listOf(fourthMark, marksRow, walk),
            "READS_MARKS, three marks, one leg" to listOf(walk),
            "TARGETS_TAGS, an invalid quantity and mark" to listOf(mark, qty),
            "TARGETS_TAGS, a fourth mark" to listOf(fourthMark, marksRow, walk),
            "TARGETS_TAGS, three marks, one leg" to listOf(walk),
            "TARGETS_GRADES, an invalid quantity and mark" to listOf(mark),
            "TARGETS_GRADES, a fourth mark" to listOf(fourthMark, marksRow, walk),
            "TARGETS_GRADES, three marks, one leg" to listOf(walk),
            "TARGETS_GRADES_FROM_RETURNS, an invalid quantity and mark" to listOf(mark),
            "TARGETS_GRADES_FROM_RETURNS, a fourth mark" to listOf(fourthMark, marksRow, walk),
            "TARGETS_GRADES_FROM_RETURNS, three marks, one leg" to listOf(walk),
        ), actual)
    }

    @Test
    fun everyModelIsKernelConsistent() {
        assertEquals(emptyList<String>(), underUtc { models.values.flatMap { MessageParameterProbes.kernel(it) } })
    }
}
