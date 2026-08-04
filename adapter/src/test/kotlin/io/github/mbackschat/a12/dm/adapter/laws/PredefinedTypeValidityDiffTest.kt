package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeCheckError
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeFactory
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeValidationParam
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldValidator
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.PredefinedTypeRegistry
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Optional

/**
 * Differential — `ValidType`/`InvalidType` (`Valid(field, "EMail")` / `Invalid(field, "EMail")`), the
 * predefined-custom-field-type validity predicates. The kernel makes these a runtime CLIENT SPI
 * (`IPredefinedType`; KERNEL-FINDINGS §8) — the bare kernel ships no validator and is a degenerate no-op, so
 * to get a REAL ground truth this test provides the SAME validator to BOTH engines: the kernel oracle through
 * its per-call processing configuration (a factory delegating to the interpreter's own [PredefinedTypeRegistry]
 * spec), and the interpreter through its default registry (the built-in `EMail`). Identical validator on both
 * sides → the differential verifies the OPERATOR SEMANTICS (firing, polarity, empty/not-specified handling),
 * not the regex.
 *
 * Probe-confirmed: `Valid` fires iff the present value is a valid instance (VALUE), `Invalid` its complement
 * (VALUE); an EMPTY field is "not specified" → neither fires (no `FieldFilled` guard needed).
 */
class PredefinedTypeValidityDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(FieldDef(NAME, Kind.STRING))

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines — no free-text condition literals. `Dsl.validType(m.field(p), "EMail")` is Valid(field, "EMail").
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = DslRenderer().render(c)
    private val cn = m.field(NAME)

    /** A kernel custom-field-type validator for "EMail" that delegates to the SAME spec the interpreter uses,
     *  so both engines apply byte-identical validation logic. */
    private val factory = object : ICustomFieldTypeFactory {
        private val spec = PredefinedTypeRegistry.DEFAULT.getValue("EMail")
        override fun createCustomFieldValidator(name: String): Optional<ICustomFieldValidator> =
            if (name != "EMail") Optional.empty() else Optional.of(object : ICustomFieldValidator {
                override fun validate(value: String, p: ICustomFieldTypeValidationParam, isDisplay: Boolean): Optional<ICustomFieldTypeCheckError> =
                    if (spec.accepts(value)) Optional.empty()
                    else Optional.of(object : ICustomFieldTypeCheckError {
                        override fun getErrorMessage() = "invalid"
                        override fun getErrorKey() = "EMAIL_INVALID"
                    })
            })
    }

    /** [cond] with CustomerName = [name] (null = empty); compare kernel vs interpreter (firing + polarity). */
    private fun agree(cond: String, name: String?, msg: String) {
        val kModel = RuntimeLaws.withRule(order, "/Order", "R", "../CustomerName", "VT", cond)
        val def = ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "VT", "../CustomerName")))
        val kDoc = RuntimeLaws.doc().group("/Order", 1)
        val iDoc = def.newDocument().group("/Order", 1)
        if (name != null) { kDoc.field(NAME, name, 1, 1); iDoc.field(NAME, name, 1, 1) }
        val k = RuntimeLaws.messages(kModel, kDoc, factory)
            .firstOrNull { it.code() == "VT" }?.type()?.name
        val i = DmInterpreter(def).validate(iDoc).messages.firstOrNull { it.code == "VT" }
            ?.type?.let { if (it == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR" }
        assertEquals(k, i, "$msg  (kernel=$k interp=$i)")
    }

    private val valid = "alice@example.com"
    private val invalid = "not-an-email"

    // Guarded form (the catalog's idiomatic `FieldFilled(...) And Valid(...)`).
    @Test fun validEmailValidFires() = agree(render(cn.filled().and(Dsl.validType(cn, "EMail"))), valid, "valid email → Valid fires VALUE")
    @Test fun invalidEmailValidNoFire() = agree(render(cn.filled().and(Dsl.validType(cn, "EMail"))), invalid, "invalid email → Valid no fire")
    @Test fun invalidEmailInvalidFires() = agree(render(cn.filled().and(Dsl.invalidType(cn, "EMail"))), invalid, "invalid email → Invalid fires VALUE")
    @Test fun validEmailInvalidNoFire() = agree(render(cn.filled().and(Dsl.invalidType(cn, "EMail"))), valid, "valid email → Invalid no fire")

    // Bare form — empty is "not specified": neither Valid nor Invalid fires (no FieldFilled guard needed).
    @Test fun bareValidValidFires() = agree(render(Dsl.validType(cn, "EMail")), valid, "bare Valid, valid → fires VALUE")
    @Test fun bareValidInvalidNoFire() = agree(render(Dsl.validType(cn, "EMail")), invalid, "bare Valid, invalid → no fire")
    @Test fun bareValidEmptyNoFire() = agree(render(Dsl.validType(cn, "EMail")), null, "bare Valid, empty → no fire (not specified)")
    @Test fun bareInvalidInvalidFires() = agree(render(Dsl.invalidType(cn, "EMail")), invalid, "bare Invalid, invalid → fires VALUE")
    @Test fun bareInvalidValidNoFire() = agree(render(Dsl.invalidType(cn, "EMail")), valid, "bare Invalid, valid → no fire")
    @Test fun bareInvalidEmptyNoFire() = agree(render(Dsl.invalidType(cn, "EMail")), null, "bare Invalid, empty → no fire (not specified)")

    @Test
    fun predefinedValidatorTriChecksGeneratedJava() {
        val cond = render(Dsl.validType(cn, "EMail"))
        val model = RuntimeLaws.withRule(order, "/Order", "R", "../CustomerName", "VT", cond)
        val def = ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "VT", "../CustomerName")))
        val javaEngine = RuntimeLaws.kernelJavaEngine(model)

        listOf(valid to "VALUE_ERROR", invalid to null, "a\u00a0@b.co" to "VALUE_ERROR").forEach { (name, expected) ->
            val kDoc = RuntimeLaws.doc().group("/Order", 1).field(NAME, name, 1, 1)
            val iDoc = def.newDocument().group("/Order", 1).field(NAME, name, 1, 1)
            val groovy = RuntimeLaws.messages(model, kDoc, factory)
                .firstOrNull { it.code() == "VT" }?.type()?.name
            val javaStatic = javaEngine.messages(javaEngine.instance(kDoc), factory)
                .firstOrNull { it.code() == "VT" }?.type()?.name
            val interpreter = DmInterpreter(def).validateFull(iDoc).firstOrNull { it.code == "VT" }
                ?.type?.let { if (it == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR" }

            assertEquals(expected, groovy, "$name discriminator")
            assertEquals(groovy, javaStatic, "$name — Groovy-dynamic vs generated Java")
            assertEquals(groovy, interpreter, "$name — Groovy-dynamic vs interpreter")
        }
    }

    @Test
    fun unregisteredTypeNameIsAcceptedByTheModelAndBothPolaritiesFireAtRuntime() {
        listOf(Dsl.validType(cn, "Unregistered"), Dsl.invalidType(cn, "Unregistered")).forEach { condition ->
            val model = RuntimeLaws.withRule(order, "/Order", "R", "../CustomerName", "VT", render(condition))
            val doc = RuntimeLaws.doc().group("/Order", 1).field(NAME, valid, 1, 1)
            val javaEngine = RuntimeLaws.kernelJavaEngine(model)

            // The NATURE of the observable, not merely the presence of the code: a message that carries the
            // rule's code could also have been a captured validator failure attributed to the rule, which is a
            // different result domain. Projecting code+type separates them, and both engines must agree on it.
            // The full registration/fill matrix, and what it refutes, is
            // UnregisteredPredefinedTypeObservableDiffTest (KF51).
            val fired = { messages: List<RuntimeLaws.Msg> ->
                messages.filter { it.code() == "VT" }.map { "${it.code()}/${it.type()}" }
            }
            val dynamic = fired(RuntimeLaws.messages(model, doc, factory))
            val generated = fired(javaEngine.messages(javaEngine.instance(doc), factory))
            assertEquals(listOf("VT/VALUE_ERROR"), dynamic, "one fired rule message with a real polarity")
            val definition = ModelDef(
                fields,
                listOf(RuleDef("/Order/R", "/Order", render(condition), "VT", "../CustomerName")),
            )
            val interpreted = DmInterpreter(definition).validate(
                definition.newDocument().group("/Order", 1).field(NAME, valid, 1, 1),
            )

            assertEquals(dynamic, generated)
            assertEquals(emptyList<Any>(), interpreted.messages, "the interpreter must not guess kernel SPI fallback semantics")
            assertEquals(1, interpreted.unsupported.size, "the intentional unregistered-type divergence stays explicit")
            assertTrue(interpreted.unsupported.single().reason.contains("unknown predefined type"))
        }
    }

    private companion object {
        const val NAME = "/Order/CustomerName"
    }
}
