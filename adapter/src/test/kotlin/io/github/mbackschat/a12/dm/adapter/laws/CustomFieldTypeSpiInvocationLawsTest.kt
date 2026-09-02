package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeCheckError
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeConversionResult
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeFactory
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeValidationParam
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldValidator
import io.github.mbackschat.a12.dm.modelkit.CustomFieldBuilder
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Optional
import java.util.function.Consumer

/**
 * Kernel-only laws — **which arms of the custom-field-type SPI the engine actually calls, and what happens when
 * the validator is missing.** a12-kernel-lean's `SPEC-2026-09-02-05` asked for exactly this: their clause records
 * the SPI's shape from a source read and states that no claim is made about whether, where, or in which direction
 * the engine invokes either conversion. Their project cannot reach it; ours hosts the factory, so it can.
 * `SPEC-2026-09-02-06` asks the paired question about an unregistered validator, and it is measured here rather
 * than restated: a claim of ours about that case had been travelling on a docstring.
 *
 * **Kernel-only on purpose, and stated rather than implied.** The interpreter's `CustomFieldType` SPI carries the
 * acceptance arm alone, so there is no interpreter side to differential against — which is itself the reconciling
 * answer to `-05`, and it is now an explicit exclusion in the module's customization-parity paragraph rather than
 * a silent omission.
 *
 * **The instrument records calls, so an absence is a reading.** A test that merely asserted "validation still
 * works" would pass whether or not a conversion ran. Every arm of the validator appends to one ordered log, and
 * the assertions read that log — so "no conversion was invoked" is the observed content of a list that
 * demonstrably received the `validate` entry beside it.
 *
 * Own domain (retail bank account maintenance).
 */
class CustomFieldTypeSpiInvocationLawsTest {

    private companion object {
        const val TYPE = "AccountKey"
        const val ACCOUNT = "/Account"
        const val KEY = "/Account/Key"
        const val HOLDER = "/Account/Holder"
        const val MIN = 6
        const val MAX = 12
        const val GOOD = "DE44ABCD"
    }

    /** Every SPI arm appends here, so an arm that was never called is an absence in a list with content. */
    private val log = mutableListOf<String>()

    private fun conversion(value: String) = object : ICustomFieldTypeConversionResult {
        override fun getConvertedValue() = value
        override fun getErrorMessage(): Optional<String> = Optional.empty()
    }

    /** A validator that accepts everything and records how it was asked. Acceptance is not what is under test. */
    private val recording = object : ICustomFieldValidator {
        override fun validate(
            value: String,
            param: ICustomFieldTypeValidationParam,
            isDisplayValue: Boolean,
        ): Optional<ICustomFieldTypeCheckError> {
            log += "validate(value=$value, min=${param.minLength.orElse(null)}, " +
                "max=${param.maxLength.orElse(null)}, locale=${param.errorMsgLocale}, " +
                "isDisplayValue=$isDisplayValue)"
            return Optional.empty()
        }

        override fun convertDisplay2Internal(displayValue: String): ICustomFieldTypeConversionResult {
            log += "convertDisplay2Internal($displayValue)"
            return conversion(displayValue)
        }

        override fun convertInternal2Display(internalValue: String): ICustomFieldTypeConversionResult {
            log += "convertInternal2Display($internalValue)"
            return conversion(internalValue)
        }
    }

    // Every method of `ICustomFieldTypeFactory` is `default`, so it is not a functional interface and there is
    // no SAM form to write — an anonymous object is the only spelling.
    private val registered = object : ICustomFieldTypeFactory {
        override fun createCustomFieldValidator(name: String): Optional<ICustomFieldValidator> =
            if (name == TYPE) Optional.of(recording) else Optional.empty()
    }

    /** The state `SPEC-2026-09-02-06` is about: the type is declared and no validator answers for it. */
    private val unregistered = object : ICustomFieldTypeFactory {
        override fun createCustomFieldValidator(name: String): Optional<ICustomFieldValidator> = Optional.empty()
    }

    private val base = DocumentModelDraft.named("account-maintenance").locale("en_US")
        .rootGroup("Account", Consumer { account ->
            account.stringField("Holder", Consumer { })
            account.customField("Key", Consumer<CustomFieldBuilder> { it.type(TYPE).minLength(MIN).maxLength(MAX) })
        })
        .build().toJson()

    /** A rule that READS the custom cell, so the validation of that cell is on the path a rule depends on. */
    private val condition = DslRenderer().render(BoundModel.ofJson(base).custom(KEY).eq(GOOD))
    private val model = RuntimeLaws.withRule(base, ACCOUNT, "KeyMatches", "../Key", "KEY_MATCH", condition)

    private fun document() = RuntimeLaws.doc().group(ACCOUNT, 1)
        .field(HOLDER, "L. Weber", 1, 1).field(KEY, GOOD, 1, 1)

