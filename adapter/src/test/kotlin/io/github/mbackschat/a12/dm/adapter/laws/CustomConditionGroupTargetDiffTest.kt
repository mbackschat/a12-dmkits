package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.md.document.api.IDocument
import com.mgmtp.a12.kernel.md.document.api.IEntityInstance
import com.mgmtp.a12.kernel.md.rt.api.ICustomCondition
import com.mgmtp.a12.kernel.md.rt.api.IMessage
import io.github.mbackschat.a12.dm.interpreter.CustomCondition
import io.github.mbackschat.a12.dm.interpreter.CustomConditionRegistry
import io.github.mbackschat.a12.dm.interpreter.InterpreterConfiguration
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.interpreter.ValidationFinding
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A `CustomCondition` leaf in a rule whose error entity is a GROUP raises inside A12 when it is reached, before the
 * condition is called, so the rule reports its own code once as VALUE at its first error address with nothing
 * referenced (KERNEL-FINDINGS KF615, the rule-raise channel of [KF513]).
 *
 * The raise is A12's: `applikationsBedingung` converts the rule's error field first, and for a group the codegen
 * hands it no field name (`CodeGenRuleDefinitionBuilder`, codegen bean; observed through the probe's
 * `evaluationRaises`, `KernelProbeRuleRaiseTest`). The rows separate it from the rivals a reader would reach for:
 * a fixed group and a rule declared beneath the root raise as the nested repeatable group does, so neither
 * repetition nor scope decides it; a field of the same group is called on every row; a guard that is false on
 * every row keeps the leaf unreached and the rule silent; and a guard false on the first row only still reports
 * at the first address. Every row compares code, type, error address and referenced fields on all three engines.
 */
class CustomConditionGroupTargetDiffTest {

    @Test
    fun aGroupTargetRaisesWhereItsCustomLeafIsReachedOnEveryEngine() {
        DOCUMENTS.forEach { (label, document) ->
            val calls = mutableListOf<String>()
            val callback = object : ICustomCondition {
                override fun check(document: IDocument, relevant: Set<IEntityInstance>?,
                    invalid: Set<IEntityInstance>, error: IEntityInstance): Boolean {
                    calls += error.path
                    return true
                }
            }
            val conditions = mapOf("Approved" to callback)
            val dynamic = RuntimeLaws.kernelEngine(model)
            val groovy = dynamic.validationMessages(dynamic.instance(document), null, Locale.US, conditions, null)
                .map(::signature).sorted()
            val groovyCalls = calls.toList().also { calls.clear() }
            val static = RuntimeLaws.kernelJavaEngine(model)
            val java = static.validationMessages(static.instance(document), null, Locale.US, conditions, null)
                .map(::signature).sorted()
            assertEquals(groovy, java, "$label: groovy vs java-static")
            assertEquals(groovyCalls.toSet(), calls.toSet(), "$label: the strategies call alike")
            calls.clear()

            val registry = CustomConditionRegistry.builder().register("Approved", CustomCondition { invocation ->
                calls += invocation.errorPointer.path
                true
            }).build()
            val report = prepared.createInterpreter(InterpreterConfiguration.builder().customConditions(registry).build())
                .validateFull(prepared.documents.readDocument(document))
            assertEquals(emptyList<Any>(), report.unsupported, label)
            assertEquals(groovy, report.findings.map(::signature).sorted(), "$label: kernel vs interpreter")
            assertEquals(groovyCalls.toSet(), calls.toSet(), "$label: the interpreter calls only where A12 does")
            assertEquals(EXPECTED.getValue(label), groovy, "$label: the pinned kernel answer")
        }
    }

    private fun signature(message: IMessage): String = "${message.errorCode}|${message.messageType}|" +
        "${message.errorFieldPointer.fullName()}${message.errorFieldPointer.repetitionIndexes()}|" +
        message.referencedFieldsPointers.map { "${it.fullName()}${it.repetitionIndexes()}" }.sorted()

    private fun signature(finding: ValidationFinding): String = "${finding.code}|${finding.type.name}_ERROR|" +
        "${finding.errorPath.fullName}${finding.errorPath.repetitionIndexes}|" +
        finding.referenced.map { "${it.fullName}${it.repetitionIndexes}" }.sorted()

