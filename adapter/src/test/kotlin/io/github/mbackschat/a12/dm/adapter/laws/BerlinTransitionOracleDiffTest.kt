package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.eval.instantToLocal
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import java.time.Instant
import java.time.ZoneOffset
import java.time.zone.ZoneRulesProvider
import java.util.Date
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Exhaustive Route-B evidence against an independently recorded legacy-java.util transition vector
 * (OpenJDK 21.0.11, tzdb 2026a). For immediately before, at, and after all 62 pinned transitions, one
 * prepared dynamic-Groovy engine and one prepared generated-Java engine extract every local clock component
 * from the injected instant. The interpreter primitive is checked against the same independent offset. The
 * vector is not read from DateMath, so an epoch/offset/ordering mutation cannot make expected and actual move
 * together. The common BerlinHistoricalTzTest separately locks every gap/overlap resolution choice.
 */
class BerlinTransitionOracleDiffTest {

    private data class Transition(val epoch: Long, val offset: Long)

    private val base = DocumentModelDraft.named("timezone-oracle").locale("en_US")
        .rootGroup("Clock") { clock ->
            clock.numberField("Year") { it.fractionDigits(0, 0) }
                .numberField("Month") { it.fractionDigits(0, 0) }
                .numberField("Day") { it.fractionDigits(0, 0) }
                .numberField("Hour") { it.fractionDigits(0, 0) }
                .numberField("Minute") { it.fractionDigits(0, 0) }
                .numberField("Second") { it.fractionDigits(0, 0) }
        }.build().toJson()
    private val edit = EditSession.read(base)
    private val clock = edit.group(CLOCK)
    private val year = clock.num("Year")
    private val month = clock.num("Month")
    private val day = clock.num("Day")
    private val hour = clock.num("Hour")
    private val minute = clock.num("Minute")
    private val second = clock.num("Second")
    private val now = Dsl.now()
    private val condition = Dsl.yearFromDate(now).eq(year.asNumber())
        .and(Dsl.monthFromDate(now).eq(month.asNumber()))
        .and(Dsl.dayFromDate(now).eq(day.asNumber()))
        .and(Dsl.hoursFromTime(now).eq(hour.asNumber()))
        .and(Dsl.minutesFromTime(now).eq(minute.asNumber()))
        .and(Dsl.secondsFromTime(now).eq(second.asNumber()))
    private val rule: Rule = clock.rule {
        it.name("ExactLocalClock").code(CODE).errorField(year.field()).`when`(condition)
            .severity(Severity.ERROR).message(Dsl.en("clock matches")).build()
    }
    private val kernelModel = edit.config().timeZone("Europe/Berlin").let { TypedRules.inject(edit.toJson(), rule) }
    private val groovy = RuntimeLaws.kernelEngine(kernelModel)
    private val java = RuntimeLaws.kernelJavaEngine(kernelModel)
    private val oracle = ORACLE_EPOCH.indices.map { Transition(ORACLE_EPOCH[it], ORACLE_OFFSET[it]) }

    @Test
    fun immediatelyBeforeAtAndAfterEveryPinnedTransitionAgreeAcrossBothKernelStrategiesAndInterpreter() {
        val runtimeProfile = "JDK ${Runtime.version()} / tzdb ${
            ZoneRulesProvider.getVersions("Europe/Berlin").lastKey()
        }"
        assertTrue(Runtime.version().feature() == 21,
            "oracle pinned on OpenJDK 21.0.11 / tzdb 2026a; running $runtimeProfile")

        oracle.forEachIndexed { index, transition ->
            val beforeOffset = if (index == 0) 3_600L else oracle[index - 1].offset
            listOf(
                transition.epoch - 1 to beforeOffset,
                transition.epoch to transition.offset,
                transition.epoch + 1 to transition.offset,
            ).forEach { (instant, offset) ->
                val expectedLocal = instant + offset
                assertEquals(expectedLocal, instantToLocal(instant, ModelTimeZone.EUROPE_BERLIN),
                    "interpreter transition $index at $instant; pinned OpenJDK 21.0.11/tzdb2026a, running $runtimeProfile")

                val local = Instant.ofEpochSecond(expectedLocal).atOffset(ZoneOffset.UTC)
                val doc = RuntimeLaws.doc().group(CLOCK, 1)
                    .field(YEAR, local.year.toString(), 1, 1)
                    .field(MONTH, local.monthValue.toString(), 1, 1)
                    .field(DAY, local.dayOfMonth.toString(), 1, 1)
                    .field(HOUR, local.hour.toString(), 1, 1)
                    .field(MINUTE, local.minute.toString(), 1, 1)
                    .field(SECOND, local.second.toString(), 1, 1)
                val clockInstant = Date.from(Instant.ofEpochSecond(instant))
                val groovyCodes = groovy.firedCodesSorted(groovy.instance(doc), clockInstant)
                val javaCodes = java.firedCodesSorted(java.instance(doc), clockInstant)
                assertTrue(CODE in groovyCodes,
                    "dynamic Groovy transition $index at $instant; pinned OpenJDK 21.0.11/tzdb2026a, running $runtimeProfile")
                assertEquals(groovyCodes, javaCodes,
                    "generated Java transition $index at $instant; pinned OpenJDK 21.0.11/tzdb2026a, running $runtimeProfile")
            }
        }
    }

    private companion object {
        const val CLOCK = "/Clock"
        const val YEAR = "$CLOCK/Year"
        const val MONTH = "$CLOCK/Month"
        const val DAY = "$CLOCK/Day"
        const val HOUR = "$CLOCK/Hour"
        const val MINUTE = "$CLOCK/Minute"
        const val SECOND = "$CLOCK/Second"
        const val CODE = "TZ_EXACT"

        val ORACLE_EPOCH = longArrayOf(
            -1693706400L, -1680483600L, -1663455600L, -1650150000L, -1632006000L, -1618700400L,
            -938905200L, -857257200L, -844556400L, -828226800L, -812502000L, -796777200L,
            -781052400L, -776563200L, -765936000L, -761180400L, -748479600L, -733273200L,
            -717631200L, -714610800L, -710380800L, -701910000L, -684975600L, -670460400L,
            -654130800L, -639010800L, 323830800L, 338950800L, 354675600L, 370400400L,
            386125200L, 401850000L, 417574800L, 433299600L, 449024400L, 465354000L,
            481078800L, 496803600L, 512528400L, 528253200L, 543978000L, 559702800L,
            575427600L, 591152400L, 606877200L, 622602000L, 638326800L, 654656400L,
            670381200L, 686106000L, 701830800L, 717555600L, 733280400L, 749005200L,
            764730000L, 780454800L, 796179600L, 811904400L, 828234000L, 846378000L,
            859683600L, 877827600L,
        )
        val ORACLE_OFFSET = longArrayOf(
            7200L, 3600L, 7200L, 3600L, 7200L, 3600L,
            7200L, 3600L, 7200L, 3600L, 7200L, 3600L,
            7200L, 10800L, 7200L, 3600L, 7200L, 3600L,
            7200L, 10800L, 7200L, 3600L, 7200L, 3600L,
            7200L, 3600L, 7200L, 3600L, 7200L, 3600L,
            7200L, 3600L, 7200L, 3600L, 7200L, 3600L,
            7200L, 3600L, 7200L, 3600L, 7200L, 3600L,
            7200L, 3600L, 7200L, 3600L, 7200L, 3600L,
            7200L, 3600L, 7200L, 3600L, 7200L, 3600L,
            7200L, 3600L, 7200L, 3600L, 7200L, 3600L,
            7200L, 3600L,
        )
    }
}
