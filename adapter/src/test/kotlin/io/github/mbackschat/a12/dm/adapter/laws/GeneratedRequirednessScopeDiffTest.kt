package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Where A12's generated requiredness runs (KERNEL-FINDINGS KF552, a12-kernel-lean SPEC-2026-10-13-06): per
 * instantiated in-capacity row, never above capacity; a parent-relative requirement only once its group holds an
 * admitted value; under partial validation only where the field itself is selected, with the group gate reading
 * selected content only. A required-empty field is unavailable to an authored rule reading it.
 *
 * `AMOUNT_CAP` asks for a filled amount first: under partial validation an authored rule also runs on the declared
 * rows the document lacks (KERNEL-FINDINGS KF553), a separate mechanism this class keeps out of its denominator.
 *
 * Own domain (an insurance policy, its holder, its covers and their terms, and a fleet of vehicles).
 */
class GeneratedRequirednessScopeDiffTest {

    private val model: String = run {
        val session = EditSession.read(DocumentModelDraft.named("policy-requiredness").locale("en_US")
            .rootGroup("Policy") { p -> p
                .numberField("Premium") { it.fractionDigits(0).required() }.stringField("Marker") { }
                .group("Holder") { h -> h.stringField("City") { }.numberField("Zip") { it.fractionDigits(0) }
                    .stringField("Street") { it.requiredIfParentPresent() }.stringField("Phone") { it.required() } }
                .group("Covers") { c -> c.repeatable(3).numberField("Amount") { it.fractionDigits(0).required() }
                    .group("Terms") { t -> t.stringField("Wording") { it.requiredIfParentPresent() }
                        .stringField("Clause") { it.required() } } }
                .group("Fleet") { f -> f.stringField("Plate") { it.requiredIfParentPresent() }
                    .group("Vehicles") { it.repeatable(4).stringField("Vin") { } } } }
            .build().toJson())
        val policy = session.group("/Policy")
        policy.addRule {
            it.name("PremiumCap").code("PREMIUM_CAP").errorField(policy.num("Premium"))
                .`when`(policy.num("Premium").lt(100)).message(Dsl.en("The premium is below the floor.")).build()
        }
        val covers = session.group("/Policy/Covers")
        covers.addRule {
            it.name("AmountCap").code("AMOUNT_CAP").errorField(covers.num("Amount"))
                .`when`(covers.num("Amount").filled().and(covers.num("Amount").lt(100))).message(Dsl.en("The cover amount is below the floor.")).build()
        }
        val holder = session.group("/Policy/Holder")
        holder.addRule {
            it.name("StreetMissing").code("STREET_MISSING").errorField(holder.str("Street"))
                .`when`(holder.str("Street").notFilled()).message(Dsl.en("Give the holder's street.")).build()
        }
        session.toJson()
    }

    private fun policy(vararg members: String) = """{"Policy":{"Premium":150${members.joinToString("") { ",$it" }}}}"""

    private val documents = linkedMapOf(
        "an empty document" to """{"Policy":{}}""",
        "a holder with a city" to policy(""""Holder":{"City":"Linz"}"""),
        "a holder with only a malformed zip" to policy(""""Holder":{"Zip":"abc"}"""),
        "an empty holder object" to policy(""""Holder":{}"""),
        "an empty vehicle row" to policy(""""Fleet":{"Vehicles":[{}]}"""),
        "an empty cover row" to policy(""""Covers":[{}]"""),
        "a cover whose terms hold a clause" to policy(""""Covers":[{"Amount":200,"Terms":{"Clause":"C1"}}]"""),
        "a fourth cover above capacity" to
            policy(""""Covers":[{"Amount":200,"Terms":{"Clause":"C1"}},{"Amount":200,"Terms":{"Clause":"C2"}},""" +
                """{"Amount":200,"Terms":{"Clause":"C3"}},{}]"""),
        "a low premium, filled" to """{"Policy":{"Premium":50}}""",
    )

