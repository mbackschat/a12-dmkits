package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.FORMAL_RULE_PATH
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.NumberTrait
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tri-engine lock for `NumberType.positivesOnly` — the unsigned-number formal check `zahlMitVorzeichen`, its
 * placement in the format ladder, and the two classes it is DISJOINT from rather than ordered against
 * ([KERNEL-FINDINGS](../../../../../../../../../docs/KERNEL-FINDINGS.md) KF253).
 *
 * <p>Every row is a **within-model** control: one model declares the whole grid, so two rows differ in exactly
 * the builder call under test and nothing else. Comparing two models would vary everything not thought about.
 * The `signed*` twins are what make the negatives measurements: the same value on the signed sibling reports a
 * different class (or none), so a silent row cannot pass by the value being universally clean.
 */
class NumberUnsignedFormalDiffTest {

    private val model = DocumentModelDraft.named("unsigned-number-formal").locale("en_US")
        .rootGroup("Ledger") { g ->
            g.numberField("SignedInt") { it.fractionDigits(0) }
                .numberField("UnsignedInt") { it.fractionDigits(0).positivesOnly() }
                .numberField("SignedIntNoZero") { it.fractionDigits(0).zeroNotAllowed() }
                .numberField("UnsignedIntNoZero") { it.fractionDigits(0).positivesOnly().zeroNotAllowed() }
                .numberField("SignedCents") { it.fractionDigits(2) }
                .numberField("UnsignedCents") { it.fractionDigits(2).positivesOnly() }
                .numberField("UnsignedShort") { it.fractionDigits(0).positivesOnly().minLength(3) }
                .numberField("UnsignedBounded") { it.fractionDigits(0).positivesOnly().min("10").max("20") }
                .numberField("MoneyNoZero") {
                    it.fractionDigits(2).zeroNotAllowed().unit(NumberTrait.AMOUNT)
                }
                .numberField("UnsignedMoneyNoZero") {
                    it.fractionDigits(2).positivesOnly().zeroNotAllowed().unit(NumberTrait.AMOUNT)
                }
        }
        .build().toJson()

    private val definition = ModelLoader.load(model)
    private val javaEngine = RuntimeLaws.kernelJavaEngine(model)

    @Test
    fun aMinusOnAnUnsignedFieldIsTheSignClassAndOnItsSignedTwinIsClean() {
        agree("UnsignedInt", "-1", "zahlMitVorzeichen")
        agree("SignedInt", "-1", null)
        agree("UnsignedCents", "-1.00", "zahlMitVorzeichen")
        agree("SignedCents", "-1.00", null)
        agree("UnsignedInt", "1", null)
    }

    /**
     * The signed-zero class conjoins `nullErlaubt && hatVorzeichen`, so it cannot co-occur with either
     * neighbour: on an unsigned field the sign class reports, on a zero-forbidding field `zahlIstNull` does.
     * The KF37 ladder read these as positions 4 → 5 → 10; only the unsigned/zero-forbidding cells show that
     * two of those orderings are unobservable.
     */
    @Test
    fun theSignedZeroClassIsDisjointFromBothItsLadderNeighbours() {
        agree("SignedInt", "-0", "zahlNullEnthaeltVorzeichen")
        agree("UnsignedInt", "-0", "zahlMitVorzeichen")
        agree("SignedIntNoZero", "-0", "zahlIstNull")
        agree("UnsignedIntNoZero", "-0", "zahlMitVorzeichen")
        agree("SignedCents", "-0.00", "zahlNullEnthaeltVorzeichen")
        agree("UnsignedCents", "-0.00", "zahlMitVorzeichen")
    }

    /**
     * And the disjointness moves the separator ordering too: `-0.0` on a scale-0 field is the signed-zero class
     * where zero is allowed and the SEPARATOR class where it is not, because the signed-zero clause is skipped
     * and the separator check is the next one to fire.
     */
    @Test
    fun forbiddingZeroFlipsWhichOfSignedZeroAndTheSeparatorCheckReports() {
        agree("SignedInt", "-0.0", "zahlNullEnthaeltVorzeichen")
        agree("SignedIntNoZero", "-0.0", "zahlHatDezimalTrenner")
    }

