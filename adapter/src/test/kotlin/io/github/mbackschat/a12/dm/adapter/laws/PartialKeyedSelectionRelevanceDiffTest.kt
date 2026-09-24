package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.md.rt.api.IMessage
import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws.Relevant
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.readDocumentJson
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Tri-engine differential — **what partial validation asks of a keyed read, and of every cell
 * `FirstFilledValue` visits.** A keyed operand is judged at the coordinate its key resolves to: the selected
 * row's, or, for a key no row carries, the row at the kernel's unbound repetition (`IIdentifier.UNKNOWN`, the
 * `-5` a message pointer shows). A relevant entity covers that unbound row only by wildcarding the keyed level,
 * so listing every existing row concretely does not make a no-match decidable. `FirstFilledValue` treats a cell
 * outside the relevant set as undecided wherever it visits one, empty or filled, until the first filled cell
 * stops the walk.
 *
 * The grid is one model with one rule per consumer, so each coverage is a single `validatePart` per engine and
 * the coverage is the only variable between rows. Full validation fires every rule, which is what makes each
 * silence below a relevance fact rather than a false condition. Every rule's error field `Reference` is relevant
 * in every coverage, so the rule-run gate is never what silences it.
 *
 * Own domain (a parcel order). Conditions are raw because `GroupFilled` over a keyed group has no typed form yet
 * (DEV-API-GAPS DG67), and one spelling serves every row.
 */
class PartialKeyedSelectionRelevanceDiffTest {

