package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.md.document.apiV2.PartiallyKnownDocumentMultiPointer
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.A12PartiallyKnownMultiPointer
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.interpreter.eval.readDocumentJson
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.ModelDocument
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeFactory
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldValidator
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldType
import java.util.Date
import java.util.Optional
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
            "date keys, a literal and a later field key" to Row(DATE_KEYS, bare.field(WANTED_DAY, "2026-05-06", 1, 1)),
            "date keys, the field key equal to the literal" to Row(DATE_KEYS, bare.field(WANTED_DAY, "2026-01-02", 1, 1)),
            "a month key" to Row(MONTH_KEY, bare),
            "a year key and a key in another format" to Row(PARTIAL_DATE_KEYS, bare),
            "a year-less key" to Row(YEARLESS_KEY, bare),
            "a year-less key under a declared base year" to Row(YEARLESS_KEY_IN_2024, bare),
            "custom-typed keys, a literal and a field key" to Row(CUSTOM_KEYS, bare.field(WANTED_REF, "007.50", 1, 1)),
            "a text join, no claim for the reserve's code" to Row(JOINS, RuntimeLaws.doc().group("/Portfolio", 1)
                .group("/Portfolio/Reserves", 1, 1).field("/Portfolio/Reserves/Code", "C", 1, 1, 1)
                .field("/Portfolio/Reserves/Held", "3", 1, 1, 1), TEXT_JOIN),
            "a number join, no credit for the fee's number stored with a scale" to Row(JOINS, RuntimeLaws.doc()
                .group("/Portfolio", 1).group("/Portfolio/Fees", 1, 1).field("/Portfolio/Fees/No", "7.50", 1, 1, 1)
                .field("/Portfolio/Fees/Rate", "3", 1, 1, 1), NUMBER_JOIN),
            "a number join of two declared fraction digits, no rebate for the levy's 7.00" to Row(JOINS, RuntimeLaws.doc()
                .group("/Portfolio", 1).group("/Portfolio/Levies", 1, 1).field("/Portfolio/Levies/No", "7.00", 1, 1, 1)
                .field("/Portfolio/Levies/Rate", "3", 1, 1, 1), SCALED_JOIN),
            "a number join, the missing side declaring two fraction digits" to Row(JOINS, RuntimeLaws.doc()
                .group("/Portfolio", 1).group("/Portfolio/Fees", 1, 1).field("/Portfolio/Fees/No", "7.5", 1, 1, 1)
                .field("/Portfolio/Fees/Rate", "3", 1, 1, 1), FEE_WITHOUT_REBATE),
            "a number join, the present side declaring two fraction digits" to Row(JOINS, RuntimeLaws.doc()
                .group("/Portfolio", 1).group("/Portfolio/Levies", 1, 1).field("/Portfolio/Levies/No", "7.00", 1, 1, 1)
                .field("/Portfolio/Levies/Rate", "3", 1, 1, 1), LEVY_WITHOUT_CREDIT),
            "an enumeration join, no tier for the grade's level" to Row(JOINS, RuntimeLaws.doc()
                .group("/Portfolio", 1).group("/Portfolio/Grades", 1, 1).field("/Portfolio/Grades/Level", "GOLD", 1, 1, 1)
                .field("/Portfolio/Grades/Weight", "3", 1, 1, 1), GRADE_WITHOUT_TIER),
            "time and date-time keys" to Row(TEMPORAL_KEYS, bare),
            "a date and a time key, the model in UTC" to Row(TEMPORAL_KEYS_IN_UTC, bare),
            "an error re-anchored onto the missed row" to Row(REANCHORED, bare),
            "two text keys under partial validation of the portfolio" to Row(TEXT_KEYS, bare.field(WANTED, "B", 1, 1),
                relevant = "/Portfolio"),
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
            // Midnight in the model's zone, Europe/Berlin: 2026-01-02, 2026-05-06 and 2026-03-01.
            "date keys, a literal and a later field key" to listOf(
                "$DAY Date:1767308400000", "$DAY Date:1778018400000", "$HOURS Date:1767308400000", "$HOURS Date:1778018400000", "$OWNER -"),
            "date keys, the field key equal to the literal" to listOf("$DAY Date:1767308400000", "$HOURS Date:1767308400000", "$OWNER -"),
            "a month key" to listOf("$MONTH Date:1772319600000", "$OWNER -", "$VISITS Date:1772319600000"),
            // 2027-01-01 and 2026-04-03; a year-less 02-14 in 2000, or in the declared base year 2024.
            "a year key and a key in another format" to listOf(
                "$OWNER -", "$EURO Date:1775167200000", "$EURO_N Date:1775167200000", "$YEAR Date:1798758000000", "$YEAR_N Date:1798758000000"),
            "a year-less key" to listOf("$OWNER -", "$MONTH_DAY Date:950482800000", "$MONTH_DAY_N Date:950482800000"),
            "a year-less key under a declared base year" to listOf(
                "$OWNER -", "$MONTH_DAY Date:1707865200000", "$MONTH_DAY_N Date:1707865200000"),
            // A custom type's key is its text as written, so `007.50` is not read as a number.
            "custom-typed keys, a literal and a field key" to listOf("$COVER String:007.50", "$COVER String:AB12",
                "$OWNER -", "$REF String:007.50", "$REF String:AB12"),
            "a text join, no claim for the reserve's code" to listOf("$CODE String:C", "$COST String:C"),
            "a number join, no credit for the fee's number stored with a scale" to listOf("$CREDIT BigDecimal:7.5", "$CREDIT_NO BigDecimal:7.5"),
            "a number join of two declared fraction digits, no rebate for the levy's 7.00" to
                listOf("$REBATE BigDecimal:7.00", "$REBATE_NO BigDecimal:7.00"),
            // A join key takes the scale of the side that iterates it, never of the side that lacks the row.
            "a number join, the missing side declaring two fraction digits" to listOf("$REBATE BigDecimal:7.5", "$REBATE_NO BigDecimal:7.5"),
            "a number join, the present side declaring two fraction digits" to listOf("$CREDIT BigDecimal:7.00", "$CREDIT_NO BigDecimal:7.00"),
            "an enumeration join, no tier for the grade's level" to listOf("$CAP String:GOLD", "$LEVEL String:GOLD"),
            // A time is 10:30 of 1970-01-01, and a date-time 10:30 of 2026-01-02, each in the model's zone.
            "time and date-time keys" to listOf("$SEATS Date:1767346200000", "$STARTS Date:1767346200000", "$OWNER -",
                "$AT Date:34200000", "$LOAD Date:34200000"),
            // The model's zone decides, not the JVM's: the rows above in Europe/Berlin, this one in UTC, either JVM.
            "a date and a time key, the model in UTC" to listOf(
                "$OWNER -", "$DAY Date:1767312000000", "$HOURS Date:1767312000000", "$AT Date:37800000", "$LOAD Date:37800000"),
            "an error re-anchored onto the missed row" to listOf("$CODE String:A", "$COST String:A", "$OWNER -"),
            "two text keys under partial validation of the portfolio" to listOf(
                "$CODE String:A", "$CODE String:B", "$COST String:A", "$COST String:B", "$OWNER -"),
        )
        val dynamic = rows.mapValues { (_, row) -> kernel(row, dynamic = true) }
        val generated = rows.mapValues { (_, row) -> kernel(row, dynamic = false) }
        assertEquals(dynamic, generated, "static Java against dynamic Groovy")
        assertEquals(expected.mapValues { it.value.sorted() }, dynamic.mapValues { (_, channels) -> channels.firstGroup }, "dynamic Groovy")
        // The other channels name the missed row too, and never with its key.
        assertEquals(listOf<String>(), dynamic.filterValues { it.atUnknownRow.isEmpty() }.keys.toList(), "rows whose other channels miss no row")
        assertEquals(listOf<String>(), dynamic.values.flatMap { it.atUnknownRow }.filterNot { it.endsWith(" -") }, "a keyed error, referenced or fill-to-fix pointer")
        assertEquals(dynamic, rows.mapValues { (_, row) -> interpreter(row) }, "interpreter against dynamic Groovy")
    }

    /** One condition over one document, validated in full, or partially over [relevant]'s first row. */
    private data class Row(val model: String, val doc: RuntimeLaws.Doc, val code: String = RULE, val relevant: String? = null)

    /** The first group's members, `pointer type:key`, and every error, referenced or fill-to-fix pointer at the unknown row. */
    private data class Channels(val firstGroup: List<String>, val atUnknownRow: List<String>)

    private fun kernel(row: Row, dynamic: Boolean): Channels {
        val relevant = row.relevant?.let { setOf(RuntimeLaws.Relevant.of(it, 1)) }
        val messages = if (dynamic) RuntimeLaws.sharedKernelEngine(row.model).let { engine ->
            if (relevant == null) engine.validationMessages(engine.instance(row.doc), null, Locale.US, ACCEPTING)
            else engine.partialValidationMessages(engine.instance(row.doc), relevant, null, Locale.US, ACCEPTING)
        } else RuntimeLaws.sharedKernelJavaEngine(row.model).let { engine ->
            if (relevant == null) engine.validationMessages(engine.instance(row.doc), null, Locale.US, ACCEPTING)
            else engine.partialValidationMessages(engine.instance(row.doc), relevant, null, Locale.US, ACCEPTING)
        }
        val message = messages.single { it.errorCode == row.code }
        fun member(pointer: PartiallyKnownDocumentMultiPointer) =
            "$pointer ${pointer.semanticIndexValueForUnknownIndexGroup().map(::key).orElse("-")}"
        val others = listOf(message.errorFieldPointer) + message.referencedFieldsPointers + message.refOmissionErrorResponsiblePointers
        return Channels(message.groupsOfPossiblyCausingEntities.first().map(::member).sorted(),
            others.filter { PartiallyKnownDocumentMultiPointer.UNKNOWN in it.repetitionIndexes() }.map(::member).sorted())
    }

    private fun interpreter(row: Row): Channels {
        val definition = ModelLoader.load(row.model)
        val interpreter = DmInterpreter(definition, customFieldTypes = mapOf(POLICY_CODE to CustomFieldType { _, _ -> null }))
        val document = definition.readDocumentJson(RuntimeLaws.canonicalDocumentJson(row.model, row.doc))
        val report = row.relevant?.let { interpreter.validatePart(document, setOf(RelevantEntity(it, listOf(1)))) }
            ?: interpreter.validate(document)
        val message = report.messages.single { it.code == row.code }
        fun spelling(pointer: A12PartiallyKnownMultiPointer) =
            pointer.fullName.trim('/').split('/').zip(pointer.repetitionIndexes).joinToString("") { (part, index) -> "/$part[$index]" }
        fun member(pointer: A12PartiallyKnownMultiPointer) =
            "${spelling(pointer)} ${pointer.semanticIndexValueForUnknownIndexGroup?.let(::key) ?: "-"}"
        val others = listOf(message.errorPointer) + message.referencedPointers + message.fillToFixPointers
        return Channels(message.groupsOfPossiblyCausingEntities.first().map(::member).sorted(),
            others.filter { A12PartiallyKnownMultiPointer.UNKNOWN in it.repetitionIndexes }.map(::member).sorted())
    }

    /** A key as `type:value`; a `Date` as its epoch millis, which its equality reads, rather than in the JVM's zone. */
    private fun key(value: Any): String = "${value::class.simpleName}:${(value as? Date)?.time ?: value}"

    /** A kernel validator that accepts every `PolicyCode`, as the interpreter's registered type does. */
    private val ACCEPTING = object : ICustomFieldTypeFactory {
        override fun createCustomFieldValidator(name: String): Optional<ICustomFieldValidator> =
            Optional.of(ICustomFieldValidator { _, _, _ -> Optional.empty() })
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
        const val WANTED_DAY = "/Portfolio/WantedDay"
        const val DAY = "/Portfolio[1]/Shifts[-5]/Day[1]"
        const val HOURS = "/Portfolio[1]/Shifts[-5]/Hours[1]"
        const val MONTH = "/Portfolio[1]/Visits[-5]/Month[1]"
        const val VISITS = "/Portfolio[1]/Visits[-5]/Count[1]"
        const val YEAR = "/Portfolio[1]/Renewals[-5]/Year[1]"
        const val WANTED_REF = "/Portfolio/WantedRef"
        const val REF = "/Portfolio[1]/Policies[-5]/Ref[1]"
        const val COVER = "/Portfolio[1]/Policies[-5]/Cover[1]"
        const val POLICY_CODE = "PolicyCode"
        const val YEAR_N = "/Portfolio[1]/Renewals[-5]/Premium[1]"
        const val EURO = "/Portfolio[1]/Inspections[-5]/On[1]"
        const val EURO_N = "/Portfolio[1]/Inspections[-5]/Score[1]"
        const val MONTH_DAY = "/Portfolio[1]/Anniversaries[-5]/Day[1]"
        const val MONTH_DAY_N = "/Portfolio[1]/Anniversaries[-5]/Bonus[1]"
        const val TEXT_JOIN = "ReserveWithoutClaim"
        const val NUMBER_JOIN = "FeeWithoutCredit"
        const val CREDIT = "/Portfolio[1]/Credits[-5]/Amount[1]"
        const val CREDIT_NO = "/Portfolio[1]/Credits[-5]/No[1]"
        const val SCALED_JOIN = "LevyWithoutRebate"
        const val FEE_WITHOUT_REBATE = "FeeWithoutRebate"
        const val LEVY_WITHOUT_CREDIT = "LevyWithoutCredit"
        const val GRADE_WITHOUT_TIER = "GradeWithoutTier"
        const val REBATE = "/Portfolio[1]/Rebates[-5]/Amount[1]"
        const val REBATE_NO = "/Portfolio[1]/Rebates[-5]/No[1]"
        const val AT = "/Portfolio[1]/Slots[-5]/At[1]"
        const val LOAD = "/Portfolio[1]/Slots[-5]/Load[1]"
        const val STARTS = "/Portfolio[1]/Bookings[-5]/Starts[1]"
        const val SEATS = "/Portfolio[1]/Bookings[-5]/Seats[1]"

        val TEXT_KEYS = model("[Claims/Cost For \"A\"] > 1 Or [Claims/Cost For Wanted] > 1 Or FieldNotFilled(Owner)")
        val NUMBER_KEYS = model("[Fees/Rate For \"7\"] > 1 Or [Fees/Rate For \"7.0\"] > 1 Or [Fees/Rate For WantedNo] > 1 Or FieldNotFilled(Owner)")
        val DATE_KEYS = model("[Shifts/Hours For \"2026-01-02\"] > 1 Or [Shifts/Hours For WantedDay] > 1 Or FieldNotFilled(Owner)")
        val MONTH_KEY = model("[Visits/Count For \"2026-03\"] > 1 Or FieldNotFilled(Owner)")
        val PARTIAL_DATE_KEYS = model("[Renewals/Premium For \"2027\"] > 1 Or [Inspections/Score For \"03.04.2026\"] > 1 Or FieldNotFilled(Owner)")
        val YEARLESS_KEY = model("[Anniversaries/Bonus For \"02-14\"] > 1 Or FieldNotFilled(Owner)")
        val YEARLESS_KEY_IN_2024: String = ModelDocument.read(YEARLESS_KEY).setBaseYear(2024).toJson()
        val CUSTOM_KEYS = model("[Policies/Cover For \"AB12\"] > 1 Or [Policies/Cover For WantedRef] > 1 Or FieldNotFilled(Owner)")
        val TEMPORAL_KEYS = model("[Slots/Load For \"10:30:00\"] > 1 Or [Bookings/Seats For \"2026-01-02T10:30:00\"] > 1 Or FieldNotFilled(Owner)")
        val TEMPORAL_KEYS_IN_UTC: String = ModelDocument.read(
            model("[Shifts/Hours For \"2026-01-02\"] > 1 Or [Slots/Load For \"10:30:00\"] > 1 Or FieldNotFilled(Owner)"),
        ).setTimeZone("UTC").toJson()
        val REANCHORED = model("[Claims/Cost For \"A\"] > 1 Or FieldNotFilled(Owner)", errorField = "../Claims/Cost")
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
            for ((code, condition, error) in listOf(
                Triple(FEE_WITHOUT_REBATE, "FieldFilled(Fees/Rate) And FieldNotFilled(Rebates/Amount)", "../Rebates/Amount"),
                Triple(LEVY_WITHOUT_CREDIT, "FieldFilled(Levies/Rate) And FieldNotFilled(Credits/Amount)", "../Credits/Amount"),
                Triple(GRADE_WITHOUT_TIER, "FieldFilled(Grades/Weight) And FieldNotFilled(Tiers/Cap)", "../Tiers/Cap"),
            )) {
                session.addRule(DraftRule.builder().groupPath("/Portfolio").name(code).errorEntityRelPath(error)
                    .errorCondition(condition).errorCode(code).severity("ERROR")
                    .messages(listOf(LocalizedText("en_US", "The joined row is missing."))).build())
            }
        }.toJson()

        fun model(condition: String, errorField: String = "../Owner"): String = EditSession.read(
            DocumentModelDraft.named("portfolio").locale("en_US")
                .rootGroup("Portfolio") { portfolio ->
                    portfolio.stringField("Owner") { }.stringField("Wanted") { }.numberField("WantedNo") { it.fractionDigits(0, 2) }
                        .dateField("WantedDay") { it.format("yyyy-MM-dd") }
                        .customField("WantedRef") { it.type(POLICY_CODE).minLength(1).maxLength(9) }
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
                        .group("Shifts") { shifts ->
                            shifts.repeatable(3).indexField("Day").dateField("Day") { it.format("yyyy-MM-dd") }.numberField("Hours") { }
                        }
                        .group("Visits") { visits ->
                            visits.repeatable(3).indexField("Month").dateField("Month") { it.format("yyyy-MM") }.numberField("Count") { }
                        }
                        .group("Renewals") { it.repeatable(3).indexField("Year").dateField("Year") { d -> d.format("yyyy") }.numberField("Premium") { } }
                        .group("Inspections") { it.repeatable(3).indexField("On").dateField("On") { d -> d.format("dd.MM.yyyy") }.numberField("Score") { } }
                        .group("Anniversaries") { it.repeatable(3).indexField("Day").dateField("Day") { d -> d.format("MM-dd") }.numberField("Bonus") { } }
                        .group("Policies") { policies ->
                            policies.repeatable(3).indexField("Ref").customField("Ref") { it.type(POLICY_CODE).minLength(1).maxLength(9) }
                                .numberField("Cover") { }
                        }
                        .group("Grades") { grades ->
                            grades.repeatable(3).indexField("Level").enumField("Level") { it.value("GOLD").value("SILVER") }
                                .numberField("Weight") { }
                        }
                        .group("Slots") { it.repeatable(3).indexField("At").timeField("At") { t -> t.format("HH:mm:ss") }.numberField("Load") { } }
                        .group("Bookings") { bookings ->
                            bookings.repeatable(3).indexField("Starts").dateTimeField("Starts") { it.format("yyyy-MM-dd'T'HH:mm:ss") }
                                .numberField("Seats") { }
                        }
                        .group("Tiers") { tiers ->
                            tiers.repeatable(3).indexField("Level").enumField("Level") { it.value("GOLD").value("SILVER") }
                                .numberField("Cap") { }
                        }
                }.build().toJson().let { ModelDocument.read(it).setTimeZone("Europe/Berlin").toJson() },
        ).also { session ->
            session.addRule(DraftRule.builder().groupPath("/Portfolio").name(RULE).errorEntityRelPath(errorField)
                .errorCondition(condition).errorCode(RULE).severity("ERROR")
                .messages(listOf(LocalizedText("en_US", "The portfolio needs an owner."))).build())
        }.toJson()
    }
}
