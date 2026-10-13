package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.FirstFilledOperand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A required-empty field is unavailable to every authored read, not only to a value comparison (KERNEL-FINDINGS
 * KF552). Each rule here would fire through a required-empty cell if that cell read as definitely empty: presence
 * tests, the field-list quantifiers, fill and value counts, a first-filled selection over a field and over an
 * enumeration category, the value aggregates, `SumOfProducts`, a value-list quantifier and group presence, filtered
 * and unfiltered, over a parent-relative requirement, a repeatable one and an absolute one in an absent group. Each
 * required operand has an optional twin (`Lane`, `Rank`, `Bonus`, `Remark`, `Office`) the same rules fire on.
 *
 * Own domain (an insurance claim's holder and its covers).
 */
class RequiredEmptyReadDiffTest {

    private val model: String = run {
        val session = EditSession.read(DocumentModelDraft.named("claim-required-reads").locale("en_US")
            .rootGroup("Claim") { c -> c.stringField("Ref") { }
                .group("Holder") { h -> h.stringField("City") { }.stringField("Street") { it.requiredIfParentPresent() }
                    .stringField("Lane") { }
                    .enumField("Grade") { it.value("BASIC").value("PLUS").category("Band", "LOW", "HIGH").requiredIfParentPresent() }
                    .enumField("Rank") { it.value("BASIC").value("PLUS").category("Band", "LOW", "HIGH") } }
                .group("Covers") { v -> v.repeatable(3).numberField("Amount") { it.fractionDigits(0).required() }
                    .numberField("Bonus") { it.fractionDigits(0) }
                    .stringField("Label") { it.required() }.stringField("Remark") { } }
                .group("Contact") { it.stringField("Phone") { f -> f.required() } }
                .group("Office") { it.stringField("Phone") { } } }
            .build().toJson())
        val holder = session.group("/Claim/Holder")
        val city = holder.field("City")
        for (name in listOf("Street", "Lane")) {
            val field = holder.field(name)
            val rules: List<Pair<String, Condition>> = listOf(
                "NotFilled" to Dsl.fieldNotFilled(field),
                "NoneFilled" to Dsl.noFieldFilled(field),
                "NotAll" to Dsl.notAllFieldsFilled(field, city),
                "NotCollectively" to Dsl.fieldsNotCollectivelyFilled(field, city),
                "Count" to Dsl.numberOfFilledFields(field, city).lt(2),
                "FirstFilled" to Dsl.firstFilledString(field, city).eq("Linz"),
                "NoneInList" to Dsl.noFieldValueIncludedInValueList(listOf("z"), holder.str(name), holder.str("City")),
            )
            for ((suffix, condition) in rules) holder.addRule {
                it.name("$name$suffix").code("$name$suffix").errorField(field).`when`(condition)
                    .message(Dsl.en("Check the holder's address.")).build()
            }
        }
        for (name in listOf("Grade", "Rank")) holder.addRule {
            it.name("${name}Band").code("${name}Band").errorField(holder.field(name))
                .`when`(Dsl.firstFilledString(holder.enumOf(name).category("Band").firstFilledOperand(),
                    FirstFilledOperand.of(city)).eq("Linz"))
                .message(Dsl.en("Check the holder's grade.")).build()
        }
        val claim = session.group("/Claim")
        for ((name, path) in listOf("Amount" to "Covers*/Amount", "Bonus" to "Covers*/Bonus")) {
            val column = claim.flatten(path)
            val rules: List<Pair<String, Condition>> = listOf(
                "NoneFilled" to Dsl.noFieldFilled(column),
                "NotAll" to Dsl.notAllFieldsFilled(column),
                "Count" to Dsl.numberOfFilledFields(column).lt(2),
                "FirstFilled" to Dsl.firstFilledNumber(column).eq(5),
            )
            for ((suffix, condition) in rules) claim.addRule {
                it.name("$name$suffix").code("$name$suffix").errorField(claim.field("Ref"))
                    .`when`(claim.str("Ref").filled().and(condition))
                    .message(Dsl.en("Check the covers.")).build()
            }
        }
        // The rest of the list readers, each over the required column and its optional twin.
        val bonusEmpty = session.group("/Claim/Covers").num("Bonus").notFilled()
        for ((name, other) in listOf("Amount" to "Bonus", "Bonus" to "Bonus")) {
            val column = claim.flatten("Covers*/$name")
            val kept = claim.flatten("Covers*/$name").having(bonusEmpty)
            val rules: List<Pair<String, Condition>> = listOf(
                "MoreThanOne" to Dsl.moreThanOneFieldFilled(column),
                "NotExactlyOne" to Dsl.notExactlyOneFieldFilled(column),
                "Sum" to Dsl.sum(column).lt(100),
                "Max" to Dsl.maxValue(column).lt(100),
                "Min" to Dsl.minValue(column).lt(100),
                "Distinct" to Dsl.numberOfDifferentValues(column).lt(5),
                "ValueCount" to Dsl.numberOfValueInFields(5, column).eq(2),
                "NotUnique" to Dsl.fieldValuesNotUnique(column),
                "Products" to Dsl.sumOfProducts(column, claim.flatten("Covers*/$other")).lt(100),
                "KeptSum" to Dsl.sum(kept).lt(100),
                "KeptCount" to Dsl.numberOfFilledFields(kept).lt(1),
                "KeptValueCount" to Dsl.numberOfValueInFields(5, kept).lt(1),
            )
            for ((suffix, condition) in rules) claim.addRule {
                it.name("$name$suffix").code("$name$suffix").errorField(claim.field("Ref"))
                    .`when`(claim.str("Ref").filled().and(condition)).message(Dsl.en("Check the covers.")).build()
            }
        }
        for (name in listOf("Label", "Remark")) {
            val column = claim.flatten("Covers*/$name")
            val rules: List<Pair<String, Condition>> = listOf(
                "NoneInList" to Dsl.noFieldValueIncludedInValueList(listOf("z"), column),
                "ValueCount" to Dsl.numberOfValueInFields("a", column).eq(2),
            )
            for ((suffix, condition) in rules) claim.addRule {
                it.name("$name$suffix").code("$name$suffix").errorField(claim.field("Ref"))
                    .`when`(claim.str("Ref").filled().and(condition)).message(Dsl.en("Check the covers.")).build()
            }
        }
        for (name in listOf("Contact", "Office")) {
            val group = claim.group(name)
            val rules: List<Pair<String, Condition>> = listOf(
                "NotFilled" to Dsl.groupNotFilled(group),
                "NoneFilled" to Dsl.noGroupFilled(group),
            )
            for ((suffix, condition) in rules) claim.addRule {
                it.name("$name$suffix").code("$name$suffix").errorField(claim.field("Ref"))
                    .`when`(claim.str("Ref").filled().and(condition)).message(Dsl.en("Check the contact.")).build()
            }
        }
        session.toJson()
    }

    private val documents = linkedMapOf(
        "a holder with a city and an empty cover row" to
            """{"Claim":{"Ref":"C-1","Holder":{"City":"Linz"},"Covers":[{"Amount":5,"Bonus":5},{}]}}""",
        "a required-empty first cover row" to
            """{"Claim":{"Ref":"C-1","Holder":{"City":"Linz","Street":"Main","Grade":"BASIC"},"Covers":[{},{"Amount":5,"Bonus":5}]}}""",
        "two covers around a required-empty row" to """{"Claim":{"Ref":"C-1","Holder":{},"Covers":[""" +
            """{"Amount":5,"Bonus":5,"Label":"a","Remark":"a"},{},{"Amount":5,"Bonus":5,"Label":"a","Remark":"a"}]}}""",
        "an empty holder" to """{"Claim":{"Ref":"C-1","Holder":{},"Covers":[{"Amount":5,"Bonus":5}]}}""",
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
     * The computation channel, where a plain required-empty cell stays an ordinary empty read and only the empty
     * index cell the preliminary index check invalidates is unavailable: each tier guard reads presence, so an
     * available empty cell selects the second tier and an unavailable one poisons the instance, and a count over
     * the index column, filtered or not, is poisoned too.
     */
    private val computed: String = run {
        val session = EditSession.read(DocumentModelDraft.named("claim-required-computed").locale("en_US")
            .rootGroup("Claim") { c -> c.numberField("CodeCount") { it.fractionDigits(0) }
                .numberField("KeptCodeCount") { it.fractionDigits(0) }
                .group("Covers") { v -> v.repeatable(3).stringField("Code") { }.numberField("Amount") { it.fractionDigits(0).required() }
                    .numberField("CodeTier") { it.fractionDigits(0) }.numberField("AmountTier") { it.fractionDigits(0) }
                    .indexField("Code") } }
            .build().toJson())
        val covers = session.group("/Claim/Covers")
        for (name in listOf("Code", "Amount")) {
            val read = covers.field(name)
            covers.addComputation(covers.num("${name}Tier")) {
                it.name("${name}TierComp").`when`(Dsl.fieldFilled(read)).calc(Dsl.number(1))
                    .`when`(Dsl.fieldNotFilled(read)).calc(Dsl.number(2))
                    .message(Dsl.en("Check the cover tier.")).build()
            }
        }
        val root = session.group("/Claim")
        root.addComputation(root.num("CodeCount")) {
            it.name("CodeCountComp").calc(Dsl.numberOfFilledFields(root.flatten("Covers*/Code")))
                .message(Dsl.en("Check the code count.")).build()
        }
        root.addComputation(root.num("KeptCodeCount")) {
            it.name("KeptCodeCountComp").calc(Dsl.numberOfFilledFields(root.flatten("Covers*/Code")
                .having(covers.num("Amount").notFilled()))).message(Dsl.en("Check the code count.")).build()
        }
        session.toJson()
    }

    @Test
    fun theComputationChannelReadsOnlyThePreliminaryIndexCellAsUnavailable() {
        val input = mapOf("a cover row with neither code nor amount" to """{"Claim":{"Covers":[{}]}}""")
        DocumentDifferential.assertAgree(computed, input)
        // CodeTier, CodeCount and KeptCodeCount are poisoned, and a never-filled poisoned target reports nothing.
        assertEquals(mapOf("Covers/AmountTier" to "VALUE|2"),
            DocumentDifferential.kernel(computed, input.values.single(), dynamic = true).outcomes)
    }

    /**
     * The required side of every rule, per document, and for each read the required cell silences, its optional twin
     * firing in the same document. A reader decided by the cells it knows still fires: two filled rows settle
     * `MoreThanOneFieldFilled`, `NotExactlyOneFieldFilled` and `FieldValuesNotUnique`, a filled first row settles
     * `FirstFilledValue`, and a missing third row is an ordinary empty for `NotAllFieldsFilled`.
     */
    @Test
    fun theKernelSilencesEveryReadOfTheRequiredEmptyCell() {
        fun fired(document: String) = DocumentDifferential.kernelMessages(model, documents.getValue(document), true)
            .map { it.code }.toSet()
        fun requiredSide(fired: Set<String>) = fired.filter { REQUIRED.any(it::startsWith) }.toSortedSet()
        fun assertSilenced(document: String, required: Set<String>, silenced: List<String>) {
            val codes = fired(document)
            assertEquals(required.toSortedSet(), requiredSide(codes), document)
            for (code in silenced) {
                val twin = TWINS.entries.single { code.startsWith(it.key) }.let { (from, to) -> to + code.removePrefix(from) }
                assertEquals(true, twin in codes && code !in codes, "$document: $code silent, $twin fires")
            }
        }
        val holderReads = listOf("Count", "FirstFilled", "NoneFilled", "NoneInList", "NotAll", "NotCollectively", "NotFilled")
        val columnReads = listOf("Count", "Distinct", "KeptCount", "KeptSum", "KeptValueCount", "Max", "Min", "Products", "Sum")
        assertSilenced("a holder with a city and an empty cover row", setOf("AmountFirstFilled", "AmountNotAll"),
            holderReads.map { "Street$it" } + "GradeBand" + columnReads.map { "Amount$it" } + "LabelNoneInList" +
                listOf("ContactNotFilled", "ContactNoneFilled"))
        assertSilenced("a required-empty first cover row", setOf("AmountNotAll", "StreetNoneInList"),
            listOf("AmountFirstFilled") + columnReads.map { "Amount$it" })
        assertSilenced("two covers around a required-empty row", setOf("AmountFirstFilled", "AmountMoreThanOne",
            "AmountNotExactlyOne", "AmountNotUnique", "StreetCount", "StreetNoneFilled", "StreetNoneInList", "StreetNotAll",
            "StreetNotFilled"), listOf("AmountNotAll", "AmountValueCount", "LabelValueCount"))
        // Nothing in the holder, so Street is not required and reads as an ordinary empty; no cover row lacks an amount.
        assertSilenced("an empty holder", setOf("AmountCount", "AmountDistinct", "AmountFirstFilled", "AmountKeptCount",
            "AmountKeptSum", "AmountKeptValueCount", "AmountMax", "AmountMin", "AmountNotAll", "AmountProducts", "AmountSum",
            "StreetCount", "StreetNoneFilled", "StreetNoneInList", "StreetNotAll", "StreetNotFilled"), emptyList())
    }

    private companion object {
        /** Each required operand and its optional twin. */
        val TWINS = mapOf("Street" to "Lane", "Grade" to "Rank", "Amount" to "Bonus", "Label" to "Remark", "Contact" to "Office")
        val REQUIRED = TWINS.keys
    }
}
