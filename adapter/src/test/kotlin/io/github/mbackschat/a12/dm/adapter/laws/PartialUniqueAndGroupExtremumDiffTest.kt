package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws.Relevant
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.readDocumentJson
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.Locale

/**
 * Tri-engine differential — **partial validation of `FieldValuesNotUnique` and of every consumer of an UNSTARRED
 * group operand** (KERNEL-FINDINGS KF623, a12-kernel-lean SPEC-2026-10-26-06).
 *
 * Two contracts, each measured on both kernel strategies:
 *  - `FieldValuesNotUnique` judges no extent: it fires exactly when a duplicate exists among the RELEVANT cells,
 *    and a cell outside the coverage is skipped wherever it sits, filled or empty. So the coverage `{1, 3}` fires
 *    over `4, 5, 4` and stays silent over `4, 4, 5`, and listing the held rows concretely is as good as a wildcard.
 *  - A non-repeatable group named as an operand (`Sum(/Order/Totals)`, `AllFieldsFilled(/Order/Totals)`, …) is
 *    read field by field, exactly as the same operator reads the field list it expands to: each consumer asks its
 *    own question of the contained cells. So `MinValue` over a group with only `Net = 1` relevant stays silent,
 *    while `AtLeastOneFieldFilled` over the same coverage fires, and the explicit field-list forms (`MinF`,
 *    `DupF`) answer row for row as their group forms do.
 *
 * The interpreter once ignored the coverage on both routes, so every rule here fired under every coverage.
 * Full validation fires every rule, which makes each silence below a relevance fact rather than a false
 * condition, and the error field `Ref` is relevant in every coverage, so the rule-run gate never silences one.
 *
 * Own domain (an order with totals, product codes and lines).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PartialUniqueAndGroupExtremumDiffTest {

    private val base = DocumentModelDraft.named("order-partial-unique").locale("en_US")
        .rootGroup("Order") { o -> o.stringField("Ref") { }
            .group("Totals") { t -> t.numberField("Net") { it.fractionDigits(0) }.numberField("Tax") { it.fractionDigits(0) } }
            .group("Codes") { c -> c.numberField("X") { it.fractionDigits(0) }.numberField("Y") { it.fractionDigits(0) } }
            .group("Lines") { l -> l.repeatable(4).numberField("Qty") { it.fractionDigits(0) } } }
        .build().toJson()

    /** `Totals` holds `Net = 1, Tax = 5` and `Codes` holds `X = Y = 7` in every document, so each condition holds. */
    private val groupRules = linkedMapOf(
        "Min" to "MinValue(/Order/Totals) < 3",
        "MinF" to "MinValue(/Order/Totals/Net, /Order/Totals/Tax) < 3",
        "MaxG" to "MaxValue(/Order/Totals) > 4",
        "SumG" to "Sum(/Order/Totals) > 5",
        "Ndv" to "NumberOfDifferentValues(/Order/Totals) == 2",
        "NffG" to "NumberOfFilledFields(/Order/Totals) == 2",
        "NvifG" to "NumberOfValueInFields(1 In /Order/Totals) == 1",
        "AllG" to "AllFieldsFilled(/Order/Totals)",
        "FillG" to "AtLeastOneFieldFilled(/Order/Totals)",
        "DupF" to "FieldValuesNotUnique(/Order/Codes/X, /Order/Codes/Y)",
        "DupG" to "FieldValuesNotUnique(/Order/Codes)",
    )

    private val model = (groupRules + ("Dup" to "FieldValuesNotUnique(/Order/Lines*/Qty)")).entries.fold(base) { json, (code, c) ->
        RuntimeLaws.withRule(json, "/Order", "${code}Rule", "../Ref", code, "FieldFilled(/Order/Ref) And $c")
    }

    private fun document(vararg qty: Int?) = """{"Order":{"Ref":"R","Totals":{"Net":1,"Tax":5},"Codes":{"X":7,"Y":7},""" +
        """"Lines":[${qty.joinToString(",") { if (it == null) "{}" else """{"Qty":$it}""" }}]}}"""

    private val ref = "/Order/Ref" to listOf(0, 0)
    private fun qty(vararg rows: Int) = listOf(ref) + rows.map { "/Order/Lines/Qty" to listOf(0, it, 0) }
    private fun fields(group: String, vararg names: String) = listOf(ref) + names.map { "/Order/$group/$it" to listOf(0, 0, 0) }

    /** The codes each engine fires — dynamic Groovy, static Java, the interpreter — with `null` naming full validation. */
    private fun fires(document: String, coverage: List<Pair<String, List<Int>>>?): List<Set<String>> {
        val dynamic = RuntimeLaws.kernelEngine(model)
        val static = RuntimeLaws.kernelJavaEngine(model)
        val def = ModelLoader.load(model)
        val interpreted = if (coverage == null) DmInterpreter(def).validate(def.readDocumentJson(document))
            else DmInterpreter(def).validatePart(def.readDocumentJson(document),
                coverage.mapTo(HashSet()) { (path, reps) -> RelevantEntity(path, reps) })
        assertEquals(emptyList<Any>(), interpreted.unsupported, "declined, not evaluated")
        val kernelRelevant = coverage?.mapTo(HashSet()) { (path, reps) -> Relevant(path, reps) }
        return listOf(
            if (kernelRelevant == null) dynamic.validationMessages(dynamic.instance(document), null, Locale.US)
                else dynamic.partialValidationMessages(dynamic.instance(document), kernelRelevant, null, Locale.US),
            if (kernelRelevant == null) static.validationMessages(static.instance(document), null, Locale.US)
                else static.partialValidationMessages(static.instance(document), kernelRelevant, null, Locale.US),
        ).map { messages -> messages.mapTo(sortedSetOf()) { it.errorCode } } + listOf(interpreted.messages.mapTo(sortedSetOf()) { it.code })
    }

    private fun assertFires(expected: Set<String>, family: Set<String>, document: String,
            coverage: List<Pair<String, List<Int>>>?) {
        val label = coverage?.toString() ?: "full validation"
        val (dynamic, static, interpreted) = fires(document, coverage)
        assertEquals(expected, dynamic.intersect(family), "dynamic-Groovy — $label")
        assertEquals(expected, static.intersect(family), "static-Java — $label")
        assertEquals(expected, interpreted.intersect(family), "INTERPRETER — $label")
    }

    @Test
    fun aDuplicateIsDecidedAmongTheRelevantCellsAlone() {
        val dup = setOf("Dup")
        // An irrelevant cell between the duplicates is skipped, so `4, 5, 4` and `4, empty, 4` answer alike.
        val split = listOf(null to dup, qty(0) to dup, qty(1, 2) to emptySet(), qty(1, 3) to dup,
            qty(1, 2, 3) to dup, qty(1) to emptySet())
        val expected = linkedMapOf(
            document(4, 4, 5) to listOf(null to dup, qty(0) to dup, qty(1, 2) to dup, qty(1, 3) to emptySet(),
                qty(1, 2, 3) to dup, qty(1) to emptySet()),
            document(4, 5, 4) to split,
            document(4, null, 4) to split,
        )
        for ((document, rows) in expected) for ((coverage, fires) in rows) assertFires(fires, dup, document, coverage)
    }

    @Test
    fun anUnstarredGroupOperandIsReadFieldByField() {
        val family = groupRules.keys
        val decided = setOf("AllG", "FillG", "MaxG", "Min", "MinF", "Ndv", "NffG", "NvifG", "SumG")
        val expected = listOf(
            null to family,
            fields("Totals", "Net") to setOf("FillG"),
            fields("Totals", "Tax") to setOf("FillG"),
            fields("Totals", "Net", "Tax") to decided,
            listOf(ref, "/Order/Totals" to listOf(0, 0)) to decided,
            fields("Codes", "X") to emptySet(),
            fields("Codes", "X", "Y") to setOf("DupF", "DupG"),
            qty(0) to emptySet(),
        )
        for ((coverage, fires) in expected) assertFires(fires, family, document(4, 4, 5), coverage)
    }
}
