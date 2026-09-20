package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A clause-carrying operand gets a KEY rather than silencing the list it sits in.
 *
 * Every operand used to reduce to its bare path or to nothing, and "nothing" propagated: one operand the
 * reader could not reduce made the WHOLE list unjudgeable. So the plain duplicate in
 * `AllFieldsFilled(Lines/Amount For "A", Quantity, Quantity)` went unreported although it needs no clause
 * read at all — while the identical list without that first operand was reported. A12's identity is four
 * components rather than one — resolved path, category projection, semantic index, and the `$`
 * outer-iteration flag (KERNEL-FINDINGS kf439) — so such an operand is differently KEYED, not unjudgeable.
 *
 * **Measured against the kernel 2026-09-20** (`dmtool rule check --engine=kernel`, recorded in the PR #3
 * review record under `docs/archived/`): A12 reports `MVK_DUPLICATE_PARAM1` for that list, and ADMITS a pair
 * sharing a path under different index keys. The second row matters most here, because reporting it would be
 * an over-rejection — the checker answering invalid on a legal model, the one direction this project forbids.
 *
 * **The index-selected path resolves too, as of 2026-09-20 (DG57).** A semantic-index key ADDRESSES the
 * repetition it selects — it pins one row rather than iterating ([KF429]) — so `Lines/Amount For "SKU-1"`
 * is a resolvable read, not an unaddressed one, and the duplicate the kernel reports for two identical such
 * reads is reported here. Scoped to a path crossing exactly ONE repeatable level, which is the whole
 * authorable domain on the measured shape: two indexed levels under one key are
 * `MVK_MULTIPLE_INDEX_FIELDS` before any duplicate arm is asked
 * (`validate.laws.DuplicateOverlapCarrierSweepLawsTest`).
 *
 * **And the per-operand skip has a list-wide exception, which is the sharper half.** An operand that is
 * genuinely UNADDRESSED — a repeatable level with neither a star nor a key — makes A12 answer
 * `MVK_NO_WILDCARD` for the whole call, in both operand orders, so a duplicate elsewhere in that list is
 * not this family's to report. Skipping such an operand individually and judging its siblings was an
 * over-rejection; the two order rows that measure it are in the sweep above.
 */
class ClauseCarryingOperandDuplicateTest {

    private fun codes(condition: String): List<String> = check(
        ModelSource(
            """
            {"header":{"id":"order","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"Quantity","Field":{"fieldType":{"type":"NumberType"}}},
                {"type":"Group","id":"g1","name":"Lines","Group":{
                  "repeatability":50,"indexFieldName":"Sku","elements":[
                  {"type":"Field","id":"f2","name":"Sku","Field":{"fieldType":{"type":"StringType"}}},
                  {"type":"Field","id":"f3","name":"Amount","Field":{"fieldType":{"type":"NumberType"}}}
                ]}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"CONDITION_HERE","errorEntityRelPath":"../Reference",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
            """.trimIndent().replace("CONDITION_HERE", condition.replace("\"", "\\\"")),
        ),
    ).findings.map { it.code }

    private fun subject(call: String): List<String> =
        codes("FieldFilled(Reference) And $call").filter { it.startsWith("MVK_DUPLICATE_PARAM") }

    /**
     * The fixture's own liveness, and it is not ceremony. An index key is written with double quotes, which
     * are JSON string delimiters here: left raw they end `errorCondition` early, the model fails to parse,
     * and every row below reads an empty finding list as "nothing is wrong" — which is exactly what the
     * first draft of this class did.
     */
    @Test
    fun theFixtureParsesAndTheCheckerReadsItsRule() {
        assertEquals(
            listOf("MVK_DUPLICATE_PARAM1"),
            codes("FieldFilled(Reference) And AllFieldsFilled(Quantity, Quantity)"),
            "the quote-free control must still reach the duplicate gate",
        )
        assertTrue(
            codes("""FieldFilled(Reference) And AllFieldsFilled(Lines/Amount For "SKU-1", Quantity)""")
                .none { it == "MVK_INVALID_ENTITY" },
            "an index-keyed operand must parse, not read as an unknown entity",
        )
    }

