package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.CUSTOM_TYPE_INVALID
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldTypeDeclaration
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.PredefinedType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldType
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.interpreter.model.UnsupportedSubject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A field DECLARED with a **custom field type** (`CustomFieldType{name}`) is the implicit, per-cell version of
 * the `Valid(field, name)` operator: every filled cell is auto-validated against the registered [PredefinedType]
 * of that name — the kernel's client-SPI model (KERNEL-FINDINGS "Custom-extensibility surface" / the
 * `ICustomFieldValidator` delegation). Runs on JVM AND JS (the registry + the per-cell check are commonMain),
 * so a Kotlin/JS library consumer reaches the same 100% the JVM does (the differential vs the real kernel lives
 * in `:adapter`). Three behaviours, all locked here on the pure engine:
 *  - **registered + rejected** → a `customFieldTypeInvalid` formal error on the cell, AND a rule referencing it
 *    is suppressed (the value is "unknown", branch-scoped like any formal error);
 *  - **registered + accepted** → clean; a referencing rule evaluates normally;
 *  - **unregistered** → the cell is reported `unsupported` (no validator to run — the kernel would *throw*), and
 *    validation **continues leniently** (the value is used as a plain string), so the tool can still test-run a
 *    model whose custom field handlers aren't hosted (the CLI's intended degrade). A registered handler that
 *    reports itself unavailable uses the same identity fallback after publishing the cell as unsupported.
 */
class CustomFieldTypeMultiplatformTest {

    // A "Code" custom type: length in [3, 5]. The rule fires (Length < 10) on any short value — so it FIRES on a
    // valid cell AND on a leniently-used unregistered cell, but is SUPPRESSED when a rejected cell goes unknown.
    private val def = ModelDef(
        listOf(FieldDef(CODE, Kind.STRING, customType = CustomFieldTypeDeclaration("Code"))),
        listOf(RuleDef("/Order/R", "/Order", "Length(Code) < 10", "R", "../Code")),
    )
    private val codeType = mapOf("Code" to PredefinedType(minLength = 3, maxLength = 5))

    private fun docOf(value: String): Document =
        def.newDocument().group("/Order", 1).also { it.field(CODE, value, 1, 1) }

    private fun registered(value: String) = DmInterpreter(def, predefinedTypes = codeType).validate(docOf(value))
    private fun unregistered(value: String) = DmInterpreter(def).validate(docOf(value))   // DEFAULT has no "Code"

    private val indexedDef = ModelDef(
        fields = listOf(
            FieldDef(CUSTOMER, Kind.STRING),
            FieldDef(INDEX_CODE, Kind.STRING, customType = CustomFieldTypeDeclaration("Code")),
            FieldDef(QTY, Kind.NUMBER),
        ),
        rules = listOf(RuleDef(
            "/Order/Indexed",
            "/Order",
            "FieldFilled(/Order/Lines/Qty For \"A\")",
            "INDEXED",
            "../Customer",
        )),
        repeatableGroups = setOf(LINES),
        indexFields = setOf(INDEX_CODE),
        groupPaths = setOf("/Order", LINES),
    )

    private fun indexedDoc(vararg values: String): Document = indexedDef.newDocument().group("/Order", 1)
        .field(CUSTOMER, "Acme", 1, 1)
        .also { doc ->
            values.forEachIndexed { index, value ->
                val row = index + 1
                doc.group(LINES, 1, row)
                    .field(INDEX_CODE, value, 1, row, 1)
                    .field(QTY, row.toString(), 1, row, 1)
            }
        }

    @Test
    fun registeredAcceptEvaluatesNormally() {
        val out = registered("abcd")   // len 4 — valid
        assertTrue(out.messages.none { it.code == CUSTOM_TYPE_INVALID }, "a valid custom value raises no formal error")
        assertTrue(out.messages.any { it.code == "R" }, "and the referencing rule evaluates (fires: Length 4 < 10)")
    }

    @Test
    fun registeredRejectFlagsTheCellAndSuppressesTheRule() {
        val out = registered("ab")   // len 2 — below minLength 3 → rejected
        assertTrue(out.messages.any { it.code == CUSTOM_TYPE_INVALID }, "a rejected custom value raises customFieldTypeInvalid")
        assertTrue(out.messages.none { it.code == "R" }, "and the rule referencing the now-unknown cell is suppressed")
    }

    @Test
    fun unregisteredReportsUnsupportedAndEvaluatesLeniently() {
        val out = unregistered("ab")   // no validator registered for "Code"
        assertTrue(out.messages.none { it.code == CUSTOM_TYPE_INVALID }, "no validator → no custom formal error")
        assertEquals(listOf(io.github.mbackschat.a12.dm.interpreter.model.UnsupportedRule(
            "/Order[1]/Code", "custom field type 'Code' has no registered validator", UnsupportedSubject.CELL)),
            out.unsupported.filter { it.subject == UnsupportedSubject.CELL })
        assertTrue(out.messages.any { it.code == "R" }, "validation continues leniently — the value is a plain string, rule fires")
    }