    /**
     * The one nested exception inside the unsigned arm: a zero-magnitude value on an unsigned, zero-forbidding
     * **money** field reports `zahlIstNull`. `MoneyNoZero` is the trait control and `-5.00` the magnitude
     * control — without both, "money disables the sign gate" would fit every row.
     */
    @Test
    fun anUnsignedZeroForbiddingMoneyFieldReportsTheZeroClassForASignedZero() {
        agree("UnsignedMoneyNoZero", "-0.00", "zahlIstNull")
        agree("UnsignedIntNoZero", "-0", "zahlMitVorzeichen")
        agree("MoneyNoZero", "-0.00", "zahlIstNull")
        agree("UnsignedMoneyNoZero", "-5.00", "zahlMitVorzeichen")
    }

    /** Three gates beat the sign class; six lose to it. Each row is one declaration away from its neighbour. */
    @Test
    fun theSignClassSitsBetweenTheParseChecksAndEveryDeclaredConstraint() {
        agree("UnsignedInt", "+1", "zahlMitPlus")
        agree("UnsignedInt", "-abc", "zahlHatUngueltigeZeichen")
        agree("UnsignedInt", "- 1", "zahlHatUngueltigeZeichen")
        agree("UnsignedInt", "-1234567890123456", "stelligkeitZuLang")

        agree("UnsignedInt", "-1.5", "zahlMitVorzeichen")
        agree("UnsignedInt", "-07", "zahlMitVorzeichen")
        agree("UnsignedShort", "-1", "zahlMitVorzeichen")
        agree("UnsignedBounded", "-5", "zahlMitVorzeichen")
        agree("UnsignedBounded", "-15", "zahlMitVorzeichen")
    }

    /**
     * `positivesOnly` beside a NEGATIVE `minValue` is accepted by the kernel's own consistency gate — measured,
     * because a client-side refusal of that pairing would have looked obviously right and been wrong.
     */
    @Test
    fun aNegativeMinValueBesidePositivesOnlyIsKernelValid() {
        val odd = DocumentModelDraft.named("odd-unsigned").locale("en_US")
            .rootGroup("Ledger") { g ->
                g.numberField("Balance") { it.fractionDigits(2).positivesOnly().min("-5.00").max("5.00") }
            }
            .build().toJson()
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(odd))

        val doc = RuntimeLaws.doc().group("/Ledger", 1).field("/Ledger/Balance", "-1.00", 1, 1)
        assertEquals(
            listOf("zahlMitVorzeichen"),
            RuntimeLaws.messages(odd, doc).map { it.code() }.sorted(),
        )
    }

    /** Groovy-dynamic (normative) == generated Java == interpreter, on the one field the row names. */
    private fun agree(field: String, value: String, expected: String?) {
        val path = "/Ledger/$field"
        val dynamic = codes(RuntimeLaws.messages(model, doc(path, value)).map { it.code() })
        val generated = codes(javaEngine.messages(javaEngine.instance(doc(path, value))).map { it.code() })
        val interpreted = codes(
            DmInterpreter(definition)
                .validate(definition.newDocument().group(ROOT, 1).field(path, value, 1, 1))
                .messages.filter { it.rulePath == FORMAL_RULE_PATH }.map { it.code },
        )

        assertEquals(listOfNotNull(expected), dynamic, "$field = '$value' — dynamic Groovy is normative")
        assertEquals(dynamic, generated, "$field = '$value' — dynamic Groovy vs generated Java")
        assertEquals(dynamic, interpreted, "$field = '$value' — dynamic Groovy vs interpreter")
    }

    private fun doc(path: String, value: String) =
        RuntimeLaws.doc().group(ROOT, 1).field(path, value, 1, 1)

    private fun codes(raw: List<String>) = raw.sorted()

    private companion object {
        const val ROOT = "/Ledger"
    }
}
