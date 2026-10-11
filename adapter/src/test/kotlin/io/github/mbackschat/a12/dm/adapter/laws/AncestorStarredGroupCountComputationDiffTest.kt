package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Locale

/**
 * Differential + tri-check — `NumberOfFilledGroups` on the COMPUTATION arm, over a fixed group below a starred
 * level (`Batches*` + `/Seal`), a starred repeatable group, and two fixed groups (KERNEL-FINDINGS KF463;
 * a12-kernel-lean SPEC-2026-09-24-02, every row independently measured here).
 *
 * Each document is read as `value:TYPE` per computation: the value it computes, and the type of its implicit
 * self-validation message, which fires because every stored tally is seeded wrong (V is VALUE_ERROR, O is
 * OMISSION_ERROR, `-` is no message). The rule arm is `AncestorStarredGroupCountDiffTest`'s; this arm is known to
 * part from it on malformed content ([KF120]), which is why it is measured rather than inferred.
 */
class AncestorStarredGroupCountComputationDiffTest {

    private companion object {
        const val ITEM = "/Item"
        const val TITLE = "/Item/Title"
        const val GRADING = "/Item/Grading"
        const val GRADE = "/Item/Grading/Grade"
        const val NOTE = "/Item/Grading/Note"
        const val LAB = "/Item/Lab"
        const val TECH = "/Item/Lab/Tech"
        const val BATCHES = "/Item/Batches"
        const val SEAL = "/Item/Batches/Seal"
        const val CODE = "/Item/Batches/Seal/Code"
        const val WEIGHT = "/Item/Batches/Seal/Weight"
        const val STAMPS = "/Item/Batches/Seal/Stamps"
        const val MARK = "/Item/Batches/Seal/Stamps/Mark"
        const val BOXES = "/Item/Batches/Boxes"
        const val TAG = "/Item/Batches/Boxes/Lid/Tag"
        const val CRATES = "/Item/Crates"
        const val TARE = "/Item/Crates/Tare"
        const val SEAL_TALLY = "/Item/SealTally"
        const val ROW_TALLY = "/Item/RowTally"
        const val FIXED_TALLY = "/Item/FixedTally"
        const val TWICE_TALLY = "/Item/TwiceTally"
        const val MIXED_TALLY = "/Item/MixedTally"
        const val LID_TALLY = "/Item/LidTally"
        /** A computation's self-validation message carries the computation's name as its code. */
        val BY_CODE = mapOf(
            "SealTallyComp" to SEAL_TALLY, "RowTallyComp" to ROW_TALLY, "FixedTallyComp" to FIXED_TALLY,
            "TwiceTallyComp" to TWICE_TALLY, "MixedTallyComp" to MIXED_TALLY, "LidTallyComp" to LID_TALLY,
        )
        val LABELS = listOf(
            "seal" to SEAL_TALLY, "rows" to ROW_TALLY, "fixed" to FIXED_TALLY,
            "twice" to TWICE_TALLY, "mixed" to MIXED_TALLY, "lids" to LID_TALLY,
        )
    }

    private val base = DocumentModelDraft.named("warehouse-seal-computation").locale("en_US")
        .rootGroup("Item") { i ->
            i.field("Title", FieldKind.STRING)
                .numberField("SealTally") { it.fractionDigits(0) }
                .numberField("RowTally") { it.fractionDigits(0) }
                .numberField("FixedTally") { it.fractionDigits(0) }
                .numberField("TwiceTally") { it.fractionDigits(0) }
                .numberField("MixedTally") { it.fractionDigits(0) }
                .numberField("LidTally") { it.fractionDigits(0) }
                .group("Grading") { it.field("Grade", FieldKind.NUMBER).field("Note", FieldKind.STRING) }
                .group("Lab") { it.field("Tech", FieldKind.STRING) }
                .group("Batches") { b ->
                    b.repeatable(2).field("Lot", FieldKind.STRING)
                        .group("Seal") { s ->
                            s.field("Code", FieldKind.STRING).field("Weight", FieldKind.NUMBER)
                                .group("Stamps") { it.repeatable(2).field("Mark", FieldKind.STRING) }
                        }
                        .group("Boxes") { x -> x.repeatable(2).group("Lid") { it.field("Tag", FieldKind.STRING) } }
                }
                .group("Crates") { it.repeatable(2).field("Tare", FieldKind.NUMBER) }
        }.build().toJson()

