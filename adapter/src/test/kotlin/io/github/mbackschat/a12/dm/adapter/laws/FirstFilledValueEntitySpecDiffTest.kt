package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.KernelAdapter
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.util.Date
import java.util.function.Consumer

/** Tri-engine lock for every runtime-reachable owner `entitySpec` shape accepted by `FirstFilledValue`.
 *  Indexed outer groups are unreachable because the owner rejects every semantic index inside `Having`;
 *  `SemanticIndexSlotLawsTest` locks that boundary. */
class FirstFilledValueEntitySpecDiffTest {

    private val base = DocumentModelDraft.named("selection").locale("en_US")
        .rootGroup("Selection", Consumer { root ->
            root.stringField("FallbackText", Consumer { })
                .stringField("Expected", Consumer { })
                .numberField("FallbackNumber", Consumer { it.fractionDigits(0) })
                .enumField("Priority", Consumer { field ->
                    field.value("STANDARD").value("EXPRESS")
                        .category("Speed", "NORMAL", "FAST")
                })
                .group("Address", Consumer { address ->
                    address.stringField("Street", Consumer { })
                        .stringField("City", Consumer { })
                })
                .group("NestedAddress", Consumer { address ->
                    address.group("Lines", Consumer { lines ->
                        lines.repeatable(10).stringField("Text", Consumer { })
                    })
                })
                .group("Rows", Consumer { rows ->
                    rows.repeatable(10).indexField("Key")
                        .stringField("Key", Consumer { })
                        .numberField("Primary", Consumer { it.fractionDigits(0) })
                        .enumField("Priority", Consumer { field ->
                            field.value("STANDARD").value("EXPRESS")
                                .category("Speed", "NORMAL", "FAST")
                        })
                })
                .group("IndexedNumbers", Consumer { rows ->
                    rows.repeatable(10).indexField("Key")
                        .numberField("Key", Consumer { it.fractionDigits(0) })
                        .numberField("Primary", Consumer { it.fractionDigits(0) })
                        .numberField("Secondary", Consumer { it.fractionDigits(0) })
                })
                .group("IndexedParents", Consumer { parents ->
                    parents.repeatable(10).indexField("Key")
                        .group("Children", Consumer { children ->
                            children.repeatable(10).numberField("Value", Consumer { it.fractionDigits(0) })
                        })
                        .numberField("Key", Consumer { it.fractionDigits(0) })
                })
                .group("FilterRows", Consumer { rows ->
                    rows.repeatable(10)
                        .numberField("Candidate", Consumer { it.fractionDigits(0) })
                        .stringField("Key", Consumer { })
                        .enumField("Priority", Consumer { field ->
                            field.value("STANDARD").value("EXPRESS")
                                .category("Speed", "NORMAL", "FAST")
                        })
                })
        }).build().toJson()

    private val model = BoundModel.ofJson(base)
    private val modelJson = rules().fold(base, TypedRules::inject)
    private val java by lazy { RuntimeLaws.kernelJavaEngine(modelJson) }
    private val definition by lazy { ModelLoader.load(modelJson) }

    @Test
    fun emptyOwnerEntitiesContinueToTheNextOperand() {
        agree(filledSelectors = false, expected = EMPTY_CODES)
    }

    @Test
    fun aFilledOwnerEntityTerminatesBeforeTheFallback() {
        agree(filledSelectors = true, expected = FILLED_CODES)
    }

    @Test
    fun outerGroupEntitySpecsRoundTripWithoutDroppingTheirCorrelationOrSemanticIndex() {
        val renderer = DslRenderer()
        val adapter = KernelAdapter.ofJson(modelJson)

        rules().filter { it.code in ROUND_TRIP_CODES }.forEach { rule ->
            val read = assertInstanceOf(ReadResult.Converted::class.java, adapter.read("$ROOT/${rule.name}"))
            assertEquals(renderer.render(rule.condition), renderer.render(read.condition), rule.code)
        }
    }

