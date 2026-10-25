package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.md.document.api.IDocument
import com.mgmtp.a12.kernel.md.document.api.IEntityInstance
import com.mgmtp.a12.kernel.md.rt.api.DocumentProcessingConfig
import com.mgmtp.a12.kernel.md.rt.api.ICustomCondition
import com.mgmtp.a12.kernel.md.rt.api.ICustomConditionFactory
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import java.util.Locale
import java.util.Optional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Whether A12 asks its custom-condition factory for the name in a group-target rule before that rule raises
 * (KERNEL-FINDINGS KF615). The rule's condition is the only use of its name, so the factory's log answers it: a
 * lookup of `GroupProbe` would put the lookup ahead of the raise, and its absence puts the raise first. The
 * field-target rule's `FieldProbe` is the positive control that the factory is consulted at all, and lookups and
 * calls share one ordered log, so an eager lookup of every declared name would show as both names before any call.
 *
 * Asked by a12-kernel-lean's SPEC-2026-10-25-02, which withdrew the timing KF615 had from source reading alone.
 * Own domain (an insurance policy).
 */
class CustomConditionGroupTargetLookupLawsTest {

    private val model: String = run {
        val base = DocumentModelDraft.named("policy-group-custom-lookup").locale("en_US")
            .rootGroup("Policy") { p -> p
                .stringField("Holder") { }
                .group("Contact") { c -> c.stringField("Phone") { } } }
            .build().toJson()
        listOf(
            DraftRule("/Policy", "ContactReview", "../Contact", "GroupFilled(Contact) And CustomCondition GroupProbe",
                "CONTACT", "ERROR"),
            DraftRule("/Policy", "HolderReview", "../Holder", "FieldFilled(Holder) And CustomCondition FieldProbe",
                "HOLDER", "ERROR"),
        ).fold(base) { json, rule -> RuleValidator().inject(json, rule) }
    }

    private val document = """{"Policy":{"Holder":"h","Contact":{"Phone":"1"}}}"""

    private fun recordingFactory(log: MutableList<String>) = object : ICustomConditionFactory {
        override fun createCustomConditionV2(customConditionName: String): Optional<ICustomCondition> {
            log += "lookup $customConditionName"
            return Optional.of(object : ICustomCondition {
                override fun check(document: IDocument, relevant: Set<IEntityInstance>?,
                    invalid: Set<IEntityInstance>, error: IEntityInstance): Boolean {
                    log += "call $customConditionName"
                    return true
                }
            })
        }
    }

    @Test
    fun theGroupTargetRuleRaisesBeforeItsNameIsLookedUpOnBothStrategies() {
        val logs = linkedMapOf<String, List<String>>()
        val codes = linkedMapOf<String, List<String>>()
        val dynamic = RuntimeLaws.kernelEngine(model)
        val static = RuntimeLaws.kernelJavaEngine(model)
        for ((strategy, run) in listOf<Pair<String, (DocumentProcessingConfig) -> List<String>>>(
            "groovy" to { config -> dynamic.validationMessages(dynamic.instance(document), config).map { it.errorCode } },
            "java" to { config -> static.validationMessages(static.instance(document), config).map { it.errorCode } },
        )) {
            val log = mutableListOf<String>()
            codes[strategy] = run(DocumentProcessingConfig.builder(Locale.US)
                .customConditionFactory(recordingFactory(log)).build()).sorted()
            logs[strategy] = log
        }
        val expectedLog = listOf("lookup FieldProbe", "call FieldProbe")
        assertEquals(mapOf("groovy" to expectedLog, "java" to expectedLog), logs)
        assertEquals(mapOf("groovy" to listOf("CONTACT", "HOLDER"), "java" to listOf("CONTACT", "HOLDER")), codes)
    }
}