    private companion object {

        val model: String = run {
            val base = DocumentModelDraft.named("policy-group-custom").locale("en_US")
                .rootGroup("Policy") { p -> p
                    .stringField("Holder") { }
                    .group("Contact") { c -> c.stringField("Phone") { } }
                    .group("Claims") { c -> c.repeatable(9).indexField("ClaimNo")
                        .stringField("ClaimNo") { }
                        .stringField("Status") { }
                        .group("Items") { i -> i.repeatable(5).stringField("ItemNo") { } } } }
                .build().toJson()
            val validator = RuleValidator()
            listOf(
                DraftRule("/Policy", "ContactReview", "../Contact", "GroupFilled(Contact) And CustomCondition Approved", "CONTACT", "ERROR"),
                DraftRule("/Policy", "ItemsReview", "../Claims/Items", "GroupFilled(Claims/Items) And CustomCondition Approved", "ITEMS", "ERROR"),
                DraftRule("/Policy/Claims", "ClaimItemsReview", "../Items",
                    "GroupFilled(Items) And FieldFilled(Status) And CustomCondition Approved", "CLAIM_ITEMS", "ERROR"),
                DraftRule("/Policy/Claims", "ItemNumberReview", "../Items/ItemNo",
                    "FieldFilled(Items/ItemNo) And CustomCondition Approved", "ITEM_NO", "ERROR"),
                DraftRule("/Policy", "HolderReview", "../Holder", "FieldFilled(Holder) And CustomCondition Approved", "HOLDER", "ERROR"),
            ).fold(base) { json, rule -> validator.inject(json, rule) }
        }

        val prepared: PreparedModel = PreparedModel.loadExpandedJson(model)

        private const val CLAIMS = """{"ClaimNo":"c1","Status":"open","Items":[{"ItemNo":"i1"},{"ItemNo":"i2"}]},
            {"ClaimNo":"c2","Items":[{"ItemNo":"i3"}]}"""

        val DOCUMENTS = linkedMapOf(
            "everything filled" to """{"Policy":{"Holder":"h","Contact":{"Phone":"1"},"Claims":[$CLAIMS]}}""",
            // The second claim alone has a status, so the claim-scoped group rule reaches its leaf off the first row.
            "status on the second claim only" to """{"Policy":{"Claims":[{"ClaimNo":"c1","Items":[{"ItemNo":"i1"}]},
                {"ClaimNo":"c2","Status":"open","Items":[{"ItemNo":"i2"}]}]}}""",
            "no status, no contact" to """{"Policy":{"Claims":[{"ClaimNo":"c1","Items":[{"ItemNo":"i1"}]}]}}""",
        )

        val EXPECTED: Map<String, List<String>> = mapOf(
            "everything filled" to listOf(
                "CLAIM_ITEMS|VALUE_ERROR|/Policy/Claims/Items[1, 1, 1]|[]",
                "CONTACT|VALUE_ERROR|/Policy/Contact[1, 1]|[]",
                "HOLDER|VALUE_ERROR|/Policy/Holder[1, 1]|[/Policy/Holder[1, 1]]",
                "ITEMS|VALUE_ERROR|/Policy/Claims/Items[1, 1, 1]|[]",
                "ITEM_NO|VALUE_ERROR|/Policy/Claims/Items/ItemNo[1, 1, 1, 1]|[/Policy/Claims/Items/ItemNo[1, 1, 1, 1]]",
                "ITEM_NO|VALUE_ERROR|/Policy/Claims/Items/ItemNo[1, 1, 2, 1]|[/Policy/Claims/Items/ItemNo[1, 1, 2, 1]]",
                "ITEM_NO|VALUE_ERROR|/Policy/Claims/Items/ItemNo[1, 2, 1, 1]|[/Policy/Claims/Items/ItemNo[1, 2, 1, 1]]",
            ),
            "status on the second claim only" to listOf(
                "CLAIM_ITEMS|VALUE_ERROR|/Policy/Claims/Items[1, 1, 1]|[]",
                "ITEMS|VALUE_ERROR|/Policy/Claims/Items[1, 1, 1]|[]",
                "ITEM_NO|VALUE_ERROR|/Policy/Claims/Items/ItemNo[1, 1, 1, 1]|[/Policy/Claims/Items/ItemNo[1, 1, 1, 1]]",
                "ITEM_NO|VALUE_ERROR|/Policy/Claims/Items/ItemNo[1, 2, 1, 1]|[/Policy/Claims/Items/ItemNo[1, 2, 1, 1]]",
            ),
            "no status, no contact" to listOf(
                "ITEMS|VALUE_ERROR|/Policy/Claims/Items[1, 1, 1]|[]",
                "ITEM_NO|VALUE_ERROR|/Policy/Claims/Items/ItemNo[1, 1, 1, 1]|[/Policy/Claims/Items/ItemNo[1, 1, 1, 1]]",
            ),
        )
    }
}
