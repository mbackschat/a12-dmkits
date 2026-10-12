package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.conformance.CaseMeta
import io.github.mbackschat.a12.dm.interpreter.conformance.CaseSource
import io.github.mbackschat.a12.dm.interpreter.conformance.ConformanceCase
import io.github.mbackschat.a12.dm.interpreter.conformance.InterpreterReplay
import io.github.mbackschat.a12.dm.interpreter.conformance.Op
import io.github.mbackschat.a12.dm.interpreter.conformance.Placement
import io.github.mbackschat.a12.dm.interpreter.conformance.PlacementKind
import io.github.mbackschat.a12.dm.interpreter.conformance.RelevantPointer
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * The conformance corpus **replay guard** (CONFORMANCE-CORPUS-SPEC §12) — replays every committed case in the
 * `corpus/cases` tree through all three JVM engines ([CorpusReplay]) and asserts each PASSes. This is the
 * standing guard the corpus exists to be:
 *
 * - the **interpreter** leg is the conformance claim (zero FAIL, zero SKIP = full conformance over the corpus);
 * - the **groovy-dynamic** leg re-runs the real kernel, so a kernel change that alters a committed expectation
 *   fails here (the KERNEL-UPGRADE re-capture signal);
 * - the **java-static** leg asserts the codegen strategy matches the anchor (or its recorded divergence).
 *
 * Runs in the dedicated `corpusReplayTest` Gradle task with the corpus files as declared inputs (no false green).
 */
class CorpusReplayTest {

    @Test
    fun canonicalFullAndPartialCasesUseIndependentKernelJsonIngress() {
        val model = BoundModel.ofJson(
            DocumentModelDraft.named("parcel-corpus-ingress").locale("en_US")
                .rootGroup("Parcel") { it.numberField("Qty") { field -> field.fractionDigits(0) } }
                .build().toJson(),
        )
        val quantity = model.groupAt("/Parcel").orElseThrow().num("Qty")
        val modelJson = model.toJson()
        val placements = listOf(Placement(PlacementKind.FIELD, quantity.field().path().value(), listOf(1, 1), "250.00"))
        for (op in listOf(Op.ValidateFull, Op.ValidatePart(listOf(RelevantPointer(quantity.field().path().value(), listOf(1, 1)))))) {
            val case = ConformanceCase(
                CaseMeta("decimal-ingress", CorpusEngines.KERNEL_VERSION, CaseSource.CURATED, listOf("§7")),
                "models/parcel-corpus-ingress.json", """{"Parcel":{"Qty":250.00}}""", op,
                expected = emptyList(),
            )
            for ((engine, result) in CorpusReplay.replay(case, modelJson)) {
                assertEquals(InterpreterReplay.Verdict.PASS, result.verdict, "$engine / ${op.kind}: ${result.detail}")
            }
            val legacy = listOf("zahlHatDezimalTrenner|VALUE_ERROR|/Parcel[1]/Qty")
            assertEquals(legacy, CorpusEngines.groovyDynamic(modelJson, placements, op), "explicit V1 dynamic ingress")
            assertEquals(legacy, CorpusEngines.javaStatic(modelJson, placements, op), "explicit V1 static ingress")
        }
    }

    @Test
    fun corpusIsPopulated() {
        assertTrue(CorpusFiles.all().size >= MIN_CASES) {
            "expected at least $MIN_CASES committed cases under ${CorpusFiles.casesDir()} " +
                "(found ${CorpusFiles.all().size}); regenerate with -PrunCorpusCapture"
        }
    }

    @TestFactory
    fun everyCaseReplaysThreeWays(): List<DynamicTest> = CorpusFiles.all().map { cf ->
        DynamicTest.dynamicTest("${cf.path.parent.fileName}/${cf.case.meta.id}") {
            if (cf.case.op !is Op.Compute) {
                val relevant = (cf.case.op as? Op.ValidatePart)?.relevant?.mapTo(HashSet()) {
                    io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity(it.path, it.reps)
                }
                val clock = cf.case.clock?.let {
                    io.github.mbackschat.a12.dm.interpreter.model.EvalClock(it.substringBefore('T'), it)
                }
                val dynamic = DocumentDifferential.kernelMessages(cf.modelJson, cf.case.document, true, relevant,
                    CorpusEngines.clockDate(cf.case.clock), sharedEngine = true)
                assertRichMessages(dynamic, DocumentDifferential.kernelMessages(cf.modelJson, cf.case.document, false,
                    relevant, CorpusEngines.clockDate(cf.case.clock), sharedEngine = true), "${cf.case.meta.id}/static")
                assertRichMessages(dynamic, DocumentDifferential.interpreterMessages(cf.modelJson, cf.case.document,
                    relevant, clock), "${cf.case.meta.id}/interpreter")
            }
            val results = CorpusReplay.replay(cf.case, cf.modelJson)
            val bad = results.filterValues { it.verdict != InterpreterReplay.Verdict.PASS }
            assertTrue(bad.isEmpty()) {
                "conformance verdict != PASS for ${cf.path}:\n" +
                    bad.entries.joinToString("\n") { "  ${it.key}: ${it.value.verdict} — ${it.value.detail}" }
            }
        }
    }

    @Test
    fun corpusRichAgreementRejectsWrongTerminalCoordinatesInEveryRole() {
        val model = DocumentModelDraft.named("corpus-pointer").locale("en_US")
            .rootGroup("Parcel") { root -> root.group("Rows") { it.repeatable(2).stringField("Code") {} } }
            .build().toJson()
        val json = """{"Parcel":{"Rows":[{},{},{}]}}"""
        val engine = RuntimeLaws.kernelEngine(model)
        val source = engine.validationMessages(engine.instance(json), null, java.util.Locale.US).single().errorFieldPointer
        val changed = io.github.mbackschat.a12.dm.interpreter.A12PartiallyKnownMultiPointer.of(source.fullName(),
            source.repetitionIndexes().dropLast(1) + (source.repetitionIndexes().last() + 1))
        val key = DocumentDifferential.pointer(source)
        val wrong = DocumentDifferential.pointer(changed)
        val checks = listOf("error", "reference", "fill").map { role -> org.junit.jupiter.api.function.Executable {
            fun signature(pointer: String) = MessageSignature("CORPUS_POINTER", "VALUE_ERROR",
                if (role == "error") pointer else key,
                if (role == "reference") listOf(pointer) else emptyList(),
                if (role == "fill") listOf(pointer) else emptyList())
            val expected = listOf(signature(key))
            val incorrect = listOf(signature(wrong))
            assertEquals(expected.single().brief, incorrect.single().brief)
            assertRichMessages(expected, expected, "$role positive control")
            org.junit.jupiter.api.Assertions.assertThrows(AssertionError::class.java) {
                assertRichMessages(expected, incorrect, role)
            }
        } }
        org.junit.jupiter.api.Assertions.assertAll(checks)
    }

    private fun assertRichMessages(expected: List<MessageSignature>, actual: List<MessageSignature>, label: String) {
        assertEquals(expected, actual, label)
    }

    private companion object {
        const val MIN_CASES = 6
    }
}
