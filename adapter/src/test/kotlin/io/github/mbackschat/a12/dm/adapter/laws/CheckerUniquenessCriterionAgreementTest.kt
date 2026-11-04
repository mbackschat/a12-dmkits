package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.KernelConsistencyOracle
import io.github.mbackschat.a12.dm.consistency.ConsistencyReport
import io.github.mbackschat.a12.dm.consistency.ConsistencyWorkspace
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.core.ModelResolver
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.MountOptions
import io.github.mbackschat.a12.dm.modelkit.UniquenessCriterion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A12's document uniqueness criterion sentences against the portable checker's criterion family (KERNEL-FINDINGS
 * KF671, CONSISTENCY-GAPS DG177).
 *
 * Kernel 31.1.1's `DocumentUniquenessCriteriaRule` states every name two criteria share first, then each criterion's
 * name, field list, each field, each declared locale's text and each text's grammar. Each row compares A12's classes,
 * in A12's order, with the checker's codes, each beside the criterion it names: A12's sentence quotes the name and the
 * checker's explanation backticks it, and without the name the sentences two criteria share would be indistinguishable. The rows that combine classes are the ones that settle the order and
 * whether one class stops the next; the single-class rows are their controls. Own domain (insurance policies).
 */
class CheckerUniquenessCriterionAgreementTest {

    private val oracle = KernelConsistencyOracle()

    private fun policy(): DocumentModelDraft.Builder = DocumentModelDraft.named("policy-criteria").locale("en_US").locale("de_DE")
        .rootGroup("Policy") { p ->
            p.stringField("PolicyNo") { it.required() }.stringField("Product") { it.required() }
                .stringField("Holder") { it.notPersisted() }
                .group("Office") { o -> o.stringField("Branch") { it.requiredIfParentPresent() } }
                .group("Claims") { c -> c.repeatable(3).group("Detail") { d -> d.stringField("Note") { it.required() } } }
        }

    private fun key(name: String, vararg fields: String, en: String? = "Taken", de: String? = "Vergeben") =
        UniquenessCriterion(name, fields.toList(), LinkedHashMap<String, String>().apply {
            en?.let { put("en_US", it) }
            de?.let { put("de_DE", it) }
        })

    private fun with(vararg criteria: UniquenessCriterion): String =
        criteria.fold(policy()) { model, criterion -> model.uniquenessCriterion(criterion) }.build().toJson()

    @Test
    fun theCheckerStatesEveryCriterionSentenceAsA12Does() {
        val rows = linkedMapOf(
            "a sound criterion — the control" to with(key("PolicyKey", "/Policy/PolicyNo", "/Policy/Product")),
            "an empty name" to with(key("", "/Policy/PolicyNo")),
            "an empty name over an optional field" to with(key("", "/Policy/Holder")),
            "no field" to with(key("PolicyKey")),
            "no field and no text" to with(key("PolicyKey", en = null, de = null)),
            "a field that does not exist" to with(key("PolicyKey", "/Policy/Missing")),
            "a group in the field slot" to with(key("PolicyKey", "/Policy/Office")),
            "a relative field path" to with(key("PolicyKey", "PolicyNo")),
            "a field named with .value" to with(key("PolicyKey", "/Policy/PolicyNo.value")),
            "a missing field before an optional one" to with(key("PolicyKey", "/Policy/Missing", "/Policy/Holder")),
            "an optional transient field" to with(key("PolicyKey", "/Policy/Holder")),
            "an optional transient field named twice" to with(key("PolicyKey", "/Policy/Holder", "/Policy/Holder")),
            "a field required only with its parent" to with(key("PolicyKey", "/Policy/Office/Branch")),
            "a field below a repeatable group's subgroup" to with(key("PolicyKey", "/Policy/Claims/Detail/Note")),
            "no text for the second locale" to with(key("PolicyKey", "/Policy/PolicyNo", de = null)),
            "no text for either locale" to with(key("PolicyKey", "/Policy/PolicyNo", en = null, de = null)),
            "an empty text" to with(key("PolicyKey", "/Policy/PolicyNo", de = "")),
            "a blank text" to with(key("PolicyKey", "/Policy/PolicyNo", de = " ")),
            "a text naming the field with .value" to with(key("PolicyKey", "/Policy/PolicyNo", en = "No \$/Policy/PolicyNo.value\$")),
            "a text naming a field outside the criterion" to with(key("PolicyKey", "/Policy/PolicyNo", en = "No \$/Policy/Product\$")),
            "a text naming two fields outside the criterion" to
                with(key("PolicyKey", "/Policy/PolicyNo", en = "No \$/Policy/Product\$ \$PolicyNo\$")),
            "both texts naming a field outside the criterion" to
                with(key("PolicyKey", "/Policy/PolicyNo", en = "No \$/Policy/Product\$", de = "Nr \$/Policy/Product\$")),
            "an unterminated parameter beside a bad one" to
                with(key("PolicyKey", "/Policy/PolicyNo", en = "No \$/Policy/Product\$ \$/Policy/PolicyNo")),
            "a text with a line break beside a bad parameter" to
                with(key("PolicyKey", "/Policy/PolicyNo", en = "No \$/Policy/Product\$\nagain")),
            "a text with a control character" to with(key("PolicyKey", "/Policy/PolicyNo", en = "Taken\u0007")),
            "a text with a lone surrogate" to with(key("PolicyKey", "/Policy/PolicyNo", en = "Taken\uD800")),
            "a missing text beside a bad text" to with(key("PolicyKey", "/Policy/PolicyNo", en = "No \$/Policy/Product\$", de = null)),
            "every class of one criterion" to
                with(key("PolicyKey", "/Policy/Missing", "/Policy/Holder", en = "No \$/Policy/Product\$", de = null)),
            "a refused rule beside an unterminated parameter" to RuntimeLaws.withRule(
                with(key("PolicyKey", "/Policy/PolicyNo", en = "No \$/Policy/PolicyNo")), "/Policy", "NoCheck", "../PolicyNo",
                "NO", "FieldFilled(Nowhere)"),
            "two criteria of one name" to with(key("PolicyKey", "/Policy/PolicyNo"), key("PolicyKey", "/Policy/Product")),
            // `ProductKey` is asked first, but `PolicyKey` sits in the lower `HashMap` bucket (3 against 14), so A12's
            // grouping states `PolicyKey` first, against insertion order.
            "two names shared twice, interleaved" to with(key("ProductKey", "/Policy/Product"), key("PolicyKey", "/Policy/PolicyNo"),
                key("ProductKey", "/Policy/PolicyNo"), key("PolicyKey", "/Policy/Product")),
            "a shared name beside another criterion's missing field" to with(key("HolderKey", "/Policy/Holder"),
                key("PolicyKey", "/Policy/PolicyNo"), key("PolicyKey", "/Policy/Missing")),
        )
        val observed = rows.mapValues { (_, json) -> a12(json, ReferenceResolvers.selfContained()) to ours(check(ModelSource(json))) }
        assertEquals(observed.mapValues { (_, it) -> it.first to it.first }, observed, "(row → A12's classes in order, the checker's)")
        assertEquals(listOf("$NOT_REQUIRED@PolicyKey", "$TRANSIENT@PolicyKey"), observed.getValue("an optional transient field").first,
            "the control the multi-class rows rest on: A12 states both demands of one field")
        assertEquals(setOf("WRONG_NUMBER_OF_DOLLARS@PolicyKey", "MVK_INVALID_ENTITY@(unnamed)"),
            observed.getValue("a refused rule beside an unterminated parameter").first.toSet(),
            "a criterion has no DSL beside it, so a refused rule condition withholds none of its error-text classes")
        assertEquals(listOf("WRONG_ENCODING@PolicyKey"), observed.getValue("a text with a lone surrogate").first,
            "the encoding gate is asked of a criterion text, not only of a rule message")
    }