    private val model: String = BoundModel.ofJson(base).let { m ->
        listOf(
            m.computation(m.num(SEAL_TALLY)).name("SealTallyComp")
                .calc(Dsl.numberOfFilledGroups(m.flattenGroup("$BATCHES*/Seal")))
                .message(Dsl.en("Seal tally.")).build(),
            m.computation(m.num(ROW_TALLY)).name("RowTallyComp")
                .calc(Dsl.numberOfFilledGroups(m.flattenGroup("$BATCHES*")))
                .message(Dsl.en("Row tally.")).build(),
            m.computation(m.num(FIXED_TALLY)).name("FixedTallyComp")
                .calc(Dsl.numberOfFilledGroups(m.group(GRADING), m.group(LAB)))
                .message(Dsl.en("Fixed tally.")).build(),
            m.computation(m.num(TWICE_TALLY)).name("TwiceTallyComp")
                .calc(Dsl.numberOfFilledGroups(m.flattenGroup("$BATCHES*/Seal"), m.flattenGroup("$BATCHES*/Seal")))
                .message(Dsl.en("Twice tally.")).build(),
            m.computation(m.num(MIXED_TALLY)).name("MixedTallyComp")
                .calc(Dsl.numberOfFilledGroups(m.flattenGroup("$BATCHES*/Seal"), m.flattenGroup("$CRATES*")))
                .message(Dsl.en("Mixed tally.")).build(),
            m.computation(m.num(LID_TALLY)).name("LidTallyComp")
                .calc(Dsl.numberOfFilledGroups(m.flattenGroup("$BATCHES*/Boxes*/Lid")))
                .message(Dsl.en("Lid tally.")).build(),
        ).fold(base) { json, computation -> TypedRules.inject(json, computation) }
    }

    /** Every tally seeded wrong, so each computation's self-validation fires wherever its value is decided. */
    private fun seeded(b: Both) = b.group(ITEM, 1).field(TITLE, "crate", 1, 1)
        .field(SEAL_TALLY, "9", 1, 1).field(ROW_TALLY, "9", 1, 1).field(FIXED_TALLY, "9", 1, 1)
        .field(TWICE_TALLY, "9", 1, 1).field(MIXED_TALLY, "9", 1, 1).field(LID_TALLY, "9", 1, 1)

    private fun lidded(b: Both, row: Int, box: Int) = b.group(BATCHES, 1, row).group(BOXES, 1, row, box)
        .field(TAG, "T$row$box", 1, row, box, 1, 1)

    private fun sealed(b: Both, row: Int) = b.group(BATCHES, 1, row).field(CODE, "S$row", 1, row, 1, 1)

