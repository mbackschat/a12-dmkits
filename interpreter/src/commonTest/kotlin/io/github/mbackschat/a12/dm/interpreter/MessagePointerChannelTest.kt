package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.workspace.WorkspacePreparationFixtureJson
import io.github.mbackschat.a12.dm.interpreter.model.Message as EngineMessage
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import io.github.mbackschat.a12.dm.interpreter.footprint.FootprintScenarios
import io.github.mbackschat.a12.dm.interpreter.footprint.FootprintScenario
import kotlin.test.assertTrue

/**
 * **The message channel carries A12's message pointer, not a string.**
 *
 * A12 types every address on `IMessage` as `PartiallyKnownDocumentMultiPointer`, so this library does too:
 * [ValidationFinding.errorPath], `referenced` and `fillToFix` are that type, and the engine *retains* it rather
 * than deriving it later from text.
 *
 * **The direction is forced, not chosen.** The interpreter's house string is lossy — its renderer writes an
 * index on every segment except the last, so `("/Plan/Demand", [1, -5])` renders `/Plan[1]/Demand` and
 * `("/Order/Items", [1, 0])` renders `/Order[1]/Items`. Both sentinels this type exists for are among what the
 * string drops, so the typed value must be the stored one and the string a projection of it; the reverse cannot
 * be reconstructed.
 */
class MessagePointerChannelTest {

    @Test
    fun identicalOmissionChannelsShareTheirPointerProjection() {
        val fixture = FootprintScenarios.fixture(FootprintScenario.EQUALITY_CORRELATION, 4)
        val result = DmInterpreter(fixture.model).validate(fixture.document)
        assertEquals(4, result.messages.size)
        for (message in result.messages) {
            assertEquals(MsgType.OMISSION, message.type)
            assertTrue(message.referencedPointers.isNotEmpty())
            assertSame(message.referencedPointers, message.fillToFixPointers)
        }
    }

    private fun preparedPermit(): PreparedModel {
        val caseId = "self-contained"
        val entryModelId = WorkspacePreparationFixtureJson.entryModelId(caseId)
        val source = WorkspaceModelSource.builder()
            .json(WorkspacePreparationFixtureJson.sources(caseId).getValue(entryModelId))
            .build()
        return ModelWorkspace.fromSources(listOf(source)).prepare(entryModelId)
    }

    private fun aFinding(): ValidationFinding {
        val prepared = preparedPermit()
        val snapshot = prepared.documents.readDocument(
            """{"Permit":{"ApplicationNo":"P-42","RequestedArea":120}}""",
        )
        val findings = prepared.createInterpreter().validateFull(snapshot).findings
        assertTrue(findings.isNotEmpty(), "the fixture must produce a finding for this test to mean anything")
        return findings.first()
    }

    @Test
    fun aFindingsErrorPathIsTheMessagePointerType() {
        val pointer: A12PartiallyKnownMultiPointer = aFinding().errorPath
        assertTrue(pointer.fullName.startsWith("/"), "fullName is the owner's join: '${pointer.fullName}'")
        assertEquals(
            A12PointerSyntax.splitNames(pointer.fullName).size,
            pointer.repetitionIndexes.size,
            "one index per name, as the owner's value carries",
        )
    }

    @Test
    fun referencedAndFillToFixAreListsOfTheSameType() {
        val finding = aFinding()
        val referenced: List<A12PartiallyKnownMultiPointer> = finding.referenced
        val fillToFix: List<A12PartiallyKnownMultiPointer> = finding.fillToFix
        (referenced + fillToFix).forEach { pointer ->
            assertEquals(
                A12PointerSyntax.splitNames(pointer.fullName).size,
                pointer.repetitionIndexes.size,
                "every published message pointer is arity-consistent: $pointer",
            )
        }
    }

    /**
     * The discriminating case for the *direction*: a terminal index the house renderer omits must still be on
     * the typed value. Any implementation that projects the channel out of the rendered string fails here,
     * because the information is not in that string to recover.
     */
    @Test
    fun terminalIndexesSurviveTheActualEngineToPublicProjectionOnEveryChannel() {
        val error = A12PartiallyKnownMultiPointer.of("/Error", listOf(3))
        val referenced = A12PartiallyKnownMultiPointer.of("/Referenced", listOf(0))
        val fillToFix = A12PartiallyKnownMultiPointer.of("/Fill", listOf(-5))
        val projected = validationFinding(
            EngineMessage(
                code = "POINTER-CHANNEL",
                rulePath = "/Rule",
                errorPointer = error,
                severity = Severity.ERROR,
                type = MsgType.OMISSION,
                referencedPointers = listOf(referenced),
                fillToFixPointers = listOf(fillToFix),
            ),
        )

        assertEquals(error, projected.errorPath)
        assertEquals(listOf(referenced), projected.referenced)
        assertEquals(listOf(fillToFix), projected.fillToFix)
    }
}
