package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.CUSTOM_TYPE_INVALID
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.PredefinedType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
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
 *    model whose custom field handlers aren't hosted (the CLI's intended degrade).
 */
class CustomFieldTypeMultiplatformTest {

    // A "Code" custom type: length in [3, 5]. The rule fires (Length < 10) on any short value — so it FIRES on a
    // valid cell AND on a leniently-used unregistered cell, but is SUPPRESSED when a rejected cell goes unknown.
    private val def = ModelDef(
        listOf(FieldDef(CODE, Kind.STRING, customTypeName = "Code")),
        listOf(RuleDef("/Order/R", "/Order", "Length(Code) < 10", "R", "../Code")),
    )
    private val codeType = mapOf("Code" to PredefinedType(minLength = 3, maxLength = 5))

    private fun docOf(value: String): Document =
        Document(def.kinds, def.declaredReps).group("/Order", 1).also { it.field(CODE, value, 1, 1) }

    private fun registered(value: String) = DmInterpreter(def, predefinedTypes = codeType).validate(docOf(value))
    private fun unregistered(value: String) = DmInterpreter(def).validate(docOf(value))   // DEFAULT has no "Code"

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
        assertTrue(out.unsupported.any { "Code" in it.reason }, "the cell is reported unsupported (the kernel would throw)")
        assertTrue(out.messages.any { it.code == "R" }, "validation continues leniently — the value is a plain string, rule fires")
    }

    private companion object {
        const val CODE = "/Order/Code"
    }
}
