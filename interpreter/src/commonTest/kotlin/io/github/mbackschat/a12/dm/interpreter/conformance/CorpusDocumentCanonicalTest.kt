package io.github.mbackschat.a12.dm.interpreter.conformance

import io.github.mbackschat.a12.dm.interpreter.eval.readDocumentJson
import io.github.mbackschat.a12.dm.interpreter.eval.toDocumentJson
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every committed corpus document is **already canonical** — the codec's fixed point.
 *
 * This is the standing guard the step-4 migration left behind, and it earns its place for two reasons. First, a
 * case file is read by three runners (JVM, Node, the browser qualification) and by a human; a non-canonical
 * encoding would still *read* correctly, because A12's reader is deliberately permissive (an object at a
 * repeating group, an array at a non-repeating one), so drift here is invisible until someone diffs a
 * regenerated file. Second, it means the corpus exercises the reader against exactly the bytes A12's own writer
 * would produce, which is the whole point of storing the canonical shape instead of an internal placement list.
 *
 * Runs on JVM and Kotlin/JS, like the rest of the multiplatform replay suite.
 */
class CorpusDocumentCanonicalTest {

    @Test
    fun everyCommittedCaseDocumentIsTheCodecsFixedPoint() {
        val files = readCorpusFiles()
        assertTrue(files.isNotEmpty(), "the corpus must be discoverable (CORPUS_DIR / repo-root walk-up)")

        for (file in files) {
            val case = CaseJson.parse(file.text)
            val model = ModelLoader.load(readCorpusModel(case.modelRef))

            val reEncoded = model.readDocumentJson(case.document).toDocumentJson(model)

            assertEquals(
                case.document,
                reEncoded,
                "corpus case ${file.family}/${file.id} carries a non-canonical document — regenerate it through " +
                    "the codec (read then encode); A12's reader is permissive, so this cannot fail at replay time",
            )
        }
    }
}
