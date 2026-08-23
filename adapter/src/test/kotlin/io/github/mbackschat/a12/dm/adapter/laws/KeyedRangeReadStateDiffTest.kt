package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Differential — the four read states of a semantically-indexed DATE_RANGE cell, separated by a PRESENCE
 * operand, and the gate-escape shape that a keyed negative presence authors (SPEC-2026-08-23-07).
 *
 * **What the presence operand separates.** A clean no-match and a matched-but-EMPTY cell both read as empty,
 * so a negative presence fires on both. A duplicated key and a formally INVALID matched cell both read
 * UNKNOWN, so neither presence polarity fires. "Unavailable rather than resolved by row order" is that second
 * pair, and without the presence operand the two pairs are indistinguishable from outside.
 *
 * **And what rides the escape.** `FieldNotFilled(Rows/F For "k")` with the error field inside the indexed
 * group and NO positive guard is admitted — a keyed lookup is not an iterated read, so the
 * negative-condition-in-iteration gate does not reach it. It then fires ONCE, and its error address carries
 * the row coordinate **-5**: not an invented number and not the `[0]` wildcard marker, but the kernel's
 * clean-no-match sentinel that KERNEL-SEMANTICS §9 already records for message-pointer targeting. So the
 * shape is readable rather than merely strange — a consumer maps `-5` to "no matching row".
 * [aKeyedNegativePresenceFiresOnceWithANegativeRowCoordinate] pins the exact address beside a guarded twin
 * and a guarded direct-operand control, both of which address a real row on the same document.
 *
 * Own domain (e-commerce seasonal promotions).
 */
class KeyedRangeReadStateDiffTest {

    private val base = DocumentModelDraft.named("promo-keyed").locale("en_US")
        .rootGroup("Shop") { shop ->
            shop.stringField("Reference") { }
            shop.group("Runs") { runs ->
                runs.repeatable(20).indexField("RunKey")
                    .stringField("RunKey") { }
                    .stringField("RunNote") { }
                    .dateRangeField("RunWindow") { it.format("yyyy-MM-dd").separator("/") }
            }
        }.build().toJson()

    private val m = BoundModel.ofJson(base)
    private val keyed = m.dateRange("$RUNS/RunWindow").forIndex("spring")

    /** `FieldNotFilled([RunWindow For "spring"])` at the FORM locus — fires exactly on the empty pair. */
    private val absenceModel = RuntimeLaws.withRule(
        base, SHOP, "KeyedAbsence", "../Reference", CODE,
        DslRenderer().render(Dsl.fieldFilled(m.field("$SHOP/Reference")).and(keyed.semanticIndex().notFilled())),
    )

    /** …and its positive twin, which must fire on neither pair, so "UNKNOWN" is not just "the rule is silent". */
    private val presenceModel = RuntimeLaws.withRule(
        base, SHOP, "KeyedPresence", "../Reference", CODE,
        DslRenderer().render(Dsl.fieldFilled(m.field("$SHOP/Reference")).and(keyed.semanticIndex().filled())),
    )

