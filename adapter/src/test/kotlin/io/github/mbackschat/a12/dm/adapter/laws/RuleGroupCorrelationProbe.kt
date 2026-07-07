package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Test

/** THROWAWAY probe (IG42 drill) — rendered $-Having surface + kernel/interp fire counts for the RuleGroup idiom. */
class RuleGroupCorrelationProbe {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val m = BoundModel.ofJson(order)

    @Test fun printRenderedControlForm() {
        val items = m.group("/Order/Items")
        val corrLess = Dsl.atLeastOneFieldFilled(m.flatten("/Order/Items*/Count").having(
            Dsl.currentRepetition(items).ne(Dsl.currentRepetition(items, true))
                .and(m.num("/Order/Items/Count").lt(m.num("/Order/Items/Count").outer()))))
        println("RENDERED-CONTROL: " + DslRenderer().render(corrLess))
    }

    private fun kFires(cond: String, counts: List<String?>): Any {
        return try {
            val doc = RuntimeLaws.doc().group("/Order", 1).field("/Order/CustomerName", "Acme", 1, 1)
            counts.forEachIndexed { i, c ->
                val r = i + 1
                doc.group("/Order/Items", 1, r).field("/Order/Items/Sku", ('A' + i).toString(), 1, r, 1)
                if (c != null) doc.field("/Order/Items/Count", c, 1, r, 1)
            }
            val model = RuntimeLaws.withRule(order, "/Order/Items", "Corr", "../Count", "CORR", cond)
            RuntimeLaws.messages(model, doc).count { it.code() == "CORR" }
        } catch (e: Exception) { "KERNEL-ERR: ${e.message?.take(400)}" }
    }

    private fun iFires(cond: String, counts: List<String?>): Any {
        return try {
            val model = RuntimeLaws.withRule(order, "/Order/Items", "Corr", "../Count", "CORR", cond)
            val def = ModelLoader.load(model)
            val doc = Document(def.kinds, def.declaredReps, def.unsignedFields, def.rangeConfig, def.dateFormats, def.scales)
            counts.forEachIndexed { i, c ->
                val r = i + 1
                doc.field("/Order/Items/Sku", ('A' + i).toString(), 1, r, 1)
                if (c != null) doc.field("/Order/Items/Count", c, 1, r, 1)
            }
            val out = DmInterpreter(def).validateFull(doc)
            val fires = out.count { it.code == "CORR" }
            "$fires"
        } catch (e: Exception) { "INTERP-ERR: ${e.message?.take(400)}" }
    }

    private fun fires(cond: String, field: String, values: List<String?>): Pair<Any, Any> {
        val model = try { RuntimeLaws.withRule(order, "/Order/Items", "Corr", "../Count", "CORR", cond) }
            catch (e: Exception) { return "MODEL-ERR: ${e.message?.take(400)}" to "-" }
        val k = try {
            val doc = RuntimeLaws.doc().group("/Order", 1)
            values.forEachIndexed { i, v ->
                val r = i + 1
                doc.group("/Order/Items", 1, r)
                if (v != null) doc.field(field, v, 1, r, 1)
            }
            RuntimeLaws.messages(model, doc).count { it.code() == "CORR" }
        } catch (e: Exception) { "KERNEL-ERR: ${e.message?.take(400)}" }
        val i = try {
            val def = ModelLoader.load(model)
            val doc = Document(def.kinds, def.declaredReps, def.unsignedFields, def.rangeConfig, def.dateFormats, def.scales)
            values.forEachIndexed { idx, v ->
                val r = idx + 1
                doc.group("/Order/Items", 1, r)
                if (v != null) doc.field(field, v, 1, r, 1)
            }
            val out = DmInterpreter(def).validateFull(doc)
            "${out.count { it.code == "CORR" }}"
        } catch (e: Exception) { "INTERP-ERR: ${e.message?.take(400)}" }
        return k to i
    }

    @Test fun probeDateVariants() {
        val self = "CurrentRepetition(RuleGroup) != CurrentRepetition(\$RuleGroup)"
        val rangeOverlap = "GroupFilled(RuleGroup) And AtLeastOneFieldFilled(../Items*/ItemCoverage Having " +
            "($self And [ItemCoverage@From] <= [\$ItemCoverage@To] And [ItemCoverage@To] >= [\$ItemCoverage@From]))"
        val dateSimple = "GroupFilled(RuleGroup) And AtLeastOneFieldFilled(../Items*/PromiseDate Having " +
            "($self And [PromiseDate] <= [\$PromiseDate]))"
        val cov = listOf<String?>("01.01.2026-31.01.2026", "15.01.2026-15.02.2026", "01.03.2026-31.03.2026")
        val dates = listOf<String?>("2026-01-10", "2026-01-05", "2026-02-01")
        val (k1, i1) = fires(rangeOverlap, "/Order/Items/ItemCoverage", cov)
        println("== rangeOverlap: kernel=$k1  interp=$i1")
        val (k2, i2) = fires(dateSimple, "/Order/Items/PromiseDate", dates)
        println("== dateSimple: kernel=$k2  interp=$i2")
    }

