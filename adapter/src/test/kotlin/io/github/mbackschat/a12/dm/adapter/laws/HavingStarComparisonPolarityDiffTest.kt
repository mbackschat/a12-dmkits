package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check (groovy-dynamic == java-static == interpreter) — the fired POLARITY of a
 * comparison whose operand is an aggregate over a `Having`-FILTERED star (the IG56 E3 residual flip class:
 * a real workspace per-row implicit computation-validation `[F] != RoundDown([Share]/100 *
 * FirstFilledValue(star Having <active-window>), 0)` fired OMISSION in the kernel on a row whose filter
 * DROPPED every lease, VALUE in the interpreter — while the no-rows-at-all sibling row agreed OMISSION).
 *
 * The omission analysis (`starOmission` — "could filling raise the aggregate off its empty identity?") was
 * `Having`-BLIND (it saw the star's RAW rows — a filled cell exists → not fillable → VALUE). The kernel
 * ESCALATES unconditionally, exactly as it does for the filtered fill quantifiers (IF101): even a fired
 * comparison whose filter KEPT a filled row is OMISSION — one filtered-iterator escalation rule for every
 * consumer of a `Having`-filtered star.
 */
class HavingStarComparisonPolarityDiffTest {

    private companion object {
        const val AS_OF = "/Fleet/AsOf"
        const val SHARE = "/Fleet/Vehicles/UsageShare"
        const val HOURS = "/Fleet/Vehicles/EffectiveHours"
        const val L_START = "/Fleet/Vehicles/Leases/StartDate"
        const val L_END = "/Fleet/Vehicles/Leases/EndDate"
        const val L_WEEKLY = "/Fleet/Vehicles/Leases/WeeklyHours"
        const val VEHICLES = "/Fleet/Vehicles"
        const val LEASES = "/Fleet/Vehicles/Leases"
    }

    private val base = DocumentModelDraft.named("fleet").locale("en_US")
        .rootGroup("Fleet") { f ->
            f.dateField("AsOf") { }
            f.group("Vehicles") { v ->
                v.repeatable(10)
                    .numberField("UsageShare") { it.fractionDigits(0, 0) }
                    .numberField("EffectiveHours") { it.fractionDigits(0, 0) }
                v.group("Leases") { l ->
                    l.repeatable(10).dateField("StartDate") { }.dateField("EndDate") { }
                        .numberField("WeeklyHours") { it.fractionDigits(0, 0) }
                }
            }
        }.build().toJson()

    /** The corpus shape, clock-free: the active-window filter compares against the AsOf date field. */
    private val kModel = RuntimeLaws.withTieredComputation(base, VEHICLES, "EffHoursComp", "../EffectiveHours", null,
        listOf(RuntimeLaws.CompAlt(null,
            "RoundDown([UsageShare]/100 * FirstFilledValue(Leases*/WeeklyHours Having " +
                "([/Fleet/Vehicles/Leases/StartDate] <= [/Fleet/AsOf] And (FieldNotFilled(/Fleet/Vehicles/Leases/EndDate) " +
                "Or [/Fleet/Vehicles/Leases/EndDate] >= [/Fleet/AsOf]))), 0)")))

    /** One vehicle row: a share, a STALE stored hours value, and an optional lease (start, end?, weekly). */
    private fun Both.vehicle(r: Int, share: String, staleHours: String,
                             lease: Triple<String, String?, String>? = null): Both {
        group(VEHICLES, 1, r).field(SHARE, share, 1, r, 1).field(HOURS, staleHours, 1, r, 1)
        lease?.let { (start, end, weekly) ->
            group(LEASES, 1, r, 1).field(L_START, start, 1, r, 1, 1).field(L_WEEKLY, weekly, 1, r, 1, 1)
            end?.let { field(L_END, it, 1, r, 1, 1) }
        }
        return this
    }

    private fun agree(expect: List<String>, msg: String, build: (Both) -> Unit) {
        val def = ModelLoader.load(kModel)
        val b = Both(RuntimeLaws.doc(), def.newDocument())
        b.group("/Fleet", 1).field(AS_OF, "2026-01-15", 1, 1)
        build(b)
        val groovy = RuntimeLaws.messages(kModel, b.kDoc).filter { it.code() == "EffHoursComp" }
            .map { "${it.field()}|${it.type().name}" }.sorted()
        val engine = RuntimeLaws.kernelJavaEngine(kModel)
        val b2 = Both(RuntimeLaws.doc(), def.newDocument())
        b2.group("/Fleet", 1).field(AS_OF, "2026-01-15", 1, 1)
        build(b2)
        val javaStatic = engine.messagesModern(b2.kDoc, null, java.util.Locale.US).filter { it.code() == "EffHoursComp" }
            .map { "${it.field()}|${it.type().name}" }.sorted()
        val interp = DmInterpreter(def).validateFull(b.iDoc).filter { it.code == "EffHoursComp" }
            .map { "${it.errorPath}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" }.sorted()
        assertEquals(expect.sorted(), groovy, "$msg — the kernel groovy-dynamic fire set")
        assertEquals(groovy, javaStatic, "$msg — groovy vs java-static (tri-check)")
        assertEquals(groovy, interp, "$msg — kernel vs interpreter")
    }

    private fun at(r: Int, pol: String) = "/Fleet[1]/Vehicles[$r]/EffectiveHours|$pol"

    @Test fun filterDroppedAllRowsFiresOmissionLikeTheEmptyStar() =
        agree(listOf(at(1, "OMISSION_ERROR"), at(2, "OMISSION_ERROR")),
            "row 1 has NO leases; row 2's only lease ENDED before AsOf (filter drops it) — both fire OMISSION") { b ->
            b.vehicle(1, "100", "-670")
            b.vehicle(2, "100", "-946", lease = Triple("2025-06-08", "2025-03-01", "50"))
        }

    @Test fun keptFilledRowIsOmissionToo() =
        agree(listOf(at(1, "OMISSION_ERROR")),
            "the filter KEEPS a filled lease and the stored value disagrees — the escalation is unconditional") { b ->
            b.vehicle(1, "100", "-946", lease = Triple("2025-06-08", null, "50"))
        }

    @Test fun silentWhenTheStoredValueMatchesTheTerm() =
        agree(emptyList(), "stored 50 == term 50 → silent") { b ->
            b.vehicle(1, "100", "50", lease = Triple("2025-06-08", null, "50"))
        }
}