    @Test
    fun theModelsAreKernelAdmitted() {
        for (json in listOf(absenceModel, presenceModel, escapeModel, guardedModel, directModel)) {
            assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(json), "the kernel admits the model")
        }
    }

    /**
     * The four states as one table. Each cell is `<absence fires>/<presence fires>`, so the EMPTY pair reads
     * `true/false` and the UNAVAILABLE pair reads `false/false` — the presence column is what tells them apart.
     */
    @Test
    fun theFourReadStatesSplitIntoAnEmptyPairAndAnUnavailablePair() {
        val actual = linkedMapOf(
            "matched and filled" to polarity(listOf(Row("spring", "2024-03-01/2024-05-31"))),
            "clean no-match" to polarity(listOf(Row("summer", "2024-06-01/2024-08-31"))),
            "matched but EMPTY" to polarity(listOf(Row("spring", null))),
            "DUPLICATED key" to polarity(listOf(Row("spring", "2024-03-01/2024-05-31"), Row("spring", null))),
            "matched but formally INVALID" to polarity(listOf(Row("spring", "not-a-range"))),
        )
        val expected = linkedMapOf(
            "matched and filled" to "false/true",
            "clean no-match" to "true/false",
            "matched but EMPTY" to "true/false",
            "DUPLICATED key" to "false/false",
            "matched but formally INVALID" to "false/false",
        )
        assertEquals(expected, actual, "no-match and matched-empty read empty; a duplicate and an invalid cell are UNKNOWN")
    }

    /** The duplicate additionally reports `uniqueIndex` on each participating index cell. */
    @Test
    fun aDuplicatedKeyReportsOnEachParticipatingIndexCell() {
        val rows = listOf(Row("spring", "2024-03-01/2024-05-31"), Row("spring", null))
        val codes = RuntimeLaws.messages(absenceModel, doc(rows)).map { it.code() }
        assertTrue(codes.count { it == "uniqueIndex" } == 2,
            "each duplicated index cell reports: $codes")
    }

    /**
     * The gate escape. The unguarded keyed negative presence is admitted, fires once, and addresses with a
     * NEGATIVE row coordinate; both controls address the real row on the same document.
     */
    @Test
    fun aKeyedNegativePresenceFiresOnceWithANegativeRowCoordinate() {
        val rows = listOf(Row("summer", null))
        val escape = RuntimeLaws.messages(escapeModel, doc(rows)).filter { it.code() == CODE }
        val guarded = RuntimeLaws.messages(guardedModel, doc(rows)).filter { it.code() == CODE }
        val direct = RuntimeLaws.messages(directModel, doc(rows)).filter { it.code() == CODE }

        assertEquals(1, escape.size, "the escaping rule fires once, not per row: $escape")
        assertTrue(escape.single().errorField().contains("[1, -5, 1]"),
            "the escaping rule addresses with the kernel's clean-no-match sentinel row: "
                + escape.single().errorField())
        assertEquals(listOf("/Shop[1]/Runs[1]/RunNote"), guarded.map { it.field() },
            "the guarded twin addresses the real row")
        assertEquals(listOf("/Shop[1]/Runs[1]/RunWindow"), direct.map { it.field() },
            "the guarded direct-operand control addresses the real row")
    }

    /** The escaping shape: error field inside the indexed group, keyed negative presence, NO positive guard. */
    private val escapeModel = RuntimeLaws.withRule(
        base, RUNS, "KeyedEscape", "../RunWindow", CODE,
        DslRenderer().render(m.dateRange("$RUNS/RunWindow").forIndex("spring").semanticIndex().notFilled()),
    )

    /**
     * …its guarded twin, and a guarded rule over the DIRECT operand — both address normally.
     *
     * <p>The twin's error field is the row's NOTE rather than the range, and that is forced rather than
     * chosen: once a guard puts the rule into iteration the kernel requires an ITERATING reference to the
     * error field ({@code MVK_ERROR_FIELD_AND_ITERATION_INVALID}), and a keyed lookup is not one. That is the
     * same fact the escape exploits, seen from the other side — which is why the escaping rule has no
     * iteration to address into.
     */
    private val guardedModel = RuntimeLaws.withRule(
        base, RUNS, "KeyedGuarded", "../RunNote", CODE,
        DslRenderer().render(
            Dsl.fieldFilled(m.field("$RUNS/RunNote"))
                .and(m.dateRange("$RUNS/RunWindow").forIndex("spring").semanticIndex().notFilled()),
        ),
    )

    private val directModel = RuntimeLaws.withRule(
        base, RUNS, "DirectGuarded", "../RunWindow", CODE,
        DslRenderer().render(
            Dsl.fieldFilled(m.field("$RUNS/RunNote")).and(m.dateRange("$RUNS/RunWindow").notFilled()),
        ),
    )

    private data class Row(val key: String, val window: String?)

    /** `<absence fires>/<presence fires>`, asserted identical on both kernel strategies and the interpreter. */
    private fun polarity(rows: List<Row>): String =
        "${fires(absenceModel, rows)}/${fires(presenceModel, rows)}"

    private fun fires(json: String, rows: List<Row>): Boolean {
        val dynamic = RuntimeLaws.messages(json, doc(rows)).any { it.code() == CODE }
        val engine = RuntimeLaws.kernelJavaEngine(json)
        val generated = engine.messages(engine.instance(doc(rows))).any { it.code() == CODE }
        val definition = ModelLoader.load(json)
        val interpreted = DmInterpreter(definition).validateFull(interpreterDoc(definition, rows))
            .any { it.code == CODE }

        assertEquals(dynamic, generated, "generated Java disagrees with normative dynamic Groovy")
        assertEquals(dynamic, interpreted, "the interpreter disagrees with normative dynamic Groovy")
        return dynamic
    }

    private fun doc(rows: List<Row>): RuntimeLaws.Doc {
        val document = RuntimeLaws.doc().group(SHOP, 1).field("$SHOP/Reference", "R1", 1, 1)
        rows.forEachIndexed { i, row ->
            document.group(RUNS, 1, i + 1)
                .field("$RUNS/RunKey", row.key, 1, i + 1, 1)
                .field("$RUNS/RunNote", "N${i + 1}", 1, i + 1, 1)
            row.window?.let { document.field("$RUNS/RunWindow", it, 1, i + 1, 1) }
        }
        return document
    }

    private fun interpreterDoc(
        definition: io.github.mbackschat.a12.dm.interpreter.model.ModelDef,
        rows: List<Row>,
    ): io.github.mbackschat.a12.dm.interpreter.eval.Document {
        val document = definition.newDocument().group(SHOP, 1).field("$SHOP/Reference", "R1", 1, 1)
        rows.forEachIndexed { i, row ->
            document.group(RUNS, 1, i + 1)
                .field("$RUNS/RunKey", row.key, 1, i + 1, 1)
                .field("$RUNS/RunNote", "N${i + 1}", 1, i + 1, 1)
            row.window?.let { document.field("$RUNS/RunWindow", it, 1, i + 1, 1) }
        }
        return document
    }

    private companion object {
        const val SHOP = "/Shop"
        const val RUNS = "/Shop/Runs"
        const val CODE = "KEYED"
    }
}
