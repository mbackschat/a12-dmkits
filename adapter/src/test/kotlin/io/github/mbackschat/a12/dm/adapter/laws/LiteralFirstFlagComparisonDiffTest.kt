package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * `True`/`False` written before a Boolean or confirm operand, plain or key-selected, fires exactly as on both kernel
 * strategies. The typed surface only writes the field first, so this spelling reaches an engine only as stored text;
 * the oracle is the tri-engine agreement on a document grid that sets each flag true, false and absent.
 *
 * Own domain (an insurance policy whose claims are keyed by code).
 */
class LiteralFirstFlagComparisonDiffTest {

    private val model = RULES.entries.fold(
        DocumentModelDraft.named("policy-literal-first").locale("en_US")
            .rootGroup("Policy", Consumer { p ->
                p.stringField("Holder", Consumer { })
                    .booleanField("Renewed", Consumer { })
                    .group("Claims", Consumer { c ->
                        c.repeatable(5).indexField("Code")
                            .stringField("Code", Consumer { })
                            .booleanField("Approved", Consumer { })
                            .confirmField("Signed", Consumer { })
                    })
            }).build().toJson(),
    ) { json, (code, condition) ->
        // the error field must be named in the condition (KERNEL-FINDINGS KF324); Holder is filled in every document
        RuntimeLaws.withRule(json, "/Policy", "R$code", "../Holder", code, "FieldFilled($HOLDER) And $condition")
    }

    @Test
    fun aLiteralFirstFlagComparisonFiresAsOnBothKernelStrategies() {
        val definition = ModelLoader.load(model)
        val java = RuntimeLaws.kernelJavaEngine(model)
        val fired = DOCUMENTS.mapValues { (_, flags) ->
            val kernelDoc = RuntimeLaws.doc().group("/Policy", 1).group(CLAIMS, 1, 1)
                .field(HOLDER, "H-1", 1, 1).field(CODE, "C1", 1, 1, 1)
            val interpreterDoc = definition.newDocument().group("/Policy", 1).group(CLAIMS, 1, 1)
                .field(HOLDER, "H-1", 1, 1).field(CODE, "C1", 1, 1, 1)
            flags.forEach { (path, value) ->
                val coordinates = if (path == RENEWED) intArrayOf(1, 1) else intArrayOf(1, 1, 1)
                kernelDoc.field(path, value, *coordinates)
                interpreterDoc.field(path, value, *coordinates)
            }
            val dynamic = RuntimeLaws.messages(model, kernelDoc).map { it.code() }.filter(RULES::containsKey).sorted()
            assertEquals(dynamic, java.firedCodesSorted(java.instance(kernelDoc)).filter(RULES::containsKey),
                "generated Java")
            assertEquals(dynamic, DmInterpreter(definition).validateFull(interpreterDoc).map { it.code }
                .filter(RULES::containsKey).sorted(), "interpreter")
            dynamic
        }
        assertEquals(EXPECTED, fired)
    }

    private companion object {
        const val HOLDER = "/Policy/Holder"
        const val RENEWED = "/Policy/Renewed"
        const val CLAIMS = "/Policy/Claims"
        const val CODE = "/Policy/Claims/Code"
        const val APPROVED = "/Policy/Claims/Approved"
        const val SIGNED = "/Policy/Claims/Signed"

        val RULES = linkedMapOf(
            "PLAIN_FALSE" to "False == [$RENEWED]",
            "SELECTED_TRUE" to "True == [$APPROVED For \"C1\"]",
            "SELECTED_NOT_FALSE" to "False != [$APPROVED For \"C1\"]",
            "SIGNED" to "True == [$SIGNED For \"C1\"]",
            "NOT_SIGNED" to "True != [$SIGNED For \"C1\"]",
        )

        val DOCUMENTS = linkedMapOf(
            "set" to mapOf(RENEWED to "true", APPROVED to "true", SIGNED to "true"),
            "cleared" to mapOf(RENEWED to "false", APPROVED to "false"),
            "absent" to emptyMap(),
        )

        val EXPECTED = mapOf(
            "set" to listOf("SELECTED_NOT_FALSE", "SELECTED_TRUE", "SIGNED"),
            "cleared" to listOf("NOT_SIGNED", "PLAIN_FALSE"),
            "absent" to listOf("NOT_SIGNED"),
        )
    }
}
