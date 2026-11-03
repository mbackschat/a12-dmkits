package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.DocumentDifferential.underUtc
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Which operand failure a temporal difference reports when both operands fail (a12-kernel-lean SPEC-2026-10-08-02).
 * `DifferenceInDays` reads its first operand, then its second; here one operand is a leg's stamp — read directly,
 * shifted a day, or rebuilt as a Date from its day — and the other is a wall label the Berlin model zone skips. A
 * computation stops at a FORMALLY unavailable first operand before it reaches the second, while validation reaches
 * both; an empty first operand is no such stop. Each difference is computed plainly and as a filter on a leg sum,
 * and checked by a rule over each.
 *
 * The JVM default is pinned to UTC, under which the kernel's static check admits the gap label
 * (`DateTimeConstantZoneSplitDiffTest`); the model zone is Europe/Berlin.
 *
 * Own domain (parcel delivery legs).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DifferenceOperandFailureOrderDiffTest {
    private companion object {
        const val ISO_DT = "yyyy-MM-dd'T'HH:mm:ss"

        /** A Julian leap day, which Europe/Berlin cannot read: the label fails when reached (KERNEL-FINDINGS KF665). */
        const val GAP = "29.02.1500T10:00:00"
        val SOURCES = listOf("Direct", "Shifted", "Built")
        val TARGETS = SOURCES.flatMap { s -> listOf("${s}Fwd", "${s}Rev", "${s}FwdLeg", "${s}RevLeg") }
    }

    private val model: String = underUtc {
        val draft = DocumentModelDraft.named("parcel-difference-order").locale("en_US")
            .rootGroup("Parcel") { p ->
                p.stringField("Ref") { }
                p.dateTimeField("Picked") { it.format(ISO_DT) }
                p.group("Legs") { l -> l.repeatable(3)
                    .numberField("Weight") { n -> n.fractionDigits(0) }
                    .dateTimeField("Stamp") { it.format(ISO_DT) } }
                for (name in TARGETS) p.numberField(name) { n -> n.fractionDigits(0) }
            }.build().toJson()
        val session = EditSession.read(draft).also { it.config().timeZone("Europe/Berlin") }
        val p = session.group("/Parcel")
        val legs = session.group("/Parcel/Legs")
        val gap = Dsl.dateFromDateTime(Dsl.dateTimeValue(GAP))
        val picked = p.dateTime("Picked")
        val stamp = legs.dateTime("Stamp")
        fun forwardFrom(source: String, root: Boolean): NumberExpr {
            val read = if (root) picked else stamp
            return when (source) {
                "Direct" -> Dsl.differenceInDays(read, gap)
                "Shifted" -> Dsl.differenceInDays(Dsl.addDays(read, 1L), gap)
                else -> Dsl.differenceInDays(
                    Dsl.date(Dsl.dayFromDate(read), Dsl.temporalComponent("03"), Dsl.temporalComponent("2024")), gap)
            }
        }
        fun reverseFrom(source: String, root: Boolean): NumberExpr {
            val read = if (root) picked else stamp
            return when (source) {
                "Direct" -> Dsl.differenceInDays(gap, read)
                "Shifted" -> Dsl.differenceInDays(gap, Dsl.addDays(read, 1L))
                else -> Dsl.differenceInDays(gap,
                    Dsl.date(Dsl.dayFromDate(read), Dsl.temporalComponent("03"), Dsl.temporalComponent("2024")))
            }
        }
        var json = session.toJson()
        val ref = p.str("Ref")
        for (source in SOURCES) {
            for ((suffix, difference) in listOf("Fwd" to forwardFrom(source, true), "Rev" to reverseFrom(source, true))) {
                json = TypedRules.inject(json, p.computation(p.num(source + suffix)) {
                    it.name(source + suffix + "Comp").calc(difference).message(Dsl.en("Check the leg dates.")).build()
                })
                json = TypedRules.inject(json, p.rule {
                    it.name(source + suffix + "Rule").code(source + suffix + "Rule").errorField(ref.field())
                        .`when`(ref.filled().and(difference.eq(0))).message(Dsl.en("Check the leg dates.")).build()
                })
            }
            for ((suffix, difference) in listOf("FwdLeg" to forwardFrom(source, false), "RevLeg" to reverseFrom(source, false))) {
                val kept = Dsl.sum(p.flatten("Legs*/Weight").having(difference.eq(0).and(legs.num("Weight").gt(0))))
                json = TypedRules.inject(json, p.computation(p.num(source + suffix)) {
                    it.name(source + suffix + "Comp").calc(kept).message(Dsl.en("Check the leg dates.")).build()
                })
                json = TypedRules.inject(json, p.rule {
                    it.name(source + suffix + "Rule").code(source + suffix + "Rule").errorField(ref.field())
                        .`when`(ref.filled().and(kept.gt(0))).message(Dsl.en("Check the leg dates.")).build()
                })
            }
        }
        json
    }

    private fun parcel(stamp: String) =
        """{"Parcel":{"Ref":"P-1","Picked":"$stamp","Legs":[{"Weight":"7","Stamp":"$stamp"}],""" +
            TARGETS.joinToString(",") { "\"$it\":\"99\"" } + "}}"

    private val documents = linkedMapOf(
        "filled" to parcel("2024-01-01T00:00:00"),
        "empty" to parcel(""),
        "malformed" to parcel("bad"),
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
     * The separating rows, pinned against the normative kernel: a malformed first operand stops a computation before
     * the gap, so the target clears with no failure of its own; the reverse order reaches the gap first and keeps
     * it; an empty first operand still reaches the gap; and validation reaches the gap past a malformed first operand.
     */
    @Test
    fun aFormalFirstOperandStopsOnlyTheComputation() {
        fun run(doc: String) = underUtc { DocumentDifferential.kernel(model, documents.getValue(doc), dynamic = true) }
        val malformed = run("malformed")
        val empty = run("empty")
        fun targetFailure(observed: DocumentObservation, target: String) =
            observed.operandErrors.any { it.code == "berechnungsWertFehler" && it.pointer.endsWith("/$target") }
        val actual = linkedMapOf<String, Any?>()
        for (source in SOURCES) {
            for (suffix in listOf("Fwd", "FwdLeg")) {
                actual["$source$suffix, malformed first: target"] = malformed.outcomes["$source$suffix"]
                actual["$source$suffix, malformed first: a gap failure at the target"] =
                    targetFailure(malformed, "$source$suffix")
                actual["$source$suffix, malformed first: the rule"] =
                    malformed.messages.filter { it.code == "$source${suffix}Rule" }.map { it.brief }
                actual["$source$suffix, empty first: a gap failure at the target"] = targetFailure(empty, "$source$suffix")
            }
            actual["${source}Rev, malformed second: a gap failure at the target"] = targetFailure(malformed, "${source}Rev")
        }
        val expected = linkedMapOf<String, Any?>()
        for (source in SOURCES) {
            for (suffix in listOf("Fwd", "FwdLeg")) {
                expected["$source$suffix, malformed first: target"] = "CLEARED"
                expected["$source$suffix, malformed first: a gap failure at the target"] = false
                expected["$source$suffix, malformed first: the rule"] = listOf("$source${suffix}Rule|VALUE_ERROR|/Parcel[1]/Ref")
                expected["$source$suffix, empty first: a gap failure at the target"] = true
            }
            expected["${source}Rev, malformed second: a gap failure at the target"] = true
        }
        assertEquals(expected, actual)
    }
}
