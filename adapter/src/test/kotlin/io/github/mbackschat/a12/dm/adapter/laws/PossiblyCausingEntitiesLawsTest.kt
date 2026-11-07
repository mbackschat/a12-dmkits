package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.md.rt.api.IMessage
import io.github.mbackschat.a12.dm.core.PossibleCauseFilter
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.A12PartiallyKnownMultiPointer
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.readDocumentJson
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * What kernel 31.3.1 reports as a message's possibly-causing entities, `IMessage.getGroupsOfPossiblyCausingEntities`
 * (KERNEL-FINDINGS KF683). Each row names the rule shape and the document state that decide it; the expected groups
 * are the kernel's own output, read through the raw pointer `toString`, and the interpreter must report the same.
 *
 * The grid varies one thing per row against [base]: the operator that references an entity (field, group, root
 * group, aggregate, `Having` filter), whether it is given at the firing row, the message type, and the rule's filter.
 */
class PossiblyCausingEntitiesLawsTest {

    private val model: String = EditSession.read(
        DocumentModelDraft.named("claims").locale("en_US")
            .rootGroup("Policy") { policy ->
                policy.numberField("Premium") { }.stringField("Holder") { }.stringField("Ref") { it.required() }
                    .group("Rider") { it.stringField("Code") { } }
                    .group("Lines") { it.repeatable(5).numberField("Amount") { }.stringField("Note") { } }
            }
            .rootGroup("Agent") { it.stringField("Name") { } }
            .rootGroup("Branch") { it.repeatable(3).stringField("City") { } }
            .rootGroup("Ledger") { ledger ->
                ledger.stringField("Owner") { }
                    .group("Claims") { it.repeatable(3).indexField("Code").stringField("Code") { }.numberField("Cost") { } }
                    .group("Fees") { it.repeatable(3).indexField("No").numberField("No") { }.numberField("Rate") { } }
            }
            .build().toJson(),
    ).also { session ->
        listOf(
            rule("/Policy", "OnlyEmpty", "Holder", "FieldNotFilled(Holder)"),
            rule("/Policy", "ValueOnly", "Premium", "[Premium] > 100"),
            rule("/Policy", "FilledAndEmpty", "Holder", "FieldFilled(Premium) And FieldNotFilled(Holder)"),
            rule("/Policy", "EmptyGroup", "Premium", "FieldFilled(Premium) And GroupNotFilled(Rider)"),
            rule("/Policy", "Aggregate", "Premium", "Sum(Lines*/Amount) < [Premium]"),
            rule("/Policy/Lines", "PerRow", "Note", "FieldFilled(Amount) And FieldNotFilled(Note)"),
            rule("/Policy", "FilterNotFilled", "Holder", "FieldFilled(Premium) And FieldNotFilled(Holder)",
                PossibleCauseFilter(listOf(), listOf("Holder"))),
            rule("/Policy", "FilterFilled", "Holder", "FieldFilled(Premium) And FieldNotFilled(Holder)",
                PossibleCauseFilter(listOf("Premium"), listOf())),
            rule("/Policy", "FilterEmpty", "Holder", "FieldFilled(Premium) And FieldNotFilled(Holder)",
                PossibleCauseFilter(listOf(), listOf())),
            rule("/Policy", "Either", "Premium", "[Premium] > Sum(Lines*/Amount) Or FieldNotFilled(Holder)"),
            rule("/Policy", "RootGroup", "Premium", "FieldFilled(Premium) And GroupNotFilled(/Agent)"),
            rule("/Policy", "OtherRoot", "Premium", "FieldFilled(Premium) And FieldNotFilled(/Agent/Name)"),
            rule("/Policy", "Filtered", "Premium", "Sum(Lines*/Amount Having [Note] == \"x\") < [Premium]"),
            rule("/Policy", "GroupTarget", "Rider", "FieldFilled(Premium) And GroupFilled(Rider)"),
            rule("/Policy", "RootAggregate", "Holder", "FieldNotFilled(Holder) And NumberOfFilledGroups(/Branch*) < 9"),
            rule("/Policy", "NestedAggregate", "Holder", "FieldNotFilled(Holder) And NumberOfFilledGroups(Lines*) < 9"),
            rule("/Policy", "RootOrValue", "Premium", "GroupNotFilled(/Agent) Or [Premium] > 100"),
            rule("/Policy", "OtherRootOrValue", "Premium", "FieldNotFilled(/Agent/Name) Or [Premium] > 100"),
            rule("/Ledger", "RnuUnfiltered", "Claims/Cost", "RepetitionNotUnique(Claims/Cost) And FieldFilled(Owner)"),
            rule("/Ledger", "RnuOwnerOnly", "Claims/Cost", "RepetitionNotUnique(Claims/Cost) And FieldFilled(Owner)",
                PossibleCauseFilter(listOf("Owner"), listOf())),
            rule("/Ledger", "RnuKeyFilled", "Claims/Cost", "RepetitionNotUnique(Claims/Cost) And FieldFilled(Owner)",
                PossibleCauseFilter(listOf("Claims/Cost"), listOf())),
            rule("/Ledger", "TwoMisses", "Owner",
                "[Claims/Cost For \"A\"] > 1 Or [Claims/Cost For \"B\"] > 1 Or [Fees/Rate For \"7\"] > 1 Or FieldNotFilled(Owner)"),
        ).forEach(session::addRule)
    }.toJson()

