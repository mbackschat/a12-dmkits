package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import java.util.Locale
import java.util.function.Consumer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tri-engine differential — **an over-limit row ABSORBS every violation beneath it**: the outermost violated row
 * draws one `zuGrosseZeile`, one `zuGrosseKontextnummer` per written node under it, and an over-limit row nested
 * inside that subtree draws no row code and is named nowhere. a12-kernel-lean's `SPEC-2026-08-30-10`, measured
 * here on our own fixture.
 *
 * **Why it needed its own file rather than a row in [OverRepetitionFormalGateDiffTest].** That class establishes
 * over-repetition as an address-formal gate on a two-level fixture, where "the outermost violated row" and "the
 * violated row" are the same row and cannot come apart. Absorption is only visible with a violation *inside* a
 * violation, which needs three levels.
 *
 * **The consequence worth stating, because it is not obvious from any single row: the formal channel is NOT
 * monotone in the document.** Fixing the outer row reveals an inner violation that no message had named, so a
 * consumer deriving the set of violated groups from the message stream sees one group where two are violated.
 *
 * **And one measurement here is about OUR rendering, not the kernel's.** The kernel addresses each instantiated
 * descendant row distinctly; our formatted address collapses them, so the ordinary formatted comparison is blind
 * to a row misattribution exactly on this class of message. That is measured rather than reasoned about, in its
 * own test, and recorded as `IG` work — the formatted comparison is kept beside it rather than replaced, because
 * it is the contract every other differential in this package asserts.
 *
 * Own domain (subscription billing).
 */
class NestedOverLimitAbsorptionDiffTest {

    private companion object {
        const val BILLING = "/Billing"
        const val ACCOUNT = "/Billing/Account"
        const val INVOICES = "/Billing/Invoices"
        const val REF = "/Billing/Invoices/Ref"
        const val LABEL = "/Billing/Invoices/Periods/Label"
        const val AMOUNT = "/Billing/Invoices/Periods/Charges/Amount"
        const val ROW_CODE = "zuGrosseZeile"
        const val NODE_CODE = "zuGrosseKontextnummer"
        const val BAD_NUMBER = "zahlHatUngueltigeZeichen"

        /** `PartiallyKnownDocumentMultiPointerImpl[/a/b, [1, 3, 1]]` → `/a/b|1,3,1`, or a loud failure. */
        private val RAW = Regex("""\[(/[^,\]]+), \[([0-9, ]*)]]""")
    }

    /** Three nested repeatable levels, each capped at 2, so index 3 is over-limit at any of them. */
    private val base = DocumentModelDraft.named("billing").locale("en_US")
        .rootGroup("Billing", Consumer { b ->
            b.stringField("Account", Consumer { })
                .group("Invoices", Consumer<GroupBuilder> { i ->
                    i.repeatable(2).stringField("Ref", Consumer { })
                        .group("Periods", Consumer<GroupBuilder> { p ->
                            p.repeatable(2).stringField("Label", Consumer { })
                                .group("Charges", Consumer<GroupBuilder> { c ->
                                    c.repeatable(2).field("Amount", FieldKind.NUMBER)
                                })
                        })
                })
        }).build().toJson()

    private class Both(model: String) {
        val kDoc = RuntimeLaws.doc().group(BILLING, 1)
        val iDoc: Document = ModelLoader.load(model).newDocument().group(BILLING, 1)

        fun field(path: String, v: String, vararg reps: Int) {
            kDoc.field(path, v, *reps); iDoc.field(path, v, *reps)
        }

        fun group(path: String, vararg reps: Int) {
            kDoc.group(path, *reps); iDoc.group(path, *reps)
        }
    }

    /**
     * The formatted `code@address` list across all three engines, asserted to agree and returned for the row's own
     * expectation. A LIST, not a set: the multiplicity is the claim, and a set would silently absorb it.
     */
    private fun agreedMessages(label: String, build: (Both) -> Unit): List<String> {
        val def = ModelLoader.load(base)
        val both = Both(base).also(build)
        val dynamic = RuntimeLaws.messages(base, both.kDoc).map { "${it.code()}@${it.field()}" }.sorted()
        val static = RuntimeLaws.kernelJavaEngine(base)
            .messagesModern(Both(base).also(build).kDoc, null, Locale.US)
            .map { "${it.code()}@${it.field()}" }.sorted()
        val interp = DmInterpreter(def).validateFull(both.iDoc).map { "${it.code}@${it.errorPath}" }.sorted()
        assertEquals(dynamic, static, "$label — static-Java vs dynamic-Groovy")
        assertEquals(dynamic, interp, "$label — INTERPRETER vs dynamic-Groovy")
        return dynamic
    }

