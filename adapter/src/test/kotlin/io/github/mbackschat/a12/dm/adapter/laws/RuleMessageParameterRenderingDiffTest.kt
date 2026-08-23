package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tri-engine lock for what the WIDER rule-message parameter forms actually RENDER (KERNEL-SEMANTICS §13,
 * [KF198]) — the category suffix, the Base Year terminal with its offsets, and the two per-format defaults.
 * [RuleMessageParameterLawsTest] locks which of them the kernel ADMITS; this one locks the text a user reads.
 *
 * Three rows are within-model controls, and each exists because the obvious statement is fixture-true rather
 * than general:
 *
 *  * **the value suffix on an Enumeration renders the value's LABEL**, and only falls back to the stored
 *    S-value when the declaration carries none — `Tier` and `RawTier` differ in exactly that;
 *  * **the unfilled-Number default is keyed on MINIMUM fractional digits**, not on "declaring two decimals" —
 *    `Premium` (max 2, min 0) renders `0` beside `MinPremium` (min 2) rendering `0.00`;
 *  * **the `$Field$` fallback is the SHORT NAME only when the field is unlabelled** — a declared label wins,
 *    which `Handler` shows beside `Reference`.
 *
 * A category access renders the category TOKEN the stored value maps to (`STANDARD`), never the category name
 * and never the stored value; an empty Enumeration renders the empty string under both suffixes while its name
 * still renders.
 *
 * Insurance domain (own fixtures). Authored end-to-end through the typed surface — modelkit draft, EditSession,
 * rulekit refs — so the model passes the real consistency gate before any text is read.
 */
class RuleMessageParameterRenderingDiffTest {

    private companion object {
        const val POLICY = "/Policy"
        const val REFERENCE = "/Policy/Reference"
        const val HANDLER = "/Policy/Handler"
        const val PREMIUM = "/Policy/Premium"
        const val MIN_PREMIUM = "/Policy/MinPremium"
        const val TIER = "/Policy/Tier"
        const val RAW_TIER = "/Policy/RawTier"
    }

    private val model: String = run {
        val base = DocumentModelDraft.named("claims-policy").locale("en_US").baseYear(2020)
            .rootGroup("Policy") { policy ->
                policy
                    .stringField("Reference") { }
                    .stringField("Handler") { it.label("en_US", "Claims handler") }
                    .numberField("Premium") { it.fractionDigits(0, 2) }
                    .numberField("MinPremium") { it.fractionDigits(2, 2) }
                    .enumField("Tier") { e ->
                        e.value("BASIC") { it.label("en_US", "Basic cover") }
                            .value("PREMIUM") { it.label("en_US", "Premium cover") }
                            .category("Band", "STANDARD", "PRIORITY")
                    }
                    .enumField("RawTier") { e ->
                        e.value("BASIC").value("PREMIUM").category("Band", "STANDARD", "PRIORITY")
                    }
            }
            .build().toJson()
        EditSession.read(base).also { edit ->
            edit.addRule { b ->
                val reference = b.str(REFERENCE)
                b.rule(b.group(POLICY)).name("LabelledTier").code("LABELLED_TIER").errorField(reference)
                    .`when`(reference.filled().and(b.enumOf(TIER).`is`("BASIC"))).severity(Severity.ERROR)
                    .message(Dsl.en("cat=[\$Tier->Band\$] val=[\$Tier.value\$] name=[\$Tier\$]")).build()
            }
            edit.addRule { b ->
                val reference = b.str(REFERENCE)
                b.rule(b.group(POLICY)).name("UnlabelledTier").code("UNLABELLED_TIER").errorField(reference)
                    .`when`(reference.filled().and(b.enumOf(RAW_TIER).`is`("BASIC"))).severity(Severity.ERROR)
                    .message(Dsl.en("cat=[\$RawTier->Band\$] val=[\$RawTier.value\$]")).build()
            }
            edit.addRule { b ->
                val reference = b.str(REFERENCE)
                b.rule(b.group(POLICY)).name("EmptyTier").code("EMPTY_TIER").errorField(reference)
                    .`when`(reference.filled().and(b.field(TIER).notFilled())).severity(Severity.ERROR)
                    .message(Dsl.en("cat=[\$Tier->Band\$] val=[\$Tier.value\$] name=[\$Tier\$]")).build()
            }
            edit.addRule { b ->
                val reference = b.str(REFERENCE)
                b.rule(b.group(POLICY)).name("BaseYearText").code("BASE_YEAR").errorField(reference)
                    .`when`(reference.filled()).severity(Severity.ERROR)
                    .message(Dsl.en("[\$BaseYear\$] [\$BaseYear+3\$] [\$BaseYear-1\$] [\$\$]")).build()
            }
            edit.addRule { b ->
                val reference = b.str(REFERENCE)
                b.rule(b.group(POLICY)).name("ZeroDefaults").code("ZERO_DEFAULTS").errorField(reference)
                    .`when`(
                        reference.filled()
                            .and(b.field(PREMIUM).notFilled())
                            .and(b.field(MIN_PREMIUM).notFilled()),
                    ).severity(Severity.ERROR)
                    .message(Dsl.en("max=[\$Premium.value\$] min=[\$MinPremium.value\$]")).build()
            }
            edit.addRule { b ->
                val reference = b.str(REFERENCE)
                b.rule(b.group(POLICY)).name("NameFallback").code("NAME_FALLBACK").errorField(reference)
                    .`when`(reference.filled().and(b.str(HANDLER).filled())).severity(Severity.ERROR)
                    .message(Dsl.en("plain=[\$Reference\$] labelled=[\$Handler\$]")).build()
            }
            edit.validate()
        }.toJson()
    }