    /** Premium filled, Holder, Rider and Agent empty, two of five Lines rows with an Amount, the second with a Note. */
    private val base = RuntimeLaws.doc().group("/Policy", 1).field("/Policy/Premium", "200", 1, 1)
        .group("/Policy/Lines", 1, 1).group("/Policy/Lines", 1, 2)
        .field("/Policy/Lines/Amount", "5", 1, 1, 1).field("/Policy/Lines/Amount", "7", 1, 2, 1)
        .field("/Policy/Lines/Note", "x", 1, 2, 1)

    @Test
    fun anOmissionNamesTheEmptyEntitiesFirstAndTheFilledOnesSecond() {
        assertEquals(
            mapOf(
                "OnlyEmpty" to groups(setOf(HOLDER), setOf()),
                "FilledAndEmpty" to groups(setOf(HOLDER), setOf(PREMIUM)),
                "Either" to groups(setOf(AMOUNTS, HOLDER), setOf(PREMIUM)),
                "PerRow" to groups(setOf("/Policy[1]/Lines[1]/Note[1]"), setOf("/Policy[1]/Lines[1]/Amount[1]")),
                "OtherRoot" to groups(setOf("/Agent[1]/Name[1]"), setOf(PREMIUM)),
            ),
            causes(base, "OnlyEmpty", "FilledAndEmpty", "Either", "PerRow", "OtherRoot"),
        )
    }

    @Test
    fun aValueErrorNamesEveryEntityInOneGroup() {
        val riderFilled = base.group("/Policy/Rider", 1, 1).field("/Policy/Rider/Code", "C", 1, 1, 1)
        assertEquals(
            mapOf("ValueOnly" to groups(setOf(PREMIUM)), "GroupTarget" to groups(setOf(PREMIUM, "/Policy[1]/Rider[1]"))),
            causes(riderFilled, "ValueOnly", "GroupTarget"),
        )
    }

    /** A group predicate names the group itself, a root group included, where the fill-to-fix channel names fields. */
    @Test
    fun aGroupIsNamedAsTheGroup() {
        assertEquals(
            mapOf(
                "EmptyGroup" to groups(setOf("/Policy[1]/Rider[1]"), setOf(PREMIUM)),
                "RootGroup" to groups(setOf("/Agent[1]"), setOf(PREMIUM)),
            ),
            causes(base, "EmptyGroup", "RootGroup"),
        )
    }

