package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.md.document.apiV2.PartiallyKnownDocumentMultiPointer
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
 * The key a semantic index found no row for, as A12 keeps it on a possibly-causing entity's pointer
 * (`semanticIndexValueForUnknownIndexGroup`, KERNEL-FINDINGS KF683). It is the key converted to the index field's
 * basic type — a `String` for a text or enumeration index, a `BigDecimal` for a number one — and it is part of the
 * pointer's equality, so each missed key is its own member and `7` and `7.0` are two. An empty field key leaves a
 * member without one; a key field that is not formally valid leaves no member at all. The error pointer, the
 * referenced pointers and the fill-to-fix pointers never carry it.
 *
 * Each row is one condition over one document; every expected value is what both kernel strategies report, and the
 * interpreter must report the same. Own domain (an insurance portfolio's claims, fees and tiers).
 */
class UnknownIndexKeyLawsTest {

    @Test
    fun aMissedKeyIsKeptOnTheCauseMemberAsItsIndexFieldTypesIt() {
        val rows = linkedMapOf(
            "two text keys, the field key empty" to Row(TEXT_KEYS, bare),
            "two text keys, the field key B" to Row(TEXT_KEYS, bare.field(WANTED, "B", 1, 1)),
            "two text keys, the field key equal to the literal" to Row(TEXT_KEYS, bare.field(WANTED, "A", 1, 1)),
            "number keys of three scales" to Row(NUMBER_KEYS, bare.field(WANTED_NO, "8.50", 1, 1)),
            "number keys, the field key not a number" to Row(NUMBER_KEYS, bare.field(WANTED_NO, "x", 1, 1)),
            "number keys, the field key a number of three fraction digits" to Row(NUMBER_KEYS, bare.field(WANTED_NO, "8.505", 1, 1)),
            "an enumeration key" to Row(ENUM_KEY, bare),
            "a text join, no claim for the reserve's code" to Row(JOINS, RuntimeLaws.doc().group("/Portfolio", 1)
                .group("/Portfolio/Reserves", 1, 1).field("/Portfolio/Reserves/Code", "C", 1, 1, 1)
                .field("/Portfolio/Reserves/Held", "3", 1, 1, 1), TEXT_JOIN),
            "a number join, no credit for the fee's number stored with a scale" to Row(JOINS, RuntimeLaws.doc()
                .group("/Portfolio", 1).group("/Portfolio/Fees", 1, 1).field("/Portfolio/Fees/No", "7.50", 1, 1, 1)
                .field("/Portfolio/Fees/Rate", "3", 1, 1, 1), NUMBER_JOIN),
            "a number join of two declared fraction digits, no rebate for the levy's 7.00" to Row(JOINS, RuntimeLaws.doc()
                .group("/Portfolio", 1).group("/Portfolio/Levies", 1, 1).field("/Portfolio/Levies/No", "7.00", 1, 1, 1)
                .field("/Portfolio/Levies/Rate", "3", 1, 1, 1), SCALED_JOIN),
        )
        val expected = linkedMapOf(
            "two text keys, the field key empty" to listOf(
                "$CODE -", "$CODE String:A", "$COST -", "$COST String:A", "$OWNER -", "$WANTED_CELL -"),
            "two text keys, the field key B" to listOf(
                "$CODE String:A", "$CODE String:B", "$COST String:A", "$COST String:B", "$OWNER -"),
            "two text keys, the field key equal to the literal" to listOf("$CODE String:A", "$COST String:A", "$OWNER -"),
            "number keys of three scales" to listOf(
                "$NO BigDecimal:7", "$NO BigDecimal:7.0", "$NO BigDecimal:8.50",
                "$RATE BigDecimal:7", "$RATE BigDecimal:7.0", "$RATE BigDecimal:8.50", "$OWNER -"),
            "number keys, the field key not a number" to listOf(
                "$NO BigDecimal:7", "$NO BigDecimal:7.0", "$RATE BigDecimal:7", "$RATE BigDecimal:7.0", "$OWNER -"),
            "number keys, the field key a number of three fraction digits" to listOf(
                "$NO BigDecimal:7", "$NO BigDecimal:7.0", "$RATE BigDecimal:7", "$RATE BigDecimal:7.0", "$OWNER -"),
            "an enumeration key" to listOf("$OWNER -", "$CAP String:GOLD", "$LEVEL String:GOLD"),
            "a text join, no claim for the reserve's code" to listOf("$CODE String:C", "$COST String:C"),
            "a number join, no credit for the fee's number stored with a scale" to listOf("$CREDIT BigDecimal:7.5", "$CREDIT_NO BigDecimal:7.5"),
            "a number join of two declared fraction digits, no rebate for the levy's 7.00" to
                listOf("$REBATE BigDecimal:7.00", "$REBATE_NO BigDecimal:7.00"),
        )
        val dynamic = rows.mapValues { (_, row) -> kernel(row, dynamic = true) }
        val generated = rows.mapValues { (_, row) -> kernel(row, dynamic = false) }
        assertEquals(dynamic, generated, "static Java against dynamic Groovy")
        assertEquals(expected.mapValues { (_, members) -> Channels(members.sorted(), keyedOthers = emptyList()) }, dynamic, "dynamic Groovy")
        assertEquals(dynamic, rows.mapValues { (_, row) -> interpreter(row) }, "interpreter against dynamic Groovy")
    }

    private data class Row(val model: String, val doc: RuntimeLaws.Doc, val code: String = RULE)

    /** The first group's members, `pointer type:key`, and every error, referenced or fill-to-fix pointer that carries a key. */
    private data class Channels(val firstGroup: List<String>, val keyedOthers: List<String>)

    private fun kernel(row: Row, dynamic: Boolean): Channels {
        val messages = if (dynamic) RuntimeLaws.sharedKernelEngine(row.model).let { it.validationMessages(it.instance(row.doc), null, Locale.US) }
            else RuntimeLaws.sharedKernelJavaEngine(row.model).let { it.validationMessages(it.instance(row.doc), null, Locale.US) }
        val message = messages.single { it.errorCode == row.code }
        fun member(pointer: PartiallyKnownDocumentMultiPointer) =
            "$pointer ${pointer.semanticIndexValueForUnknownIndexGroup().map { "${it::class.simpleName}:$it" }.orElse("-")}"
        val others = listOf(message.errorFieldPointer) + message.referencedFieldsPointers + message.refOmissionErrorResponsiblePointers
        return Channels(message.groupsOfPossiblyCausingEntities.first().map(::member).sorted(),
            others.filter { it.semanticIndexValueForUnknownIndexGroup().isPresent }.map(::member).sorted())
    }

    private fun interpreter(row: Row): Channels {
        val definition = ModelLoader.load(row.model)
        val message = DmInterpreter(definition).validate(definition.readDocumentJson(RuntimeLaws.canonicalDocumentJson(row.model, row.doc)))
            .messages.single { it.code == row.code }
        fun spelling(pointer: A12PartiallyKnownMultiPointer) =
            pointer.fullName.trim('/').split('/').zip(pointer.repetitionIndexes).joinToString("") { (part, index) -> "/$part[$index]" }
        fun member(pointer: A12PartiallyKnownMultiPointer) =
            "${spelling(pointer)} ${pointer.semanticIndexValueForUnknownIndexGroup?.let { "${it::class.simpleName}:$it" } ?: "-"}"
        val others = listOf(message.errorPointer) + message.referencedPointers + message.fillToFixPointers
        return Channels(message.groupsOfPossiblyCausingEntities.first().map(::member).sorted(),
            others.filter { it.semanticIndexValueForUnknownIndexGroup != null }.map(::member).sorted())
    }

    private val bare get() = RuntimeLaws.doc().group("/Portfolio", 1)

    private companion object {
        const val RULE = "Misses"
        const val WANTED = "/Portfolio/Wanted"
        const val WANTED_NO = "/Portfolio/WantedNo"
        const val OWNER = "/Portfolio[1]/Owner[1]"
        const val WANTED_CELL = "/Portfolio[1]/Wanted[1]"
        const val CODE = "/Portfolio[1]/Claims[-5]/Code[1]"
        const val COST = "/Portfolio[1]/Claims[-5]/Cost[1]"
        const val NO = "/Portfolio[1]/Fees[-5]/No[1]"
        const val RATE = "/Portfolio[1]/Fees[-5]/Rate[1]"
        const val LEVEL = "/Portfolio[1]/Tiers[-5]/Level[1]"
        const val CAP = "/Portfolio[1]/Tiers[-5]/Cap[1]"
        const val TEXT_JOIN = "ReserveWithoutClaim"
        const val NUMBER_JOIN = "FeeWithoutCredit"
        const val CREDIT = "/Portfolio[1]/Credits[-5]/Amount[1]"
        const val CREDIT_NO = "/Portfolio[1]/Credits[-5]/No[1]"
        const val SCALED_JOIN = "LevyWithoutRebate"
        const val REBATE = "/Portfolio[1]/Rebates[-5]/Amount[1]"
        const val REBATE_NO = "/Portfolio[1]/Rebates[-5]/No[1]"

        val TEXT_KEYS = model("[Claims/Cost For \"A\"] > 1 Or [Claims/Cost For Wanted] > 1 Or FieldNotFilled(Owner)")
        val NUMBER_KEYS = model("[Fees/Rate For \"7\"] > 1 Or [Fees/Rate For \"7.0\"] > 1 Or [Fees/Rate For WantedNo] > 1 Or FieldNotFilled(Owner)")
        val ENUM_KEY = model("[Tiers/Cap For \"GOLD\"] > 1 Or FieldNotFilled(Owner)")

        /** Claims join Reserves on `Code`, and Fees join Credits on `No`, each rule naming the side with no row. */
        val JOINS = EditSession.read(model("FieldNotFilled(Owner)")).also { session ->
            session.addRule(DraftRule.builder().groupPath("/Portfolio").name(TEXT_JOIN).errorEntityRelPath("../Claims/Cost")
                .errorCondition("FieldFilled(Reserves/Held) And FieldNotFilled(Claims/Cost)").errorCode(TEXT_JOIN)
                .severity("ERROR").messages(listOf(LocalizedText("en_US", "A reserve needs its claim."))).build())
            session.addRule(DraftRule.builder().groupPath("/Portfolio").name(NUMBER_JOIN).errorEntityRelPath("../Credits/Amount")
                .errorCondition("FieldFilled(Fees/Rate) And FieldNotFilled(Credits/Amount)").errorCode(NUMBER_JOIN)
                .severity("ERROR").messages(listOf(LocalizedText("en_US", "A fee needs its credit."))).build())
            session.addRule(DraftRule.builder().groupPath("/Portfolio").name(SCALED_JOIN).errorEntityRelPath("../Rebates/Amount")
                .errorCondition("FieldFilled(Levies/Rate) And FieldNotFilled(Rebates/Amount)").errorCode(SCALED_JOIN)
                .severity("ERROR").messages(listOf(LocalizedText("en_US", "A levy needs its rebate."))).build())
        }.toJson()

        fun model(condition: String): String = EditSession.read(
            DocumentModelDraft.named("portfolio").locale("en_US")
                .rootGroup("Portfolio") { portfolio ->
                    portfolio.stringField("Owner") { }.stringField("Wanted") { }.numberField("WantedNo") { it.fractionDigits(0, 2) }
                        .group("Claims") { it.repeatable(3).indexField("Code").stringField("Code") { }.numberField("Cost") { } }
                        .group("Fees") { fees ->
                            fees.repeatable(3).indexField("No").numberField("No") { it.fractionDigits(0, 2) }.numberField("Rate") { }
                        }
                        .group("Reserves") { it.repeatable(3).indexField("Code").stringField("Code") { }.numberField("Held") { } }
                        .group("Credits") { credits ->
                            credits.repeatable(3).indexField("No").numberField("No") { it.fractionDigits(0, 2) }.numberField("Amount") { }
                        }
                        .group("Levies") { levies ->
                            levies.repeatable(3).indexField("No").numberField("No") { it.fractionDigits(2, 2) }.numberField("Rate") { }
                        }
                        .group("Rebates") { rebates ->
                            rebates.repeatable(3).indexField("No").numberField("No") { it.fractionDigits(2, 2) }.numberField("Amount") { }
                        }
                        .group("Tiers") { tiers ->
                            tiers.repeatable(3).indexField("Level").enumField("Level") { it.value("GOLD").value("SILVER") }
                                .numberField("Cap") { }
                        }
                }.build().toJson(),
        ).also { session ->
            session.addRule(DraftRule.builder().groupPath("/Portfolio").name(RULE).errorEntityRelPath("../Owner")
                .errorCondition(condition).errorCode(RULE).severity("ERROR")
                .messages(listOf(LocalizedText("en_US", "The portfolio needs an owner."))).build())
        }.toJson()
    }
}