    /**
     * Expansion drops an included model's own criteria, so a criterion over a missing field in the provider is judged
     * nowhere, while the host's own criterion over a mounted field is judged on the expanded model. The control is the
     * host's criterion over a field that is missing, which both state.
     */
    @Test
    fun onlyTheEntryModelsOwnCriteriaAreJudged() {
        val branch = DocumentModelDraft.named("branch").locale("en_US").locale("de_DE")
            .rootGroup("Branch") { b -> b.stringField("Code") { it.required() } }
            .uniquenessCriterion(key("BranchKey", "/Branch/Missing"))
            .build().toJson()
        fun host(vararg criteria: UniquenessCriterion) = criteria.fold(DocumentModelDraft.named("policy-host").locale("en_US")
            .locale("de_DE").rootGroup("Policy") { p ->
                p.stringField("PolicyNo") { it.required() }.mount("Office", "branch", MountOptions.single())
            }) { model, criterion -> model.uniquenessCriterion(criterion) }.build().toJson()
        val rows = linkedMapOf(
            "the host's criterion over a missing field — the control" to host(key("PolicyKey", "/Policy/Missing")),
            "the provider's criterion over a missing field" to host(),
            "the host's criterion over a mounted field" to host(key("OfficeKey", "/Policy/Office/Code")),
        )
        val resolver = ModelResolver { asked -> if (asked == "branch") branch else null }
        val observed = rows.mapValues { (_, json) ->
            a12(json, resolver) to
                ours(ConsistencyWorkspace.fromSources(listOf(ModelSource(json), ModelSource(branch))).check("policy-host"))
        }
        assertEquals(observed.mapValues { (_, it) -> it.first to it.first }, observed, "(row → A12's classes in order, the checker's)")
        assertEquals(listOf("$NOT_FOUND@PolicyKey"), observed.getValue("the host's criterion over a missing field — the control").first,
            "the host's criteria are judged at all, so the silent rows are not silent for want of a sentence")
    }

    private fun a12(json: String, resolver: ModelResolver): List<String> = oracle.checkConsistency(json, resolver).map {
        val message = it.message().trim()
        "${legibleNotificationClass(message)}@${named(A12_NAME.find(message))}"
    }

    private fun ours(report: ConsistencyReport): List<String> = report.findings.filter { it.justifiesRejection }
        .map { "${it.code}@${named(OUR_NAME.find(it.explain))}" }

    /** A criterion's name as a sentence carries it; A12's empty-name sentence quotes none, so an empty one reads alike. */
    private fun named(match: MatchResult?): String = match?.groupValues?.get(1)?.ifEmpty { null } ?: "(unnamed)"

    private companion object {
        const val NOT_FOUND = "RK_CRITERION_FIELD_NOT_FOUND"
        const val NOT_REQUIRED = "RK_CRITERION_FIELD_NOT_REQUIRED"
        const val TRANSIENT = "RK_CRITERION_FIELD_TRANSIENT"
        val A12_NAME = Regex("""^Document Uniqueness Criterion '([^']*)':""")
        val OUR_NAME = Regex("""criterion `([^`]*)`""")
    }
}