    /**
     * An aggregated level is the wildcard 0. It is given when one repetition is filled and empty while one of the
     * group's repetitions up to its capacity is not, absent rows included; when both hold, the entity stays only in
     * the empty group. A `Having` filter's operand is an entity too.
     */
    @Test
    fun anAggregateIsEmptyUntilEveryRepetitionIsFilled() {
        assertEquals(
            mapOf(
                "Aggregate" to groups(setOf(AMOUNTS), setOf(PREMIUM)),
                "Filtered" to groups(setOf(AMOUNTS, NOTES), setOf(PREMIUM)),
            ),
            causes(base, "Aggregate", "Filtered"),
        )
        val everyRow = RuntimeLaws.doc().group("/Policy", 1).field("/Policy/Premium", "200", 1, 1)
        (1..5).forEach { row -> everyRow.group("/Policy/Lines", 1, row).field("/Policy/Lines/Amount", "1", 1, row, 1) }
        assertEquals(
            mapOf(
                "Aggregate" to groups(setOf(AMOUNTS, PREMIUM)),
                "Filtered" to groups(setOf(NOTES), setOf(AMOUNTS, PREMIUM)),
            ),
            causes(everyRow, "Aggregate", "Filtered"),
        )
    }

    /** A filter keeps only its listed entities in their own list; the other list then names none. */
    @Test
    fun aFilterReplacesTheEntitiesOfBothLists() {
        assertEquals(
            mapOf("FilterNotFilled" to groups(setOf(HOLDER), setOf()), "FilterFilled" to groups(setOf(), setOf(PREMIUM))),
            causes(base, "FilterNotFilled", "FilterFilled"),
        )
    }

    /**
     * A stored filter whose two lists are both empty is no filter: A12 derives the causes as for an unfiltered rule,
     * so the rule names what `FilledAndEmpty`, its unfiltered twin, names. The rival reading, a filter that lets no
     * entity through, predicts `[[], []]`.
     */
    @Test
    fun aFilterWithBothListsEmptyLeavesTheCausesDerived() {
        assertEquals(mapOf("FilterEmpty" to groups(setOf(HOLDER), setOf(PREMIUM))), causes(base, "FilterEmpty"))
    }

    /**
     * A `RepetitionNotUnique` key cell is a candidate like any other, so a filter that does not name the key leaves
     * the duplicate cluster out, and one that names it in `ifFilled` keeps only its given cells. The rival reading,
     * the cluster joined after the filter, predicts the key cells under every filter.
     */
    @Test
    fun aDuplicateClusterPassesThroughTheFilter() {
        val duplicates = RuntimeLaws.doc().group("/Ledger", 1).field("/Ledger/Owner", "O", 1, 1)
            .group("/Ledger/Claims", 1, 1).field("/Ledger/Claims/Code", "A", 1, 1, 1).field("/Ledger/Claims/Cost", "5", 1, 1, 1)
            .group("/Ledger/Claims", 1, 2).field("/Ledger/Claims/Code", "B", 1, 2, 1).field("/Ledger/Claims/Cost", "5", 1, 2, 1)
        val keys = setOf("/Ledger[1]/Claims[1]/Cost[1]", "/Ledger[1]/Claims[2]/Cost[1]")
        assertEquals(
            mapOf(
                "RnuUnfiltered" to List(2) { groups(keys + OWNER) },
                "RnuOwnerOnly" to List(2) { groups(setOf(OWNER)) },
                "RnuKeyFilled" to List(2) { groups(keys) },
            ),
            everyMessage(duplicates, null, "RnuUnfiltered", "RnuOwnerOnly", "RnuKeyFilled"),
        )
    }

