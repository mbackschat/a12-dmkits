package io.github.mbackschat.a12.dm.interpreter.load

import io.github.mbackschat.a12.dm.interpreter.model.DatePrecision
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.Requiredness
import io.github.mbackschat.a12.dm.interpreter.model.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The multiplatform [ModelLoader] — DM-JSON → [ModelDef] on BOTH targets (JVM + JS; `:interpreter:check` runs
 * both). Small hand-written DM-JSON string fixtures exercise the whole map: kinds, repeatable groups, rules,
 * tiered computations, enum values/categories, requiredness, index fields, variant filtering, `baseYear`, the
 * raw-number fidelity (F1), and the malformed-JSON contract. The exhaustive parity-vs-Jackson check over every
 * real fixture lives in `:cli` (`ModelLoaderParityTest`, where both loaders are visible).
 */
class ModelLoaderTest {

    // A compact but feature-dense model: a non-repeatable root Order with an enum, a partial DATE, a
    // variant-ONLY field (dropped at the null variant), and a repeatable Lines group carrying an unsigned
    // scaled NUMBER (with a fractional minValue), a rule, and a tiered computation. No `$` in any string
    // (Kotlin raw strings interpolate `$`).
    private val model = """
        {
          "header": { "id": "shop", "modelType": "document" },
          "content": {
            "modelInfo": { "baseYear": "2024" },
            "modelRoot": {
              "rootGroups": [ {
                "type": "Group", "id": "/Order", "name": "Order",
                "Group": {
                  "repeatability": 1,
                  "elements": [
                    { "type": "Field", "id": "/Order/Tier", "name": "Tier",
                      "Field": { "fieldType": { "type": "EnumerationType", "EnumerationType": {
                        "values": [ {"value":"BASIC"}, {"value":"PRO"} ],
                        "categories": [ {"name":"Pricing","values":["FREE","PAID"]} ] } } } },
                    { "type": "Field", "id": "/Order/When", "name": "When",
                      "Field": { "requirednessConfig": {"mode":"absoluteOrRelativeToNextRepAncestor"},
                        "fieldType": { "type":"DateType", "DateType": {"format":"dd.MM.yyyy","datePrecision":"DAY_OPTIONAL"} } } },
                    { "type": "Field", "id": "/Order/Secret", "name": "Secret",
                      "Field": { "variantFilter": {"filterType":"ONLY"}, "fieldType": {"type":"StringType"} } },
                    { "type": "Group", "id": "/Order/Lines", "name": "Lines",
                      "Group": { "repeatability": 5, "indexFieldName": "LineNo",
                        "elements": [
                          { "type": "Field", "id": "/Order/Lines/LineNo", "name": "LineNo",
                            "Field": { "fieldType": {"type":"NumberType","NumberType":{"maxFractionalDigits":0}} } },
                          { "type": "Field", "id": "/Order/Lines/Amount", "name": "Amount",
                            "Field": { "requirednessConfig": {"mode":"relativeToParent"},
                              "fieldType": { "type":"NumberType", "NumberType": {
                                "maxFractionalDigits": 2, "positivesOnly": true, "minValue": 100.50, "maxValue": 1000 } } } },
                          { "type": "Rule", "id": "/Order/Lines/AmountTooLow", "name": "AmountTooLow",
                            "Rule": { "errorCondition": "[Amount] < 100.50", "errorCode": "AMOUNT_TOO_LOW",
                              "errorEntityRelPath": "../Amount", "severity": "WARNING",
                              "errorMessage": [ {"locale":"en_US","text":"Amount too low."} ] } },
                          { "type": "Computation", "id": "/Order/Lines/AmountComp", "name": "AmountComp",
                            "Computation": { "computedFieldRelPath": "../Amount",
                              "commonPrecondition": "FieldFilled(LineNo)",
                              "computationAlternatives": [
                                {"precondition":"[LineNo] > 1","operation":"[LineNo] * 10"},
                                {"operation":"100.50"} ] } }
                        ] } }
                  ] } }
              ] } } }
        """.trimIndent()

    private fun load(): ModelDef = ModelLoader.load(model)

    @Test
    fun collectsFieldsInDocumentOrderDroppingTheVariantOnlyField() {
        // Secret carries variantFilter ONLY → dropped at the null variant; the rest stay in document order.
        assertEquals(
            listOf("/Order/Tier", "/Order/When", "/Order/Lines/LineNo", "/Order/Lines/Amount"),
            load().fields.map { it.path },
        )
    }

