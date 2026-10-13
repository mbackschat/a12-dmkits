package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeCheckError
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeFactory
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeValidationParam
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldValidator
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldType
import io.github.mbackschat.a12.dm.modelkit.CustomFieldBuilder
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import java.util.Locale
import java.util.Optional
import java.util.function.Consumer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The ordered full-input check of a STORED value, against both kernel strategies (KERNEL-FINDINGS KF547):
 * a raw `noValueValidation` String bypasses every check; every other kind meets the technical length guard
 * (`wertZuLang`, more than 99,999 UTF-16 units of the AUTHORED text) first, then charset → leading blank →
 * trailing blank → the declared type format, stopping at the first failure. A custom field meets the same
 * gates before its registered validator, which a failed gate never reaches.
 *
 * Each row varies one value against an at-the-boundary control: 99,999 units passes the guard, 100,000 fails
 * it, whether the 100,000th unit is a letter, half of a supplementary character, or the CR of an authored
 * CRLF that normalization later folds to one unit. A computed value meets none of the input gates, but a
 * String target still has its type's default maximum of 99,999. Own domain (an order's free-text fields).
 */
class CheckedInputOrderDiffTest {

    @Test
    fun theGuardRefusesMoreThan99999UnitsOfAnyKindBeforeEveryOtherCheck() {
        agree(NOTE, "a".repeat(LIMIT), emptyList())
        agree(NOTE, "a".repeat(LIMIT + 1), listOf(at(TOO_LONG, NOTE)))
        // A date-format failure if the guard were String-only.
        agree(SHIP, "1".repeat(LIMIT + 1), listOf(at(TOO_LONG, SHIP)))
    }

    @Test
    fun theGuardPrecedesCharsetAndBlankChecks() {
        // The same supplementary character is a charset failure one unit below the limit, so the row above it
        // can only be wertZuLang because the guard runs first.
        agree(NOTE, "a".repeat(LIMIT - 2) + ASTRAL, listOf(at(CHARSET, NOTE)))
        agree(NOTE, "a".repeat(LIMIT - 1) + ASTRAL, listOf(at(TOO_LONG, NOTE)))
        agree(NOTE, " " + "a".repeat(LIMIT), listOf(at(TOO_LONG, NOTE)))
    }

    /**
     * 100,000 authored units are 99,999 after the CRLF → LF fold, so a guard measuring the evaluated text would
     * admit the second value. The rule and the computation are the value's consumers: each reads the control and
     * neither reads the refused value.
     */
    @Test
    fun theGuardCountsTheAuthoredCrlfPairAsTwoUnits() {
        val admitted = "a".repeat(LIMIT - 2) + "\r\n"
        val refused = "a".repeat(LIMIT - 1) + "\r\n"
        agree(NOTE, admitted, emptyList())
        agree(NOTE, refused, listOf(at(TOO_LONG, NOTE)))
        agree(NOTE, admitted, listOf(at(NOTE_LONG, NOTE)), model = ruled)
        agree(NOTE, refused, listOf(at(TOO_LONG, NOTE)), model = ruled)
        assertEquals("$SIZE_AT|VALUE|${LIMIT - 1}", computedOutcome(SIZE, NOTE, admitted))
        assertEquals("$SIZE_AT|CLEARED", computedOutcome(SIZE, NOTE, refused))
    }

    @Test
    fun aRawStringBypassesTheGuardCharsetAndBlankChecks() {
        // The ordinary String twin of each value is the positive control: the same text is refused there.
        for ((value, code) in listOf(" x" to LEADING, "x " to TRAILING, "x$ASTRAL" to CHARSET,
                "a".repeat(LIMIT + 1) to TOO_LONG)) {
            agree(NOTE, value, listOf(at(code, NOTE)))
            agree(MEMO, value, emptyList())
        }
    }

    @Test
    fun aFailedInputGateNeverReachesTheCustomValidator() {
        agree(CODE, "ok", emptyList(), expectedValidatorCalls = 1)
        agree(CODE, " ok", listOf(at(LEADING, CODE)), expectedValidatorCalls = 0)
        agree(CODE, "ok$ASTRAL", listOf(at(CHARSET, CODE)), expectedValidatorCalls = 0)
        agree(CODE, "a".repeat(LIMIT + 1), listOf(at(TOO_LONG, CODE)), expectedValidatorCalls = 0)
    }