    @Test
    fun theModelIsKernelConsistent() {
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model))
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        DocumentDifferential.assertAgree(model, documents)
    }

    /**
     * Full validation: requiredness per instantiated in-capacity row, a parent-relative requirement only once its
     * group holds content (an empty vehicle row is content, a malformed zip is not), and a required-empty `Street`
     * unavailable to the authored `STREET_MISSING` rule that fires wherever `Street` is not required.
     */
    @Test
    fun requirednessRunsPerInstantiatedRowAndOnlyBehindHeldContent() {
        fun findings(document: String) = DocumentDifferential.kernelMessages(model, documents.getValue(document), true)
            .map { "${it.code}@${it.pointer.removePrefix("/Policy[1]/")}" }.sorted()
        assertEquals(listOf("STREET_MISSING@Holder[1]/Street", "mandatoryField@Holder[1]/Phone", "mandatoryField@Premium"),
            findings("an empty document"))
        assertEquals(listOf("mandatoryField@Holder[1]/Phone", "mandatoryField@Holder[1]/Street"), findings("a holder with a city"))
        assertEquals(listOf("STREET_MISSING@Holder[1]/Street", "mandatoryField@Holder[1]/Phone", "zahlHatUngueltigeZeichen@Holder[1]/Zip"),
            findings("a holder with only a malformed zip"))
        assertEquals(listOf("STREET_MISSING@Holder[1]/Street", "mandatoryField@Fleet[1]/Plate", "mandatoryField@Holder[1]/Phone"),
            findings("an empty vehicle row"))
        assertEquals(listOf("STREET_MISSING@Holder[1]/Street", "mandatoryField@Covers[1]/Amount",
            "mandatoryField@Covers[1]/Terms[1]/Clause", "mandatoryField@Holder[1]/Phone"), findings("an empty cover row"))
        assertEquals(listOf("STREET_MISSING@Holder[1]/Street", "mandatoryField@Covers[1]/Terms[1]/Wording",
            "mandatoryField@Covers[2]/Terms[1]/Wording", "mandatoryField@Covers[3]/Terms[1]/Wording",
            "mandatoryField@Holder[1]/Phone", "zuGrosseZeile@Covers"), findings("a fourth cover above capacity"))
    }

    /**
     * The report lists every generated requirement before any authored rule's message (a12-kernel-lean
     * SPEC-2026-10-13-06). Compared in report order, not sorted: sorted, `mandatoryField` would come last.
     */
    @Test
    fun generatedRequirednessIsReportedBeforeAuthoredRules() {
        val input = """{"Policy":{"Premium":50,"Holder":{}}}"""
        fun kernel(dynamic: Boolean) = (if (dynamic) RuntimeLaws.kernelEngine(model).let { it.validationMessages(it.instance(input), null, Locale.US) }
            else RuntimeLaws.kernelJavaEngine(model).let { it.validationMessages(it.instance(input), null, Locale.US) })
            .map { "${it.errorCode}@${DocumentDifferential.pointer(it.errorFieldPointer).removePrefix("/Policy[1]/")}" }
        val prepared = PreparedModel.loadExpandedJson(model)
        val interpreted = prepared.createInterpreter().validateFull(prepared.documents.readDocument(input)).findings
            .map { "${it.code}@${DocumentDifferential.pointer(it.errorPath).removePrefix("/Policy[1]/")}" }
        val expected = listOf("mandatoryField@Holder[1]/Phone[1]", "STREET_MISSING@Holder[1]/Street[1]", "PREMIUM_CAP@Premium[1]")
        assertEquals(expected, kernel(dynamic = true), "dynamic Groovy")
        assertEquals(expected, kernel(dynamic = false), "static Java")
        assertEquals(expected, interpreted, "interpreter")
    }

    /** Partial validation: the field must be selected, and the parent gate reads selected content only. A
     *  coordinate of 0 is a wildcard, so the cover amount's selection names every row. */
    @Test
    fun partialValidationRequiresTheFieldAndReadsSelectedContent() {
        val input = policy(""""Holder":{"City":"Linz"}""", """"Covers":[{}]""")
        val selections = linkedMapOf(
            "nothing" to emptySet(),
            "the marker" to setOf(RelevantEntity("/Policy/Marker", listOf(0, 0))),
            "every cover's amount" to setOf(RelevantEntity.anyRep("/Policy/Covers/Amount")),
            "the first cover's amount" to setOf(RelevantEntity("/Policy/Covers/Amount", listOf(0, 1, 0))),
            "the missing second cover's amount" to setOf(RelevantEntity("/Policy/Covers/Amount", listOf(0, 2, 0))),
            "only the street" to setOf(RelevantEntity("/Policy/Holder/Street", listOf(0, 0, 0))),
            "the holder" to setOf(RelevantEntity("/Policy/Holder", listOf(0, 0))),
        )
        val observed = selections.mapValues { (label, selected) ->
            val dynamic = DocumentDifferential.kernelMessages(model, input, true, selected)
            assertEquals(dynamic, DocumentDifferential.kernelMessages(model, input, false, selected), "$label: static")
            assertEquals(dynamic, DocumentDifferential.interpreterMessages(model, input, selected), "$label: interpreter")
            dynamic.map { "${it.code}@${it.pointer.removePrefix("/Policy[1]/")}" }.sorted()
        }
        assertEquals(mapOf(
            "nothing" to emptyList(),
            "the marker" to emptyList(),
            "every cover's amount" to listOf("mandatoryField@Covers[1]/Amount"),
            "the first cover's amount" to listOf("mandatoryField@Covers[1]/Amount"),
            "the missing second cover's amount" to emptyList(),
            // City is not selected, so the holder holds no selected content and Street is an available empty.
            "only the street" to listOf("STREET_MISSING@Holder[1]/Street"),
            "the holder" to listOf("mandatoryField@Holder[1]/Phone", "mandatoryField@Holder[1]/Street"),
        ), observed)
    }
}