    @Test
    fun mapsFieldKindsScaleAndSign() {
        val def = load()
        val amount = def.fields.first { it.path == "/Order/Lines/Amount" }
        assertEquals(Kind.NUMBER, amount.kind)
        assertEquals(2, amount.scale)
        assertTrue(!amount.signed, "positivesOnly:true → unsigned")
        assertTrue("/Order/Lines/Amount" in def.unsignedFields)
        assertEquals(Kind.DATE, def.fields.first { it.path == "/Order/When" }.kind)
        assertEquals(Kind.STRING, def.fields.first { it.path == "/Order/Tier" }.kind)
    }

    @Test
    fun numberTypeWithoutMaxFractionalDigitsIsScaleZero() {
        // The kernel's NumberType.maxFractionalDigits is a primitive int defaulting to 0, so a NumberType that
        // omits it is scale-0 (an integer: a decimal separator is zahlHatDezimalTrenner). The loader must
        // default to 0, NOT null (which would wrongly permit decimals). IF78. Differential: the compute/validate
        // clearing on such a field is `adapter.laws.FieldConstraintFormalDiffTest`/`ComputeInvalidOperandDiffTest`.
        val bare = """
            { "header": { "id": "m", "modelType": "document" }, "content": { "modelRoot": { "rootGroups": [
              { "type": "Group", "id": "/G", "name": "G", "Group": { "repeatability": 1, "elements": [
                { "type": "Field", "id": "/G/N", "name": "N", "Field": { "fieldType": {
                  "type": "NumberType", "NumberType": {} } } },
                { "type": "Field", "id": "/G/Ranged", "name": "Ranged", "Field": { "fieldType": {
                  "type": "NumberType", "NumberType": { "minValue": 1, "maxValue": 99 } } } } ] } } ] } } }
        """.trimIndent()
        val def = ModelLoader.load(bare)
        assertEquals(0, def.fields.first { it.path == "/G/N" }.scale, "bare NumberType → scale 0")
        assertEquals(0, def.fields.first { it.path == "/G/Ranged" }.scale, "range-only NumberType (no maxFrac) → scale 0")
    }

    @Test
    fun preservesRawNumberTextForFractionalConstraints() {
        // F1: the value is read via the tree API and NEVER round-tripped through a Double/JS number, so a
        // declared minValue of 100.50 keeps its exact literal (a Double round-trip would yield "100.5").
        val amount = load().constraintsOf("/Order/Lines/Amount")
        assertEquals("100.50", amount.minValue)
        assertEquals("1000", amount.maxValue)
    }

    @Test
    fun mapsEnumValuesAndCategories() {
        val tier = load().fields.first { it.path == "/Order/Tier" }
        assertEquals(listOf("BASIC", "PRO"), tier.enumValues)
        assertEquals(mapOf("Pricing" to listOf("FREE", "PAID")), tier.enumCategories)
    }

    @Test
    fun mapsDateFormatAndPartialPrecision() {
        val whenField = load().fields.first { it.path == "/Order/When" }
        assertEquals("dd.MM.yyyy", whenField.format)
        assertEquals(DatePrecision.DAY_OPTIONAL, whenField.datePrecision)
    }

    @Test
    fun mapsRequirednessModesAndIndexFieldAutoRequire() {
        val def = load()
        assertEquals(Requiredness.ABSOLUTE, def.fields.first { it.path == "/Order/When" }.required)
        assertEquals(Requiredness.RELATIVE_TO_PARENT, def.fields.first { it.path == "/Order/Lines/Amount" }.required)
        // LineNo declares no explicit requiredness, but is the group's index field → auto-required relative-to-parent.
        assertTrue("/Order/Lines/LineNo" in def.indexFields)
        assertEquals(Requiredness.RELATIVE_TO_PARENT, def.requirednessOf("/Order/Lines/LineNo"))
    }

    @Test
    fun collectsRepeatableGroupsAndDeclaredReps() {
        val def = load()
        assertEquals(setOf("/Order/Lines"), def.repeatable)
        assertEquals(mapOf("/Order/Lines" to 5), def.declaredReps)
    }

    @Test
    fun mapsRulesWithSeverityAndMessages() {
        val rule = load().rules.single()
        assertEquals("/Order/Lines/AmountTooLow", rule.path)
        assertEquals("/Order/Lines", rule.group)
        assertEquals("[Amount] < 100.50", rule.conditionText)
        assertEquals("AMOUNT_TOO_LOW", rule.errorCode)
        assertEquals("../Amount", rule.errorEntityRelPath)
        assertEquals(Severity.WARNING, rule.severity)
        assertEquals(mapOf("en_US" to "Amount too low."), rule.messages)
    }

