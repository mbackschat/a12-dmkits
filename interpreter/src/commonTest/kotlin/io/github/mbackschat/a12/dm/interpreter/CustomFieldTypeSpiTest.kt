package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.CUSTOM_TYPE_INVALID
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldType
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldTypeDeclaration
import io.github.mbackschat.a12.dm.interpreter.model.CustomTypeRejection
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
 *  - **code type + rejected** → the validator's PROJECT-SPECIFIC code on the cell (IF150 — the kernel's
 *    `getErrorKey()`, not a fixed constant), and a referencing rule is suppressed;
 *  - **code type + accepted** → clean;
 *  - **declarative and code-based registries COEXIST** — each custom-typed field dispatches to its own registry:
 *    the code-based cell carries its project code, the declarative cell the interpreter's own `customFieldTypeInvalid`.
 */
class CustomFieldTypeSpiTest {

    // An IMPERATIVE type: valid iff the value starts with "DE" — trivially code, not a format the declarative
    // PredefinedType expresses as a single accepts() the same way. (A real one would be IBAN's mod-97.) Its
    // rejection carries the project-specific key "IBAN_INVALID".
    private val ibanType = mapOf<String, CustomFieldType>(
        "Iban" to CustomFieldType { value, _ ->
            if (value.startsWith("DE")) null else CustomTypeRejection("IBAN_INVALID")
        },
    )

    private val def = ModelDef(listOf(
        FieldDef(IBAN, Kind.STRING, customType = CustomFieldTypeDeclaration("Iban")),
    ), emptyList())

    private fun out(value: String) =
        DmInterpreter(def, customFieldTypes = ibanType)
            .validate(def.newDocument().group("/Pay", 1).also { it.field(IBAN, value, 1, 1) })

    @Test
    fun codeTypeAcceptsAValidValue() {
        assertTrue(out("DE89370400440532013000").messages.none { it.errorPath.contains("Iban") },
            "a value the code-based check() accepts raises no formal error")
    }

    @Test
    fun codeTypeRejectsAnInvalidValueWithItsProjectCode() {
        val o = out("NOT-AN-IBAN")
        assertTrue(o.messages.any { it.code == "IBAN_INVALID" }, "a value the code-based check() rejects fires the validator's project code")
        assertTrue(o.messages.none { it.code == CUSTOM_TYPE_INVALID }, "the fixed constant is NOT used for a code-based rejection")
        assertTrue(o.unsupported.isEmpty(), "a REGISTERED code type is consulted, not reported unsupported")
    }

    @Test
    fun declarativeAndCodeBasedRegistriesCoexist() {
        // Two custom-typed fields: Iban (code-based) + Code (declarative length 3..5). Each must dispatch to its
        // own registry — the declarative cell uses PredefinedType.accepts, the code cell uses CustomFieldType.validate.
        val both = ModelDef(listOf(
            FieldDef(IBAN, Kind.STRING, customType = CustomFieldTypeDeclaration("Iban")),
            FieldDef(CODE, Kind.STRING, customType = CustomFieldTypeDeclaration("Code")),
        ), emptyList())
        val doc = both.newDocument().group("/Pay", 1).also {
            it.field(IBAN, "NOT-AN-IBAN", 1, 1)   // rejected by the code type
            it.field(CODE, "ab", 1, 1)            // rejected by the declarative type (len 2 < 3)
        }
        val o = DmInterpreter(both, predefinedTypes = mapOf("Code" to PredefinedType(minLength = 3, maxLength = 5)),
            customFieldTypes = ibanType).validate(doc)
        // The code-based Iban cell carries its project key; the declarative Code cell the interpreter's constant.
        assertTrue(o.messages.any { it.code == "IBAN_INVALID" && it.errorPath.contains("Iban") },
            "the code-based Iban cell is rejected with its project code: ${o.messages.map { it.code to it.errorPath }}")
        assertTrue(o.messages.any { it.code == CUSTOM_TYPE_INVALID && it.errorPath.contains("Code") },
            "the declarative Code cell is rejected with the constant (path untouched): ${o.messages.map { it.code to it.errorPath }}")
        assertTrue(o.unsupported.isEmpty(), "both types are registered — nothing unsupported")
    }

    private companion object {
        const val IBAN = "/Pay/Iban"
        const val CODE = "/Pay/Code"
    }
}
