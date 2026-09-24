package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Differential + tri-check — what `NumberOfFilledGroups` counts over every operand shape its group list admits:
 * a fixed group below a starred repeatable level (`Batches*` + `/Seal`, KERNEL-FINDINGS KF462) alone and beside
 * a fixed group, a starred repeatable group beside a fixed one, and two fixed groups (KF463).
 *
 * A count is read as the one `count == n` rule that fires, and its polarity as that message's type, so every
 * document yields a `n:TYPE` reading under dynamic Groovy, static Java and the interpreter, or no reading at all
 * where the count is not evaluable. The documents separate the readings a reader could hold: a Seal counted when
 * its batch exists, when the Seal group is instantiated, or when it holds admitted content; a malformed value
 * that suppresses the count against one that rides beside admitted content; and a count that has reached the
 * declared extent, which types VALUE, against one that can still grow. A last test reads both kernel strategies
 * under partial coverage.
 */
class AncestorStarredGroupCountDiffTest {

    private companion object {
        const val ITEM = "/Item"
        const val TITLE = "/Item/Title"
        const val GRADING = "/Item/Grading"
        const val GRADE = "/Item/Grading/Grade"
        const val NOTE = "/Item/Grading/Note"
        const val SPECS = "/Item/Specs"
        const val LABEL = "/Item/Specs/Label"
        const val BATCHES = "/Item/Batches"
        const val LOT = "/Item/Batches/Lot"
        const val SEAL = "/Item/Batches/Seal"
        const val CODE = "/Item/Batches/Seal/Code"
        const val WEIGHT = "/Item/Batches/Seal/Weight"
        const val MSG = "ASGC"
        const val MAX_COUNT = 5
        const val SUPPRESSED = ""
    }

    private val base = DocumentModelDraft.named("warehouse-seal-runtime").locale("en_US")
        .rootGroup("Item") { i ->
            i.field("Title", FieldKind.STRING)
                .group("Grading") { it.field("Grade", FieldKind.NUMBER).field("Note", FieldKind.STRING) }
                .group("Specs") { it.field("Label", FieldKind.STRING) }
                .group("Batches") { b ->
                    b.repeatable(4).field("Lot", FieldKind.STRING)
                        .group("Seal") { it.field("Code", FieldKind.STRING).field("Weight", FieldKind.NUMBER) }
                }
        }.build().toJson()

    private fun titled(b: Both) = b.group(ITEM, 1).field(TITLE, "crate", 1, 1)

    private fun sealed(b: Both, row: Int) = b.group(BATCHES, 1, row).field(CODE, "S$row", 1, row, 1, 1)

    /** Document name → how it is built; every expectation list below follows this order. */
    private val documents: Map<String, (Both) -> Unit> = linkedMapOf(
        "no batch" to { b -> titled(b) },
        "two batches, both sealed" to { b -> sealed(sealed(titled(b), 1), 2) },
        "two batches, only the first sealed" to { b ->
            sealed(titled(b), 1).group(BATCHES, 1, 2).field(LOT, "L2", 1, 2, 1)
        },
        "two batches, each Seal instantiated and empty" to { b ->
            titled(b).group(BATCHES, 1, 1).group(SEAL, 1, 1, 1).group(BATCHES, 1, 2).group(SEAL, 1, 2, 1)
        },
        "three batches, the first and third sealed" to { b -> sealed(sealed(titled(b), 1).group(BATCHES, 1, 2), 3) },
        "four batches, all sealed" to { b -> (1..4).fold(titled(b)) { acc, row -> sealed(acc, row) } },
        "one sealed batch, Grading graded" to { b -> sealed(titled(b), 1).group(GRADING, 1, 1).field(GRADE, "4", 1, 1, 1) },
        "one Seal holding only a malformed weight" to { b ->
            titled(b).group(BATCHES, 1, 1).field(WEIGHT, "abc", 1, 1, 1, 1)
        },
        "a malformed-only Seal beside a sealed one" to { b ->
            sealed(titled(b), 1).group(BATCHES, 1, 2).field(WEIGHT, "abc", 1, 2, 1, 1)
        },
        "one sealed Seal also holding a malformed weight" to { b -> sealed(titled(b), 1).field(WEIGHT, "abc", 1, 1, 1, 1) },
        "one sealed batch, Grading noted with a malformed grade" to { b ->
            sealed(titled(b), 1).group(GRADING, 1, 1).field(GRADE, "x", 1, 1, 1).field(NOTE, "ok", 1, 1, 1)
        },
        "one sealed batch, Grading holding only a malformed grade" to { b ->
            sealed(titled(b), 1).group(GRADING, 1, 1).field(GRADE, "x", 1, 1, 1)
        },
        "Grading noted with a malformed grade, Specs labelled" to { b ->
            titled(b).group(GRADING, 1, 1).field(GRADE, "x", 1, 1, 1).field(NOTE, "ok", 1, 1, 1)
                .group(SPECS, 1, 1).field(LABEL, "L", 1, 1, 1)
        },
    )