    private fun agree(filledSelectors: Boolean, expected: Set<String>) {
        val kernelDocument = RuntimeLaws.doc().group(ROOT, 1)
            .field(FALLBACK_TEXT, "x", 1, 1)
            .field(EXPECTED, "FAST", 1, 1)
            .field(FALLBACK_NUMBER, "5", 1, 1)
            .group(ROWS_GROUP, 1, 1)
            .field(ROW_KEY, if (filledSelectors) "MATCH" else "OTHER", 1, 1, 1)
            .field(ROW_PRIMARY, if (filledSelectors) "0" else "9", 1, 1, 1)
            .field(ROW_PRIORITY, if (filledSelectors) "EXPRESS" else "", 1, 1, 1)
            .group(INDEXED_GROUP, 1, 1)
            .field(INDEXED_KEY, if (filledSelectors) "0" else "2", 1, 1, 1)
            .field(INDEXED_PRIMARY, if (filledSelectors) "0" else "9", 1, 1, 1)
            .group(FILTER_ROWS, 1, 1)
            .field(FILTER_CANDIDATE, "5", 1, 1, 1)
            .field(FILTER_KEY, "MATCH", 1, 1, 1)
            .field(FILTER_PRIORITY, if (filledSelectors) "EXPRESS" else "", 1, 1, 1)
        if (filledSelectors) {
            kernelDocument.group(ADDRESS_GROUP, 1, 1).field(STREET, "outer", 1, 1, 1)
                .field(PRIORITY, "STANDARD", 1, 1)
                .group(NESTED_ADDRESS, 1, 1)
                .group(NESTED_LINES, 1, 1, 2)
                .field(NESTED_TEXT, "first", 1, 1, 2, 1)
                .group(INDEXED_PARENTS, 1, 1)
                .field(INDEXED_PARENT_KEY, "0", 1, 1, 1)
                .group(INDEXED_CHILDREN, 1, 1, 2)
                .field(INDEXED_CHILD_VALUE, "7", 1, 1, 2, 1)
        }

        val clock = Date(0)
        val dynamic = RuntimeLaws.messagesModern(modelJson, kernelDocument, clock).map { it.code() }.toSet()
        val generated = java.messagesModern(kernelDocument, clock).map { it.code() }.toSet()

        val document = definition.newDocument().group(ROOT, 1)
            .field(FALLBACK_TEXT, "x", 1, 1)
            .field(EXPECTED, "FAST", 1, 1)
            .field(FALLBACK_NUMBER, "5", 1, 1)
            .group(ROWS_GROUP, 1, 1)
            .field(ROW_KEY, if (filledSelectors) "MATCH" else "OTHER", 1, 1, 1)
            .field(ROW_PRIMARY, if (filledSelectors) "0" else "9", 1, 1, 1)
            .field(ROW_PRIORITY, if (filledSelectors) "EXPRESS" else "", 1, 1, 1)
            .group(INDEXED_GROUP, 1, 1)
            .field(INDEXED_KEY, if (filledSelectors) "0" else "2", 1, 1, 1)
            .field(INDEXED_PRIMARY, if (filledSelectors) "0" else "9", 1, 1, 1)
            .group(FILTER_ROWS, 1, 1)
            .field(FILTER_CANDIDATE, "5", 1, 1, 1)
            .field(FILTER_KEY, "MATCH", 1, 1, 1)
            .field(FILTER_PRIORITY, if (filledSelectors) "EXPRESS" else "", 1, 1, 1)
        if (filledSelectors) {
            document.group(ADDRESS_GROUP, 1, 1).field(STREET, "outer", 1, 1, 1)
                .field(PRIORITY, "STANDARD", 1, 1)
                .group(NESTED_ADDRESS, 1, 1)
                .group(NESTED_LINES, 1, 1, 2)
                .field(NESTED_TEXT, "first", 1, 1, 2, 1)
                .group(INDEXED_PARENTS, 1, 1)
                .field(INDEXED_PARENT_KEY, "0", 1, 1, 1)
                .group(INDEXED_CHILDREN, 1, 1, 2)
                .field(INDEXED_CHILD_VALUE, "7", 1, 1, 2, 1)
        }
        val outcome = DmInterpreter(definition).validate(document)
        val interpreted = outcome.messages.map { it.code }.toSet()

        assertEquals(expected, dynamic, "normative dynamic-Groovy")
        assertEquals(dynamic, generated, "generated Java")
        assertEquals(dynamic, interpreted, "kernel-free interpreter")
        assertEquals(emptyList<Any>(), outcome.unsupported, "every owner entitySpec is supported")
    }

