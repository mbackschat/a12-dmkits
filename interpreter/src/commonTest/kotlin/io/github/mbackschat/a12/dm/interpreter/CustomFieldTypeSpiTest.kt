package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.CUSTOM_TYPE_INVALID
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldType
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.PredefinedType
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The CODE-BASED [CustomFieldType] SPI (IF44) — the imperative twin of the declarative [PredefinedType], for a
 * custom type whose validity is logic a length/pattern can't express (a checksum). It is dispatched ONLY after
 * the declarative registry misses, so the declarative path is unchanged (the monomorphic-hot-path rule;
 * `CustomTypePerfTest` guards the perf, this guards the behaviour). Runs on JVM AND JS (commonMain), so a
 * Kotlin/JS library consumer reaches the same support. Three behaviours + the coexistence proof:
 *  - **code type + rejected** → `customFieldTypeInvalid` on the cell (and a referencing rule is suppressed);
 *  - **code type + accepted** → clean;
 *  - **declarative and code-based registries COEXIST** — each custom-typed field dispatches to its own registry,
 *    proving the code path is additive (the declarative `accepts` path is not rerouted through the SPI).
 */
class CustomFieldTypeSpiTest {

    // An IMPERATIVE type: valid iff the value starts with "DE" — trivially code, not a format the declarative
    // PredefinedType expresses as a single accepts() the same way. (A real one would be IBAN's mod-97.)
    private val ibanType = mapOf<String, CustomFieldType>("Iban" to CustomFieldType { it.startsWith("DE") })

    private val def = ModelDef(listOf(FieldDef(IBAN, Kind.STRING, customTypeName = "Iban")), emptyList())

    private fun out(value: String) =
        DmInterpreter(def, customFieldTypes = ibanType)
            .validate(def.newDocument().group("/Pay", 1).also { it.field(IBAN, value, 1, 1) })

    @Test
    fun codeTypeAcceptsAValidValue() {
        assertTrue(out("DE89370400440532013000").messages.none { it.code == CUSTOM_TYPE_INVALID },
            "a value the code-based validate() accepts raises no formal error")
    }

    @Test
    fun codeTypeRejectsAnInvalidValue() {
        val o = out("NOT-AN-IBAN")
        assertTrue(o.messages.any { it.code == CUSTOM_TYPE_INVALID }, "a value the code-based validate() rejects fires customFieldTypeInvalid")
        assertTrue(o.unsupported.isEmpty(), "a REGISTERED code type is consulted, not reported unsupported")
    }

    @Test
    fun declarativeAndCodeBasedRegistriesCoexist() {
        // Two custom-typed fields: Iban (code-based) + Code (declarative length 3..5). Each must dispatch to its
        // own registry — the declarative cell uses PredefinedType.accepts, the code cell uses CustomFieldType.validate.
        val both = ModelDef(listOf(
            FieldDef(IBAN, Kind.STRING, customTypeName = "Iban"),
            FieldDef(CODE, Kind.STRING, customTypeName = "Code"),
        ), emptyList())
        val doc = both.newDocument().group("/Pay", 1).also {
            it.field(IBAN, "NOT-AN-IBAN", 1, 1)   // rejected by the code type
            it.field(CODE, "ab", 1, 1)            // rejected by the declarative type (len 2 < 3)
        }
        val o = DmInterpreter(both, predefinedTypes = mapOf("Code" to PredefinedType(minLength = 3, maxLength = 5)),
            customFieldTypes = ibanType).validate(doc)
        val bad = o.messages.filter { it.code == CUSTOM_TYPE_INVALID }.map { it.errorPath }
        assertTrue(bad.any { it.contains("Iban") }, "the code-based Iban cell is rejected: $bad")
        assertTrue(bad.any { it.contains("Code") }, "the declarative Code cell is rejected (path untouched): $bad")
        assertTrue(o.unsupported.isEmpty(), "both types are registered — nothing unsupported")
    }

    private companion object {
        const val IBAN = "/Pay/Iban"
        const val CODE = "/Pay/Code"
    }
}
