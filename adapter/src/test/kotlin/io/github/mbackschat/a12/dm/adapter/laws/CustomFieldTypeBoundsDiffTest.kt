package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeCheckError
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeFactory
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeValidationParam
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldValidator
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldType
import io.github.mbackschat.a12.dm.interpreter.model.CustomTypeRejection
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.modelkit.CustomFieldBuilder
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Optional
import java.util.function.Consumer

/**
 * Differential — a field DECLARED with a **custom field type** is auto-validated per cell, the implicit per-cell
 * form of `Valid(field, type)` (KERNEL-FINDINGS "Custom-extensibility surface"). The kernel delegates the whole
 * check to a registered `ICustomFieldValidator` (no validator → it THROWS; bounds are only hints). So, exactly
 * like [PredefinedTypeValidityDiffTest], this runs the SAME length predicate in all three engines — the kernel
 * strategies via their per-call custom-field-type factory, the interpreter via its [CustomFieldType] SPI — and verifies the OBSERVABLE
 * behaviour: a **rejected** cell becomes "unknown" and SUPPRESSES a value-reading rule that references it,
 * bit-for-bit with the kernel; an **accepted** cell lets the rule evaluate. (The per-cell formal error CODE now
 * ALSO matches — the interpreter emits the validator's project-specific key, IF150 — locked in
 * `CustomFieldTypeRejectionCodeDiffTest`; this test locks the downstream suppression.)
 *
 * The kernel side registers the validator through the modern per-call `DocumentProcessingConfig.customFieldTypeFactory`
 * (the [ICustomFieldTypeFactory] threaded via [RuntimeLaws.messages]) — the successor to the removed static
 * `CustomFieldTypeService` registration (A12K-3589); no static registry, so no per-test register/clean lifecycle.
 */
class CustomFieldTypeBoundsDiffTest {

    // Authored with modelkit (dogfood): a `/Order/Code` field of custom type "Code", length in [3, 5].
    private val json = DocumentModelDraft.named("form").locale("en_US")
        .rootGroup("Order", Consumer { g ->
            g.customField("Code", Consumer<CustomFieldBuilder> { it.type("Code").minLength(3).maxLength(5) })
        })
        .build().toJson()
    private val def = ModelDef(listOf(FieldDef(CODE, Kind.STRING, customTypeName = "Code")), emptyList())
    private val acceptsCode: (String) -> Boolean = { it.length in 3..5 }
    // The interpreter SPI mirrors the kernel validator: reject with the SAME project key (IF150).
    private val codeTypes = mapOf("Code" to CustomFieldType { if (acceptsCode(it)) null else CustomTypeRejection("CODE_INVALID") })

    /** A kernel validator for "Code" enforcing length [3, 5] — the same domain as the interpreter's [PredefinedType]. */
    private val factory = object : ICustomFieldTypeFactory {
        override fun createCustomFieldValidator(name: String): Optional<ICustomFieldValidator> =
            if (name != "Code") Optional.empty() else Optional.of(object : ICustomFieldValidator {
                override fun validate(value: String, p: ICustomFieldTypeValidationParam, isDisplay: Boolean): Optional<ICustomFieldTypeCheckError> =
                    if (acceptsCode(value)) Optional.empty()
                    else Optional.of(object : ICustomFieldTypeCheckError {
                        override fun getErrorMessage() = "bad"
                        override fun getErrorKey() = "CODE_INVALID"
                    })
            })
    }

    private fun iDoc() = def.newDocument()

    // A value-reading rule over the custom field, authored through the typed DSL: `BoundModel.custom(...)` returns a
    // string-shaped handle for a CUSTOM-kind field, so `== "abcd"` is expressed without a hand-written string. Both
    // engines parse the same rendered condition; the rule reads the cell, so a rejected (Unknown) cell suppresses it.
    private val cond = DslRenderer().render(BoundModel.ofJson(json).custom(CODE).eq("abcd"))
    private val ruleModel = RuntimeLaws.withRule(json, "/Order", "R", "../Code", "R", cond)
    private val ruleDef = def.copy(rules = listOf(RuleDef("/Order/R", "/Order", cond, "R", "../Code")))
    private val javaEngine by lazy { RuntimeLaws.kernelJavaEngine(ruleModel) }

    /** Rule R reads the cell; a rejected cell → Unknown → R suppressed, exactly as the kernel. */
    private fun assertRuleAgrees(value: String, msg: String) {
        val kDoc = RuntimeLaws.doc().group("/Order", 1).field(CODE, value, 1, 1)
        val iDoc = iDoc().group("/Order", 1).field(CODE, value, 1, 1)
        val kFired = RuntimeLaws.messages(ruleModel, kDoc, factory).any { it.code() == "R" }
        val jFired = javaEngine.messages(javaEngine.instance(kDoc), factory).any { it.code() == "R" }
        val iFired = DmInterpreter(ruleDef, customFieldTypes = codeTypes).validateFull(iDoc).any { it.code == "R" }
        assertEquals(kFired, jFired, "$msg  (Groovy-dynamic=$kFired generated-Java=$jFired)")
        assertEquals(kFired, iFired, "$msg  (Groovy-dynamic=$kFired interpreter=$iFired)")
    }

    @Test fun acceptedCellLetsTheRuleEvaluate() = assertRuleAgrees("abcd", "valid custom value → rule evaluates (fires: == abcd)")
    @Test fun rejectedCellSuppressesTheRule() = assertRuleAgrees("ab", "rejected custom value → rule suppressed in both engines")

    private companion object {
        const val CODE = "/Order/Code"
    }
}