    @Test
    fun theBelowStarCountOnTheComputationArm() {
        val documents: Map<String, (Both) -> Unit> = linkedMapOf(
            "no batch" to { b -> seeded(b) },
            "two batches, both sealed" to { b -> sealed(sealed(seeded(b), 1), 2) },
            "two batches, each Seal instantiated and empty" to { b ->
                seeded(b).group(BATCHES, 1, 1).group(SEAL, 1, 1, 1).group(BATCHES, 1, 2).group(SEAL, 1, 2, 1)
            },
            "one Seal holding only a malformed weight" to { b ->
                seeded(b).group(BATCHES, 1, 1).field(WEIGHT, "abc", 1, 1, 1, 1)
            },
            "one Seal holding a code and a malformed weight" to { b -> sealed(seeded(b), 1).field(WEIGHT, "abc", 1, 1, 1, 1) },
            "one Seal holding only an instantiated stamp row" to { b ->
                seeded(b).group(BATCHES, 1, 1).group(SEAL, 1, 1, 1).group(STAMPS, 1, 1, 1, 1)
            },
            "one Seal holding only a marked stamp row" to { b ->
                seeded(b).group(BATCHES, 1, 1).field(MARK, "m", 1, 1, 1, 1, 1)
            },
            "three batches all sealed, over the limit of two" to { b -> sealed(sealed(sealed(seeded(b), 1), 2), 3) },
            "a malformed-only Seal beside a sealed one" to { b ->
                sealed(seeded(b), 1).group(BATCHES, 1, 2).field(WEIGHT, "abc", 1, 2, 1, 1)
            },
            "two sealed batches, a third over the limit whose Seal holds only a malformed weight" to { b ->
                sealed(sealed(seeded(b), 1), 2).group(BATCHES, 1, 3).field(WEIGHT, "abc", 1, 3, 1, 1)
            },
            "one sealed batch, one weighed crate" to { b -> sealed(seeded(b), 1).group(CRATES, 1, 1).field(TARE, "3", 1, 1, 1) },
            "two lids tagged in one batch" to { b -> lidded(lidded(seeded(b), 1, 1), 1, 2) },
            "every lid tagged" to { b -> lidded(lidded(lidded(lidded(seeded(b), 1, 1), 1, 2), 2, 1), 2, 2) },
            "Grading holding only a malformed grade" to { b -> seeded(b).group(GRADING, 1, 1).field(GRADE, "x", 1, 1, 1) },
            "Grading holding a note and a malformed grade" to { b ->
                seeded(b).group(GRADING, 1, 1).field(GRADE, "x", 1, 1, 1).field(NOTE, "ok", 1, 1, 1)
            },
            "Grading holding only a malformed grade, Lab staffed" to { b ->
                seeded(b).group(GRADING, 1, 1).field(GRADE, "x", 1, 1, 1).group(LAB, 1, 1).field(TECH, "T", 1, 1, 1)
            },
        )
        val expected = linkedMapOf(
            "no batch" to "seal 0:O, rows 0:O, fixed 0:O, twice 0:O, mixed 0:O, lids 0:O",
            "two batches, both sealed" to "seal 2:V, rows 2:V, fixed 0:O, twice 4:V, mixed 2:O, lids 0:O",
            "two batches, each Seal instantiated and empty" to "seal 0:O, rows 2:V, fixed 0:O, twice 0:O, mixed 0:O, lids 0:O",
            "one Seal holding only a malformed weight" to "seal 1:-, rows 1:O, fixed 0:O, twice 2:-, mixed 1:-, lids 0:O",
            "one Seal holding a code and a malformed weight" to "seal 1:O, rows 1:O, fixed 0:O, twice 2:O, mixed 1:O, lids 0:O",
            "one Seal holding only an instantiated stamp row" to "seal 1:O, rows 1:O, fixed 0:O, twice 2:O, mixed 1:O, lids 0:O",
            "one Seal holding only a marked stamp row" to "seal 1:O, rows 1:O, fixed 0:O, twice 2:O, mixed 1:O, lids 0:O",
            "three batches all sealed, over the limit of two" to "seal 2:V, rows 2:V, fixed 0:O, twice 4:V, mixed 2:O, lids 0:O",
            "a malformed-only Seal beside a sealed one" to "seal 2:-, rows 2:V, fixed 0:O, twice 4:-, mixed 2:-, lids 0:O",
            "two sealed batches, a third over the limit whose Seal holds only a malformed weight" to
                "seal 2:V, rows 2:V, fixed 0:O, twice 4:V, mixed 2:O, lids 0:O",
            "one sealed batch, one weighed crate" to "seal 1:O, rows 1:O, fixed 0:O, twice 2:O, mixed 2:O, lids 0:O",
            "two lids tagged in one batch" to "seal 0:O, rows 1:O, fixed 0:O, twice 0:O, mixed 0:O, lids 2:O",
            "every lid tagged" to "seal 0:O, rows 2:V, fixed 0:O, twice 0:O, mixed 0:O, lids 4:V",
            "Grading holding only a malformed grade" to "seal 0:O, rows 0:O, fixed 1:-, twice 0:O, mixed 0:O, lids 0:O",
            "Grading holding a note and a malformed grade" to "seal 0:O, rows 0:O, fixed 1:O, twice 0:O, mixed 0:O, lids 0:O",
            "Grading holding only a malformed grade, Lab staffed" to "seal 0:O, rows 0:O, fixed 2:-, twice 0:O, mixed 0:O, lids 0:O",
        )
        val definition = ModelLoader.load(model)
        val javaEngine = RuntimeLaws.kernelJavaEngine(model)
        val readings = documents.mapValues { (_, build) ->
            val dynamic = Both(RuntimeLaws.doc(), definition.newDocument()).also(build)
            val staticDoc = Both(RuntimeLaws.doc(), definition.newDocument()).also(build).kDoc
            val groovyValues = RuntimeLaws.compute(model, dynamic.kDoc)
                .associate { it.path() to (if (it.cleared()) "CLEARED" else if (it.errored()) "ERRORED" else number(it.value().orElse(""))) }
            val groovyTypes = RuntimeLaws.messages(model, dynamic.kDoc).mapNotNull { m ->
                BY_CODE[m.code()]?.let { it to (m.type().name == "VALUE_ERROR") }
            }.toMap()
            val javaValues = javaEngine.computeOutcomesSorted(javaEngine.instance(staticDoc)).associate {
                it.substringBefore('|') to when (val v = it.substringAfter('|')) { "ERR" -> "ERRORED"; "CLEARED" -> "CLEARED"; else -> number(v) }
            }
            val javaTypes = javaEngine.messagesModern(staticDoc, null, Locale.US).mapNotNull { m ->
                BY_CODE[m.code()]?.let { it to (m.type().name == "VALUE_ERROR") }
            }.toMap()
            val interpreter = DmInterpreter(definition)
            val interpretedValues = interpreter.compute(dynamic.iDoc).associate {
                it.path to when (it.outcome) {
                    ComputeOutcome.CLEARED -> "CLEARED"; ComputeOutcome.ERRORED -> "ERRORED"; ComputeOutcome.VALUE -> number(it.value ?: "")
                }
            }
            val interpretedTypes = interpreter.validate(dynamic.iDoc).messages.mapNotNull { m ->
                BY_CODE[m.code]?.let { it to (m.type == MsgType.VALUE) }
            }.toMap()
            Triple(reading(groovyValues, groovyTypes), reading(javaValues, javaTypes), reading(interpretedValues, interpretedTypes))
        }
        assertEquals(expected, readings.mapValues { it.value.first }, "dynamic Groovy")
        assertEquals(readings.mapValues { it.value.first }, readings.mapValues { it.value.second }, "static Java")
        assertEquals(readings.mapValues { it.value.first }, readings.mapValues { it.value.third }, "interpreter")
    }

    /** A computed value with its scale dropped, so `2` and `2.0` cannot read as different counts. */
    private fun number(text: String): String = text.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: "\"$text\""

    private fun String.toBigDecimalOrNull(): BigDecimal? = runCatching { BigDecimal(this) }.getOrNull()

    private fun reading(values: Map<String, String>, valueTyped: Map<String, Boolean>): String =
        LABELS.joinToString(", ") { (label, path) ->
            val type = valueTyped[path]?.let { if (it) "V" else "O" } ?: "-"
            "$label ${values[path] ?: "absent"}:$type"
        }
}