    /**
     * A Seal counts by its content: an instantiated empty Seal adds nothing, a malformed-only one makes the count
     * not evaluable, and one holding admitted content counts although it also holds an error. Four sealed
     * batches reach the declared extent, one Seal per `Batches` row, so that count types VALUE.
     */
    @Test
    fun theSoleScopeCountsEachSealByItsContentAgainstTheBatchCapacity() = assertAllEnginesRead(
        "$BATCHES*/Seal",
        listOf("0:O", "2:O", "1:O", "0:O", "2:O", "4:V", "1:O", SUPPRESSED, SUPPRESSED, "1:O", "1:O", "1:O", "0:O"),
    )

    /** Beside a fixed group, the count adds that group's own contribution, decided by the same per-instance rule. */
    @Test
    fun theScopeBesideAGroupAddsThatGroupByTheSameRule() = assertAllEnginesRead(
        "$BATCHES*/Seal, $GRADING",
        listOf("0:O", "2:O", "1:O", "0:O", "2:O", "4:O", "2:O", SUPPRESSED, SUPPRESSED, "1:O", "2:O", SUPPRESSED, "1:O"),
    )

    /**
     * A repeatable scope counts every instantiated row, whatever it holds, so a malformed value below a row
     * leaves it counted, while a malformed-only fixed group beside it still suppresses the count.
     */
    @Test
    fun aRepeatableScopeBesideAGroupCountsInstantiatedRows() = assertAllEnginesRead(
        "$BATCHES*, $GRADING",
        listOf("0:O", "2:O", "2:O", "2:O", "3:O", "4:O", "2:O", "1:O", "2:O", "1:O", "2:O", SUPPRESSED, "1:O"),
    )

    /** Two fixed groups: one holding admitted content counts although it also holds a malformed value. */
    @Test
    fun aFixedGroupHoldingAdmittedContentBesideAnErrorCounts() = assertAllEnginesRead(
        "$GRADING, $SPECS",
        listOf("0:O", "0:O", "0:O", "0:O", "0:O", "0:O", "1:O", "0:O", "0:O", "0:O", "1:O", SUPPRESSED, "2:V"),
    )

