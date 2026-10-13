package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.md.rt.api.IMessage
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A computation's message renders as its generated rule's, row by row (a12-kernel-lean `SPEC-2026-10-13-12`, its
 * runtime computation row): a computation into a keyed repeatable group, guarded by a common precondition that
 * reads the root and the row. The first row agrees with the computation, the second disagrees and fires, and the
 * third disagrees but fails its precondition, so only the second row reports, every parameter at that row.
 *
 * Own domain (an insurance quote whose lines are keyed by reference). Message text stays raw, because its tokens
 * are the measured input.
 */
class ComputationMessageRowDiffTest {

    private val model: String = run {
        val base = DocumentModelDraft.named("quote-computation-rows").locale("en_US")
            .rootGroup("Quote") { q -> q.stringField("Agent") { }
                .group("Lines") { l -> l.repeatable(5).indexField("Ref")
                    .stringField("Ref") { }.numberField("Amount") { it.fractionDigits(0) }
                    .numberField("Gross") { it.fractionDigits(0) } } }
            .build().toJson()
        EditSession.read(base).also { edit ->
            val root = edit.group("/Quote")
            val lines = edit.group("/Quote/Lines")
            lines.addComputation(lines.num("Gross")) {
                it.name("GrossComp")
                    .commonPrecondition(root.str("Agent").filled().and(lines.num("Amount").lt(5L)))
                    .calc(lines.num("Amount").asNumber().times(2))
                    .message(Dsl.en("row=[\$#RuleGroup\$] g=[\$Gross.value\$] a=[\$Amount.value\$] " +
                        "i=[\$index(Ref).value\$] up=[\$../Agent.value\$] n=[\$Amount\$]"))
                    .build()
            }
            edit.validate()
        }.toJson()
    }

    private val document = """{"Quote":{"Agent":"Ada","Lines":[
        {"Ref":"k0","Amount":2,"Gross":4},{"Ref":"k1","Amount":3,"Gross":7},{"Ref":"k2","Amount":5,"Gross":1}]}}"""

    @Test
    fun bothKernelStrategiesRenderTheFiringRow() {
        assertEquals(EXPECTED, kernelTexts(dynamic = true))
        assertEquals(EXPECTED, kernelTexts(dynamic = false))
    }

    @Test
    fun theInterpreterRendersTheSameRow() {
        val prepared = PreparedModel.loadExpandedJson(model)
        val report = prepared.createInterpreter().validateFull(prepared.documents.readDocument(document))
        assertEquals(emptyList<Any>(), report.unsupported)
        assertEquals(EXPECTED,
            report.findings.map { "${it.code} ${DocumentDifferential.pointer(it.errorPath)} ${it.text}" }.sorted())
    }

    /** Addresses, types, referenced and fill-to-fix pointers agree too. */
    @Test
    fun allThreeEnginesAgreeOnEveryMessageSignature() {
        DocumentDifferential.assertAgree(model, mapOf("the quote document" to document))
    }

    private fun kernelTexts(dynamic: Boolean): List<String> {
        val messages = if (dynamic) RuntimeLaws.kernelEngine(model).let { it.validationMessages(it.instance(document), null, Locale.US) }
            else RuntimeLaws.kernelJavaEngine(model).let { it.validationMessages(it.instance(document), null, Locale.US) }
        return messages.map { m: IMessage -> "${m.errorCode} ${DocumentDifferential.pointer(m.errorFieldPointer)} ${m.errorText}" }.sorted()
    }

    private companion object {
        val EXPECTED = listOf("GrossComp /Quote[1]/Lines[2]/Gross[1] row=[2] g=[7] a=[3] i=[k1] up=[Ada] n=[Amount]")
    }
}
