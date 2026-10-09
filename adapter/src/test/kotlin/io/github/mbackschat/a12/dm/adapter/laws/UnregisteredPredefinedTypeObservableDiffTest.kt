package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeCheckError
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeFactory
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeValidationParam
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldValidator
import io.github.mbackschat.a12.dm.interpreter.model.PredefinedTypeRegistry
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Optional

/**
 * Answers the lean project's `EXP-2026-08-04-01`: **what IS the observable** when `Valid`/`Invalid` name a
 * predefined type no validator supplies?
 *
 * Two accounts were live, and they predict different RESULT DOMAINS rather than different verdicts.
 * KERNEL-FINDINGS `KF51` calls the bare kernel a *degenerate no-op* in which both polarities fire on any filled
 * value, which needs a domain admitting two contradictory firings and forbids an `Invalid ↔ ¬Valid` law. The
 * requesting project read kernel runtime source instead and derived a *raising lookup*: `predefinedTypeGueltig`
 * and `predefinedTypeUngueltig` gate on value-specified first and differ afterwards only by comparing the
 * validator outcome against *Valid*, so post-gate they are exact complements and no single outcome can make both
 * fire — with `FormatDefinitionVordefiniert.getCustomFieldValidator` raising when the name is absent. The
 * maintained case could not separate them: it asserted only that a message carrying the rule's CODE appeared,
 * which a rule firing and a captured validator failure attributed to the rule both satisfy.
 *
 * **This test therefore retains the NATURE of each observation** — the message's `MessageType`, the responsible
 * field, and whether anything escaped as a throwable — for each polarity, on a filled and on an empty field,
 * under both kernel strategies.
 *
 * **It also splits a variable neither account named, which turns out to be the one that matters.**
 * "Unregistered" is two distinct kernel states: **no factory at all** in the processing configuration, and **a
 * factory present that returns `Optional.empty()` for this name**. The source argument above is about the
 * second — a factory was consulted and produced nothing — while `KF51`'s "no registered `IPredefinedType`" is
 * the first. A single observation covering only one of them would confirm one account while leaving the other
 * standing on the state it never reached, which is exactly the failure mode the requesting ledger warns about.
 *
 * Bound deliberately, per the request: nothing here speaks about the registered-validator branch, about
 * custom-typed **fields** (`KF54`'s separate delegation path), or about the interpreter's fail-fast policy for an
 * unknown name — that intentional divergence stays locked in `PredefinedTypeValidityDiffTest`.
 */
class UnregisteredPredefinedTypeObservableDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = RuntimeLaws.render(c)
    private val cn = m.field(NAME)

    /** A factory that answers for `EMail` only — so `Unregistered` reaches it and it returns nothing. */
    private val missingNameFactory = object : ICustomFieldTypeFactory {
        private val spec = PredefinedTypeRegistry.DEFAULT.getValue("EMail")
        override fun createCustomFieldValidator(name: String): Optional<ICustomFieldValidator> =
            if (name != "EMail") Optional.empty() else Optional.of(object : ICustomFieldValidator {
                override fun validate(
                    value: String,
                    p: ICustomFieldTypeValidationParam,
                    isDisplay: Boolean,
                ): Optional<ICustomFieldTypeCheckError> =
                    if (spec.accepts(value)) Optional.empty() else Optional.of(object : ICustomFieldTypeCheckError {
                        override fun getErrorMessage() = "invalid"
                        override fun getErrorKey() = "EMAIL_INVALID"
                    })
            })
    }

    /**
     * One cell of the matrix, rendered as a stable string so a whole run's observations can be compared at once:
     * either `throw:<ExceptionSimpleName>` or the retained message list (empty means the rule produced nothing).
     */
    private fun observe(polarity: String, filled: Boolean, factory: ICustomFieldTypeFactory?): Pair<String, String> {
        val condition = if (polarity == "Valid") Dsl.validType(cn, TYPE) else Dsl.invalidType(cn, TYPE)
        val model = RuntimeLaws.withRule(order, "/Order", "R", "../$FIELD", CODE, render(condition))
        val doc = RuntimeLaws.doc().group("/Order", 1).let {
            if (filled) it.field(NAME, "alice@example.com", 1, 1) else it
        }
        val dynamic = capture { if (factory == null) RuntimeLaws.messages(model, doc) else RuntimeLaws.messages(model, doc, factory) }
        val generated = capture {
            val engine = RuntimeLaws.kernelJavaEngine(model)
            val instance = engine.instance(doc)
            if (factory == null) engine.messages(instance) else engine.messages(instance, factory)
        }
        return dynamic to generated
    }

    private fun capture(observation: () -> List<RuntimeLaws.Msg>): String =
        try {
            observation().joinToString(" | ") { "${it.code()}/${it.type()}/${it.errorField()}" }.ifEmpty { "<none>" }
        } catch (e: Throwable) {
            "throw:${e::class.simpleName}"
        }

    /**
     * The whole matrix in one assertion, so a single run reports every cell rather than stopping at the first.
     * The expectation is the RECORDED kernel result; a kernel upgrade that changes any cell fails here with the
     * exact before/after, which is what makes this entry's answer re-checkable rather than a one-off note.
     */
    @Test
    fun theObservableIsRecordedForEveryPolarityFillAndRegistrationState() {
        val observed = LinkedHashMap<String, String>()
        for (factoryPresent in listOf(false, true)) {
            for (filled in listOf(true, false)) {
                for (polarity in listOf("Valid", "Invalid")) {
                    val (dynamic, generated) = observe(
                        polarity, filled, if (factoryPresent) missingNameFactory else null,
                    )
                    val state = if (factoryPresent) "factory-misses-name" else "no-factory"
                    val fill = if (filled) "filled" else "empty"
                    // Strategy agreement is asserted per cell, not derived: a split here is itself the finding.
                    assertEquals(
                        dynamic, generated,
                        "dynamic-Groovy and generated-Java must agree — $state/$fill/$polarity",
                    )
                    observed["$state/$fill/$polarity"] = dynamic
                }
            }
        }
        assertEquals(EXPECTED, observed, "the recorded unregistered-type observable changed")
    }

    /**
     * The claim stated as a property rather than as a table, so the file also fails when a future kernel keeps
     * the shape but changes the polarity: on a filled value both polarities produce a RULE message, never a
     * throwable and never an engine-level item, and `Invalid ↔ ¬Valid` therefore does **not** hold here.
     */
    @Test
    fun bothPolaritiesFireAsRuleMessagesSoTheComplementLawDoesNotHold() {
        val (validDynamic, _) = observe("Valid", filled = true, factory = null)
        val (invalidDynamic, _) = observe("Invalid", filled = true, factory = null)
        for (observation in listOf(validDynamic, invalidDynamic)) {
            assertEquals(FIRED, observation, "a fired rule message with a real polarity, not an engine error")
        }
        assertEquals(
            validDynamic, invalidDynamic,
            "the two polarities are NOT complements under an unsupplied validator — both fire on one value",
        )
    }

    private companion object {
        const val NAME = "/Order/CustomerName"
        const val FIELD = "CustomerName"
        const val CODE = "VT"
        const val TYPE = "Unregistered"

        /** The measured error field, identical in every firing cell. */
        private const val AT = "PartiallyKnownDocumentMultiPointerImpl[$NAME, [1, 1]]"

        /** A fired rule message with a real polarity — NOT an engine error, exception, or diagnostic. */
        private const val FIRED = "$CODE/VALUE_ERROR/$AT"

        /**
         * The measured table (kernel 30.8.1, dynamic Groovy and generated Java identical in all eight cells).
         *
         * Reading it: on a FILLED value both polarities emit the rule's message, and no cell is `throw:…` or
         * carries an engine-level item, so nothing escapes the engine. The raise is real but caught around the
         * rule, which emits its error once: `UnregisteredPredefinedTypeAbortDiffTest` separates that from a firing
         * leaf (`KF51`). On an EMPTY value neither fires and nothing
         * raises, confirming the shared value-specified gate runs ahead of any registry contact. And the two
         * registration states are indistinguishable, which closes the "the source argument was about the other
         * state" escape rather than leaving it open.
         */
        val EXPECTED = mapOf(
            "no-factory/filled/Valid" to FIRED,
            "no-factory/filled/Invalid" to FIRED,
            "no-factory/empty/Valid" to "<none>",
            "no-factory/empty/Invalid" to "<none>",
            "factory-misses-name/filled/Valid" to FIRED,
            "factory-misses-name/filled/Invalid" to FIRED,
            "factory-misses-name/empty/Valid" to "<none>",
            "factory-misses-name/empty/Invalid" to "<none>",
        )
    }
}
