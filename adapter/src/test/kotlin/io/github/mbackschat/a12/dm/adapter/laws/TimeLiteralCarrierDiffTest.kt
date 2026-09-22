package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — SPEC-2026-07-27-06's RUNTIME half: a Time constant is constructed on a **1970-01-01
 * model-zone carrier**, and that carrier must stay transport-only. A Time consumer observes the decoded
 * clock, never the hidden instant.
 *
 * The discriminator makes a Time literal meet a Time derived from a **zone-parsed** DateTime, which is the
 * only place the two accounts separate. Under `Europe/Berlin`, `2024-06-01T10:30:45` is CEST (+2), so its
 * instant decodes to `08:30:45` in UTC:
 *
 *  - `TimeFromDateTime(ScheduledAt) == "10:30:45"` **fires** — the wall clock is what is compared;
 *  - `TimeFromDateTime(ScheduledAt) == "08:30:45"` **does not** — and that is exactly the value an
 *    instant-based comparison would produce, so the negative case is the failure mode, not padding;
 *  - both outcomes are **identical under `UTC`**, which is what proves the result is zone-independent: had
 *    either side retained a model-zone instant, the two model zones would disagree.
 *
 * Every case is tri-checked — dynamic-Groovy (normative, IF136), static-Java, and the interpreter — because a
 * Time constant lowers through the per-dialect `ConstantOperation_datum.st`, so codegen parity is measured
 * rather than inherited from the shared runtime. `TimeLiteralLawsTest` owns the static syntax gate; the JVM/JS
 * half is `interpreter TimeLiteralCarrierTest`.
 */
class TimeLiteralCarrierDiffTest {

    private val berlinOrder = RuntimeLaws.model("models/order.dm.json")
    private val utcOrder = berlinOrder.replace("\"timeZone\": \"Europe/Berlin\"", "\"timeZone\": \"UTC\"")

    private val m = BoundModel.ofJson(berlinOrder)
    private fun render(c: Condition): String = RuntimeLaws.render(c)

    private val fields = listOf(
        FieldDef(SA, Kind.DATE_TIME, format = "yyyy-MM-dd'T'HH:mm:ss"),
        FieldDef(PT, Kind.TIME, format = "HH:mm:ss"),
    )

    /** The wall clock of the DateTime — the same literal text the field's wall rendering carries. */
    private fun wallClockCase(literal: String) = render(m.dateTime(SA).filled()
        .and(Dsl.timeFromDateTime(m.dateTime(SA)).eq(Dsl.timeValue(literal))))

    private fun directCase(literal: String) = render(m.time(PT).filled()
        .and(m.time(PT).eq(Dsl.timeValue(literal))))

    /**
     * Groovy / static-Java / interpreter must agree, and the kernel outcome itself is pinned by [fires] so a
     * case cannot pass by all three engines returning nothing.
     */
    private fun triCheck(cond: String, errorRel: String, fires: Boolean, msg: String, berlin: Boolean) {
        val modelJson = if (berlin) berlinOrder else utcOrder
        val zone = if (berlin) ModelTimeZone.EUROPE_BERLIN else ModelTimeZone.UTC
        val model = RuntimeLaws.withRule(modelJson, "/Order", "R", errorRel, CODE, cond)
        val doc = RuntimeLaws.doc().group("/Order", 1)
            .field(SA, STAMP, 1, 1).field(PT, CLOCK, 1, 1)
        val groovy = RuntimeLaws.messages(model, doc).filter { it.code() == CODE }.map { it.code() }.sorted()
        val engine = RuntimeLaws.kernelJavaEngine(model)
        val javaStatic = engine.messages(engine.instance(doc)).filter { it.code() == CODE }
            .map { it.code() }.sorted()
        val def = ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, CODE, errorRel)), timeZone = zone)
        val iDoc = def.newDocument().field(SA, STAMP, 1, 1).field(PT, CLOCK, 1, 1)
        val interp = DmInterpreter(def).validate(iDoc).messages.filter { it.code == CODE }
            .map { it.code }.sorted()
        val where = "$msg [${if (berlin) "Europe/Berlin" else "UTC"}]"
        assertEquals(fires, groovy.isNotEmpty(), "$where — the kernel outcome moved (groovy=$groovy)")
        assertEquals(groovy, javaStatic, "$where (java-static≠groovy)\n  groovy=$groovy\n  java=$javaStatic")
        assertEquals(groovy, interp, "$where (interp≠groovy)\n  groovy=$groovy\n  interp=$interp")
    }

    private fun bothZones(cond: String, errorRel: String, fires: Boolean, msg: String) {
        triCheck(cond, errorRel, fires, msg, berlin = true)
        triCheck(cond, errorRel, fires, msg, berlin = false)
    }

    @Test fun theWallClockIsWhatAConsumerCompares() =
        bothZones(wallClockCase("10:30:45"), "../ScheduledAt", true,
            "the DateTime's wall clock equals the literal")

    @Test fun theSourceInstantIsNotWhatAConsumerCompares() =
        bothZones(wallClockCase("08:30:45"), "../ScheduledAt", false,
            "the UTC decoding of the same instant does NOT equal the literal")

    @Test fun aDirectTimeFieldComparisonIsZoneIndependentToo() {
        bothZones(directCase("10:30:45"), "../PickupTime", true, "a stored clock equals its literal")
        bothZones(directCase("08:30:45"), "../PickupTime", false, "and not a shifted one")
    }

    private companion object {
        const val CODE = "TC"
        const val SA = "/Order/ScheduledAt"
        const val PT = "/Order/PickupTime"

        /** 2024-06-01 is CEST (+2) under Europe/Berlin, so this wall time's instant is 08:30:45Z. */
        const val STAMP = "2024-06-01T10:30:45"
        const val CLOCK = "10:30:45"
    }
}
