package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — IF86 (graduated from IG55): an AGGREGATE / COUNT over a starred INDEX field with a
 * NOT-CHECK-RELEVANT cell is NON-EVALUABLE, so the referencing rule is SUPPRESSED. An index field's cell is
 * not-check-relevant when it is a mandatory-EMPTY (or absent) cell of the auto-required index field, OR its value
 * is DUPLICATED (the `uniqueIndex` error) — the kernel's field-local index errors (IF15/IF60). The kernel's shared
 * aggregate loop `combineFeldListe` (Sum / MinValue / MaxValue / NumberOfDifferentValues) and the count
 * `anzahlAngegebenerFelder` (NumberOfFilledFields) return NICHT_PRUEF_REL on the FIRST such cell → the whole
 * result is non-evaluable → no fire; the fill quantifiers (`isWertAngegeben` → UNKNOWN) count it in NEITHER `t`
 * nor `f` → `AtLeastOneFieldFilled` stops firing when EVERY cell is not-check-relevant (a fully-duplicated index).
 * The interpreter formerly dropped an empty index cell and counted a duplicated one, so it FIRED where the kernel
 * suppressed (the earlier NDV locks compared at `== 2`/`>= 2`, blind to the "1 vs suppressed" distinction).
 *
 * Both kernel strategies share the runtime `combineFeldListe` / `anzahlAngegebenerFelder` / `isWertAngegeben`
 * ⟹ codegen-parity (static-JAVA tri-checked). Supersedes the quarantined `NdvIndexFieldEmptyCellDiffTest`.
 *
 * Own-domain (e-commerce order): line items keyed by an index field — `Sku` (string, for the string aggregates)
 * or `LineNo` (number, for the numeric aggregates) — under `/Order`, a rule referencing the sibling `Customer`
 * (so the error field is referenced — `MVK_ERROR_FIELD_NOT_REFERENCED`). Conditions are stored-form.
 */
class IndexAggregateDiffTest {

    // Index field = Sku (STRING): the string aggregates (NDV / NumberOfFilledFields) + the fill quantifiers.
    private val strBase = DocumentModelDraft.named("orderStr").locale("en_US")
        .rootGroup("Order") { o ->
            o.stringField("Customer") { }
            o.group("Lines") { g ->
                g.repeatable(20).stringField("Sku") { }.numberField("Qty") { it.fractionDigits(0) }.indexField("Sku")
            }
        }.build().toJson()

    // Index field = LineNo (NUMBER): the numeric value aggregates (Sum / MinValue / MaxValue) share `combineFeldListe`.
    private val numBase = DocumentModelDraft.named("orderNum").locale("en_US")
        .rootGroup("Order") { o ->
            o.stringField("Customer") { }
            o.group("Lines") { g -> g.repeatable(20).numberField("LineNo") { it.fractionDigits(0) }.indexField("LineNo") }
        }.build().toJson()

    private class Scenario(val kModel: String, val kDoc: RuntimeLaws.Doc, val interp: DmInterpreter, val iDoc: Document)

    /** A `/Order` doc with one `Lines` row per [cells] entry (null = an empty index cell) on BOTH engines. */
    private fun scenario(base: String, field: String, cond: String, cells: List<String?>): Scenario {
        val kModel = RuntimeLaws.withRule(base, "/Order", "R", "../Customer", CODE, "FieldFilled(Customer) And ($cond)")
        val def = ModelLoader.load(kModel)
        val kDoc = RuntimeLaws.doc().group("/Order", 1).field("/Order/Customer", "Acme", 1, 1)
        val iDoc = def.newDocument().apply { field("/Order/Customer", "Acme", 1, 1) }
        val path = "/Order/Lines/$field"
        cells.forEachIndexed { i, v ->
            val r = i + 1
            kDoc.group("/Order/Lines", 1, r); iDoc.group("/Order/Lines", 1, r)
            if (v == null) { kDoc.emptyField(path, 1, r, 1); iDoc.emptyField(path, 1, r, 1) }
            else { kDoc.field(path, v, 1, r, 1); iDoc.field(path, v, 1, r, 1) }
        }
        return Scenario(kModel, kDoc, DmInterpreter(def), iDoc)
    }

    private fun kSigs(s: Scenario) = RuntimeLaws.messages(s.kModel, s.kDoc).filter { it.code() == CODE }
        .map { "${it.field()}|${it.type().name}" }.sorted()
    private fun iSigs(s: Scenario) = s.interp.validateFull(s.iDoc).filter { it.code == CODE }
        .map { "${it.errorPath}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" }.sorted()
    private fun agree(s: Scenario, msg: String) = assertEquals(kSigs(s), iSigs(s), "$msg\n  kernel=${kSigs(s)}\n  interp=${iSigs(s)}")