    @Test
    fun mapsTieredComputationWithCommonPreconditionAndResolvesTarget() {
        val comp = load().computations.single()
        assertEquals("/Order/Lines/Amount", comp.targetPath)   // resolveRel("/Order/Lines/AmountComp", "../Amount")
        assertEquals("/Order/Lines", comp.group)
        assertEquals("AmountComp", comp.name)
        assertEquals("FieldFilled(LineNo)", comp.commonPrecondition)
        assertEquals(2, comp.alternatives.size)
        assertEquals("[LineNo] > 1", comp.alternatives[0].precondition)
        assertEquals("[LineNo] * 10", comp.alternatives[0].operation)
        assertNull(comp.alternatives[1].precondition)
        assertEquals("100.50", comp.alternatives[1].operation)
    }

    @Test
    fun readsBaseYear() {
        assertEquals(2024, load().baseYear)
    }

    @Test
    fun noBaseYearIsNull() {
        val def = ModelLoader.load("""{ "header": { "id": "x" }, "content": { "modelInfo": {} } }""")
        assertNull(def.baseYear)
        assertTrue(def.fields.isEmpty())
    }

    @Test
    fun carriesNonDefaultTimeFormat() {
        // formatOf must read TimeType.format too (not only date/datetime/range) — else a TIME field's declared
        // format is dropped and the interpreter falls back to HH:mm:ss. Locked here as a loader UNIT test rather
        // than a kernel differential: kernel codegen rejects a non-default TimeType format ("date format is
        // invalid" — probed HH:mm / HH.mm.ss / H:mm:ss), so the drop is unobservable via the kernel but real for
        // the standalone interpreter, whose format-driven parseTimeToIso honors it (IF56).
        val def = ModelLoader.load(
            """{ "content": { "modelRoot": { "rootGroups": [ {
                  "type":"Group","id":"/F","name":"F","Group": { "elements": [
                    { "type":"Field","id":"/F/T","name":"T",
                      "Field": { "fieldType": {"type":"TimeType","TimeType":{"format":"HH.mm.ss"}} } } ] } } ] } } }""",
        )
        assertEquals("HH.mm.ss", def.fields.single().format)
        assertEquals("HH.mm.ss", def.timeFormats["/F/T"])
    }

    @Test
    fun mapsDateFragmentTypeToDatePrecisionFragmentCarryingItsFormat() {
        // A DateFragmentType collapses to a DATE (KERNEL-FINDINGS §6) with a FRAGMENT precision and its
        // formatOfFragment as the format — which drives the eval's completion. Each of the four fragment
        // formats must round-trip through the loader (IF57).
        val def = ModelLoader.load(
            """{ "content": { "modelRoot": { "rootGroups": [ {
                  "type":"Group","id":"/Card","name":"Card","Group": { "elements": [
                    { "type":"Field","id":"/Card/Ym","name":"Ym",
                      "Field": { "fieldType": {"type":"DateFragmentType","DateFragmentType":{"formatOfFragment":"yyyy-MM"}} } },
                    { "type":"Field","id":"/Card/Y","name":"Y",
                      "Field": { "fieldType": {"type":"DateFragmentType","DateFragmentType":{"formatOfFragment":"yyyy"}} } },
                    { "type":"Field","id":"/Card/M","name":"M",
                      "Field": { "fieldType": {"type":"DateFragmentType","DateFragmentType":{"formatOfFragment":"MM"}} } },
                    { "type":"Field","id":"/Card/Md","name":"Md",
                      "Field": { "fieldType": {"type":"DateFragmentType","DateFragmentType":{"formatOfFragment":"MM-dd"}} } } ] } } ] } } }""",
        )
        for ((path, fmt) in listOf("/Card/Ym" to "yyyy-MM", "/Card/Y" to "yyyy", "/Card/M" to "MM", "/Card/Md" to "MM-dd")) {
            val f = def.fields.single { it.path == path }
            assertEquals(Kind.DATE, f.kind, path)
            assertEquals(DatePrecision.FRAGMENT, f.datePrecision, path)
            assertEquals(fmt, f.format, path)
            assertEquals(fmt, def.dateFormats[path], path)                       // included in the DATE-format map
            assertEquals(DatePrecision.FRAGMENT, def.datePrecisionOf(path), path)
        }
    }

    @Test
    fun malformedJsonThrowsIllegalArgument() {
        assertFailsWith<IllegalArgumentException> { ModelLoader.load("{ not json") }
    }
}