    /**
     * A computed value meets no input gate: a 100,000-unit Custom target is stored and reaches its validator.
     * A String target without a declared maximum is refused anyway, because its type carries 99,999 — the
     * Custom twin is what shows that the refusal is the type's, not a guard on computed values. A declared
     * maximum above 99,999 is refused at model check, so the default is the only way to reach it.
     */
    @Test
    fun aComputedValueMeetsTheTargetTypeNotTheInputGuard() {
        val half = "a".repeat(LIMIT / 2)
        assertEquals("$COPY_AT|VALUE|${half + half}", computedOutcome(COPY, NOTE, half))
        val over = "a".repeat((LIMIT + 1) / 2)
        assertEquals("$COPY_AT|ERRORED|${over + over}", computedOutcome(COPY, NOTE, over))
        assertEquals("$CODE_AT|VALUE|${over + over}", computedOutcome(CODE, NOTE, over))
    }

    /**
     * Compute [target] over a document whose only source value is [source]=[value] and every target is seeded;
     * both kernel strategies and the interpreter must agree on its `pointer|outcome|value` signature.
     */
    private fun computedOutcome(target: String, source: String, value: String): String {
        fun kDoc() = seeded(RuntimeLaws.doc().group("/Order", 1).field(source, value, 1, 1))
        val groovy = signature(RuntimeLaws.compute(computing, kDoc(), AcceptingFactory()).single { it.path() == target })
        val engine = RuntimeLaws.kernelJavaEngine(computing)
        val java = engine.computationSignaturesSorted(engine.instance(kDoc()), Locale.US, null, mapOf(), AcceptingFactory())
            .single { it.startsWith("${pointerOf(target)}|") }
        assertEquals(groovy, java, "static-Java — $target")
        val def = ModelLoader.load(computing)
        val result = DmInterpreter(def, customFieldTypes = mapOf(TYPE to CustomFieldType { _, _ -> null }))
            .compute(seeded(def.newDocument().group("/Order", 1).field(source, value, 1, 1)))
            .single { it.path == target }
        val interpreter = when (result.outcome) {
            ComputeOutcome.CLEARED -> "${pointerOf(target)}|CLEARED"
            ComputeOutcome.ERRORED -> "${pointerOf(target)}|ERRORED|${result.value.orEmpty()}"
            ComputeOutcome.VALUE -> "${pointerOf(target)}|VALUE|${result.value.orEmpty()}"
        }
        assertEquals(groovy, interpreter, "interpreter — $target")
        return groovy
    }

    private fun signature(c: RuntimeLaws.Computed): String = when {
        c.cleared() -> "${c.pointer()}|CLEARED"
        c.errored() -> "${c.pointer()}|ERRORED|${c.value().orElse("")}"
        else -> "${c.pointer()}|VALUE|${c.value().orElse("")}"
    }

    private fun seeded(doc: RuntimeLaws.Doc) = doc.field(COPY, "SEED", 1, 1).field(SIZE, "7", 1, 1).field(CODE, "SEED", 1, 1)
    private fun seeded(doc: Document) = doc.field(COPY, "SEED", 1, 1).field(SIZE, "7", 1, 1).field(CODE, "SEED", 1, 1)