    // ---- the documents ----

    private fun account(b: Both) = b.field(ACCOUNT, "A-1", 1, 1)

    /** Over-limit at BOTH levels: `Invoices[3]` and, inside it, `Periods[3]`. */
    private fun doublyOverLimit(b: Both) {
        account(b); b.field(LABEL, "x", 1, 3, 3, 1)
    }

    /** Over-limit at the outer level only — the control the absorption claim is read against. */
    private fun outerOverLimitOnly(b: Both) {
        account(b); b.field(LABEL, "x", 1, 3, 1, 1)
    }

    /** Two written cells under one over-limit row, at different depths. */
    private fun twoCellsUnderOneOverLimitRow(b: Both) {
        account(b); b.field(REF, "R-9", 1, 3, 1); b.field(LABEL, "x", 1, 3, 1, 1)
    }

    /** The over-limit row instantiated and written EMPTY — the row a user created and left blank. */
    private fun overLimitRowWrittenEmpty(b: Both) {
        account(b); b.group(INVOICES, 1, 3)
    }

    /** A non-numeric cell two levels below the over-limit row. */
    private fun malformedBeneathOverLimit(b: Both) {
        account(b); b.field(AMOUNT, "abc", 1, 3, 1, 1, 1)
    }

    /** The identical cell in an in-capacity sibling row — the control for the suppression. */
    private fun malformedInCapacitySibling(b: Both) {
        account(b); b.field(AMOUNT, "abc", 1, 1, 1, 1, 1)
    }

    // ---- the measurements ----

    /**
     * Absorption. The doubly over-limit document draws exactly ONE row code, at the OUTER group, and no message
     * anywhere names `Periods` as a violated row — even though `Periods[3]` exceeds its own capacity of 2. The
     * outer-only control differs from it in exactly one dimension, the inner row's index, and produces the same
     * single row code, which is what charges the absorption to nesting rather than to the fixture.
     */
    @Test
    fun anInnerOverLimitRowIsAbsorbedSoOnlyTheOutermostViolationIsNamed() {
        val doubly = agreedMessages("doubly over-limit", ::doublyOverLimit)
        val outerOnly = agreedMessages("outer over-limit only", ::outerOverLimitOnly)

        assertEquals(
            listOf(
                "$NODE_CODE@/Billing[1]/Invoices[3]/Periods",
                "$NODE_CODE@/Billing[1]/Invoices[3]/Periods",
                "$NODE_CODE@/Billing[1]/Invoices[3]/Periods",
                "$NODE_CODE@/Billing[1]/Invoices[3]/Periods[3]/Label",
                "$ROW_CODE@/Billing[1]/Invoices",
            ),
            doubly,
            "one row code at the OUTER group; the inner violation contributes nodes but no row code of its own",
        )
        assertEquals(
            listOf(
                "$NODE_CODE@/Billing[1]/Invoices[3]/Periods",
                "$NODE_CODE@/Billing[1]/Invoices[3]/Periods[1]/Label",
                "$ROW_CODE@/Billing[1]/Invoices",
            ),
            outerOnly,
            "the same single row code with the inner row in capacity — so nesting, not the fixture, absorbs",
        )
        assertEquals(1, doubly.count { it.startsWith("$ROW_CODE@") },
            "a doubly violated document must still draw exactly one row code")
    }

    /**
     * The finding set covers the WRITTEN subtree: one node finding per instantiated descendant row and per present
     * cell. The empty-row document is the discriminating control — it is the common case (a user created a row and
     * left it blank) and a declaration-driven reading would over-report on it.
     */
    @Test
    fun theFindingSetCoversWhatTheDocumentWROTEAndNotWhatTheModelDECLARES() {
        assertEquals(
            listOf(
                "$NODE_CODE@/Billing[1]/Invoices[3]/Periods",
                "$NODE_CODE@/Billing[1]/Invoices[3]/Periods[1]/Label",
                "$NODE_CODE@/Billing[1]/Invoices[3]/Ref",
                "$ROW_CODE@/Billing[1]/Invoices",
            ),
            agreedMessages("two written cells", ::twoCellsUnderOneOverLimitRow),
            "two cells at different depths give a node finding each, beside the descendant row's own",
        )
        assertEquals(
            listOf("$ROW_CODE@/Billing[1]/Invoices"),
            agreedMessages("over-limit row written empty", ::overLimitRowWrittenEmpty),
            "an over-limit row carrying nothing draws the row code alone — declarations contribute no findings",
        )
    }