    @Test fun probeOverlapOperator() {
        val self = "CurrentRepetition(RuleGroup) != CurrentRepetition(\$RuleGroup)"
        val cov = listOf<String?>("01.01.2026-31.01.2026", "15.01.2026-15.02.2026", "01.03.2026-31.03.2026")
        val withHaving = "GroupFilled(RuleGroup) And DateRangesOverlap(ItemCoverage, ../Items*/ItemCoverage Having ($self))"
        val noHaving = "GroupFilled(RuleGroup) And DateRangesOverlap(ItemCoverage, ../Items*/ItemCoverage)"
        val (k1, i1) = fires(withHaving, "/Order/Items/ItemCoverage", cov)
        println("== overlapHaving: kernel=$k1  interp=$i1")
        val (k2, i2) = fires(noHaving, "/Order/Items/ItemCoverage", cov)
        println("== overlapNoHaving: kernel=$k2  interp=$i2")
    }

    @Test fun probeHavingAppliedOrDropped() {
        val cov = listOf<String?>("01.01.2026-31.01.2026", "15.01.2026-15.02.2026", "01.03.2026-31.03.2026")
        // an always-FALSE filter: if the kernel APPLIES the Having on a predicate arg -> zero cells -> 0 fires;
        // if it DROPS the filter -> self-overlap fires every filled row -> 3.
        val alwaysFalse = "GroupFilled(RuleGroup) And DateRangesOverlap(ItemCoverage, ../Items*/ItemCoverage Having " +
            "(CurrentRepetition(RuleGroup) != CurrentRepetition(RuleGroup)))"
        val (k, i) = fires(alwaysFalse, "/Order/Items/ItemCoverage", cov)
        println("== alwaysFalseHaving: kernel=$k  interp=$i")
        // the self-exclusion variant, java-static leg included via the tri seam
        val withHaving = "GroupFilled(RuleGroup) And DateRangesOverlap(ItemCoverage, ../Items*/ItemCoverage Having " +
            "(CurrentRepetition(RuleGroup) != CurrentRepetition(\$RuleGroup)))"
        val model = RuntimeLaws.withRule(order, "/Order/Items", "Corr", "../Count", "CORR", withHaving)
        val doc = RuntimeLaws.doc().group("/Order", 1)
        cov.forEachIndexed { idx, v ->
            val r = idx + 1
            doc.group("/Order/Items", 1, r)
            if (v != null) doc.field("/Order/Items/ItemCoverage", v, 1, r, 1)
        }
        val engine = RuntimeLaws.kernelJavaEngine(model)
        val js = engine.messages(engine.instance(doc)).count { it.code() == "CORR" }
        println("== withHaving java-static=$js")
    }

    @Test fun probeFilterBinding() {
        val cov = listOf<String?>("01.01.2026-31.01.2026", "15.01.2026-15.02.2026", "01.03.2026-31.03.2026")
        // self-INCLUSION: if $ binds the outer row -> kept={self} -> no pair -> 0; if unknown-keeps -> 3
        val selfIncl = "GroupFilled(RuleGroup) And DateRangesOverlap(ItemCoverage, ../Items*/ItemCoverage Having " +
            "(CurrentRepetition(RuleGroup) == CurrentRepetition(\$RuleGroup)))"
        val (k1, i1) = fires(selfIncl, "/Order/Items/ItemCoverage", cov)
        println("== selfInclusionFilter: kernel=$k1  interp=$i1")
        // constant-index filter (no $): keeps only row 1 -> single range -> no pair -> expect 0 if applied
        val onlyRow1 = "GroupFilled(RuleGroup) And DateRangesOverlap(ItemCoverage, ../Items*/ItemCoverage Having " +
            "(CurrentRepetition(RuleGroup) == 1))"
        val (k2, i2) = fires(onlyRow1, "/Order/Items/ItemCoverage", cov)
        println("== onlyRow1Filter: kernel=$k2  interp=$i2")
        // keeps rows 1+2 (the overlapping pair) -> expect fire (3 rows fire? any-pair -> every row fires) if applied
        val rows12 = "GroupFilled(RuleGroup) And DateRangesOverlap(ItemCoverage, ../Items*/ItemCoverage Having " +
            "(CurrentRepetition(RuleGroup) <= 2))"
        val (k3, i3) = fires(rows12, "/Order/Items/ItemCoverage", cov)
        println("== rows12Filter: kernel=$k3  interp=$i3")
    }