    /**
     * Validate a document whose only value is [field]=[value]; the kernel's (code@pointer) list and the
     * interpreter's must both equal [expected]. The registered validator accepts, so it contributes no message
     * and only its invocation count can show whether a gate stopped before it.
     */
    private fun agree(
        field: String,
        value: String,
        expected: List<String>,
        expectedValidatorCalls: Int? = null,
        model: String = this.model,
    ) {
        val label = "$field=${value.take(12)}…(${value.length} units)"
        fun kDoc() = RuntimeLaws.doc().group("/Order", 1).field(field, value, 1, 1)

        val dynamicFactory = AcceptingFactory()
        val dynamic = RuntimeLaws.messages(model, kDoc(), dynamicFactory, Locale.US).map { "${it.code()}@${it.field()}" }
        assertEquals(expected, dynamic, "Groovy-dynamic — $label")

        val staticFactory = AcceptingFactory()
        val staticEngine = RuntimeLaws.kernelJavaEngine(model)
        val static = staticEngine.messages(staticEngine.instance(kDoc()), staticFactory, Locale.US)
            .map { "${it.code()}@${it.field()}" }
        assertEquals(expected, static, "static-Java — $label")

        var interpreterCalls = 0
        val def = ModelLoader.load(model)
        val interpreter = DmInterpreter(def, customFieldTypes = mapOf(TYPE to CustomFieldType { _, _ ->
            interpreterCalls++
            null
        })).validate(def.newDocument().group("/Order", 1).field(field, value, 1, 1), "en_US")
            .messages.map { "${it.code}@${it.errorPath}" }
        assertEquals(expected, interpreter, "interpreter — $label")

        expectedValidatorCalls?.let {
            assertEquals(it, dynamicFactory.calls, "Groovy-dynamic validator calls — $label")
            assertEquals(it, staticFactory.calls, "static-Java validator calls — $label")
            assertEquals(it, interpreterCalls, "interpreter validator calls — $label")
        }
    }

    private val model: String = DocumentModelDraft.named("orderinput").locale("en_US")
        .rootGroup("Order", Consumer { order ->
            order.stringField("Note") { it.lineBreaks() }
                .stringField("Memo") { it.noValueValidation().lineBreaks() }
                .dateField("Ship") { it.format("yyyy-MM-dd") }
                .customField("Code", Consumer<CustomFieldBuilder> { it.type(TYPE) })
                .stringField("Copy") { it.lineBreaks() }
                .numberField("Size") { it.fractionDigits(0) }
        })
        .build().toJson()

    private val ruled: String = run {
        val m = BoundModel.ofJson(model)
        RuntimeLaws.withRule(model, "/Order", "NoteIsLong", "../Note", NOTE_LONG, m.str(NOTE).length().gt(5))
    }

    private val computing: String = run {
        val m = BoundModel.ofJson(model)
        val note = m.str(NOTE).asString()
        val doubled = RuntimeLaws.render("/Order", note.plus(note).expression())
        val withCopy = RuntimeLaws.withComputation(model, "/Order", "CopyNote", "../Copy", doubled)
        val withCode = RuntimeLaws.withComputation(withCopy, "/Order", "CodeNote", "../Code", doubled)
        RuntimeLaws.withComputation(withCode, "/Order", "SizeNote", "../Size",
            RuntimeLaws.render("/Order", m.str(NOTE).length().expression()))
    }

    private class AcceptingFactory : ICustomFieldTypeFactory {
        var calls = 0

        override fun createCustomFieldValidator(name: String): Optional<ICustomFieldValidator> =
            if (name != TYPE) Optional.empty()
            else Optional.of(object : ICustomFieldValidator {
                override fun validate(
                    value: String,
                    param: ICustomFieldTypeValidationParam,
                    isDisplay: Boolean,
                ): Optional<ICustomFieldTypeCheckError> {
                    calls++
                    return Optional.empty()
                }
            })
    }

    private companion object {
        const val LIMIT = 99_999
        const val ASTRAL = "😀"   // U+1F600, two UTF-16 units outside the default BMP charset
        const val TYPE = "OrderCode"
        const val NOTE = "/Order/Note"
        const val MEMO = "/Order/Memo"
        const val SHIP = "/Order/Ship"
        const val CODE = "/Order/Code"
        const val COPY = "/Order/Copy"
        const val SIZE = "/Order/Size"
        val COPY_AT = pointerOf(COPY)
        val CODE_AT = pointerOf(CODE)
        val SIZE_AT = pointerOf(SIZE)
        const val TOO_LONG = "wertZuLang"
        const val CHARSET = "ZeichenNichtImZeichensatz"
        const val LEADING = "fuehrendesBlank"
        const val TRAILING = "nachfolgendesBlank"
        const val NOTE_LONG = "NOTE_LONG"

        fun pointerOf(path: String) = path.replace("/Order/", "/Order[1]/")
        fun at(code: String, path: String) = "$code@${pointerOf(path)}"
    }
}