    private fun rules(): List<Rule> = listOf(
        rule(DIRECT_GROUP, model.str(FALLBACK_TEXT).field(),
            Dsl.firstFilledString(model.firstFilledGroup(ADDRESS_GROUP),
                model.str(FALLBACK_TEXT).field().firstFilledOperand()).eq("x")),
        rule(CATEGORY, model.str(EXPECTED).field(),
            Dsl.firstFilledString(
                model.enumOf(PRIORITY).category("Speed").firstFilledOperand(),
                model.str(EXPECTED).field().firstFilledOperand(),
            ).eq("FAST")),
        rule(INDEXED_FIELD, model.num(FALLBACK_NUMBER).field(),
            Dsl.firstFilledNumber(
                model.num(ROW_PRIMARY).semanticIndex("MATCH").index().firstFilledOperand(),
                model.num(FALLBACK_NUMBER).field().firstFilledOperand(),
            ).gt(0)),
        rule(COMPOUND_INDEX_CATEGORY, model.str(FALLBACK_TEXT).field(),
            Dsl.firstFilledString(
                model.enumOf(ROW_PRIORITY).category("Speed").firstFilledOperand().forIndex("MATCH"),
                model.str(FALLBACK_TEXT).field().firstFilledOperand(),
            ).eq("FAST")),
        rule(COMPOUND_FILTER_CATEGORY, model.str(FALLBACK_TEXT).field(),
            Dsl.firstFilledString(
                model.flatten(FILTER_PRIORITY_STAR).firstFilledOperand()
                    .withCategory(model.enumOf(FILTER_PRIORITY).category("Speed"))
                    .having(model.str(FILTER_KEY).eq("MATCH")),
                model.str(FALLBACK_TEXT).field().firstFilledOperand(),
            ).eq("FAST")),
        rule(INDEXED_GROUP_CODE, model.num(FALLBACK_NUMBER).field(),
            Dsl.firstFilledNumber(
                model.firstFilledGroupFor(INDEXED_GROUP, "0"),
                model.num(FALLBACK_NUMBER).field().firstFilledOperand(),
            ).gt(0)),
        rule(NESTED_DIRECT_GROUP, model.str(FALLBACK_TEXT).field(),
            Dsl.firstFilledString(
                model.firstFilledGroup(NESTED_ADDRESS),
                model.str(FALLBACK_TEXT).field().firstFilledOperand(),
            ).eq("x")),
        rule(NESTED_INDEXED_GROUP, model.num(FALLBACK_NUMBER).field(),
            Dsl.firstFilledNumber(
                model.firstFilledGroupFor(INDEXED_PARENTS, "0"),
                model.num(FALLBACK_NUMBER).field().firstFilledOperand(),
            ).eq(7)),
        rule(OUTER_DIRECT_GROUP, model.num(FILTER_CANDIDATE).field(),
            Dsl.firstFilledNumber(model.flatten(FILTER_CANDIDATE_STAR).having(
                model.num(FILTER_CANDIDATE).eq(model.num(FALLBACK_NUMBER).outer()).and(
                    Dsl.firstFilledString(
                        model.firstFilledGroup(ADDRESS_GROUP).outer(),
                        model.str(FALLBACK_TEXT).field().firstFilledOperand(),
                    ).eq("outer"),
                ),
            )).gt(0)),
    )