    /**
     * An aggregated ROOT group is not given even when every repetition up to its capacity is filled, where a nested
     * aggregated group filled to capacity is given. The rival reading, the capacity walk for both, puts `/Branch[0]`
     * in the given group.
     */
    @Test
    fun anAggregatedRootGroupIsNeverGiven() {
        val full = RuntimeLaws.doc().group("/Policy", 1)
        (1..3).forEach { row -> full.group("/Branch", row).field("/Branch/City", "C$row", row, 1) }
        (1..5).forEach { row -> full.group("/Policy/Lines", 1, row).field("/Policy/Lines/Amount", "1", 1, row, 1) }
        assertEquals(
            mapOf(
                "RootAggregate" to groups(setOf(HOLDER, "/Branch[0]"), setOf()),
                "NestedAggregate" to groups(setOf(HOLDER), setOf("/Policy[1]/Lines[0]")),
            ),
            causes(full, "RootAggregate", "NestedAggregate"),
        )
    }

    /**
     * Under partial validation an entity outside the relevant set is asked like any other. A root group is asked
     * through A12's own root-group branch, where an undecided answer counts as not given; a field of another root
     * through the entity quantifiers, where it counts as neither.
     */
    @Test
    fun anIrrelevantEntityIsAskedUnderPartialValidation() {
        val agentFilled = base.group("/Agent", 1).field("/Agent/Name", "N", 1, 1)
        for (doc in listOf(base, agentFilled)) {
            assertEquals(
                mapOf(
                    "RootOrValue" to listOf(groups(setOf("/Agent[1]", PREMIUM))),
                    "OtherRootOrValue" to listOf(groups(setOf(PREMIUM))),
                ),
                everyMessage(doc, "/Policy", "RootOrValue", "OtherRootOrValue"),
            )
        }
    }

    /** A formal check names only the cell it reports on, in one group, an omission included. */
    @Test
    fun aFormalCheckNamesItsOwnCell() {
        val invalid = RuntimeLaws.doc().group("/Policy", 1).field("/Policy/Premium", "abc", 1, 1)
        assertEquals(
            mapOf("zahlHatUngueltigeZeichen" to groups(setOf(PREMIUM)), "mandatoryField" to groups(setOf("/Policy[1]/Ref[1]"))),
            causes(invalid, "zahlHatUngueltigeZeichen", "mandatoryField"),
        )
    }

    /**
     * Two keys that select no row each name the unknown row, and A12 keeps both: its pointer carries the key that
     * missed (`semanticIndexValueForUnknownIndexGroup`), typed as the index field is — text for a string index, a
     * `BigDecimal` for a number one — so the two are different members of one group. Here the interpreter's groups
     * are read as address sets; `UnknownIndexKeyLawsTest` holds it to each key.
     */
    @Test
    fun aMissedKeyIsNamedOncePerKey() {
        val ledger = RuntimeLaws.doc().group("/Ledger", 1)
        val kernel = RuntimeLaws.validate(model, ledger, Locale.US).messages.single { it.errorCode == "TwoMisses" }
            .groupsOfPossiblyCausingEntities
        assertEquals(
            listOf(
                listOf("$CODE String:A", "$CODE String:B", "$COST String:A", "$COST String:B", "$NO BigDecimal:7",
                    "$RATE BigDecimal:7", "/Ledger[1]/Owner[1] -"),
                listOf(),
            ),
            kernel.map { group ->
                group.map { "$it ${it.semanticIndexValueForUnknownIndexGroup().map { key -> "${key::class.simpleName}:$key" }.orElse("-")}" }
                    .sorted()
            },
        )
        assertEquals(
            mapOf("TwoMisses" to listOf(listOf(setOf(CODE, COST, NO, RATE, "/Ledger[1]/Owner[1]"), setOf()))),
            interpreterCauses(ledger, null, "TwoMisses"),
        )
    }

    /** The kernel's groups per code, after asserting that the interpreter reports the same ones; one message per code. */
    private fun causes(doc: RuntimeLaws.Doc, vararg codes: String): Map<String, List<Set<String>>> =
        everyMessage(doc, null, *codes).mapValues { (_, messages) -> messages.single() }