    /**
     * The model must be ADMITTED with the custom declaration intact. A declaration the deserializer dropped would
     * make every "no conversion was called" reading below trivially true, because nothing would be custom at all.
     */
    @Test
    fun theCustomDeclarationSurvivesTheKernelsOwnModelIngress() {
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model),
            "a refused model would make the invocation log below meaningless")
        assertTrue(RuntimeLaws.modelRoundTrip(model).contains(TYPE),
            "the custom type name must survive A12's own model serializer, or the field is not custom")
    }

    /**
     * **`SPEC-2026-09-02-05`'s open question, answered.** Full validation invokes the ACCEPTANCE arm and neither
     * conversion. The recorded argument tuple also confirms the clause's input list from the outside: the value,
     * both declared bounds, the effective locale, and the stored/display mode — with the mode `false`.
     *
     * <p>The single engine-side caller of either conversion is
     * `FormatDefinitionVordefiniert.convert`, gated on `darstellungsInfo.doConvertCustomFieldType(name)` — the
     * caller's own `PresentationInformation` opt-in, and the same flag that decides the `isDisplayValue`
     * argument here. So the codec is opt-in per presentation configuration and off this path, which is why the
     * log below carries no conversion entry.
     */
    @Test
    fun fullValidationCallsAcceptanceWithTheDeclaredBoundsAndNeitherConversion() {
        log.clear()
        val fired = RuntimeLaws.messages(model, document(), registered).map { it.code() }
        assertEquals(listOf("KEY_MATCH"), fired.filter { it == "KEY_MATCH" },
            "the reading rule must fire, or the cell was never evaluated and the log means nothing")
        assertEquals(
            listOf("validate(value=$GOOD, min=$MIN, max=$MAX, locale=en_US, isDisplayValue=false)"),
            log,
            "acceptance is invoked once for the relevant cell and neither conversion arm is touched",
        )
    }

    /** The generated-Java strategy calls the same arms with the same arguments — no per-strategy codec split. */
    @Test
    fun theGeneratedJavaStrategyCallsTheSameArms() {
        val engine = RuntimeLaws.kernelJavaEngine(model)
        log.clear()
        engine.messages(engine.instance(document()), registered)
        assertEquals(
            listOf("validate(value=$GOOD, min=$MIN, max=$MAX, locale=en_US, isDisplayValue=false)"),
            log,
            "both code-generation strategies reach the SPI the same way",
        )
    }

    /**
     * **`SPEC-2026-09-02-06`'s question, measured rather than reasoned.** A declared custom type whose validator
     * is missing does NOT degrade leniently: the lookup raises, and the raise carries the type's name.
     *
     * <p>Their entry is right to have distrusted the source-shaped prediction — the neighbouring PREDEFINED-type
     * SPI was measured to degrade instead, firing both `Valid`/`Invalid` polarities with nothing raised
     * (`PredefinedTypeValidityDiffTest.unregisteredTypeNameIsAcceptedByTheModelAndBothPolaritiesFireAtRuntime`),
     * and a source-shaped raise prediction had already failed once in that family. These are two different
     * extension points, and they behave differently; this row is the one that measures ours.
     *
     * <p>It also corrects the shape of our own earlier claim, which travelled on a docstring in
     * `CustomFieldTypeBoundsDiffTest` and said only "no validator → it THROWS". The raise is reached from the
     * FULL-check arm; `validiereFormat` returns `Valid` before touching the factory when `fullCheck` is false, so
     * "a relevant concrete cell reaching an unregistered validator fails loudly" needs the full-check qualifier.
     */
    @Test
    fun anUnregisteredValidatorRaisesRatherThanDegradingLeniently() {
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model),
            "the model parse does not reject a missing registration — the raise is a RUNTIME event")
        val raised = assertThrows(RuntimeException::class.java) {
            RuntimeLaws.messages(model, document(), unregistered)
        }
        assertTrue(generateSequence(raised as Throwable) { it.cause }.any { it.message?.contains(TYPE) == true },
            "the raise must name the missing type, or a consumer cannot tell which registration is absent — got " +
                generateSequence(raised as Throwable) { it.cause }.map { "${it::class.simpleName}: ${it.message}" }
                    .joinToString(" ← "),
        )
    }

    /** An EMPTY cell is never handed to the validator, so the raise above needs a filled one to be reachable. */
    @Test
    fun anEmptyCellNeitherCallsTheValidatorNorReachesTheMissingRegistration() {
        log.clear()
        val emptyDocument = RuntimeLaws.doc().group(ACCOUNT, 1).field(HOLDER, "L. Weber", 1, 1)
        RuntimeLaws.messages(model, emptyDocument, registered)
        assertEquals(emptyList<String>(), log, "no cell, no acceptance call")
        RuntimeLaws.messages(model, emptyDocument, unregistered)
    }
}
