package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Differential — does `compute()` render a date-ish result in the TARGET field's declared format (IF33/IG19)?
 *
 *  - **DATE** (`computedDateUsesTargetFormatLikeKernel`): `OrderDate` is `yyyy-MM-dd`, `Maturity` is `dd.MM.yyyy`;
 *    `AddYears(OrderDate, 2)` into `Maturity` — the kernel emits the target-format text (`30.06.2026`). Locked by IF33.
 *  - **DATE_TIME** (`computedDateTimeUsesTargetFormatLikeKernel`): `D` is `yyyy-MM-dd`, `T` is `HH:mm:ss`, `Stamp`
 *    is `yyyy-MM-dd'T'HH:mm:ss`; `DateTime(D, T)` into `Stamp` agrees with the kernel. This is the **parity lock**
 *    for the IG19-residual question "does the kernel reformat a computed DATE_TIME to a custom target format?".
 *    The answer is **parity by construction**: the kernel admits only ONE date-bearing DATE_TIME format — the ISO
 *    `yyyy-MM-dd'T'HH:mm:ss` (every other is rejected `The date format is invalid`, KERNEL-FINDINGS §6 "DATE_TIME
 *    field formats"), and the degenerate time-only `HH:mm:ss` datetime can't receive a full `DateTime(...)` (the
 *    implicit computation-validation compare is `MVK_INVALID_COMPARE_TO_DATE` at codegen, IF32). So every authorable
 *    computed-datetime target IS ISO — exactly the interpreter's output; no datetime re-formatter is needed.
 */
class ComputeTargetFormatDiffTest {

    private val json = DocumentModelDraft.named("cal").locale("en_US")
        .rootGroup("Cal", Consumer { g: GroupBuilder ->
            g.dateField("OrderDate", Consumer { it.format("yyyy-MM-dd") })
                .dateField("Maturity", Consumer { it.format("dd.MM.yyyy") })
        })
        .build().toJson()

    private val m = BoundModel.ofJson(json)
    private val op = DslRenderer(ConditionLanguage.EN, "/Cal").render(Dsl.addYears(m.date(ORDER), 2).expression())
    private val def = ModelDef(
        listOf(FieldDef(ORDER, Kind.DATE, format = "yyyy-MM-dd"), FieldDef(MAT, Kind.DATE, format = "dd.MM.yyyy")),
        emptyList(),
        computations = listOf(ComputationDef(MAT, "/Cal", op)),
    )
    private val kModel = RuntimeLaws.withComputation(json, "/Cal", "MaturityComp", "../Maturity", op)

    @Test fun computedDateUsesTargetFormatLikeKernel() {
        val kValue = RuntimeLaws.computedValue(RuntimeLaws.compute(kModel,
            RuntimeLaws.doc().group("/Cal", 1).field(ORDER, "2024-06-30", 1, 1)), MAT).orElse(null)
        val iValue = DmInterpreter(def).compute(Document(def.kinds, def.declaredReps, def.unsignedFields,
            def.rangeConfig, def.dateFormats, def.scales, def.dateTimeFormats).group("/Cal", 1)
            .field(ORDER, "2024-06-30", 1, 1)).firstOrNull { it.path == MAT }?.value
        assertEquals(kValue, iValue, "AddYears(OrderDate,2) into a dd.MM.yyyy target — kernel=$kValue interp=$iValue")
    }

    // ── DATE_TIME (IG19 residual) ──────────────────────────────────────────────────────────────────────────────
    private val dtJson = DocumentModelDraft.named("dt").locale("en_US")
        .rootGroup("Dt", Consumer { g: GroupBuilder ->
            g.dateField("D", Consumer { it.format("yyyy-MM-dd") })
                .timeField("T", Consumer { it.format("HH:mm:ss") })
                .dateTimeField("Stamp", Consumer { it.format("yyyy-MM-dd'T'HH:mm:ss") })
        })
        .build().toJson()

    private val dtM = BoundModel.ofJson(dtJson)
    private val dtOp = DslRenderer(ConditionLanguage.EN, "/Dt").render(Dsl.dateTime(dtM.date(D), dtM.time(T)).expression())
    private val dtDef = ModelDef(
        listOf(
            FieldDef(D, Kind.DATE, format = "yyyy-MM-dd"),
            FieldDef(T, Kind.TIME, format = "HH:mm:ss"),
            FieldDef(STAMP, Kind.DATE_TIME, format = "yyyy-MM-dd'T'HH:mm:ss"),
        ),
        emptyList(),
        computations = listOf(ComputationDef(STAMP, "/Dt", dtOp)),
    )
    private val dtKModel = RuntimeLaws.withComputation(dtJson, "/Dt", "StampComp", "../Stamp", dtOp)

    @Test fun computedDateTimeUsesTargetFormatLikeKernel() {
        val kValue = RuntimeLaws.computedValue(RuntimeLaws.compute(dtKModel,
            RuntimeLaws.doc().group("/Dt", 1).field(D, "2024-06-30", 1, 1).field(T, "10:30:00", 1, 1)), STAMP).orElse(null)
        val iValue = DmInterpreter(dtDef).compute(Document(dtDef.kinds, dtDef.declaredReps, dtDef.unsignedFields,
            dtDef.rangeConfig, dtDef.dateFormats, dtDef.scales, dtDef.dateTimeFormats, timeFormats = dtDef.timeFormats)
            .group("/Dt", 1).field(D, "2024-06-30", 1, 1).field(T, "10:30:00", 1, 1))
            .firstOrNull { it.path == STAMP }?.value
        assertEquals(kValue, iValue, "DateTime(D,T) into a dd.MM.yyyy HH:mm:ss target — kernel=$kValue interp=$iValue")
    }

    private companion object {
        const val ORDER = "/Cal/OrderDate"
        const val MAT = "/Cal/Maturity"
        const val D = "/Dt/D"
        const val T = "/Dt/T"
        const val STAMP = "/Dt/Stamp"
    }
}
