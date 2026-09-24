package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check — what `NumberOfFilledGroups` counts over a fixed group below a starred repeatable
 * level, `Batches*` + `/Seal`, which A12 admits alone and beside another group (KERNEL-FINDINGS KF462).
 *
 * The count is read as the one `== N` that fires, N from 0 to 4, so each document yields a number under dynamic
 * Groovy, under static Java and under the interpreter. The documents separate the readings a reader could hold:
 * a Seal counted when its batch exists, when the Seal group is instantiated, or when the Seal holds a value.
 */
class AncestorStarredGroupCountDiffTest {

    private companion object {
        const val ITEM = "/Item"
        const val TITLE = "/Item/Title"
        const val GRADING = "/Item/Grading"
        const val GRADE = "/Item/Grading/Grade"
        const val BATCHES = "/Item/Batches"
        const val LOT = "/Item/Batches/Lot"
        const val SEAL = "/Item/Batches/Seal"
        const val CODE = "/Item/Batches/Seal/Code"
        const val MSG = "ASGC"
    }

    private val base = DocumentModelDraft.named("warehouse-seal-runtime").locale("en_US")
        .rootGroup("Item") { i ->
            i.field("Title", FieldKind.STRING)
                .group("Grading") { it.field("Grade", FieldKind.NUMBER) }
                .group("Batches") { b ->
                    b.repeatable(4).field("Lot", FieldKind.STRING)
                        .group("Seal") { it.field("Code", FieldKind.STRING) }
                }
        }.build().toJson()

    private class Both(val kDoc: RuntimeLaws.Doc, val iDoc: Document) {
        fun group(path: String, vararg reps: Int): Both {
            kDoc.group(path, *reps); iDoc.group(path, *reps); return this
        }

        fun field(path: String, v: String, vararg reps: Int): Both {
            kDoc.field(path, v, *reps); iDoc.field(path, v, *reps); return this
        }
    }

    /**
     * Which `count == N` fires, per engine, for one document. The interpreter's entry is `unsupported` when it
     * declines the rule rather than evaluating it.
     */
    private fun counts(operands: String, build: (Both) -> Unit): Map<String, List<Any>> {
        val byEngine = linkedMapOf<String, MutableList<Any>>("groovy" to mutableListOf(), "java" to mutableListOf(),
            "interpreter" to mutableListOf())
        for (n in 0..4) {
            val condition = "FieldFilled($TITLE) And NumberOfFilledGroups($operands) == $n"
            val kModel = RuntimeLaws.withRule(base, ITEM, "R", "../Title", MSG, condition)
            val def = ModelLoader.load(kModel)
            val dynamic = Both(RuntimeLaws.doc(), def.newDocument()).also(build)
            val kernel = RuntimeLaws.messages(kModel, dynamic.kDoc)
            assertTrue(kernel.none { it.code().startsWith("MVK_") }) { "the kernel refused $condition: $kernel" }
            if (kernel.any { it.code() == MSG }) byEngine.getValue("groovy").add(n)
            val staticDoc = Both(RuntimeLaws.doc(), def.newDocument()).also(build).kDoc
            if (RuntimeLaws.kernelJavaEngine(kModel).messagesModern(staticDoc, null, java.util.Locale.US)
                    .any { it.code() == MSG }) byEngine.getValue("java").add(n)
            val outcome = DmInterpreter(def).validate(dynamic.iDoc)
            when {
                outcome.unsupported.isNotEmpty() -> byEngine.getValue("interpreter").add("unsupported")
                outcome.messages.any { it.code == MSG } -> byEngine.getValue("interpreter").add(n)
            }
        }
        return byEngine
    }

    private fun titled(b: Both) = b.group(ITEM, 1).field(TITLE, "crate", 1, 1)

    /** Document name → how it is built. */
    private val documents: Map<String, (Both) -> Unit> = linkedMapOf(
        "no batch" to { b -> titled(b) },
        "two batches, both sealed" to { b ->
            titled(b).group(BATCHES, 1, 1).field(CODE, "S1", 1, 1, 1, 1)
                .group(BATCHES, 1, 2).field(CODE, "S2", 1, 2, 1, 1)
        },
        "two batches, only the first sealed" to { b ->
            titled(b).group(BATCHES, 1, 1).field(CODE, "S1", 1, 1, 1, 1)
                .group(BATCHES, 1, 2).field(LOT, "L2", 1, 2, 1)
        },
        "two batches, each Seal instantiated and empty" to { b ->
            titled(b).group(BATCHES, 1, 1).group(SEAL, 1, 1, 1).group(BATCHES, 1, 2).group(SEAL, 1, 2, 1)
        },
        "three batches, the first and third sealed" to { b ->
            titled(b).group(BATCHES, 1, 1).field(CODE, "S1", 1, 1, 1, 1)
                .group(BATCHES, 1, 2)
                .group(BATCHES, 1, 3).field(CODE, "S3", 1, 3, 1, 1)
        },
        "one sealed batch, Grading graded" to { b ->
            titled(b).group(GRADING, 1, 1).field(GRADE, "4", 1, 1, 1)
                .group(BATCHES, 1, 1).field(CODE, "S1", 1, 1, 1, 1)
        },
    )

    private fun measure(operands: String): Map<String, Map<String, List<Any>>> =
        documents.mapValues { (_, build) -> counts(operands, build) }

    /**
     * A Seal counts when it holds a value: an instantiated but empty Seal counts nothing, where `Items*` over a
     * repeatable group counts every instantiated row ([NumberOfFilledGroupsStarDiffTest]).
     */
    @Test
    fun theSoleScopeCountsFilledSealsUnderEveryEngine() {
        val expected = mapOf("no batch" to 0, "two batches, both sealed" to 2, "two batches, only the first sealed" to 1,
            "two batches, each Seal instantiated and empty" to 0, "three batches, the first and third sealed" to 2,
            "one sealed batch, Grading graded" to 1)
        val measured = measure("/Item/Batches*/Seal")
        assertEquals(expected.mapValues { listOf(it.value) }, measured.mapValues { it.value.getValue("groovy") })
        measured.forEach { (doc, engines) ->
            assertEquals(engines.getValue("groovy"), engines.getValue("java"), "$doc — groovy vs java-static")
            assertEquals(engines.getValue("groovy"), engines.getValue("interpreter"), "$doc — kernel vs INTERPRETER")
        }
    }

    /**
     * Beside a fixed group the count adds that group's own filled state to the filled Seals, and both kernel
     * strategies agree; the interpreter declines the rule (INTERPRETER-GAPS IG156).
     */
    @Test
    fun theScopeBesideAGroupAddsItsFilledStateAndIsDeclinedByTheInterpreter() {
        val expected = mapOf("no batch" to 0, "two batches, both sealed" to 2, "two batches, only the first sealed" to 1,
            "two batches, each Seal instantiated and empty" to 0, "three batches, the first and third sealed" to 2,
            "one sealed batch, Grading graded" to 2)
        val measured = measure("/Item/Batches*/Seal, $GRADING")
        assertEquals(expected.mapValues { listOf(it.value) }, measured.mapValues { it.value.getValue("groovy") })
        measured.forEach { (doc, engines) ->
            assertEquals(engines.getValue("groovy"), engines.getValue("java"), "$doc — groovy vs java-static")
            assertEquals(List(5) { "unsupported" }, engines.getValue("interpreter"), "$doc — the interpreter declines it")
        }
    }
}
