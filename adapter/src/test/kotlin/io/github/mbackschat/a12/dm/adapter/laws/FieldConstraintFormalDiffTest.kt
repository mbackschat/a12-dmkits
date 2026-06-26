package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.FORMAL_RULE_PATH
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential for the IF20 declared field-type CONSTRAINT checks — string length/pattern, enum value-domain,
 * number range, and the min-fractional-digits rule are auto-fired as formal errors (`formalePruefung`) by the
 * kernel's `validateFull`, with the field then "unknown" so a referencing rule is suppressed. The interpreter
 * must emit the SAME formal-error code and suppress the SAME way. Codes + precedence are kernel-probe-derived
 * (KERNEL-FINDINGS §3 "declared field-type constraints").
 */
class FieldConstraintFormalDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")

    /** Splice [fieldJson] into /Order's elements (the kernel side) and return the model JSON. */
    private fun withField(fieldJson: String): String {
        val g = order.indexOf("\"id\" : \"/Order\"").let { if (it < 0) order.indexOf("\"id\": \"/Order\"") else it }
        val br = order.indexOf('[', order.indexOf("\"elements\"", g))
        return order.substring(0, br + 1) + "\n" + fieldJson.trimIndent().replace("\n", " ") + order.substring(br + 1)
    }

    /** Assert the kernel's auto formal-error code for [path]=[value] equals the interpreter's. */
    private fun agree(fieldJson: String, field: FieldDef, path: String, value: String, msg: String) {
        val kModel = withField(fieldJson)
        val kCode = RuntimeLaws.firedCodes(kModel, setOf("/Order"), mapOf(path to value)).firstOrNull()
        val def = ModelDef(listOf(field), emptyList())
        val iDoc = Document(def.kinds, def.declaredReps, def.unsignedFields, def.rangeConfig, def.dateFormats,
            def.scales, def.dateTimeFormats, def.datePrecisions, def.enumValues, def.enumCategories).group("/Order", 1)
            .field(path, value, 1, 1)
        val iCode = DmInterpreter(def).validate(iDoc).messages.firstOrNull { it.rulePath == FORMAL_RULE_PATH }?.code
        assertEquals(kCode, iCode, "$msg  (kernel=$kCode interp=$iCode)")
    }

    private val codeField = """{ "type": "Field", "id": "/Order/Code", "name": "Code", "Field": { "fieldType": {
        "type": "StringType", "StringType": { "minLength": 3, "maxLength": 6, "pattern": "[A-Z]+",
          "errorMessage": [ { "locale": "en_US", "text": "bad" }, { "locale": "de_DE", "text": "bad" } ] } } } },"""
    private val codeDef = FieldDef("/Order/Code", Kind.STRING, minLength = 3, maxLength = 6, pattern = "[A-Z]+")

    @Test fun stringTooShort() = agree(codeField, codeDef, "/Order/Code", "AB", "len 2 < min 3 → stringZuKurz")
    @Test fun stringTooLong() = agree(codeField, codeDef, "/Order/Code", "ABCDEFG", "len 7 > max 6 → stringZuLang")
    @Test fun stringPatternFails() = agree(codeField, codeDef, "/Order/Code", "abcd", "lowercase → stringFalschesMuster")
    @Test fun patternBeatsLength() = agree(codeField, codeDef, "/Order/Code", "ab", "len2 AND pattern → pattern wins")
    @Test fun stringValid() = agree(codeField, codeDef, "/Order/Code", "ABC", "valid → no formal error")

    private val scoreField = """{ "type": "Field", "id": "/Order/Score", "name": "Score", "Field": { "fieldType": {
        "type": "NumberType", "NumberType": { "maxFractionalDigits": 2, "minFractionalDigits": 2, "minValue": 1, "maxValue": 99 } } } },"""
    private val scoreDef = FieldDef("/Order/Score", Kind.NUMBER, scale = 2, minValue = "1", maxValue = "99", minFractionalDigits = 2)

    @Test fun numberTooSmall() = agree(scoreField, scoreDef, "/Order/Score", "0.50", "0.5 < min 1 → zahlZuKlein")
    @Test fun numberTooBig() = agree(scoreField, scoreDef, "/Order/Score", "150.00", "150 > max 99 → zahlZuGross")
    @Test fun tooFewDecimals() = agree(scoreField, scoreDef, "/Order/Score", "5.0", "1 frac < min 2 → zahlFalscheAnzahlNachkommastellen")
    @Test fun noDecimalSeparator() = agree(scoreField, scoreDef, "/Order/Score", "5", "no decimal, min 2 → zahlOhneDezimalTrenner")
    @Test fun tooManyDecimals() = agree(scoreField, scoreDef, "/Order/Score", "5.000", "3 frac > max 2 → zahlFalscheAnzahlNachkommastellen")
    @Test fun numberValid() = agree(scoreField, scoreDef, "/Order/Score", "50.00", "valid → no formal error")

    // ── DEFAULT-forbidden format checks (no declaration needed; a toggle turns them off) ──
    private val plainStr = """{ "type": "Field", "id": "/Order/Note", "name": "Note", "Field": { "fieldType": {
        "type": "StringType", "StringType": { } } } },"""
    private val plainStrDef = FieldDef("/Order/Note", Kind.STRING)
    private val nlOkStr = """{ "type": "Field", "id": "/Order/Note", "name": "Note", "Field": { "fieldType": {
        "type": "StringType", "StringType": { "lineBreaksPermitted": true } } } },"""
    private val nlOkDef = FieldDef("/Order/Note", Kind.STRING, lineBreaksPermitted = true)

    @Test fun newlineForbiddenByDefault() = agree(plainStr, plainStrDef, "/Order/Note", "a\nb", "newline default-forbidden → stringMitNewline")
    @Test fun newlineCleanWithoutBreak() = agree(plainStr, plainStrDef, "/Order/Note", "abc", "no newline → clean")
    @Test fun newlinePermittedWhenDeclared() = agree(nlOkStr, nlOkDef, "/Order/Note", "a\nb", "lineBreaksPermitted → clean")

    private val plainNum = """{ "type": "Field", "id": "/Order/Num", "name": "Num", "Field": { "fieldType": {
        "type": "NumberType", "NumberType": { "maxFractionalDigits": 0 } } } },"""
    private val plainNumDef = FieldDef("/Order/Num", Kind.NUMBER, scale = 0)
    private val lzOkNum = """{ "type": "Field", "id": "/Order/Num", "name": "Num", "Field": { "fieldType": {
        "type": "NumberType", "NumberType": { "maxFractionalDigits": 0, "leadingZerosAllowed": true } } } },"""
    private val lzOkDef = FieldDef("/Order/Num", Kind.NUMBER, scale = 0, leadingZerosAllowed = true)

    @Test fun leadingZerosForbiddenByDefault() = agree(plainNum, plainNumDef, "/Order/Num", "007", "leading zeros default-forbidden → zahlHatFuehrendeNullen")
    @Test fun leadingZeroSingleIsClean() = agree(plainNum, plainNumDef, "/Order/Num", "0", "lone 0 → clean")
    @Test fun leadingZerosPermittedWhenDeclared() = agree(lzOkNum, lzOkDef, "/Order/Num", "007", "leadingZerosAllowed → clean")

    private val zna = """{ "type": "Field", "id": "/Order/NZ", "name": "NZ", "Field": { "fieldType": {
        "type": "NumberType", "NumberType": { "maxFractionalDigits": 0, "zeroNotAllowed": true } } } },"""
    private val znaDef = FieldDef("/Order/NZ", Kind.NUMBER, scale = 0, zeroNotAllowed = true)

    @Test fun zeroNotAllowedFires() = agree(zna, znaDef, "/Order/NZ", "0", "zeroNotAllowed + 0 → zahlIstNull")
    @Test fun zeroNotAllowedCleanNonZero() = agree(zna, znaDef, "/Order/NZ", "5", "zeroNotAllowed + 5 → clean")

    // Enum value-domain: the order fixture's Priority (STANDARD/EXPRESS/OVERNIGHT). Out-of-domain → stringFalschesMuster.
    private val priorityDef = FieldDef("/Order/Priority", Kind.STRING, enumValues = listOf("STANDARD", "EXPRESS", "OVERNIGHT"))

    @Test fun enumOutOfDomain() {
        // Priority already exists in the fixture — no splice needed; pass an empty field JSON.
        val kCode = RuntimeLaws.firedCodes(order, setOf("/Order"), mapOf("/Order/Priority" to "BOGUS")).firstOrNull()
        val def = ModelDef(listOf(priorityDef), emptyList())
        val iDoc = Document(def.kinds, def.declaredReps, def.unsignedFields, def.rangeConfig, def.dateFormats,
            def.scales, def.dateTimeFormats, def.datePrecisions, def.enumValues, def.enumCategories).group("/Order", 1)
            .field("/Order/Priority", "BOGUS", 1, 1)
        val iCode = DmInterpreter(def).validate(iDoc).messages.firstOrNull { it.rulePath == FORMAL_RULE_PATH }?.code
        assertEquals(kCode, iCode, "out-of-domain enum → stringFalschesMuster  (kernel=$kCode interp=$iCode)")
    }

    /** Suppression: a rule referencing a constraint-violating field must NOT fire (the field is unknown) — both engines. */
    @Test fun constraintViolationSuppressesReferencingRule() {
        // DSL-EXEMPT: /Order/Score is SPLICED into the model by withField(scoreField); it is absent from the base
        // order.dm.json, so BoundModel.ofJson(order).num("/Order/Score") cannot resolve a typed handle (throws).
        val kModel = RuntimeLaws.withRule(withField(scoreField), "/Order", "R", "../Score", "R", "[Score] >= 0")
        val kFires = RuntimeLaws.firedCodes(kModel, setOf("/Order"), mapOf("/Order/Score" to "150.00")).contains("R")
        val def = ModelDef(listOf(scoreDef), listOf(RuleDef("/Order/R", "/Order", "[Score] >= 0", "R", "../Score")))
        val iDoc = Document(def.kinds, def.declaredReps, def.unsignedFields, def.rangeConfig, def.dateFormats,
            def.scales, def.dateTimeFormats, def.datePrecisions, def.enumValues, def.enumCategories).group("/Order", 1)
            .field("/Order/Score", "150.00", 1, 1)
        val iFires = DmInterpreter(def).validate(iDoc).messages.any { it.code == "R" }
        assertEquals(false, kFires, "kernel: out-of-range Score is unknown → rule R suppressed")
        assertEquals(kFires, iFires, "interpreter must suppress R exactly like the kernel")
    }
}