    @Test fun probeDiscriminators() {
        val cov = listOf<String?>("01.01.2026-31.01.2026", "15.01.2026-15.02.2026", "01.03.2026-31.03.2026")
        // FALSE conjunct + a $-term: if $-presence drops the WHOLE filter -> keeps all -> 3; if evaluated per-cell
        // (FALSE And UNKNOWN = not-TRUE -> dropped cells) -> {arg0} only -> no pair -> 0.
        val falseAndOuter = "GroupFilled(RuleGroup) And DateRangesOverlap(ItemCoverage, ../Items*/ItemCoverage Having " +
            "(CurrentRepetition(RuleGroup) != CurrentRepetition(RuleGroup) And CurrentRepetition(RuleGroup) == CurrentRepetition(\$RuleGroup)))"
        val (k1, i1) = fires(falseAndOuter, "/Order/Items/ItemCoverage", cov)
        println("== falseAndOuterFilter: kernel=$k1  interp=$i1")
        // predicted SECOND INSTANCE (catalog shape, rule on /Order): arg0 disjoint from all items, but two
        // list cells overlap each other -> any-pair fires, arg0-vs-any does not.
        val model2 = RuntimeLaws.withRule(order, "/Order", "Corr2", "../CoverageWindow", "CORR2",
            "FieldFilled(CustomerName) And DateRangesOverlap(CoverageWindow, Items*/ItemCoverage)")
        val doc2 = RuntimeLaws.doc().group("/Order", 1)
            .field("/Order/CustomerName", "Acme", 1, 1)
            .field("/Order/CoverageWindow", "01.06.2026-30.06.2026", 1, 1)
        cov.forEachIndexed { idx, v ->
            val r = idx + 1
            doc2.group("/Order/Items", 1, r)
            if (v != null) doc2.field("/Order/Items/ItemCoverage", v, 1, r, 1)
        }
        val k2 = RuntimeLaws.messages(model2, doc2).count { it.code() == "CORR2" }
        val def2 = ModelLoader.load(model2)
        val idoc2 = Document(def2.kinds, def2.declaredReps, def2.unsignedFields, def2.rangeConfig, def2.dateFormats, def2.scales)
        idoc2.field("/Order/CustomerName", "Acme", 1, 1)
        idoc2.field("/Order/CoverageWindow", "01.06.2026-30.06.2026", 1, 1)
        cov.forEachIndexed { idx, v ->
            val r = idx + 1
            idoc2.group("/Order/Items", 1, r)
            if (v != null) idoc2.field("/Order/Items/ItemCoverage", v, 1, r, 1)
        }
        val i2 = DmInterpreter(def2).validateFull(idoc2).count { it.code == "CORR2" }
        println("== secondInstanceListPair: kernel=$k2  interp=$i2")
    }

    @Test fun probeOverlapInsideHaving() {
        val self = "CurrentRepetition(RuleGroup) != CurrentRepetition(\$RuleGroup)"
        val cov = listOf<String?>("01.01.2026-31.01.2026", "15.01.2026-15.02.2026", "01.03.2026-31.03.2026")
        val opForm = "GroupFilled(RuleGroup) And AtLeastOneFieldFilled(../Items*/ItemCoverage Having " +
            "($self And DateRangesOverlap(ItemCoverage, \$ItemCoverage)))"
        val startEndForm = "GroupFilled(RuleGroup) And AtLeastOneFieldFilled(../Items*/ItemCoverage Having " +
            "($self And StartOfDateRange(ItemCoverage) <= EndOfDateRange(\$ItemCoverage) " +
            "And EndOfDateRange(ItemCoverage) >= StartOfDateRange(\$ItemCoverage)))"
        val (k1, i1) = fires(opForm, "/Order/Items/ItemCoverage", cov)
        println("== insideHavingOp: kernel=$k1  interp=$i1")
        val (k2, i2) = fires(startEndForm, "/Order/Items/ItemCoverage", cov)
        println("== insideHavingStartEnd: kernel=$k2  interp=$i2")
    }

    @Test fun probeVariants() {
        val counts = listOf<String?>("5", "6", "9")
        // control: group-name self-exclusion (the locked form), rule ON the repeatable group
        val control = "AtLeastOneFieldFilled(../Items*/Count Having (CurrentRepetition(../Items) != CurrentRepetition(\$../Items) And [Count] < [\$Count]))"
        // the IG42 idiom: RuleGroup keyword self-exclusion
        val idiom = "AtLeastOneFieldFilled(../Items*/Count Having (CurrentRepetition(RuleGroup) != CurrentRepetition(\$RuleGroup) And [Count] < [\$Count]))"
        // guarded variants — the BA-mandated per-row guard for a rule ON the iterated group (IF63 ground)
        val controlG = "GroupFilled(RuleGroup) And $control"
        val idiomG = "GroupFilled(RuleGroup) And $idiom"
        for ((name, cond) in listOf("control" to control, "idiom" to idiom, "controlG" to controlG, "idiomG" to idiomG)) {
            println("== $name: $cond")
            println("   kernel=${kFires(cond, counts)}  interp=${iFires(cond, counts)}")
        }
    }
}