    /**
     * The defect this closes. The two rows move exactly one variable — whether an unrelated clause-carrying
     * operand is prepended — and the duplicate they both carry needs no clause read at all.
     */
    @Test
    fun anUnreducibleOperandNoLongerAnswersForItsSiblings() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a plain duplicate, alone"] = subject("AllFieldsFilled(Quantity, Quantity)")
        actual["the same duplicate, behind an index-clause operand"] =
            subject("""AllFieldsFilled(Lines/Amount For "SKU-1", Quantity, Quantity)""")

        val expected = mutableMapOf<String, List<String>>()
        expected["a plain duplicate, alone"] = listOf("MVK_DUPLICATE_PARAM1")
        expected["the same duplicate, behind an index-clause operand"] = listOf("MVK_DUPLICATE_PARAM1")
        assertEquals(expected, actual, "an operand the reader cannot reduce must not answer for its siblings")
    }

    /**
     * DG57: an index-selected read is ADDRESSED, so its duplicate is reported rather than skipped.
     *
     * The key is what addresses the repetition here — a bare `Lines/Amount` twice is `MVK_NO_WILDCARD` at
     * the kernel, not a duplicate — so the last row is the within-model control that isolates the clause as
     * the operative variable rather than the path.
     */
    @Test
    fun anIndexSelectedReadIsAddressedAndItsDuplicateIsReported() {
        val actual = mutableMapOf<String, List<String>>()
        actual["the same field under ONE key, twice"] =
            subject("""AllFieldsFilled(Lines/Amount For "SKU-1", Lines/Amount For "SKU-1")""")
        actual["a keyed read beside a plain duplicate elsewhere"] =
            subject("""AllFieldsFilled(Lines/Amount For "SKU-1", Quantity, Quantity)""")
        actual["the same field WITHOUT a key, twice"] =
            subject("AllFieldsFilled(Lines/Amount, Lines/Amount)")

        val expected = mutableMapOf<String, List<String>>()
        expected["the same field under ONE key, twice"] = listOf("MVK_DUPLICATE_PARAM1")
        expected["a keyed read beside a plain duplicate elsewhere"] = listOf("MVK_DUPLICATE_PARAM1")
        expected["the same field WITHOUT a key, twice"] = emptyList()
        assertEquals(expected, actual, "the semantic index addresses the repetition it selects")
    }

    /**
     * The list-wide abstention an UNADDRESSED operand forces, measured in both orders at the kernel: A12
     * answers `MVK_NO_WILDCARD` for the whole call, so the duplicate sitting elsewhere in it is not ours to
     * name. Judging each operand on its own would report it — which is why this is a row and not a comment.
     */
    @Test
    fun anUnaddressedOperandSilencesTheWholeListRatherThanItselfAlone() {
        val actual = mutableMapOf<String, List<String>>()
        actual["duplicate pair AFTER an unaddressed operand"] =
            subject("AllFieldsFilled(Lines/Amount, Quantity, Quantity)")
        actual["duplicate pair BEFORE an unaddressed operand"] =
            subject("AllFieldsFilled(Quantity, Quantity, Lines/Amount)")

        val expected = mutableMapOf<String, List<String>>()
        expected["duplicate pair AFTER an unaddressed operand"] = emptyList()
        expected["duplicate pair BEFORE an unaddressed operand"] = emptyList()
        assertEquals(expected, actual, "the wildcard bar pre-empts the duplicate arms for the whole list")
    }

    /**
     * The over-rejection control, and the row that must never go red: A12 ADMITS two reads of one path under
     * different index keys, so the checker stays silent whatever else changes about the identity.
     */
    @Test
    fun differentlyKeyedOperandsAreNeverReportedAsDuplicates() {
        val actual = mutableMapOf<String, List<String>>()
        actual["the same path under DIFFERENT index keys"] =
            subject("""AllFieldsFilled(Lines/Amount For "SKU-1", Lines/Amount For "SKU-2")""")
        actual["an ancestor and its descendant under DIFFERENT index keys"] =
            subject("""AllFieldsFilled(Lines For "SKU-1", Lines/Amount For "SKU-2")""")
        actual["the same STARRED read listed twice"] =
            subject("AllFieldsFilled(Lines*/Amount, Lines*/Amount)")

        val expected = mutableMapOf<String, List<String>>()
        expected["the same path under DIFFERENT index keys"] = emptyList()
        expected["an ancestor and its descendant under DIFFERENT index keys"] = emptyList()
        expected["the same STARRED read listed twice"] = emptyList()
        assertEquals(expected, actual, "a shape A12 admits must never draw a duplicate code")
    }
}