    private val definition = ModelLoader.load(model)

    /** One document mirrored across the kernel [RuntimeLaws.Doc] and the interpreter document. */
    private inner class Build {
        val kDoc = RuntimeLaws.doc().group(POLICY, 1)
        val iDoc = definition.newDocument().group(POLICY, 1)
        fun field(path: String, value: String) {
            kDoc.field(path, value, 1, 1)
            iDoc.field(path, value, 1, 1)
        }
    }

    /** Reference, Handler and both Enumerations filled; the two Numbers absent. */
    private fun filled() = Build().apply {
        field(REFERENCE, "P-1")
        field(HANDLER, "H-9")
        field(TIER, "BASIC")
        field(RAW_TIER, "BASIC")
    }

    /** Only the Reference — so the empty-Enumeration rule fires instead. */
    private fun bare() = Build().apply { field(REFERENCE, "P-1") }

    private fun agree(code: String, expected: String, build: Build) {
        val dynamic = RuntimeLaws.errorTextOf(model, code, build.kDoc, Locale.US).orElseThrow()
        val java = RuntimeLaws.kernelJavaEngine(model)
        val generated = java.errorTextOf(java.instance(build.kDoc), code, Locale.US).orElseThrow()
        val interpreted = DmInterpreter(definition).validateFull(build.iDoc, "en_US").single { it.code == code }.text
        assertEquals(expected, dynamic, "normative Groovy-dynamic")
        assertEquals(dynamic, generated, "generated Java  kernel=$dynamic java=$generated")
        assertEquals(dynamic, interpreted, "interpreter  kernel=$dynamic interp=$interpreted")
    }

    /** The category access yields the TOKEN the stored value maps to; the value suffix yields the LABEL. */
    @Test fun aCategoryAccessRendersTheMappedTokenAndTheValueSuffixTheLabel() =
        agree("LABELLED_TIER", "cat=[STANDARD] val=[Basic cover] name=[Tier]", filled())

    /** …and without declared labels the value suffix falls back to the stored S-value — the discriminator. */
    @Test fun anUnlabelledEnumerationValueRendersItsStoredToken() =
        agree("UNLABELLED_TIER", "cat=[STANDARD] val=[BASIC]", filled())

    /** An empty Enumeration renders empty under BOTH suffixes, while the field's own name still renders. */
    @Test fun anEmptyEnumerationRendersEmptyUnderBothSuffixes() =
        agree("EMPTY_TIER", "cat=[] val=[] name=[Tier]", bare())

    /** The Base Year terminal renders a plain decimal with its offset applied, beside the `$$` escape. */
    @Test fun theBaseYearTerminalRendersItsOffsetDecimal() =
        agree("BASE_YEAR", "[2020] [2023] [2019] [\$]", filled())

    /** The unfilled-Number default is the declared MINIMUM fractional digits — the within-model pair. */
    @Test fun anUnfilledNumberRendersZeroAtItsMinimumFractionalDigits() =
        agree("ZERO_DEFAULTS", "max=[0] min=[0.00]", filled())

    /** `$Field$` is the short name only when unlabelled; a declared label wins. */
    @Test fun theNameFallbackIsTheShortNameOnlyWithoutADeclaredLabel() =
        agree("NAME_FALLBACK", "plain=[Reference] labelled=[Claims handler]", filled())
}