    /**
     * The suppression is confined to the violated subtree, not applied to the document. Without the in-capacity
     * sibling the silence would be equally well explained by the fixture never producing that finding at all.
     */
    @Test
    fun anOrdinaryFormalErrorIsSuppressedBeneathTheViolatedRowAndReportedBesideIt() {
        val beneath = agreedMessages("malformed beneath the over-limit row", ::malformedBeneathOverLimit)
        assertEquals(
            listOf(
                "$NODE_CODE@/Billing[1]/Invoices[3]/Periods",
                "$NODE_CODE@/Billing[1]/Invoices[3]/Periods[1]/Charges",
                "$NODE_CODE@/Billing[1]/Invoices[3]/Periods[1]/Charges[1]/Amount",
                "$ROW_CODE@/Billing[1]/Invoices",
            ),
            beneath,
            "the malformed cell exposes over-repetition instead of its format cause",
        )
        assertEquals(
            listOf("$BAD_NUMBER@/Billing[1]/Invoices[1]/Periods[1]/Charges[1]/Amount"),
            agreedMessages("the same cell in capacity", ::malformedInCapacitySibling),
            "the identical value one index lower reports its format cause, so the silence above is suppression",
        )
    }

    /**
     * **Our formatted address is LOSSY on a group node, and the tri-engine comparison above cannot see it.** The
     * kernel gives each instantiated descendant row its own coordinate — `[1, 3, 1]`, `[1, 3, 2]`, `[1, 3, 3]` on
     * one path — while `formatPointer` indexes every segment but the last, correct for a field (whose terminal
     * coordinate is not a repetition axis) and lossy for a repeatable group, so the three collapse to one string.
     *
     * <p>The consequence is a differential blind spot, not merely an ugly address: an engine attributing a
     * group-node finding to the wrong descendant row would compare equal. This test pins the kernel's own
     * addresses across both codegen strategies so the class is covered while the rendering question is decided
     * separately — changing the format moves a published address shape, including one an external consumer of the
     * probe artifact already reads, so it is not folded into a measurement commit.
     */
    @Test
    fun theKernelAddressesEachDescendantRowDistinctlyWhereOurFormattedAddressCollapsesThem() {
        val dynamic = rawAddresses(RuntimeLaws.messages(base, Both(base).also(::doublyOverLimit).kDoc))
        val static = rawAddresses(RuntimeLaws.kernelJavaEngine(base)
            .messagesModern(Both(base).also(::doublyOverLimit).kDoc, null, Locale.US))

        val expected = listOf(
            "/Billing/Invoices/Periods/Label|1, 3, 3, 1",
            "/Billing/Invoices/Periods|1, 3, 1",
            "/Billing/Invoices/Periods|1, 3, 2",
            "/Billing/Invoices/Periods|1, 3, 3",
            "/Billing/Invoices|1, 3",
        )
        assertEquals(expected, dynamic, "the kernel's own addresses, one per written node, all distinct")
        assertEquals(dynamic, static, "both codegen strategies address the nodes identically")
        assertEquals(3, dynamic.count { it.startsWith("/Billing/Invoices/Periods|") },
            "three instantiated descendant rows, three distinct kernel addresses")
        assertEquals(1, agreedMessages("collapse", ::doublyOverLimit)
            .filter { it == "$NODE_CODE@/Billing[1]/Invoices[3]/Periods" }.distinct().size,
            "and one formatted string for all three — the loss this test exists to record")
    }

    /** Fails loudly rather than skipping if the kernel's pointer `toString` ever changes shape. */
    private fun rawAddresses(messages: List<RuntimeLaws.Msg>): List<String> = messages.map { msg ->
        val m = RAW.find(msg.errorField())
            ?: throw AssertionError("kernel pointer toString no longer parses: ${msg.errorField()}")
        "${m.groupValues[1]}|${m.groupValues[2]}"
    }.sorted()
}
