package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.core.PossibleCauseFilter
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.A12PartiallyKnownMultiPointer
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.readDocumentJson
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
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
            rule("/Policy", "Either", "Premium", "[Premium] > Sum(Lines*/Amount) Or FieldNotFilled(Holder)"),
            rule("/Policy", "RootGroup", "Premium", "FieldFilled(Premium) And GroupNotFilled(/Agent)"),
            rule("/Policy", "OtherRoot", "Premium", "FieldFilled(Premium) And FieldNotFilled(/Agent/Name)"),
            rule("/Policy", "Filtered", "Premium", "Sum(Lines*/Amount Having [Note] == \"x\") < [Premium]"),
            rule("/Policy", "GroupTarget", "Rider", "FieldFilled(Premium) And GroupFilled(Rider)"),
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
     * `BigDecimal` for a number one — so the two are different members of one group. The interpreter's pointer has
     * no such member, so it names each entity once (INTERPRETER-GAPS IG188); the `causes` channel of every
     * differential compares pointer keys, which both sides then agree on.
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
            mapOf("TwoMisses" to listOf(setOf(CODE, COST, NO, RATE, "/Ledger[1]/Owner[1]"), setOf())),
            interpreterCauses(ledger, "TwoMisses"),
        )
    }

    /** The kernel's groups per code, after asserting that the interpreter reports the same ones. */
    private fun causes(doc: RuntimeLaws.Doc, vararg codes: String): Map<String, List<Set<String>>> {
        val kernel = RuntimeLaws.validate(model, doc, Locale.US).messages.filter { it.errorCode in codes }
            .associate { message ->
                message.errorCode to message.groupsOfPossiblyCausingEntities.map { group -> group.map(Any::toString).toSet() }
            }
        assertEquals(kernel, interpreterCauses(doc, *codes), "interpreter vs kernel")
        return kernel
    }

    private fun interpreterCauses(doc: RuntimeLaws.Doc, vararg codes: String): Map<String, List<Set<String>>> {
        val definition = ModelLoader.load(model)
        return DmInterpreter(definition).validate(definition.readDocumentJson(RuntimeLaws.canonicalDocumentJson(model, doc)))
            .messages.filter { it.code in codes }
            .associate { message -> message.code to message.groupsOfPossiblyCausingEntities.map { group -> group.map(::kernelSpelling).toSet() } }
    }

    /** The kernel's pointer `toString`, `/Policy[1]/Premium[1]`, from the interpreter's pointer. */
    private fun kernelSpelling(pointer: A12PartiallyKnownMultiPointer): String =
        pointer.fullName.trim('/').split('/').zip(pointer.repetitionIndexes).joinToString("") { (part, index) -> "/$part[$index]" }

    private fun groups(vararg groups: Set<String>): List<Set<String>> = groups.toList()

    private companion object {
        const val PREMIUM = "/Policy[1]/Premium[1]"
        const val HOLDER = "/Policy[1]/Holder[1]"
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
