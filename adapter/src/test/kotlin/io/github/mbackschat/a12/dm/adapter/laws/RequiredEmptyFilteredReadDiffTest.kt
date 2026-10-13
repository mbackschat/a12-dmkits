package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The presence quantifiers over a FILTERED list, and the computation's presence quantifiers, meet a required-empty
 * cell as KERNEL-FINDINGS KF552 says every authored read does: unavailable, not definitely empty. Two filters are
 * read: a plain one kept by an empty `Note`, and a correlated one, `Having $Kind = Kind`, from a rule iterating the
 * covers. Each required column has an optional twin (`Bonus`) the same rules fire on.
 *
 * Own domain (an insurance claim's covers).
 */
class RequiredEmptyFilteredReadDiffTest {

    private val model: String = run {
        val session = EditSession.read(DocumentModelDraft.named("claim-required-filtered").locale("en_US")
            .rootGroup("Claim") { c -> c.stringField("Ref") { }
                .numberField("Limit") { it.fractionDigits(0).required() }.numberField("Extra") { it.fractionDigits(0) }
                .numberField("Spare") { it.fractionDigits(0) }
                .group("Covers") { v -> v.repeatable(3).numberField("Amount") { it.fractionDigits(0).required() }
                    .numberField("Bonus") { it.fractionDigits(0) }.stringField("Note") { }.stringField("Kind") { } } }
            .build().toJson())
        val claim = session.group("/Claim")
        val covers = session.group("/Claim/Covers")
        val kind = covers.str("Kind")
        for (name in listOf("Amount", "Bonus")) {
            val kept = claim.flatten("Covers*/$name").having(covers.str("Note").notFilled())
            val rules: List<Pair<String, Condition>> = listOf(
                "KeptNone" to Dsl.noFieldFilled(kept),
                "KeptNotAll" to Dsl.notAllFieldsFilled(kept),
                "KeptNotOne" to Dsl.notExactlyOneFieldFilled(kept),
                "KeptOrSpareNone" to Dsl.noFieldFilled(kept, claim.field("Spare")),
            )
            for ((suffix, condition) in rules) claim.addRule {
                it.name("$name$suffix").code("$name$suffix").errorField(claim.field("Ref"))
                    .`when`(claim.str("Ref").filled().and(condition)).message(Dsl.en("Check the covers.")).build()
            }
            val sameKind = claim.flatten("Covers*/$name").having(kind.outer().eq(kind))
            val correlated: List<Pair<String, Condition>> = listOf(
                "SameKindNone" to Dsl.noFieldFilled(sameKind),
                "SameKindNotAll" to Dsl.notAllFieldsFilled(sameKind),
                "SameKindNotOne" to Dsl.notExactlyOneFieldFilled(sameKind),
            )
            for ((suffix, condition) in correlated) covers.addRule {
                it.name("$name$suffix").code("$name$suffix").errorField(kind.field())
                    .`when`(kind.filled().and(condition)).message(Dsl.en("Check the cover.")).build()
            }
        }
        for (name in listOf("Limit", "Extra")) claim.addRule {
            it.name("${name}NoFive").code("${name}NoFive").errorField(claim.field("Ref"))
                .`when`(claim.str("Ref").filled().and(Dsl.numberOfValueInFields(5L, claim.field(name), claim.field("Spare")).eq(0)))
                .message(Dsl.en("Check the limits.")).build()
        }
        session.toJson()
    }

    private val documents = linkedMapOf(
        "a noted cover and an empty one of the same kind" to
            """{"Claim":{"Ref":"C-1","Covers":[{"Amount":5,"Bonus":5,"Note":"n","Kind":"A"},{"Kind":"A"}]}}""",
        "two empty covers of the same kind" to """{"Claim":{"Ref":"C-1","Covers":[{"Kind":"A"},{"Kind":"A"}]}}""",
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
     * The kernel's own verdicts: every required-column rule silent and its `Bonus` twin firing in the same document,
     * except `AmountKeptNotAll`, which the declared third slot settles: absent, kept by its empty `Note`, and an
     * ordinary empty, never required.
     */
    @Test
    fun theKernelSilencesTheRequiredColumnAndFiresItsTwin() {
        fun fired(document: String) = DocumentDifferential.kernelMessages(model, documents.getValue(document), true)
            .map { it.code }.filter { it != "mandatoryField" }.toSortedSet()
        assertEquals(mapOf(
            "a noted cover and an empty one of the same kind" to
                sortedSetOf("AmountKeptNotAll", "BonusKeptNone", "BonusKeptNotAll", "BonusKeptNotOne", "BonusKeptOrSpareNone",
                    "BonusSameKindNotAll", "ExtraNoFive"),
            "two empty covers of the same kind" to
                sortedSetOf("AmountKeptNotAll", "BonusKeptNone", "BonusKeptNotAll", "BonusKeptNotOne", "BonusKeptOrSpareNone",
                    "BonusSameKindNone", "BonusSameKindNotAll", "BonusSameKindNotOne", "ExtraNoFive"),
        ), documents.keys.associateWith(::fired))
    }

    /**
     * The computation's presence quantifiers and its starred count: the empty index cell the preliminary index check
     * invalidates is unavailable and poisons the tier and the count, while a plain required-empty cell stays an
     * ordinary empty read there.
     */
    private val computed: String = run {
        val session = EditSession.read(DocumentModelDraft.named("claim-required-quantified").locale("en_US")
            .rootGroup("Claim") { c -> c.numberField("CodeTier") { it.fractionDigits(0) }
                .numberField("AmountTier") { it.fractionDigits(0) }
                .numberField("CodeCount") { it.fractionDigits(0) }.numberField("AmountCount") { it.fractionDigits(0) }
                .numberField("RankSum") { it.fractionDigits(0) }
                .group("Tiers") { t -> t.repeatable(3).numberField("Rank") { it.fractionDigits(0) }.indexField("Rank") }
                .group("Covers") { v -> v.repeatable(3).stringField("Code") { }
                    .numberField("Amount") { it.fractionDigits(0).required() }.indexField("Code") } }
            .build().toJson())
        val root = session.group("/Claim")
        for (name in listOf("Code", "Amount")) {
            val column = root.flatten("Covers*/$name")
            root.addComputation(root.num("${name}Tier")) {
                it.name("${name}TierComp").`when`(Dsl.atLeastOneFieldFilled(column)).calc(Dsl.number(1))
                    .`when`(Dsl.noFieldFilled(column)).calc(Dsl.number(2))
                    .message(Dsl.en("Check the cover tier.")).build()
            }
            root.addComputation(root.num("${name}Count")) {
                it.name("${name}CountComp").calc(Dsl.numberOfFilledFields(column))
                    .message(Dsl.en("Check the cover count.")).build()
            }
        }
        root.addComputation(root.num("RankSum")) {
            it.name("RankSumComp").calc(Dsl.sum(root.flatten("Tiers*/Rank"))).message(Dsl.en("Check the ranks.")).build()
        }
        session.toJson()
    }

    @Test
    fun theComputationsPresenceQuantifierReadsOnlyThePreliminaryIndexCellAsUnavailable() {
        val input = mapOf("a cover row with neither code nor amount" to """{"Claim":{"Covers":[{}],"Tiers":[{}]}}""")
        DocumentDifferential.assertAgree(computed, input)
        assertEquals(mapOf("AmountCount" to "VALUE|0", "AmountTier" to "VALUE|2"),
            DocumentDifferential.kernel(computed, input.values.single(), dynamic = true).outcomes)
    }
}
