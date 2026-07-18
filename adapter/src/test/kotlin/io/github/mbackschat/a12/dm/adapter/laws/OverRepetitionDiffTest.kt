package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Differential — the STRUCTURAL over-repetition check: a repeatable group with **more rows than its declared
 * `repeatability`** is flagged by `validateFull` (`zuGrosseZeile` on the innermost/row level, `zuGrosseKontextnummer`
 * on an intermediate repeatable level — KERNEL-FINDINGS "over-repetition errors"), and the over-limit rows'
 * ordinary checks are suppressed. Models `/Order/Lines` (repeatability 3) + a nested `/Order/Lines/Parts`
 * (repeatability 2); compares the (code @ field-pointer) set so both the firing AND the per-instance attribution match.
 */
class OverRepetitionDiffTest {

    private val json = DocumentModelDraft.named("form").locale("en_US")
        .rootGroup("Order", Consumer { g ->
            g.group("Lines", Consumer<GroupBuilder> { l ->
                l.repeatable(3).field("Qty", FieldKind.NUMBER)
                    .group("Parts", Consumer<GroupBuilder> { p -> p.repeatable(2).field("Cost", FieldKind.NUMBER) })
            })
        })
        .build().toJson()

    private val def = ModelDef(
        listOf(FieldDef(QTY, Kind.NUMBER), FieldDef(COST, Kind.NUMBER)),
        emptyList(),
        repeatableGroups = setOf(LINES, PARTS),
        declaredReps = mapOf(LINES to 3, PARTS to 2),
    )

    private inner class Build {
        val kDoc = RuntimeLaws.doc().group("/Order", 1)
        val iDoc = def.newDocument().group("/Order", 1)
        fun line(i: Int) { kDoc.group(LINES, 1, i); iDoc.group(LINES, 1, i); kDoc.field(QTY, "5", 1, i, 1); iDoc.field(QTY, "5", 1, i, 1) }
        fun part(li: Int, pi: Int) { kDoc.group(PARTS, 1, li, pi); iDoc.group(PARTS, 1, li, pi); kDoc.field(COST, "7", 1, li, pi, 1); iDoc.field(COST, "7", 1, li, pi, 1) }
    }

    private fun compare(label: String, build: Build.() -> Unit) {
        val b = Build().apply(build)
        val codes = setOf("zuGrosseZeile", "zuGrosseKontextnummer", "zuGrosseLaufendeNr")
        fun sigs(cs: List<Pair<String, String>>) = cs.map { "${it.first}@${it.second}" }.sorted()
        val kernel = sigs(RuntimeLaws.messages(json, b.kDoc).filter { it.code() in codes }.map { it.code() to it.field() })
        val interp = sigs(DmInterpreter(def).validate(b.iDoc).messages.filter { it.code in codes }.map { it.code to it.errorPath })
        assertEquals(kernel, interp, "$label  kernel=$kernel interp=$interp")
    }

    @Test fun withinLimitFiresNothing() = compare("3 lines / max 3") { line(1); line(2); line(3) }
    @Test fun overLimitLinesFlagged() = compare("5 lines / max 3") { line(1); line(2); line(3); line(4); line(5) }
    @Test fun overLimitNestedParts() = compare("line 1 with 4 parts / max 2") {
        line(1); part(1, 1); part(1, 2); part(1, 3); part(1, 4)
    }

    /** Over-limit rows SUPPRESS their ordinary checks: a per-Line rule fires within the cap but not on the
     *  over-limit rows (which carry only the over-repetition codes). Compares ALL message (code @ pointer) pairs.
     *  The rule is authored as a typed rulekit [io.github.mbackschat.a12.dm.rulekit.dsl.Rule] (error field + condition
     *  are refs) and fed to both engines via [TypedRules] — no hand-written path/condition strings (dogfood). */
    @Test fun overLimitRowsSuppressTheirRules() {
        val m = BoundModel.ofJson(json)
        val qty = m.num(QTY)
        val rule = m.rule(m.group(LINES)).name("R").code("R").errorField(qty).`when`(qty.gt(0))
            .severity(Severity.ERROR).message(Dsl.en("Qty is positive")).build()
        val ruleModel = TypedRules.inject(json, rule)
        val rdef = def.copy(rules = listOf(TypedRules.def(rule)))
        val b = Build().apply { line(1); line(2); line(3); line(4); line(5) }   // rows 4,5 over max 3
        fun sigs(cs: List<Pair<String, String>>) = cs.map { "${it.first}@${it.second}" }.sorted()
        val kernel = sigs(RuntimeLaws.messages(ruleModel, b.kDoc).map { it.code() to it.field() })
        val interp = sigs(DmInterpreter(rdef).validate(b.iDoc).messages.map { it.code to it.errorPath })
        assertEquals(kernel, interp, "rule fires within cap, suppressed on over-limit rows  kernel=$kernel interp=$interp")
    }

    /** Over-limit rows also suppress the auto MANDATORY + UNIQUE-INDEX checks: an index field (auto-required +
     *  unique) empty/duplicated on an over-limit row would fire `mandatoryField`/`uniqueIndex` within the cap, but
     *  on over-limit rows only the over-repetition codes fire. Index-field model; compares ALL (code @ pointer). */
    @Test fun overLimitRowsSuppressMandatoryAndUnique() {
        val idxJson = DocumentModelDraft.named("form").locale("en_US")
            .rootGroup("Order", Consumer { g ->
                g.group("Lines", Consumer<GroupBuilder> { it.repeatable(3).field("Key", FieldKind.STRING).indexField("Key") })
            }).build().toJson()
        val idxDef = ModelDef(
            listOf(FieldDef(KEY, Kind.STRING)), emptyList(),
            repeatableGroups = setOf(LINES), declaredReps = mapOf(LINES to 3), indexFields = setOf(KEY),
        )
        val kDoc = RuntimeLaws.doc().group("/Order", 1)
        val iDoc = idxDef.newDocument().group("/Order", 1)
        fun row(i: Int, key: String?) {
            kDoc.group(LINES, 1, i); iDoc.group(LINES, 1, i)
            if (key != null) { kDoc.field(KEY, key, 1, i, 1); iDoc.field(KEY, key, 1, i, 1) }
        }
        row(1, "A"); row(2, "B"); row(3, "C")   // within cap: unique, filled → no mandatory/unique
        row(4, null)                            // over cap, empty → would be mandatoryField, suppressed
        row(5, "A")                             // over cap, dup of row 1 → would be uniqueIndex, suppressed
        fun sigs(cs: List<Pair<String, String>>) = cs.map { "${it.first}@${it.second}" }.sorted()
        val kernel = sigs(RuntimeLaws.messages(idxJson, kDoc).map { it.code() to it.field() })
        val interp = sigs(DmInterpreter(idxDef).validate(iDoc).messages.map { it.code to it.errorPath })
        assertEquals(kernel, interp, "over-limit rows suppress mandatory+unique  kernel=$kernel interp=$interp")
    }

    private companion object {
        const val LINES = "/Order/Lines"
        const val PARTS = "/Order/Lines/Parts"
        const val QTY = "/Order/Lines/Qty"
        const val COST = "/Order/Lines/Parts/Cost"
        const val KEY = "/Order/Lines/Key"
    }
}