    private companion object {
        const val DOC = """{"Order":{"Reference":"R","WantedNo":3,"PickNo":1,"Flag":9,""" +
            """"Extra":{"B":4},"Service":{"S2":"EXPRESS"},""" +
            """"Packages":[{"No":1,"Carrier":"DHL","Dims":{"Height":3}},{"No":2}]}}"""

        /** A key no row carries: `3` over the packages `1` and `2`. */
        val ABSENT = setOf("AG", "AD", "AF", "AV", "AX")

        /** A key selecting package 1, filled throughout, or package 2, which holds its key alone. */
        val SELECTED = setOf("SG", "SF", "SN", "SV", "SX")

        /** Keys read from a field, `WantedNo` (a miss) and `PickNo` (package 1). */
        val FIELD_KEYED = setOf("KG", "KF", "KS", "KV")

        /** `FirstFilledValue` over a bare group, a field list and a category list, and a presence control. */
        val UNKEYED = setOf("BX", "BF", "BB", "BC", "BN")

        /** A category read outside `FirstFilledValue`, the value-read sibling of `BC`. */
        val CATEGORY = setOf("CR")

        val RULES = linkedMapOf(
            "AG" to "NoGroupFilled(/Order/Packages For \"3\")",
            "AD" to "NoGroupFilled(/Order/Packages/Dims For \"3\")",
            "AF" to "FieldNotFilled(/Order/Packages/Carrier For \"3\")",
            "AV" to "[/Order/Packages/Dims/Height For \"3\"] == 0",
            "AX" to "FirstFilledValue(/Order/Packages/Dims For \"3\") == 0",
            "SG" to "GroupFilled(/Order/Packages For \"1\")",
            "SF" to "FieldFilled(/Order/Packages/Carrier For \"1\")",
            "SN" to "FieldNotFilled(/Order/Packages/Carrier For \"2\")",
            "SV" to "[/Order/Packages/Dims/Height For \"1\"] == 3",
            "SX" to "FirstFilledValue(/Order/Packages/Dims For \"1\") == 3",
            "KG" to "NoGroupFilled(/Order/Packages For /Order/WantedNo)",
            "KF" to "FieldNotFilled(/Order/Packages/Carrier For /Order/WantedNo)",
            "KS" to "GroupFilled(/Order/Packages For /Order/PickNo)",
            "KV" to "[/Order/Packages/Dims/Height For /Order/PickNo] == 3",
            "BX" to "FirstFilledValue(/Order/Extra) == 4",
            "BF" to "FirstFilledValue(/Order/Extra/A, /Order/Extra/B) == 4",
            "BB" to "FirstFilledValue(/Order/Extra/B, /Order/Extra/A) == 4",
            "BC" to "FirstFilledValue(/Order/Service/S1 -> Speed, /Order/Service/S2 -> Speed) == \"FAST\"",
            "BN" to "NoFieldFilled(/Order/Extra/A)",
            "CR" to "[/Order/Service/S2 -> Speed] == \"FAST\"",
        )

        val REFERENCE = "/Order/Reference" to listOf(0, 0)
        val NO = "/Order/Packages/No" to listOf(0, 0, 0)
        val CARRIER = "/Order/Packages/Carrier" to listOf(0, 0, 0)
        val HEIGHT = "/Order/Packages/Dims/Height" to listOf(0, 0, 0, 0)
        val PACKAGES = "/Order/Packages" to listOf(0, 0)
        val ROW1 = "/Order/Packages" to listOf(0, 1)
        val ROW2 = "/Order/Packages" to listOf(0, 2)
        val KEYS = listOf("/Order/WantedNo" to listOf(0, 0), "/Order/PickNo" to listOf(0, 0))
        fun extra(field: String) = "/Order/Extra/$field" to listOf(0, 0, 0)
        fun service(field: String) = "/Order/Service/$field" to listOf(0, 0, 0)

        val COVERAGES: Map<String, List<Pair<String, List<Int>>>> = linkedMapOf(
            "group" to listOf(REFERENCE, PACKAGES),
            "fields" to listOf(REFERENCE, NO, CARRIER, HEIGHT),
            "keyColumn" to listOf(REFERENCE, NO),
            "rows" to listOf(REFERENCE, NO, ROW1, ROW2),
            "rowsAllFields" to listOf(REFERENCE, ROW1, ROW2),
            "targetsNoKey" to listOf(REFERENCE, CARRIER, HEIGHT),
            "none" to listOf(REFERENCE),
            "group+keys" to listOf(REFERENCE, PACKAGES) + KEYS,
            "extraB" to listOf(REFERENCE, extra("B")),
            "extraAB" to listOf(REFERENCE, extra("A"), extra("B")),
            "serviceS2" to listOf(REFERENCE, service("S2")),
            "serviceS1S2" to listOf(REFERENCE, service("S1"), service("S2")),
            "extraAll" to listOf(REFERENCE, "/Order/Extra" to listOf(0, 0), "/Order/Service" to listOf(0, 0)),
            "root" to listOf("/Order" to listOf(0)),
        )

        val model = DocumentModelDraft.named("parcel-relevance").locale("en_US")
            .rootGroup("Order") { order ->
                order.stringField("Reference") { }
                    .numberField("WantedNo") { it.fractionDigits(0, 0) }
                    .numberField("PickNo") { it.fractionDigits(0, 0) }
                    .numberField("Flag") { it.fractionDigits(0, 0) }
                    .group("Extra") { extra ->
                        extra.numberField("A") { it.fractionDigits(0, 0) }
                            .numberField("B") { it.fractionDigits(0, 0) }
                    }
                    .group("Service") { service ->
                        service.enumField("S1") { it.value("STANDARD").value("EXPRESS").category("Speed", "SLOW", "FAST") }
                            .enumField("S2") { it.value("STANDARD").value("EXPRESS").category("Speed", "SLOW", "FAST") }
                    }
                    .group("Packages") { packages ->
                        packages.repeatable(5).indexField("No")
                            .numberField("No") { it.fractionDigits(0, 0) }
                            .stringField("Carrier") { }
                            .group("Dims") { dims -> dims.numberField("Height") { it.fractionDigits(0, 0) } }
                    }
            }.build().toJson()

        /** One rule per consumer, all at `/Order` with the error field `Reference`, guarded by its presence. */
        val kModel: String = RULES.entries.fold(model) { m, (code, condition) ->
            RuntimeLaws.withRule(m, "/Order", "Rule$code", "../Reference", code,
                "FieldFilled(/Order/Reference) And $condition")
        }

        /** Which rule codes each engine fires, per coverage, with `null` for full validation. Built once: the
         *  engines cost far more than the assertions, and every test below reads the same grid. */
        val observed: Map<String?, Observation> by lazy {
            assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(kModel).filter { it.startsWith("ERROR") },
                "the kernel must admit the model, or an absent verdict is the gate's silence and not an evaluation")
            val dynamic = RuntimeLaws.kernelEngine(kModel)
            val static = RuntimeLaws.kernelJavaEngine(kModel)
            val def = ModelLoader.load(kModel)
            fun codes(messages: List<IMessage>) = messages.map { it.errorCode }.filterTo(sortedSetOf()) { it in RULES }
            val full = DmInterpreter(def).validate(def.readDocumentJson(DOC))
            assertEquals(emptyList<Any>(), full.unsupported, "declined, not evaluated")
            val rows = linkedMapOf<String?, Observation>(null to Observation(
                codes(dynamic.validationMessages(dynamic.instance(DOC), null, Locale.US)),
                codes(static.validationMessages(static.instance(DOC), null, Locale.US)),
                full.messages.map { it.code }.filterTo(sortedSetOf()) { it in RULES }))
            for ((name, coverage) in COVERAGES) {
                val kernelRelevant = coverage.mapTo(HashSet()) { (path, reps) -> Relevant(path, reps) }
                val outcome = DmInterpreter(def).validatePart(def.readDocumentJson(DOC),
                    coverage.mapTo(HashSet()) { (path, reps) -> RelevantEntity(path, reps) })
                assertEquals(emptyList<Any>(), outcome.unsupported, "declined, not evaluated — $name")
                rows[name] = Observation(
                    codes(dynamic.partialValidationMessages(dynamic.instance(DOC), kernelRelevant, null, Locale.US)),
                    codes(static.partialValidationMessages(static.instance(DOC), kernelRelevant, null, Locale.US)),
                    outcome.messages.map { it.code }.filterTo(sortedSetOf()) { it in RULES })
            }
            rows
        }
    }

    class Observation(val dynamic: Set<String>, val static: Set<String>, val interpreter: Set<String>)

    /** Asserts that [family]'s rules fire exactly as [expected] says under each coverage, on all three engines. */
    private fun assertFires(family: Set<String>, expected: Map<String?, Set<String>>) {
        for ((coverage, fires) in expected) {
            val row = observed.getValue(coverage)
            val label = coverage ?: "full validation"
            assertEquals(fires, row.dynamic.intersect(family), "dynamic-Groovy — $label")
            assertEquals(fires, row.static.intersect(family), "static-Java — $label")
            assertEquals(fires, row.interpreter.intersect(family), "INTERPRETER — $label")
        }
    }

    @Test
    fun aKeyNoRowCarriesIsDecidedOnlyWhenAWildcardCoversTheUnboundRow() {
        assertFires(ABSENT, mapOf(
            null to ABSENT,
            "group" to ABSENT,
            "fields" to ABSENT,
            "keyColumn" to emptySet(),
            // every existing row listed concretely, with the key column wildcarded: the unbound row stays uncovered
            "rows" to emptySet(),
            "rowsAllFields" to emptySet(),
            // every target covered but not the key column: no key cell is admitted, so no lookup is decided
            "targetsNoKey" to emptySet(),
            "none" to emptySet(),
            "root" to ABSENT,
        ))
    }

    @Test
    fun aSelectedRowIsJudgedByItsTargetsRelevanceAtThatRow() {
        assertFires(SELECTED, mapOf(
            null to SELECTED,
            "group" to SELECTED,
            "fields" to SELECTED,
            // the key column alone: package 1 counts as filled through its key, and no target is relevant
            "keyColumn" to setOf("SG"),
            "rows" to SELECTED,
            "rowsAllFields" to SELECTED,
            "targetsNoKey" to emptySet(),
            "none" to emptySet(),
            "root" to SELECTED,
        ))
    }

    @Test
    fun aFieldValuedKeyOutsideTheCoverageLeavesItsLookupUndecided() {
        assertFires(FIELD_KEYED, mapOf(
            null to FIELD_KEYED,
            "group" to emptySet(),
            "group+keys" to FIELD_KEYED,
            "root" to FIELD_KEYED,
        ))
    }

    /** The unkeyed siblings of the keyed walk above. A cell outside the coverage is undecided wherever the walk
     *  meets it, empty or not; a cell after the first filled one is never met. */
    @Test
    fun firstFilledValueStopsAtTheFirstCellOutsideTheCoverage() {
        assertFires(UNKEYED, mapOf(
            null to UNKEYED,
            "none" to emptySet(),
            "extraB" to setOf("BB"),
            "extraAB" to setOf("BX", "BF", "BB", "BN"),
            "serviceS2" to emptySet(),
            "serviceS1S2" to setOf("BC"),
            "extraAll" to UNKEYED,
        ))
    }

    @Test
    fun aCategoryReadOutsideTheCoverageIsUndecided() {
        assertFires(CATEGORY, mapOf(
            null to CATEGORY,
            "none" to emptySet(),
            "extraAB" to emptySet(),
            "serviceS2" to CATEGORY,
            "extraAll" to CATEGORY,
        ))
    }
}
