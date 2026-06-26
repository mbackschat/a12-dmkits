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
 * Differential — does `compute()` render a DATE result in the TARGET field's declared format (IG19)? `OrderDate`
 * is `yyyy-MM-dd`; `Maturity` is `dd.MM.yyyy`. The computed `AddYears(OrderDate, 2)` is stored into `Maturity` —
 * the question is whether the kernel emits the target-format text (`30.06.2026`) while the interpreter emits ISO.
 * Settles IG19 by comparing the computed value to the kernel's (no hardcoded expectation).
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

    private companion object {
        const val ORDER = "/Cal/OrderDate"
        const val MAT = "/Cal/Maturity"
    }
}
