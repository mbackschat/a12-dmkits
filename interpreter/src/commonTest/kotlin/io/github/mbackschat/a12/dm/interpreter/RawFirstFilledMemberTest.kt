package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for IF438: a filled raw String (`noValueValidation`) selected by
 * `FirstFilledValue` reads as the token `X` whatever its text, in a stored computation and in a condition alike,
 * and an empty one is skipped. The Kernel answers are `adapter.laws.RawFirstFilledMemberDiffTest`'s.
 */
class RawFirstFilledMemberTest {

    private val model = ModelDef(
        fields = listOf(
            FieldDef("/Claim/Attachment", Kind.STRING, noValueValidation = true, lineBreaksPermitted = true),
            FieldDef("/Claim/Pages/Content", Kind.STRING, noValueValidation = true, lineBreaksPermitted = true),
            FieldDef("/Claim/Summary", Kind.STRING),
            FieldDef("/Claim/Digest", Kind.STRING),
            FieldDef("/Claim/FirstPage", Kind.STRING),
        ),
        rules = listOf("TEXT" to "scan", "TOKEN" to "X").map { (code, compared) ->
            RuleDef("/Claim/$code", "/Claim",
                "FieldFilled(Summary) And FirstFilledValue(Attachment, Summary) == \"$compared\"", code, "../Summary")
        },
        computations = listOf(
            ComputationDef("/Claim/Digest", "/Claim", "FirstFilledValue(Attachment, Summary)"),
            ComputationDef("/Claim/FirstPage", "/Claim", "FirstFilledValue(Pages*/Content)"),
        ),
        declaredReps = mapOf("/Claim/Pages" to 3),
    )

    private fun claim(attachment: String, page: String): Document = model.newDocument().group("/Claim", 1).also { d ->
        d.field("/Claim/Attachment", attachment, 1, 1).field("/Claim/Summary", "brief", 1, 1)
        d.group("/Claim/Pages", 1, 1).field("/Claim/Pages/Content", page, 1, 1, 1)
    }

    private fun stored(d: Document) = DmInterpreter(model).compute(d).sortedBy { it.path }
        .map { it.path.substringAfterLast('/') + "=" + if (it.outcome == ComputeOutcome.VALUE) it.value else it.outcome.name }

    private fun fired(d: Document) = DmInterpreter(model).validateFull(d).map { it.code }

    @Test fun aFilledRawMemberIsStoredAsTheToken() =
        assertEquals(listOf("Digest=X", "FirstPage=X"), stored(claim("scan", "page one")))

    @Test fun aRawMemberOfOneSpaceIsFilledAndStillTheToken() =
        assertEquals(listOf("Digest=X", "FirstPage=X"), stored(claim(" ", " ")))

    @Test fun anEmptyRawMemberIsSkipped() =
        assertEquals(listOf("Digest=brief", "FirstPage=CLEARED"), stored(claim("", "")))

    @Test fun aConditionComparesTheTokenNotTheText() =
        assertEquals(listOf("TOKEN"), fired(claim("scan", "page one")))
}