    // ── value aggregate: NumberOfDifferentValues over the STRING index (kernel `combineFeldListe`) ─────────────
    private fun ndv(cells: List<String?>) = scenario(strBase, "Sku", "NumberOfDifferentValues(Lines*/Sku) >= 0", cells)
    @Test fun ndvCleanIndexFires() = agree(ndv(listOf("A", "B")), "NDV over a clean index → evaluable → fires")
    @Test fun ndvDuplicateIndexSuppressed() = agree(ndv(listOf("A", "A")), "NDV over a duplicated index → uniqueIndex not-check-relevant → suppressed")
    @Test fun ndvEmptyIndexCellSuppressed() = agree(ndv(listOf(null, "A")), "NDV over a mandatory-empty index cell → suppressed")

    // ── numeric value aggregates: Sum / MinValue / MaxValue over the NUMBER index (same `combineFeldListe`) ────
    private fun agg(op: String, cells: List<String?>) = scenario(numBase, "LineNo", "$op(Lines*/LineNo) >= 0", cells)
    @Test fun sumCleanFires() = agree(agg("Sum", listOf("1", "2")), "Sum over a clean index → fires")
    @Test fun sumDuplicateSuppressed() = agree(agg("Sum", listOf("5", "5")), "Sum over a duplicated index → suppressed")
    @Test fun sumEmptySuppressed() = agree(agg("Sum", listOf(null, "7")), "Sum over an empty index cell → suppressed")
    @Test fun minValueDuplicateSuppressed() = agree(agg("MinValue", listOf("5", "5")), "MinValue over a duplicated index → suppressed")
    @Test fun minValueEmptySuppressed() = agree(agg("MinValue", listOf(null, "7")), "MinValue over an empty index cell → suppressed")
    @Test fun maxValueDuplicateSuppressed() = agree(agg("MaxValue", listOf("5", "5")), "MaxValue over a duplicated index → suppressed")
    @Test fun maxValueEmptySuppressed() = agree(agg("MaxValue", listOf(null, "7")), "MaxValue over an empty index cell → suppressed")

    // ── count: NumberOfFilledFields (kernel `anzahlAngegebenerFelder`) over the index ─────────────────────────
    private fun nff(cells: List<String?>) = scenario(strBase, "Sku", "NumberOfFilledFields(Lines*/Sku) >= 0", cells)
    @Test fun numberOfFilledFieldsCleanFires() = agree(nff(listOf("A", "B")), "NumberOfFilledFields over a clean index → fires")
    @Test fun numberOfFilledFieldsDuplicateSuppressed() = agree(nff(listOf("A", "A")), "NumberOfFilledFields over a duplicated index → suppressed")
    @Test fun numberOfFilledFieldsEmptySuppressed() = agree(nff(listOf(null, "A")), "NumberOfFilledFields over an empty index cell → suppressed")

    // ── fill quantifier: AtLeastOneFieldFilled — the kernel skips a not-check-relevant cell ───────────────────
    private fun atLeastOne(cells: List<String?>) = scenario(strBase, "Sku", "AtLeastOneFieldFilled(Lines*/Sku)", cells)
    @Test fun atLeastOneCleanFires() = agree(atLeastOne(listOf("A", "B")), "AtLeastOne over a clean index → fires")
    @Test fun atLeastOneFullyDuplicatedNoFire() = agree(atLeastOne(listOf("A", "A")), "AtLeastOne over a fully-duplicated index → every cell not-check-relevant → no fire")
    @Test fun atLeastOneEmptyPlusPresentFires() = agree(atLeastOne(listOf(null, "A")), "AtLeastOne with an empty index cell + a present one → the present cell fires it")
    @Test fun atLeastOneAllEmptyNoFire() = agree(atLeastOne(listOf(null, null)), "AtLeastOne over all-empty index cells → not-check-relevant → no fire")

    // ── both strategies: the not-check-relevant suppression is runtime-shared; static-JAVA codegen must agree ──
    @Test fun codegenSuppressesIdentically() {
        for (s in listOf(ndv(listOf("A", "A")), ndv(listOf(null, "A")), agg("Sum", listOf("5", "5")),
                nff(listOf("A", "A")), atLeastOne(listOf("A", "A")), ndv(listOf("A", "B")))) {
            val groovy = kSigs(s)
            val engine = RuntimeLaws.kernelJavaEngine(s.kModel)
            val javaStatic = engine.messages(engine.instance(s.kDoc)).filter { it.code() == CODE }
                .map { "${it.field()}|${it.type().name}" }.sorted()
            assertEquals(groovy, iSigs(s), "codegen (interp≠groovy)\n  groovy=$groovy\n  interp=${iSigs(s)}")
            assertEquals(groovy, javaStatic, "codegen (java-static≠groovy)\n  groovy=$groovy\n  java=$javaStatic")
        }
    }
}

private const val CODE = "IDX"