    private fun rule(code: String, errorField: io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef,
                     condition: Condition): Rule =
        model.rule(model.group(ROOT))
            .name(code)
            .errorField(errorField)
            .`when`(condition)
            .code(code)
            .severity(Severity.ERROR)
            .message(Dsl.en("FirstFilledValue owner-entity differential."))
            .build()

    private companion object {
        const val ROOT = "/Selection"
        const val FALLBACK_TEXT = "/Selection/FallbackText"
        const val EXPECTED = "/Selection/Expected"
        const val FALLBACK_NUMBER = "/Selection/FallbackNumber"
        const val PRIORITY = "/Selection/Priority"
        const val ADDRESS_GROUP = "/Selection/Address"
        const val STREET = "/Selection/Address/Street"
        const val NESTED_ADDRESS = "/Selection/NestedAddress"
        const val NESTED_LINES = "/Selection/NestedAddress/Lines"
        const val NESTED_TEXT = "/Selection/NestedAddress/Lines/Text"
        const val ROWS_GROUP = "/Selection/Rows"
        const val ROW_KEY = "/Selection/Rows/Key"
        const val ROW_PRIMARY = "/Selection/Rows/Primary"
        const val ROW_PRIORITY = "/Selection/Rows/Priority"
        const val INDEXED_GROUP = "/Selection/IndexedNumbers"
        const val INDEXED_KEY = "/Selection/IndexedNumbers/Key"
        const val INDEXED_PRIMARY = "/Selection/IndexedNumbers/Primary"
        const val INDEXED_PARENTS = "/Selection/IndexedParents"
        const val INDEXED_PARENT_KEY = "/Selection/IndexedParents/Key"
        const val INDEXED_CHILDREN = "/Selection/IndexedParents/Children"
        const val INDEXED_CHILD_VALUE = "/Selection/IndexedParents/Children/Value"
        const val FILTER_ROWS = "/Selection/FilterRows"
        const val FILTER_CANDIDATE = "/Selection/FilterRows/Candidate"
        const val FILTER_KEY = "/Selection/FilterRows/Key"
        const val FILTER_PRIORITY = "/Selection/FilterRows/Priority"
        const val FILTER_PRIORITY_STAR = "/Selection/FilterRows*/Priority"
        const val FILTER_CANDIDATE_STAR = "/Selection/FilterRows*/Candidate"
        const val DIRECT_GROUP = "FFV_DIRECT_GROUP"
        const val CATEGORY = "FFV_CATEGORY"
        const val INDEXED_FIELD = "FFV_INDEXED_FIELD"
        const val COMPOUND_INDEX_CATEGORY = "FFV_COMPOUND_INDEX_CATEGORY"
        const val COMPOUND_FILTER_CATEGORY = "FFV_COMPOUND_FILTER_CATEGORY"
        const val INDEXED_GROUP_CODE = "FFV_INDEXED_GROUP"
        const val NESTED_DIRECT_GROUP = "FFV_NESTED_DIRECT_GROUP"
        const val NESTED_INDEXED_GROUP = "FFV_NESTED_INDEXED_GROUP"
        const val OUTER_DIRECT_GROUP = "FFV_OUTER_DIRECT_GROUP"
        val OUTER_CODES = setOf(OUTER_DIRECT_GROUP)
        val ROUND_TRIP_CODES = OUTER_CODES + setOf(COMPOUND_INDEX_CATEGORY, COMPOUND_FILTER_CATEGORY)
        val EMPTY_CODES = setOf(
            DIRECT_GROUP,
            CATEGORY,
            INDEXED_FIELD,
            INDEXED_GROUP_CODE,
            NESTED_DIRECT_GROUP,
        )
        val FILLED_CODES = setOf(NESTED_INDEXED_GROUP, COMPOUND_INDEX_CATEGORY, COMPOUND_FILTER_CATEGORY) + OUTER_CODES
    }
}