    @Test
    fun unsupportedIndexValuesRemainLenientButStillObeyCollisionPolicy() {
        val unique = DmInterpreter(indexedDef).validate(indexedDoc("A"))
        assertTrue(unique.unsupported.any { it.rulePath.contains("Code") }, "the missing Code validator stays loud")
        assertEquals(listOf("INDEXED"), unique.messages.map { it.code }, "one lenient key selects its row")

        val duplicated = DmInterpreter(indexedDef).validate(indexedDoc("A", "A"))
        assertEquals(2, duplicated.unsupported.count { it.rulePath.contains("Code") }, "each cell stays unsupported")
        assertEquals(
            listOf("uniqueIndex", "uniqueIndex"),
            duplicated.messages.map { it.code }.sorted(),
            "unsupported is an admission fallback, not an escape from the downstream duplicate-key policy",
        )

        val seen = mutableListOf<String>()
        val unavailable = DmInterpreter(indexedDef, customFieldTypes = mapOf("Code" to CustomFieldType { value, _ ->
            seen += value
            throw UnsupportedConstructException("registered validator unavailable")
        }))
        val unavailableUnique = unavailable.validate(indexedDoc("A"))
        assertEquals(listOf("A"), seen, "one unavailable registered validator is consulted once")
        assertEquals(1, unavailableUnique.unsupported.count { it.rulePath.contains("Code") })
        assertEquals(
            listOf("INDEXED"),
            unavailableUnique.messages.map { it.code },
            "a registered validator outage retains the same lenient identity fallback",
        )

        seen.clear()
        val unavailableDuplicate = unavailable.validate(indexedDoc("A", "A"))
        assertEquals(listOf("A", "A"), seen, "each unavailable registered cell is consulted once")
        assertEquals(2, unavailableDuplicate.unsupported.count { it.rulePath.contains("Code") })
        assertEquals(
            listOf("uniqueIndex", "uniqueIndex"),
            unavailableDuplicate.messages.map { it.code }.sorted(),
            "registered-validator unavailability cannot bypass collision policy",
        )

        val directDef = indexedDef.copy(rules = indexedDef.rules + RuleDef(
            "$LINES/Direct",
            LINES,
            "FieldFilled(Code) And [Code] == \"A\"",
            "DIRECT",
            "../Code",
        ))
        seen.clear()
        val direct = DmInterpreter(directDef, customFieldTypes = mapOf("Code" to CustomFieldType { value, _ ->
            seen += value
            throw UnsupportedConstructException("registered validator unavailable")
        })).validate(indexedDoc("A"))
        assertEquals(listOf("A"), seen, "identity and a direct read share one cached callback result")
        assertEquals(listOf("INDEXED"), direct.messages.map { it.code }, "identity-only consumers remain lenient")
        assertEquals(
            listOf(UnsupportedSubject.CELL, UnsupportedSubject.ELEMENT),
            direct.unsupported.map { it.subject },
            "the cell stays visible and a rule that reads its value remains loudly unsupported",
        )
    }

    @Test
    fun aNonRelevantCustomIndexCellIsNotAdmittedOrValidated() {
        val seen = mutableListOf<String>()
        val interpreter = DmInterpreter(indexedDef, customFieldTypes = mapOf("Code" to CustomFieldType { value, _ ->
            seen += value
            null
        }))
        val payloadOnly = setOf(
            RelevantEntity(CUSTOMER, listOf(1, 1)),
            RelevantEntity(QTY, listOf(1, 1, 1)),
        )
        val withoutIndex = interpreter.validatePart(indexedDoc("A"), payloadOnly)
        assertEquals(emptyList(), seen, "relevance excludes the custom cell before its admission callback")
        assertTrue(withoutIndex.messages.none { it.code == "INDEXED" }, "a non-relevant index contributes no key")

        val withIndex = interpreter.validatePart(
            indexedDoc("A"),
            payloadOnly + RelevantEntity(INDEX_CODE, listOf(1, 1, 1)),
        )
        assertEquals(listOf("A"), seen, "the paired relevant control reaches exactly one custom check")
        assertTrue(withIndex.messages.any { it.code == "INDEXED" }, "the relevant admitted key selects its row")
    }

    private companion object {
        const val CODE = "/Order/Code"
        const val CUSTOMER = "/Order/Customer"
        const val LINES = "/Order/Lines"
        const val INDEX_CODE = "/Order/Lines/Code"
        const val QTY = "/Order/Lines/Qty"
    }
}
