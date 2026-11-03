package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.DocumentDifferential.underUtc
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * What a wall label the model zone skips does inside a `Sum` filter (a12-kernel-lean SPEC-2026-10-07-04). Reached,
 * it is a runtime failure, not a row the filter drops: a rule over the sum reports VALUE, and a computation clears
 * with the failure at its target. A malformed stamp read BEFORE the label stops the computation first, while
 * validation reaches the label either way. Between the filter's leaves the connectives short-circuit on known
 * truth, so a false left conjunct or a true left disjunct leaves the label unread.
 *
 * The JVM default is pinned to UTC, under which the kernel's static check admits the gap label
 * (`DateTimeConstantZoneSplitDiffTest`); the model zone is Europe/Berlin.
 *
 * Own domain (parcel delivery legs).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FilterTemporalLiteralFailureDiffTest {
    private companion object {
        const val ISO_DT = "yyyy-MM-dd'T'HH:mm:ss"

        /** A Julian leap day, which Europe/Berlin cannot read: the label fails when reached (KERNEL-FINDINGS KF665). */
        const val GAP = "29.02.1500T10:00:00"
        val FILTERS = listOf("StampFirst", "LabelFirst", "FalsePrefix", "TruePrefix")
    }

    private val model: String = underUtc {
        val draft = DocumentModelDraft.named("parcel-filter-literal").locale("en_US")
            .rootGroup("Parcel") { p ->
                p.stringField("Ref") { }
                p.group("Legs") { l -> l.repeatable(3)
                    .numberField("Weight") { n -> n.fractionDigits(0) }
                    .dateTimeField("Stamp") { it.format(ISO_DT) } }
                for (name in FILTERS) p.numberField(name) { n -> n.fractionDigits(0) }
            }.build().toJson()
        val session = EditSession.read(draft).also { it.config().timeZone("Europe/Berlin") }
        val p = session.group("/Parcel")
        val legs = session.group("/Parcel/Legs")
        val weight = legs.num("Weight")
        val stamp = legs.dateTime("Stamp")
        val gap = Dsl.dateTimeValue(GAP)
        val filters: Map<String, Condition> = linkedMapOf(
            "StampFirst" to weight.gt(0).and(stamp.eq(gap)),
            "LabelFirst" to weight.gt(0).and(gap.eq(stamp)),
            "FalsePrefix" to weight.gt(100).and(stamp.eq(gap)),
            "TruePrefix" to weight.gt(0).or(stamp.eq(gap)),
        )
        var json = session.toJson()
        val ref = p.str("Ref")
        for ((name, filter) in filters) {
            val kept = Dsl.sum(p.flatten("Legs*/Weight").having(filter))
            json = TypedRules.inject(json, p.computation(p.num(name)) {
                it.name(name + "Comp").calc(kept).message(Dsl.en("Check the leg stamps.")).build()
            })
            json = TypedRules.inject(json, p.rule {
                it.name(name + "Rule").code(name + "Rule").errorField(ref.field())
                    .`when`(ref.filled().and(kept.gt(0))).message(Dsl.en("Check the leg stamps.")).build()
            })
        }
        json
    }

    private fun parcel(vararg legs: String) =
        """{"Parcel":{"Ref":"P-1","Legs":[${legs.joinToString(",")}],""" +
            FILTERS.joinToString(",") { "\"$it\":\"99\"" } + "}}"

    private fun leg(stamp: String) = """{"Weight":"7","Stamp":"$stamp"}"""

    private val documents = linkedMapOf(
        "an ordinary stamp" to parcel(leg("2024-03-31T01:30:00")),
        "an empty stamp" to parcel(leg("")),
        "a malformed stamp" to parcel(leg("bad")),
        "no legs" to parcel(),
    )

    @Test
    fun theModelIsKernelConsistentUnderTheAdmittingDefault() {
        assertEquals(emptyList<String>(), underUtc { MessageParameterProbes.kernel(model) })
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        underUtc { DocumentDifferential.assertAgree(model, documents) }
    }

    /**
     * The separating rows, pinned against the normative kernel: the reached label fails rather than drops its row,
     * a malformed stamp read first stops only the computation, the label is not reached past a known left operand of
     * either connective, and with no legs a false conjunct still leaves it unread — while an unknown disjunct, met
     * on a missing declared slot, reads on to it.
     */
    @Test
    fun aReachedLabelFailsAndAKnownPrefixLeavesItUnread() {
        fun run(doc: String) = underUtc {
            DocumentDifferential.kernel(model, documents.getValue(doc), dynamic = true)
        }
        fun row(doc: String, name: String): Triple<String?, Boolean, List<String>> {
            val observed = run(doc)
            return Triple(
                observed.outcomes[name],
                observed.operandErrors.any { it.code == "berechnungsWertFehler" && it.pointer.endsWith("/$name") },
                observed.messages.filter { it.code == name + "Rule" }.map { it.type },
            )
        }
        val actual = linkedMapOf(
            "an empty stamp, stamp first" to row("an empty stamp", "StampFirst"),
            "a malformed stamp, stamp first" to row("a malformed stamp", "StampFirst"),
            "a malformed stamp, label first" to row("a malformed stamp", "LabelFirst"),
            "a false prefix" to row("an ordinary stamp", "FalsePrefix"),
            "a true prefix" to row("an ordinary stamp", "TruePrefix"),
            "no legs" to row("no legs", "StampFirst"),
            "no legs, a true prefix" to row("no legs", "TruePrefix"),
        )
        assertEquals(linkedMapOf(
            "an empty stamp, stamp first" to Triple("CLEARED", true, listOf("VALUE_ERROR")),
            "a malformed stamp, stamp first" to Triple("CLEARED", false, listOf("VALUE_ERROR")),
            "a malformed stamp, label first" to Triple("CLEARED", true, listOf("VALUE_ERROR")),
            "a false prefix" to Triple("VALUE|0", false, emptyList()),
            "a true prefix" to Triple("VALUE|7", false, listOf("OMISSION_ERROR")),
            "no legs" to Triple("VALUE|0", false, emptyList()),
            // The filter runs on the one coordinate an empty list still walks: an empty weight leaves the Or unknown,
            // so it reads on and reaches the label (`FilterMissingSlotDiffTest`).
            "no legs, a true prefix" to Triple("CLEARED", true, listOf("VALUE_ERROR")),
        ), actual)
    }
}
