package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.md.document.api.IEntityInstance
import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws.Relevant
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.CustomCondition
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Differential — a `CustomCondition` receives the partial-validation **relevant set** (IG10). The kernel passes
 * `ICustomCondition.check` the relevant entities (null for full, the relevant subset for partial —
 * `MainValidatorController` from `getAllPruefungsrelevanteEntitiesExtern`), so a relevance-aware condition can
 * return false when its fields are not relevant. The interpreter's SPI already has the `relevant` parameter but
 * hard-coded `null`, so a partial-mode condition behaved as if full — diverging.
 *
 * `/Sale { CustomerName (error field), Region }`, rule `FieldFilled(CustomerName) And CustomCondition(RegionInScope)`.
 * Both engines register the SAME logic: *fires iff `Region` is in the relevant set (or full)*. With only
 * `CustomerName` relevant the kernel feeds the condition `{CustomerName}` → `Region` absent → no fire; the
 * interpreter must feed the same set (not null). With `Region` also relevant both fire. KERNEL-FINDINGS
 * "Partial validation".
 */
class PartialValidationCustomConditionDiffTest {

    private val json = DocumentModelDraft.named("sale").locale("en_US")
        .rootGroup("Sale", Consumer { g -> g.field("CustomerName", FieldKind.STRING).field("Region", FieldKind.STRING) })
        .build().toJson()

    private val m = BoundModel.ofJson(json)
    private val cond = DslRenderer().render(m.field(NAME).filled().and(Dsl.customCondition("RegionInScope")))
    private val kModel = RuntimeLaws.withRule(json, SALE, "R", "../CustomerName", CODE, cond)
    private val def = ModelDef(
        listOf(FieldDef(NAME, Kind.STRING), FieldDef(REGION, Kind.STRING)),
        listOf(RuleDef("$SALE/R", SALE, cond, CODE, "../CustomerName")),
    )

    /** Kernel side — fires iff `Region` is in the relevant set (or it's a full validation, relevant == null). */
    private val mdRegionInScope = object : com.mgmtp.a12.kernel.md.rt.api.ICustomCondition {
        override fun check(
            doc: com.mgmtp.a12.kernel.md.document.api.IDocument,
            relevant: Set<IEntityInstance>?,
            formallyIncorrect: Set<IEntityInstance>,
            errorEntity: IEntityInstance,
        ): Boolean = relevant == null || relevant.any { it.path == REGION }
    }

    /** Interpreter side — the SAME logic over the cross-platform SPI's `relevant: Set<CustomConditionFieldPointer>?`. */
    private val interpRegionInScope = CustomCondition { _, relevant, _, _ ->
        relevant == null || relevant.any { it.path == REGION }
    }

    private fun kDoc() = RuntimeLaws.doc().group(SALE, 1).field(NAME, "Acme", 1, 1).field(REGION, "EU", 1, 1)
    private fun iDoc() = Document(def.kinds).group(SALE, 1).field(NAME, "Acme", 1, 1).field(REGION, "EU", 1, 1)

    private fun kFires(rel: Set<Relevant>) =
        RuntimeLaws.messagesPart(kModel, kDoc(), rel, mapOf("RegionInScope" to mdRegionInScope)).any { it.code == CODE }
    private fun iFires(rel: Set<RelevantEntity>) =
        DmInterpreter(def, customConditions = mapOf("RegionInScope" to interpRegionInScope))
            .validatePart(iDoc(), rel).messages.any { it.code == CODE }

    /** IG10: only `CustomerName` relevant → the condition sees `Region` is NOT relevant → no fire. The
     *  interpreter must pass the same relevant set (before the fix it passed null → fired). */
    @Test fun regionNotRelevantSuppressesLikeKernel() {
        val kRel = setOf(Relevant.of(NAME, 0, 0))
        val iRel = setOf(RelevantEntity.anyRep(NAME))
        val k = kFires(kRel)
        val i = iFires(iRel)
        assertEquals(false, k, "kernel: Region not relevant → condition false → no fire")
        assertEquals(k, i, "Region-not-relevant — kernel=$k interp=$i")
    }

    /** Control: `Region` also relevant → the condition sees it → both fire. */
    @Test fun regionRelevantFiresLikeKernel() {
        val kRel = setOf(Relevant.of(NAME, 0, 0), Relevant.of(REGION, 0, 0))
        val iRel = setOf(RelevantEntity.anyRep(NAME), RelevantEntity.anyRep(REGION))
        val k = kFires(kRel)
        val i = iFires(iRel)
        assertEquals(true, k, "kernel: Region relevant → condition true → fires")
        assertEquals(k, i, "Region-relevant — kernel=$k interp=$i")
    }

    private companion object {
        const val SALE = "/Sale"
        const val NAME = "/Sale/CustomerName"
        const val REGION = "/Sale/Region"
        const val CODE = "REGION_RULE"
    }
}