    /**
     * Every message's groups per code, in a stable order, after asserting that generated Java and the interpreter
     * report what dynamic Groovy reports; a partial validation when [relevant] is given. Every message is compared,
     * so a code that fires on several rows cannot hide one behind another.
     */
    private fun everyMessage(doc: RuntimeLaws.Doc, relevant: String?, vararg codes: String): Map<String, List<List<Set<String>>>> {
        val selection = relevant?.let { setOf(RuntimeLaws.Relevant.of(it, 1)) }
        val dynamic = RuntimeLaws.sharedKernelEngine(model).let { engine ->
            if (selection == null) engine.validationMessages(engine.instance(doc), null, Locale.US)
            else engine.partialValidationMessages(engine.instance(doc), selection, null, Locale.US)
        }
        val generated = RuntimeLaws.sharedKernelJavaEngine(model).let { engine ->
            if (selection == null) engine.validationMessages(engine.instance(doc), null, Locale.US)
            else engine.partialValidationMessages(engine.instance(doc), selection, null, Locale.US)
        }
        val kernel = kernelCauses(dynamic, codes)
        assertEquals(kernel, kernelCauses(generated, codes), "generated Java vs dynamic Groovy")
        assertEquals(kernel, interpreterCauses(doc, relevant, *codes), "interpreter vs dynamic Groovy")
        return kernel
    }

    private fun kernelCauses(messages: List<IMessage>, codes: Array<out String>) = byCode(messages.filter { it.errorCode in codes }
        .map { message -> message.errorCode to message.groupsOfPossiblyCausingEntities.map { group -> group.map(Any::toString).toSet() } })

    private fun interpreterCauses(doc: RuntimeLaws.Doc, relevant: String?, vararg codes: String): Map<String, List<List<Set<String>>>> {
        val definition = ModelLoader.load(model)
        val document = definition.readDocumentJson(RuntimeLaws.canonicalDocumentJson(model, doc))
        val interpreter = DmInterpreter(definition)
        val report = if (relevant == null) interpreter.validate(document)
            else interpreter.validatePart(document, setOf(RelevantEntity(relevant, listOf(1))))
        return byCode(report.messages.filter { it.code in codes }
            .map { message -> message.code to message.groupsOfPossiblyCausingEntities.map { group -> group.map(::kernelSpelling).toSet() } })
    }

    private fun byCode(messages: List<Pair<String, List<Set<String>>>>): Map<String, List<List<Set<String>>>> =
        messages.groupBy({ it.first }, { it.second }).mapValues { (_, groups) -> groups.sortedBy(Any::toString) }

    /** The kernel's pointer `toString`, `/Policy[1]/Premium[1]`, from the interpreter's pointer. */
    private fun kernelSpelling(pointer: A12PartiallyKnownMultiPointer): String =
        pointer.fullName.trim('/').split('/').zip(pointer.repetitionIndexes).joinToString("") { (part, index) -> "/$part[$index]" }

    private fun groups(vararg groups: Set<String>): List<Set<String>> = groups.toList()

    private companion object {
        const val PREMIUM = "/Policy[1]/Premium[1]"
        const val HOLDER = "/Policy[1]/Holder[1]"
        const val OWNER = "/Ledger[1]/Owner[1]"
        const val AMOUNTS = "/Policy[1]/Lines[0]/Amount[1]"
        const val NOTES = "/Policy[1]/Lines[0]/Note[1]"
        const val CODE = "/Ledger[1]/Claims[-5]/Code[1]"
        const val COST = "/Ledger[1]/Claims[-5]/Cost[1]"
        const val NO = "/Ledger[1]/Fees[-5]/No[1]"
        const val RATE = "/Ledger[1]/Fees[-5]/Rate[1]"

        fun rule(group: String, name: String, target: String, condition: String, filter: PossibleCauseFilter? = null) =
            DraftRule.builder().groupPath(group).name(name).errorEntityRelPath("../$target").errorCondition(condition)
                .errorCode(name).severity("ERROR").messages(listOf(LocalizedText("en_US", "Check $name.")))
                .also { builder -> filter?.let(builder::possibleCauses) }.build()
    }
}
