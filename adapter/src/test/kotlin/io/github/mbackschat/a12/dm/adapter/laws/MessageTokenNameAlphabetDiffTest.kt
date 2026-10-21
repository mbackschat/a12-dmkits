package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.md.rt.api.IMessage
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A message parameter names its group with the error-text lexer's own letters, which are ASCII plus `ÄÖÜäöüß`
 * (`BaseErrorTextParameterLexer.g4`'s `STR`, kernel-core-service 30.8.1). A group name A12 admits may carry them
 * where a field name may not (KERNEL-FINDINGS KF315), so a value parameter whose path runs through such a group, and
 * the group's own repetition parameter, are rendered, and the interpreter has to read the same alphabet
 * (KERNEL-FINDINGS KF585).
 *
 * A key path selecting a row (`For`) is the third carrier: it may run through such a group too, for a value, a
 * category and a row number alike. The other two name positions cannot carry a German letter, which A12 settles at
 * admission: `index(…)` takes the index field's bare short name (a path there is `INVALID_INDEX_FIELD`), and a field
 * name refuses the letters, as a category name does. A `:`, which the lexer's name set also holds, is refused in
 * every name a parameter can reach (`INVALID_ENTITY`), so it is never read at evaluation.
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

    @Test
    fun aKeyPathThroughAnUmlautGroupSelectsItsRow() {
        val base = DocumentModelDraft.named("policy-claim-selection").locale("en_US")
            .rootGroup("Police") { p -> p
                .group("Prüfung") { g -> g.stringField("Pick") { } }
                .group("Schäden") { s -> s.repeatable(3).indexField("Ref").stringField("Ref") { }
                    .enumField("Status") { e -> e.value("OPEN").value("SHUT").category("Band", "HIGH", "LOW") } } }
            .build().toJson()
        val session = EditSession.read(base)
        val pick = session.group("/Police/Prüfung").str("Pick")
        val status = session.group("/Police/Schäden").enumOf("Status")
        session.group("/Police").addRule { it.name("Picked").code("PICKED").errorField(pick)
            .`when`(pick.filled().and(status.semanticIndex(pick.field()).filled()))
            .message(Dsl.en("v=[\$/Police/Schäden/Status.value For /Police/Prüfung/Pick\$] " +
                "c=[\$/Police/Schäden/Status->Band For /Police/Prüfung/Pick\$] n=[\$#/Police/Schäden For /Police/Prüfung/Pick\$]"))
            .build() }
        val selecting = session.toJson()
        val picked = """{"Police":{"Prüfung":{"Pick":"r2"},"Schäden":[{"Ref":"r1","Status":"OPEN"},{"Ref":"r2","Status":"SHUT"}]}}"""
        DocumentDifferential.assertAgree(selecting, mapOf("the second claim picked" to picked))
        assertEquals(listOf("v=[SHUT] c=[LOW] n=[2]"),
            RuntimeLaws.kernelEngine(selecting).let { it.validationMessages(it.instance(picked), null, Locale.US) }.map { it.errorText })
    }

    /**
     * The name positions that cannot carry a German letter are closed at admission (KERNEL-FINDINGS KF585). The
     * parameter is the subject, so each rule is injected with its text as written rather than built and checked.
     */
    @Test
    fun theOtherNamePositionsAreClosedAtAdmission() {
        val claims = DocumentModelDraft.named("policy-claim-positions").locale("en_US")
            .rootGroup("Police") { p -> p
                .group("Schäden") { s -> s.repeatable(3).indexField("Ref").stringField("Ref") { }.stringField("Size") { } } }
            .build().toJson()
        fun admission(parameter: String) = MessageParameterProbes.kernel(RuleValidator().inject(claims,
            DraftRule("/Police/Schäden", "Probe", "../Size", "FieldFilled(Size)", "PROBE", "ERROR", listOf(Dsl.en("p=[$parameter]")))))
        assertEquals(
            listOf(emptyList(), listOf("INVALID_INDEX_FIELD"), listOf("INVALID_INDEX_FIELD"), listOf("INVALID_ENTITY"), listOf("INVALID_ENTITY")),
            listOf("\$index(Ref).value\$", "\$index(../Schäden/Ref).value\$", "\$index(/Police/Schäden/Ref).value\$",
                "\$Si:ze.value\$", "\$../Schäden/Si:ze.value\$").map(::admission),
        )
        for (category in listOf("Größe", "Kl:asse")) {
            val categorized = DocumentModelDraft.named("policy-claim-category").locale("en_US")
                .rootGroup("Police") { p -> p
                    .enumField("Status") { e -> e.value("OPEN").value("SHUT").category(category, "HIGH", "LOW") } }
                .build().toJson()
            val refusal = runCatching { MessageParameterProbes.kernel(categorized) }.exceptionOrNull()?.message.orEmpty()
            assertTrue(refusal.contains("A category name starts with a letter and may contain only letters, digits and the underscore"),
                "$category: $refusal")
        }
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
