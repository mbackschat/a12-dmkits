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
 * What the rule-message `<field>.Vordruckzeile` parameter RENDERS — the runtime half of the form whose admission
 * half is [KF279]. **Both kernel strategies FAULT on it**, so this is a
 * characterized divergence rather than a match, and the characterization is the point: a lock that asserted
 * three-way equality would have to encode a kernel internal error as our contract.
 *
 * <p><b>Measured 2026-09-07, all three engines, one model, one document.</b> Dynamic-Groovy and static-Java
 * agree exactly — the rule's message text comes back as the runtime's own failure string, `An error occurred
 * while evaluation the data check: Index 1 out of bounds for length 1`. The interpreter renders the two tokens
 * LITERALLY, because it has no case for the suffix. Its sibling `<field>.Vordruckname` was measured beside it then;
 * kernel 31.1.1 removed that suffix from the parameter grammar, so a model carrying it no longer builds and it has
 * no runtime half to characterize (KERNEL-FINDINGS KF657). The plain `$Reference$` parameter rides in the same
 * message as a within-message control: it renders on every engine, so the fault is attributable to the
 * form-line positions and not to the rule, the document or the route.
 *
 * <p><b>Why we do not mirror it, and why we do not "fix" it either</b> (INTERPRETER-GAPS IG-form-line): the
 * form line is an H-model concept with no A12 counterpart, the kernel's static checker verifies only that the
 * argument names an existing field, and `NO_VORDRUCKZEILE` — the class its own enum reserves for "the field
 * specifies no form line" — is raised nowhere in the 30.8.1 checkout (a12-kernel-lean's source measurement,
 * their report and not a pin here). So there is no kernel behaviour to match: any value we emitted would be
 * invented. The literal token is the fail-fast reading and stays visible on purpose.
 *
 * <p>Origin: a12-kernel-lean's `SPEC-2026-09-07-12` reports the two forms' static ADMISSION and records their
 * rendering as unmeasured. Their separate note predicts, from source, that an unguarded form-line path throws.
 * This is the runtime case that prediction did not have — a different carrier from the static
 * `$RuleGroup.Vordruckzeile$` internal error they already held — and it throws.
 */
class RuleMessageFormLineParameterDiffTest {

    private companion object {
        const val POLICY = "/Policy"
        const val REFERENCE = "/Policy/Reference"
        const val ADJUSTER = "/Policy/Adjuster"
        const val CODE = "REFERENCE_GIVEN"

        /** The kernel runtime's own failure string, identical on both strategies. */
        const val KERNEL_FAULT =
            "An error occurred while evaluation the data check: Index 1 out of bounds for length 1"
    }

    private val model: String = run {
        val base = DocumentModelDraft.named("policy-form-line").locale("en_US")
            .rootGroup("Policy") { policy ->
                policy.stringField("Reference") { }.stringField("Adjuster") { }
            }
            .build().toJson()
        EditSession.read(base).also { edit ->
            edit.addRule { b ->
                val reference = b.str(REFERENCE)
                b.rule(b.group(POLICY)).name("ReferenceGiven").code(CODE).errorField(b.field(REFERENCE))
                    .`when`(reference.filled()).severity(Severity.ERROR)
                    .message(
                        Dsl.en(
                            "zeile=[\$$ADJUSTER.Vordruckzeile\$] plain=[\$Reference\$]",
                        ),
                    ).build()
            }
            // The admission gate runs here, on the spliced rule. `Adjuster` is never read by the condition, so
            // the PLAIN form of this parameter is refused INVALID_FIELD on this very shape
            // (rulekit.validate.laws.RuleMessageAdmissionGateLawsTest) — the membership bypass is what makes
            // the model admitted, and therefore what makes the runtime fault reachable at all.
            edit.validate()
        }.toJson()
    }

    private val definition = ModelLoader.load(model)

    @Test
    fun bothKernelStrategiesFaultOnTheFormLineParameterWhileTheInterpreterLeavesTheTokenLiteral() {
        val kDoc = RuntimeLaws.doc().group(POLICY, 1).field(REFERENCE, "P-1", 1, 1)
        val iDoc = definition.newDocument().group(POLICY, 1).field(REFERENCE, "P-1", 1, 1)

        val dynamic = RuntimeLaws.errorTextsOf(model, CODE, kDoc, Locale.US)
        val java = RuntimeLaws.kernelJavaEngine(model)
        val generated = java.errorTextsOf(java.instance(kDoc), CODE, Locale.US)
        val interpreted = DmInterpreter(definition).validateFull(iDoc, "en_US")
            .filter { it.code == CODE }.map { it.text }

        assertEquals(listOf(KERNEL_FAULT), dynamic, "normative Groovy-dynamic")
        assertEquals(dynamic, generated,
            "the two kernel strategies agree, so the fault is the kernel's and not one codegen dialect's")
        assertEquals(
            listOf("zeile=[\$$ADJUSTER.Vordruckzeile\$] plain=[Reference]"),
            interpreted,
            "the interpreter's characterized divergence: no case for the suffix, so the token survives",
        )
    }
}
