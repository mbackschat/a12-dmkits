package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.md.rt.api.DocumentValidationException
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.eval.FORMAL_RULE_PATH
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Differential for the IF20 declared field-type CONSTRAINT checks — string length/pattern, enum value-domain,
 * number range, and the min-fractional-digits rule are auto-fired as formal errors (`formalePruefung`) by the
 * kernel's `validateFull`, with the field then "unknown" so a referencing rule is suppressed. The interpreter
 * must emit the SAME formal-error code and suppress the SAME way. Codes + precedence are kernel-probe-derived
 * (KERNEL-FINDINGS §3 "declared field-type constraints").
 */
class FieldConstraintFormalDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")

    /**
     * EXP-2026-07-30-01 — field-owned templates have a locale-fixed token grammar distinct from rule messages.
     * Keep this on the typed modelkit surface: the experiment is about the kernel's text checker and runtime
     * renderer, not about whether hand-written DM-JSON happens to bind.
     */
    @Test
    fun fieldOwnedErrorTextStaticEnglishTokenMatrix() {
        val observed = listOf("\$field\$", "\$field.value\$", "\$Field\$", "\$Code\$").associateWith {
            RuntimeLaws.consistencyMessages(fieldMessageModel(it))
        }

        assertEquals(
            mapOf(
                "\$field\$" to emptyList(),
                "\$field.value\$" to emptyList(),
                "\$Field\$" to listOf(
                    "ERROR: Field with path '/Order/Code': Field '/Order/Code' is invalid: " +
                        "The error text parameter 'Field' is not valid. [INVALID_PARAMETER]",
                ),
                "\$Code\$" to listOf(
                    "ERROR: Field with path '/Order/Code': Field '/Order/Code' is invalid: " +
                        "The error text parameter 'Code' is not valid. [INVALID_PARAMETER]",
                ),
            ),
            observed,
        )
    }

    @Test
    fun fieldOwnedPatternTextRejectsLiteralDollarAsAnEmptyParameter() {
        val model = fieldMessageModel("Cost \$\$ \$field\$")
        val diagnostic = "Field with path '/Order/Code': Field '/Order/Code' is invalid: " +
            "The error text parameter '' is not valid. [INVALID_PARAMETER]"
        assertEquals(
            listOf("ERROR: $diagnostic"),
            RuntimeLaws.consistencyMessages(model),
        )

        val document = RuntimeLaws.doc().group("/Order", 1).field("/Order/Code", "bad", 1, 1)
        val dynamic = assertThrows(DocumentValidationException::class.java) {
            RuntimeLaws.errorTextOf(model, "stringFalschesMuster", document, Locale.US)
        }
        val generated = assertThrows(IllegalStateException::class.java) {
            RuntimeLaws.kernelJavaEngine(model)
        }
        assertEquals("Following errors were found while generating code: $diagnostic", dynamic.message)
        assertEquals(
            "generateValidationCode(JAVA) returned null; notifications=[" +
                "ERROR: Field '/Order/Code' is invalid: The error text parameter '' is not valid. " +
                "[INVALID_PARAMETER]]",
            generated.message,
        )
    }

    @Test
    fun fieldOwnedPatternValueReplacementIsStagedOnce() {
        assertFieldErrorText("Value [\$field.value\$]", "\$field\$", "Value [\$field\$]")
    }

    private fun assertFieldErrorText(template: String, value: String, expected: String) {
        val model = fieldMessageModel(template)
        val document = RuntimeLaws.doc().group("/Order", 1).field("/Order/Code", value, 1, 1)
        val dynamic = RuntimeLaws.errorTextOf(model, "stringFalschesMuster", document, Locale.US).orElseThrow()
        val java = RuntimeLaws.kernelJavaEngine(model)
        val generated = java.errorTextOf(java.instance(document), "stringFalschesMuster", Locale.US).orElseThrow()
        val definition = ModelLoader.load(model)
        val interpreterDocument = definition.newDocument()
            .group("/Order", 1)
            .field("/Order/Code", value, 1, 1)
        val interpreter = DmInterpreter(definition).validate(interpreterDocument).messages
            .single { it.code == "stringFalschesMuster" }.text

        assertEquals(expected, dynamic, "$template — dynamic Groovy")
        assertEquals(dynamic, generated, "$template — generated Java")
        assertEquals(dynamic, interpreter, "$template — dynamic Groovy vs interpreter")
    }

    private fun fieldMessageModel(template: String): String =
        DocumentModelDraft.named("field-message").locale("en_US")
            .rootGroup("Order") { order ->
                order.stringField("Code") { it.pattern("[A-Z]+", template) }
            }
            .build().toJson()

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
        val iDoc = def.newDocument().group("/Order", 1)
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

    @Test
    fun declaredPatternDialectHasGeneratedJavaTriEvidence() {
        val fieldJson = """{ "type": "Field", "id": "/Order/Dialect", "name": "Dialect", "Field": { "fieldType": {
            "type": "StringType", "StringType": { "pattern": "(?i:abc)*",
              "errorMessage": [ { "locale": "en_US", "text": "bad" }, { "locale": "de_DE", "text": "bad" } ] } } } },"""
        val model = withField(fieldJson)
        val engine = RuntimeLaws.kernelJavaEngine(model)
        val field = FieldDef("/Order/Dialect", Kind.STRING, pattern = "(?i:abc)*")
        val def = ModelDef(listOf(field), emptyList())
        listOf("ABCabc" to null, "x" to "stringFalschesMuster").forEach { (value, expected) ->
            val kDoc = RuntimeLaws.doc().group("/Order", 1).field(field.path, value, 1, 1)
            val groovy = RuntimeLaws.messages(model, kDoc).map { it.code() }.firstOrNull { it == "stringFalschesMuster" }
            val javaStatic = engine.messages(engine.instance(kDoc)).map { it.code() }.firstOrNull { it == "stringFalschesMuster" }
            val interpreter = DmInterpreter(def).validate(
                def.newDocument().group("/Order", 1).field(field.path, value, 1, 1),
            ).messages.firstOrNull { it.rulePath == FORMAL_RULE_PATH }?.code
            assertEquals(expected, groovy, "$value discriminator")
            assertEquals(groovy, javaStatic, "$value — Groovy-dynamic vs generated Java")
            assertEquals(groovy, interpreter, "$value — Groovy-dynamic vs interpreter")
        }
    }

    private val scoreField = """{ "type": "Field", "id": "/Order/Score", "name": "Score", "Field": { "fieldType": {
        "type": "NumberType", "NumberType": { "maxFractionalDigits": 2, "minFractionalDigits": 2, "minValue": 1, "maxValue": 99 } } } },"""
    private val scoreDef = FieldDef("/Order/Score", Kind.NUMBER, scale = 2, minValue = "1", maxValue = "99", minFractionalDigits = 2)

    @Test fun numberTooSmall() = agree(scoreField, scoreDef, "/Order/Score", "0.50", "0.5 < min 1 → zahlZuKlein")
    @Test fun numberTooBig() = agree(scoreField, scoreDef, "/Order/Score", "150.00", "150 > max 99 → zahlZuGross")
    @Test fun tooFewDecimals() = agree(scoreField, scoreDef, "/Order/Score", "5.0", "1 frac < min 2 → zahlFalscheAnzahlNachkommastellen")
    @Test fun noDecimalSeparator() = agree(scoreField, scoreDef, "/Order/Score", "5", "no decimal, min 2 → zahlOhneDezimalTrenner")
    @Test fun tooManyDecimals() = agree(scoreField, scoreDef, "/Order/Score", "5.000", "3 frac > max 2 → zahlFalscheAnzahlNachkommastellen")
    @Test fun numberValid() = agree(scoreField, scoreDef, "/Order/Score", "50.00", "valid → no formal error")

    @Test
    fun numberValueBoundsHaveGeneratedJavaTriEvidence() {
        val model = withField(scoreField)
        val engine = RuntimeLaws.kernelJavaEngine(model)
        val def = ModelDef(listOf(scoreDef), emptyList())

        listOf("0.50" to "zahlZuKlein", "150.00" to "zahlZuGross").forEach { (value, expected) ->
            val kDoc = RuntimeLaws.doc().group("/Order", 1).field("/Order/Score", value, 1, 1)
            val groovyDynamic = RuntimeLaws.messages(model, kDoc).map { it.code() }
                .filter { it.startsWith("zahl") }.sorted()
            val javaStatic = engine.messages(engine.instance(kDoc)).map { it.code() }
                .filter { it.startsWith("zahl") }.sorted()
            val iDoc = def.newDocument().group("/Order", 1).field("/Order/Score", value, 1, 1)
            val interpreter = DmInterpreter(def).validate(iDoc).messages.map { it.code }
                .filter { it.startsWith("zahl") }.sorted()

            assertEquals(listOf(expected), groovyDynamic, "$value must exercise the declared value bound")
            assertEquals(groovyDynamic, javaStatic, "$value: groovy-dynamic vs generated Java")
            assertEquals(groovyDynamic, interpreter, "$value: kernel vs interpreter")
        }
    }

    // ── IF78: a NumberType with NO maxFractionalDigits is scale-0 (the kernel's primitive-int default) — an
    //    INTEGER field: any decimal separator is zahlHatDezimalTrenner. The loader defaults scale to 0 (locked at
    //    the loader level by `interpreter … ModelLoaderTest`); here the interp FieldDef carries scale=0 to match. ──
    private val intField = """{ "type": "Field", "id": "/Order/Count", "name": "Count", "Field": { "fieldType": {
        "type": "NumberType", "NumberType": { "minValue": 1, "maxValue": 99 } } } },"""
    private val intDef = FieldDef("/Order/Count", Kind.NUMBER, scale = 0, minValue = "1", maxValue = "99")
    @Test fun decimalInNoMaxFracFieldIsHatDezimalTrenner() =
        agree(intField, intDef, "/Order/Count", "5.0", "decimal in a no-maxFractionalDigits field → zahlHatDezimalTrenner")
    @Test fun integerInNoMaxFracFieldIsClean() =
        agree(intField, intDef, "/Order/Count", "50", "integer in a no-maxFractionalDigits field → clean")

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

    // noValueValidation (IG17): the kernel's FormatDefinitionString.validiereFormat returns Valid immediately,
    // skipping every value check. A model is only kernel-VALID with noValueValidation when line breaks are allowed
    // and NO pattern/minLength is set (MVK_STRING_TYPE_PARAMS_INCONSISTENT / MVK_STRING_TYPE_NO_VAL_AND_NO_LINE_BREAK
    // — only maxLength may accompany it). So the observable skip is maxLength: a value LONGER than maxLength is clean
    // for the kernel but the interpreter wrongly fired stringZuLang. Reachable on shipped A12 Tools StringType fields.
    private val noValStr = """{ "type": "Field", "id": "/Order/Code", "name": "Code", "Field": { "fieldType": {
        "type": "StringType", "StringType": { "maxLength": 6, "noValueValidation": true, "lineBreaksPermitted": true } } } },"""
    private val noValDef = FieldDef("/Order/Code", Kind.STRING, maxLength = 6, noValueValidation = true, lineBreaksPermitted = true)

    @Test fun noValueValidationSkipsMaxLength() = agree(noValStr, noValDef, "/Order/Code", "ABCDEFG", "noValueValidation → maxLength (7>6) skipped → clean")
    @Test fun noValueValidationCleanWithinLength() = agree(noValStr, noValDef, "/Order/Code", "ABC", "noValueValidation, within maxLength → clean (control)")

    // IG15: NUMBER integer-digit cap (explicit maxIntegerDigits / implicit trait:"amount") + value-string length —
    // kernel-honored NumberType keys the interpreter previously dropped (disproving the "no DM-JSON key" claim).
    private val maxIntStr = """{ "type": "Field", "id": "/Order/N", "name": "N", "Field": { "fieldType": {
        "type": "NumberType", "NumberType": { "maxFractionalDigits": 0, "maxIntegerDigits": 3 } } } },"""
    private val maxIntDef = FieldDef("/Order/N", Kind.NUMBER, scale = 0, maxIntegerDigits = 3)
    @Test fun tooManyIntegerDigits() = agree(maxIntStr, maxIntDef, "/Order/N", "1234", "4 int digits > 3 → zahlZuvieleVorkommastellen")
    @Test fun integerDigitsWithinCap() = agree(maxIntStr, maxIntDef, "/Order/N", "123", "3 int digits → clean")

    // trait:"amount" integer-digit cap = 15 − scale = 13, firing zahlZuvieleVorkommastellen (the SAME code as an
    // explicit maxIntegerDigits — the kernel's TypeConverter maps both to maxDigitsBeforeDecimalPlace; IF67). Uses
    // a value whose TOTAL digits ≤ 15 so the generic stelligkeitZuLang parse check does NOT pre-empt the cap.
    private val amountStr = """{ "type": "Field", "id": "/Order/N", "name": "N", "Field": { "fieldType": {
        "type": "NumberType", "NumberType": { "maxFractionalDigits": 2, "trait": "amount" } } } },"""
    private val amountDef = FieldDef("/Order/N", Kind.NUMBER, scale = 2, amountTrait = true)
    @Test fun amountIntegerCapFires() = agree(amountStr, amountDef, "/Order/N", "12345678901234", "14 int > 15-2=13 cap, total 14 ≤ 15 → zahlZuvieleVorkommastellen (NOT stelligkeitZuLang)")
    @Test fun amountIntegerCapWithinCap() = agree(amountStr, amountDef, "/Order/N", "1234567890123", "13 int = cap, total 13 → clean")

    // stelligkeitZuLang — > 15 TOTAL digits — is a GENERIC parse check on EVERY number, NOT trait-gated (IF67);
    // it fires before the sign / scale / leading-zero / integer-cap checks (FormatDefinitionZahl order).
    private val plainN2Str = """{ "type": "Field", "id": "/Order/N", "name": "N", "Field": { "fieldType": {
        "type": "NumberType", "NumberType": { "maxFractionalDigits": 2 } } } },"""
    private val plainN2Def = FieldDef("/Order/N", Kind.NUMBER, scale = 2)
    @Test fun plainNumber16DigitsTooLong() = agree(plainNum, plainNumDef, "/Order/Num", "1234567890123456", "16 int digits > 15 → stelligkeitZuLang (plain, no amount trait — was a K-only miss)")
    @Test fun plainNumber15DigitsClean() = agree(plainNum, plainNumDef, "/Order/Num", "123456789012345", "15 digits → clean")
    @Test fun scale2Total16TooLong() = agree(plainN2Str, plainN2Def, "/Order/N", "12345678901234.56", "14 int + 2 frac = 16 total > 15 → stelligkeitZuLang")
    @Test fun amountTotal16HitsStelligkeitFirst() = agree(amountStr, amountDef, "/Order/N", "12345678901234.56", "amount, 16 total → stelligkeitZuLang pre-empts the integer cap")
    @Test fun totalDigitsBeatsLeadingZeros() = agree(plainNum, plainNumDef, "/Order/Num", "01234567890123456", "17 digits + leading zero → stelligkeitZuLang wins over zahlHatFuehrendeNullen")
    @Test fun totalDigitsBeatsDecimalSeparator() = agree(plainNum, plainNumDef, "/Order/Num", "1234567890123456.5", "scale0, 18 total + decimal → stelligkeitZuLang wins over zahlHatDezimalTrenner")

    private val numMinStr = """{ "type": "Field", "id": "/Order/N", "name": "N", "Field": { "fieldType": {
        "type": "NumberType", "NumberType": { "maxFractionalDigits": 0, "minLength": 5 } } } },"""
    private val numMinDef = FieldDef("/Order/N", Kind.NUMBER, scale = 0, numberMinLength = 5)
    @Test fun numberTooShort() = agree(numMinStr, numMinDef, "/Order/N", "12", "len 2 < min 5 → zahlZuKurz")

    private val numMaxStr = """{ "type": "Field", "id": "/Order/N", "name": "N", "Field": { "fieldType": {
        "type": "NumberType", "NumberType": { "maxFractionalDigits": 0, "maxLength": 3 } } } },"""
    private val numMaxDef = FieldDef("/Order/N", Kind.NUMBER, scale = 0, numberMaxLength = 3)
    @Test fun numberTooLong() = agree(numMaxStr, numMaxDef, "/Order/N", "12345", "len 5 > max 3 → zahlZuLang")

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

    // ── IF70: number formal-error PRECEDENCE matrix (FormatDefinitionZahl.validiereFormat order → IF67 extended).
    //    The kernel order among the declared-constraint checks: missing-decimal-separator → wrong-#-decimals →
    //    integer-cap → zero-not-allowed → LEADING-ZEROS (last) → length → range. The interpreter had TWO
    //    mis-orderings: zeroNotAllowed fired before the decimal/integer checks, and leadingZeros fired FIRST (in
    //    numberFormalError) instead of after zero-not-allowed. `agreeCode` locks the winning code AND the differential. ──

    /** As [agree], but also pins the KERNEL's winning code — so a precedence claim is locked as a fact, and a
     *  mis-prediction fails on the kernel assertion (not silently on the differential). */
    private fun agreeCode(fieldJson: String, field: FieldDef, path: String, value: String, expectedKernel: String, msg: String) {
        val kModel = withField(fieldJson)
        val kCode = RuntimeLaws.firedCodes(kModel, setOf("/Order"), mapOf(path to value)).firstOrNull()
        assertEquals(expectedKernel, kCode, "$msg — KERNEL winner")
        val def = ModelDef(listOf(field), emptyList())
        val iDoc = def.newDocument().group("/Order", 1)
            .field(path, value, 1, 1)
        val iCode = DmInterpreter(def).validate(iDoc).messages.firstOrNull { it.rulePath == FORMAL_RULE_PATH }?.code
        assertEquals(kCode, iCode, "$msg  (kernel=$kCode interp=$iCode)")
    }

    // A field carrying zeroNotAllowed AND a min-fractional-digits requirement (both apply to "0" / "0.0").
    private val zeroMinFracStr = """{ "type": "Field", "id": "/Order/ZF", "name": "ZF", "Field": { "fieldType": {
        "type": "NumberType", "NumberType": { "maxFractionalDigits": 2, "minFractionalDigits": 2, "zeroNotAllowed": true } } } },"""
    private val zeroMinFracDef = FieldDef("/Order/ZF", Kind.NUMBER, scale = 2, minFractionalDigits = 2, zeroNotAllowed = true)

    // (IF70) zero-not-allowed vs missing-decimal-separator: "0" is zero AND lacks the required separator —
    // the kernel reports the missing separator (step 7), NOT zahlIstNull (step 10).
    @Test fun missingSeparatorBeatsZeroNotAllowed() =
        agreeCode(zeroMinFracStr, zeroMinFracDef, "/Order/ZF", "0", "zahlOhneDezimalTrenner",
            "0 on a zeroNotAllowed+minFrac field → ohneDezimalTrenner wins over zahlIstNull")
    // zero-not-allowed vs too-few-decimals: "0.0" is zero AND has too few decimals — falscheAnzahl (step 8) wins.
    @Test fun tooFewDecimalsBeatsZeroNotAllowed() =
        agreeCode(zeroMinFracStr, zeroMinFracDef, "/Order/ZF", "0.0", "zahlFalscheAnzahlNachkommastellen",
            "0.0 (zero, 1 frac < min 2) → falscheAnzahl wins over zahlIstNull")

    // leading-zeros is the LAST format check (kernel step 11) — every earlier check beats it:
    @Test fun decimalSeparatorBeatsLeadingZeros() =
        agreeCode(plainNum, plainNumDef, "/Order/Num", "07.5", "zahlHatDezimalTrenner",
            "07.5 on a scale-0 field → hatDezimalTrenner wins over zahlHatFuehrendeNullen")
    @Test fun wrongDecimalsBeatsLeadingZeros() =
        agreeCode(plainN2Str, plainN2Def, "/Order/N", "07.555", "zahlFalscheAnzahlNachkommastellen",
            "07.555 (3 frac > 2) → falscheAnzahl wins over zahlHatFuehrendeNullen")
    @Test fun integerCapBeatsLeadingZeros() =
        agreeCode(maxIntStr, maxIntDef, "/Order/N", "0007", "zahlZuvieleVorkommastellen",
            "0007 (4 int > cap 3, leading zeros) → zuvieleVorkommastellen wins over zahlHatFuehrendeNullen")
    @Test fun zeroNotAllowedBeatsLeadingZeros() =
        agreeCode(zna, znaDef, "/Order/NZ", "00", "zahlIstNull",
            "00 (zero, leading zeros) → zahlIstNull wins over zahlHatFuehrendeNullen")

    // Enum value-domain: the order fixture's Priority (STANDARD/EXPRESS/OVERNIGHT). Out-of-domain → stringFalschesMuster.
    private val priorityDef = FieldDef("/Order/Priority", Kind.STRING, enumValues = listOf("STANDARD", "EXPRESS", "OVERNIGHT"))

    @Test fun enumOutOfDomain() {
        // Priority already exists in the fixture — no splice needed; pass an empty field JSON.
        val kCode = RuntimeLaws.firedCodes(order, setOf("/Order"), mapOf("/Order/Priority" to "BOGUS")).firstOrNull()
        val def = ModelDef(listOf(priorityDef), emptyList())
        val iDoc = def.newDocument().group("/Order", 1)
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
        val iDoc = def.newDocument().group("/Order", 1)
            .field("/Order/Score", "150.00", 1, 1)
        val iFires = DmInterpreter(def).validate(iDoc).messages.any { it.code == "R" }
        assertEquals(false, kFires, "kernel: out-of-range Score is unknown → rule R suppressed")
        assertEquals(kFires, iFires, "interpreter must suppress R exactly like the kernel")
    }
}