    /**
     * Under partial coverage a counted instance needs every field of it covered, on both kernel strategies: two
     * sealed batches count through `Code` and `Weight` covered by field wildcards, not through `Code` alone, and
     * a repeatable row needs its `Lot` covered as well. Kernel-only, because the interpreter misses the count
     * where the coverage names no group entity (INTERPRETER-GAPS IG157).
     */
    @Test
    fun underPartialCoverageACountedInstanceNeedsEveryFieldCovered() {
        val covers = linkedMapOf(
            "Code" to listOf(CODE),
            "Code, Weight" to listOf(CODE, WEIGHT),
            "Code, Weight, Lot" to listOf(CODE, WEIGHT, LOT),
        )
        val readings = listOf("$BATCHES*/Seal", "$BATCHES*").associateWith { operands ->
            val model = (0..MAX_COUNT).fold(base) { json, n ->
                RuntimeLaws.withRule(json, ITEM, "R$n", "../Title", "$MSG$n",
                    "FieldFilled($TITLE) And NumberOfFilledGroups($operands) == $n")
            }
            val javaEngine = RuntimeLaws.kernelJavaEngine(model)
            covers.mapValues { (_, fields) ->
                val relevant = (fields + TITLE).map { path ->
                    RuntimeLaws.Relevant.of(path, *IntArray(path.count { it == '/' }))
                }.toSet()
                fun doc() = RuntimeLaws.doc().group(ITEM, 1).field(TITLE, "crate", 1, 1)
                    .group(BATCHES, 1, 1).field(CODE, "S1", 1, 1, 1, 1).group(BATCHES, 1, 2).field(CODE, "S2", 1, 2, 1, 1)
                val dynamic = reading(RuntimeLaws.messagesPart(model, doc(), relevant).filter { it.code().startsWith(MSG) }
                    .map { it.code() to (it.type().name == "VALUE_ERROR") })
                val static = reading(javaEngine.messagesPart(doc(), relevant).filter { it.code().startsWith(MSG) }
                    .map { it.code() to (it.type().name == "VALUE_ERROR") })
                assertEquals(dynamic, static, "$operands covering $fields — static Java")
                dynamic
            }
        }
        assertEquals(
            mapOf(
                "$BATCHES*/Seal" to mapOf("Code" to SUPPRESSED, "Code, Weight" to "2:O", "Code, Weight, Lot" to "2:O"),
                "$BATCHES*" to mapOf("Code" to SUPPRESSED, "Code, Weight" to SUPPRESSED, "Code, Weight, Lot" to "2:O"),
            ),
            readings,
        )
    }

    /**
     * Asserts the kernel's reading of every document against [expected] (`n:V`/`n:O`, or [SUPPRESSED]), then static
     * Java and the interpreter against dynamic Groovy.
     */
    private fun assertAllEnginesRead(operands: String, expected: List<String>) {
        val model = (0..MAX_COUNT).fold(base) { json, n ->
            RuntimeLaws.withRule(json, ITEM, "R$n", "../Title", "$MSG$n",
                "FieldFilled($TITLE) And NumberOfFilledGroups($operands) == $n")
        }
        val definition = ModelLoader.load(model)
        val javaEngine = RuntimeLaws.kernelJavaEngine(model)
        val readings = documents.mapValues { (_, build) ->
            val dynamic = Both(RuntimeLaws.doc(), definition.newDocument()).also(build)
            val kernel = RuntimeLaws.messages(model, dynamic.kDoc)
            assertTrue(kernel.none { it.code().startsWith("MVK_") }) { "the kernel refused $operands: $kernel" }
            val staticDoc = Both(RuntimeLaws.doc(), definition.newDocument()).also(build).kDoc
            val outcome = DmInterpreter(definition).validate(dynamic.iDoc)
            Triple(
                reading(kernel.filter { it.code().startsWith(MSG) }.map { it.code() to (it.type().name == "VALUE_ERROR") }),
                reading(javaEngine.messagesModern(staticDoc, null, Locale.US).filter { it.code().startsWith(MSG) }
                    .map { it.code() to (it.type().name == "VALUE_ERROR") }),
                if (outcome.unsupported.isNotEmpty()) "unsupported"
                else reading(outcome.messages.filter { it.code.startsWith(MSG) }.map { it.code to (it.type == MsgType.VALUE) }),
            )
        }
        assertEquals(documents.keys.zip(expected).toMap(), readings.mapValues { it.value.first }, "$operands — dynamic Groovy")
        assertEquals(readings.mapValues { it.value.first }, readings.mapValues { it.value.second }, "$operands — static Java")
        assertEquals(readings.mapValues { it.value.first }, readings.mapValues { it.value.third }, "$operands — interpreter")
    }

    /** The fired ladder rungs as `n:V`/`n:O`, comma-joined; a well-formed count fires exactly one. */
    private fun reading(fired: List<Pair<String, Boolean>>): String =
        fired.map { (code, value) -> "${code.removePrefix(MSG)}:${if (value) "V" else "O"}" }.sorted().joinToString(",")
}
