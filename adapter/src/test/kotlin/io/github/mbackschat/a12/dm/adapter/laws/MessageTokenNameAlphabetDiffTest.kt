package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.md.rt.api.IMessage
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A message parameter names its group with the error-text lexer's own letters, which are ASCII plus `ÄÖÜäöüß`
 * (`BaseErrorTextParameterLexer.g4`'s `STR`, kernel-core-service 30.8.1). A group name A12 admits may carry them
 * where a field name may not (KERNEL-FINDINGS KF315), so a value parameter whose path runs through such a group, and
 * the group's own repetition parameter, are rendered, and the interpreter has to read the same alphabet
 * (KERNEL-FINDINGS KF585).
 *
 * Own domain (a policy's claims, the group named in German as the model's own vocabulary would be).
 */
class MessageTokenNameAlphabetDiffTest {

    private val model: String = run {
        val base = DocumentModelDraft.named("policy-claim-names").locale("en_US")
            .rootGroup("Police") { p -> p
                .group("Schäden") { s -> s.repeatable(3).stringField("Size") { } } }
            .build().toJson()
        val session = EditSession.read(base)
        val police = session.group("/Police")
        val schaeden = session.group("/Police/Schäden")
        val size = schaeden.str("Size")
        // The parent-relative spelling beside the generated ones is a subject here: a model may carry either.
        schaeden.addRule { it.name("SizeGiven").code("SIZE_GIVEN").errorField(size)
            .`when`(size.filled())
            .message(Dsl.en("v=[" + Dsl.fieldValue(size.field()) + "] l=[" + Dsl.fieldLabel(size.field())
                + "] n=[" + Dsl.repetitionIndex(police.group("Schäden")) + "] r=[\$../Schäden/Size.value\$]")).build() }
        session.toJson()
    }

    private val document = """{"Police":{"Schäden":[{"Size":"large"},{"Size":"small"}]}}"""

    @Test
    fun theModelIsKernelConsistent() {
        assertEquals(emptyList<String>(), MessageParameterProbes.kernel(model))
    }

    @Test
    fun bothKernelStrategiesRenderAnUmlautName() {
        assertEquals(EXPECTED, kernelTexts(dynamic = true))
        assertEquals(EXPECTED, kernelTexts(dynamic = false))
    }

    @Test
    fun allThreeEnginesAgreeOnEveryMessageSignature() {
        DocumentDifferential.assertAgree(model, mapOf("the premium document" to document))
    }

    private fun kernelTexts(dynamic: Boolean): List<String> {
        val messages = if (dynamic) RuntimeLaws.kernelEngine(model).let { it.validationMessages(it.instance(document), null, Locale.US) }
            else RuntimeLaws.kernelJavaEngine(model).let { it.validationMessages(it.instance(document), null, Locale.US) }
        return messages
            .map { m: IMessage -> "${m.errorCode} ${DocumentDifferential.pointer(m.errorFieldPointer)} ${m.errorText}" }.sorted()
    }

    private companion object {
        val EXPECTED = listOf(
            "SIZE_GIVEN /Police[1]/Schäden[1]/Size[1] v=[large] l=[Size] n=[1] r=[large]",
            "SIZE_GIVEN /Police[1]/Schäden[2]/Size[1] v=[small] l=[Size] n=[2] r=[small]",
        )
    }
}
