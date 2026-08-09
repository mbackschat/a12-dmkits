package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.DocumentConfig
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws.Relevant
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Differential — an INSTANTIATED repeatable-group row is *content*, so the kernel EVALUATES a rule on it even
 * when every field in the row is empty (KERNEL-FINDINGS §4/§9: creating a repeatable row is itself content).
 * This is the complement of [EmptyRowGateDiffTest], which covers a truly-empty NON-repeatable scope (not
 * evaluated). Two shapes over `/Cart { Lines[3] { Qty } }`:
 *
 *  - **per-row** `[Qty] < 5` (scope = the repeatable `Lines`): fires OMISSION (empty Qty = 0 < 5) on EVERY
 *    instantiated row — whether the row carries a present-but-empty `Qty` placement (what the fuzz emits) or is
 *    a bare group instance with no field placement.
 *  - **empty-identity aggregate** `MinValue` over `Lines* then Qty`, `< 5` (scope = the non-repeatable `/Cart`): the
 *    instantiated `Lines` rows make `/Cart` content-bearing, so the aggregate evaluates (0 < 5 → OMISSION @ first row).
 *  - **zero rows**: the group is empty → `/Cart` has no content → neither shape fires (both engines agree).
 *  - **sparse V1 placement**: runtime V1-to-V2 conversion pads every missing predecessor with an instantiated
 *    empty row, whether repetition 5 is addressed by a group or only by one of its fields (KF178 / IF276).
 *  - **nested validation iteration**: an existing parent with no concrete nested repeatable child contributes
 *    one implicit child row at coordinate 1, recursively; computation remains bounded to concrete target rows.
 *
 * The original cases compare kernel vs interpreter. The sparse lifecycle also pins the exact shared output so a
 * shared no-fire cannot pass. Surfaced by the aggregate-fuzz extension (the deferred `MinValue < n` rule); the
 * durable breadth net is `perf.InterpreterKernelFuzzTest` (MN re-added).
 */
class RepeatableRowContentDiffTest {

    private val json = DocumentModelDraft.named("cart").locale("en_US")
        .rootGroup("Cart", Consumer { g: GroupBuilder ->
            g.numberField("Note", Consumer { })
                .group("Lines", Consumer<GroupBuilder> { it.repeatable(10).numberField("Qty", Consumer { }) })
        })
        .build().toJson()

    private val edit = EditSession.read(json)
    private val cart = edit.group(CART)
    private val qty = edit.group(LINES).num("Qty")
    private val note = cart.num("Note")

    private val nestedJson = DocumentModelDraft.named("nested-row-content").locale("en_US")
        .rootGroup("Root", Consumer { root: GroupBuilder ->
            root.group("Outer", Consumer { outer: GroupBuilder ->
                outer.repeatable(4).group("Middle", Consumer { middle: GroupBuilder ->
                    middle.repeatable(4).group("Inner", Consumer { inner: GroupBuilder ->
                        inner.repeatable(5).numberField("Qty", Consumer { })
                    })
                })
            })
        })
        .build().toJson()
    private val nestedEdit = EditSession.read(nestedJson)
    private val nestedInner = nestedEdit.group(NESTED_INNER)
    private val nestedQty = nestedInner.num("Qty")
    private val nestedRule = nestedInner.rule {
        it.name("R_NESTED").code("NESTED").errorField(nestedQty.field()).`when`(nestedQty.lt(5))
            .severity(Severity.ERROR).message(Dsl.en("x")).build()
    }
    private val nestedDef = ModelDef(
        fields = listOf(FieldDef(NESTED_QTY, Kind.NUMBER)),
        rules = listOf(TypedRules.def(nestedRule)),
        repeatableGroups = setOf(NESTED_OUTER, NESTED_MIDDLE, NESTED_INNER),
        declaredReps = mapOf(NESTED_OUTER to 4, NESTED_MIDDLE to 4, NESTED_INNER to 5),
        groupPaths = setOf(NESTED_ROOT, NESTED_OUTER, NESTED_MIDDLE, NESTED_INNER),
    )
    private val nestedComputeJson by lazy {
        val session = EditSession.read(nestedJson)
        val inner = session.group(NESTED_INNER)
        inner.addComputation(inner.num("Qty")) { computation ->
            computation.name("NestedProjection")
                .calc(Dsl.number(7).expression())
                .message(Dsl.en("Project a value into every iterated nested row."))
                .build()
        }
        session.validate().toJson()
    }
    private val nestedDomainJson by lazy {
        val structure = DocumentModelDraft.named("nested-row-domains").locale("en_US")
            .rootGroup("Root") { root ->
                root.group("Outer") { outer ->
                    outer.repeatable(3).stringField("Marker") { }
                    outer.group("Middle") { middle ->
                        middle.repeatable(2).group("Inner") { inner ->
                            inner.repeatable(4)
                                .stringField("Key") { }
                                .numberField("Qty") { it.fractionDigits(0, 0).required() }
                                .stringField("Optional") { }
                                .indexField("Key")
                        }
                    }
                }
            }
            .build().toJson()
        EditSession.read(structure).also { session ->
            session.addRule { bound ->
                bound.rule(bound.group(NESTED_INNER)).name("GroupSelf").code("GROUP_SELF")
                    .errorField(bound.num(NESTED_QTY)).`when`(Dsl.ruleGroup().filled())
                    .severity(Severity.ERROR).message(Dsl.en("The concrete inner row is filled.")).build()
            }
            session.addRule { bound ->
                bound.rule(bound.group(NESTED_OUTER)).name("StarGroup").code("STAR_GROUP")
                    .errorField(bound.str(NESTED_MARKER))
                    .`when`(
                        bound.str(NESTED_MARKER).filled().and(
                            Dsl.atLeastOneGroupFilled(bound.flattenGroup("$NESTED_MIDDLE*/Inner*")),
                        ),
                    )
                    .severity(Severity.ERROR).message(Dsl.en("A concrete nested group exists.")).build()
            }
            session.addRule { bound ->
                bound.rule(bound.group(NESTED_ROOT)).name("NestedRnu").code("RNU_NESTED")
                    .errorField(bound.str(NESTED_OPTIONAL))
                    // This row-domain law uses one authored order; RepetitionNotUniqueKeyOrderDiffTest locks the
                    // independent invariant that reversing these same-path components changes nothing (IF278).
                    .`when`(Dsl.repetitionNotUnique(bound.field(NESTED_OPTIONAL), bound.field(NESTED_MARKER)))
                    .severity(Severity.ERROR).message(Dsl.en("The nested key tuple is duplicated.")).build()
            }
            session.validate()
        }.toJson()
    }

    private fun rowRule(code: String, errorField: FieldRef, cond: Condition): Rule = edit.group(LINES).rule {
        it.name("R_$code").code(code).errorField(errorField).`when`(cond).severity(Severity.ERROR).message(Dsl.en("x")).build()
    }
    private fun cartRule(code: String, errorField: FieldRef, cond: Condition): Rule = cart.rule {
        it.name("R_$code").code(code).errorField(errorField).`when`(cond).severity(Severity.ERROR).message(Dsl.en("x")).build()
    }

    private fun kDoc(rows: Int, emptyPlacements: Boolean) = RuntimeLaws.doc().group(CART, 1).let { d ->
        for (r in 1..rows) { d.group(LINES, 1, r); if (emptyPlacements) d.emptyField(QTY, 1, r, 1) }
        d
    }
    private fun iDoc(rows: Int, emptyPlacements: Boolean) = Document(DocumentConfig(KINDS, mapOf(LINES to 10)))
        .group(CART, 1).let { d ->
            for (r in 1..rows) { d.group(LINES, 1, r); if (emptyPlacements) d.emptyField(QTY, 1, r, 1) }
            d
        }

    private fun defOf(r: Rule) = ModelDef(listOf(FieldDef(NOTE, Kind.NUMBER), FieldDef(QTY, Kind.NUMBER)),
        listOf(TypedRules.def(r)), repeatableGroups = setOf(LINES), declaredReps = mapOf(LINES to 10))

    private fun agree(label: String, r: Rule, rows: Int, emptyPlacements: Boolean) {
        val kModel = TypedRules.inject(json, r)
        val k = RuntimeLaws.messages(kModel, kDoc(rows, emptyPlacements)).filter { it.code() == r.code() }
            .map { "${it.type()}@${it.field()}" }.toSortedSet()
        val i = DmInterpreter(defOf(r)).validate(iDoc(rows, emptyPlacements)).messages.filter { it.code == r.code() }
            .map { "${it.type}_ERROR@${it.errorPath}" }.toSortedSet()
        assertEquals(k, i, "$label — kernel=$k interp=$i")
    }

    private fun agreePart(label: String, r: Rule, rows: Int, kRel: Set<Relevant>, iRel: Set<RelevantEntity>) {
        val kModel = TypedRules.inject(json, r)
        val k = RuntimeLaws.messagesPart(kModel, kDoc(rows, false), kRel).filter { it.code() == r.code() }
            .map { "${it.type()}@${it.field()}" }.toSortedSet()
        val i = DmInterpreter(defOf(r)).validatePart(iDoc(rows, false), iRel).messages.filter { it.code == r.code() }
            .map { "${it.type}_ERROR@${it.errorPath}" }.toSortedSet()
        assertEquals(k, i, "$label — kernel=$k interp=$i")
    }

    @Test fun perRowComparisonFiresOnInstantiatedEmptyRows() {
        agree("per-row Qty<5, 3 rows + empty placements", rowRule("PRP", qty.field(), qty.lt(5)), 3, true)
        agree("per-row Qty<5, 3 bare group rows", rowRule("PRB", qty.field(), qty.lt(5)), 3, false)
    }

    @Test fun emptyIdentityAggregateFiresWhenGroupHasInstantiatedRows() {
        agree("MinValue(Lines*/Qty)<5, 3 rows + empty placements",
            cartRule("MNP", qty.field(), Dsl.minValue(cart.flatten("Lines*/Qty")).lt(5)), 3, true)
        agree("MinValue(Lines*/Qty)<5, 3 bare group rows",
            cartRule("MNB", qty.field(), Dsl.minValue(cart.flatten("Lines*/Qty")).lt(5)), 3, false)
    }

    @Test fun zeroRowsFiresNothing() {
        agree("per-row Qty<5, 0 rows", rowRule("PR0", qty.field(), qty.lt(5)), 0, false)
        agree("MinValue(Lines*/Qty)<5, 0 rows",
            cartRule("MN0", qty.field(), Dsl.minValue(cart.flatten("Lines*/Qty")).lt(5)), 0, false)
    }

    /**
     * PARTIAL validation — an explicitly-RELEVANT error-field instance is check-relevant, so the kernel evaluates
     * its rule (empty-as-0 / empty-identity FIRE) even on a non-content-bearing (empty / phantom) row, overriding
     * the content gate (IF54) — while the IF42 star-relevance gate still bounds an aggregate under a *concrete*
     * relevant row. Note the asymmetry with [zeroRowsFiresNothing]: the empty-identity aggregate does NOT fire in
     * FULL validation over 0 rows, but DOES in partial when the ancestor `/Cart` is relevant (a phantom `Lines[1]`).
     */
    @Test fun partialRelevantEmptyInstanceIsEvaluated() {
        val mn = cartRule("MNpc", qty.field(), Dsl.minValue(cart.flatten("Lines*/Qty")).lt(5))
        agreePart("MinValue empty-identity, /Cart relevant, 0 rows (phantom fire)", mn, 0,
            setOf(Relevant.of(CART, 1)), setOf(RelevantEntity(CART, listOf(1))))
        agreePart("MinValue, concrete Line1 relevant, 3 empty rows (IF42 suppresses)", mn, 3,
            setOf(Relevant.of(LINES, 1, 1)), setOf(RelevantEntity(LINES, listOf(1, 1))))
        val pln = cartRule("PLNpc", note.field(), note.lt(5))
        agreePart("plain Note<5, Note relevant, empty Note (empty-as-0 fires)", pln, 0,
            setOf(Relevant.of(NOTE, 1, 1)), setOf(RelevantEntity(NOTE, listOf(1, 1))))
    }

    @Test
    fun sparseGroupAndFieldPlacementsMaterializeV2PredecessorRows() {
        val row = rowRule("SPARSE", qty.field(), qty.lt(5))
        val rowModel = TypedRules.inject(json, row)
        val rowDef = defOf(row)
        assertTriEngine(
            label = "explicit Lines[5] group",
            code = "SPARSE",
            kernelModel = rowModel,
            interpreterModel = rowDef,
            kernelDocument = RuntimeLaws.doc().group(CART, 1).group(LINES, 1, 5),
            interpreterDocument = rowDef.newDocument().group(CART, 1).group(LINES, 1, 5),
            expected = (1..5).mapTo(sortedSetOf()) { "OMISSION_ERROR@/Cart[1]/Lines[$it]/Qty" },
        )
        assertTriEngine(
            label = "field-only Lines[5] placement",
            code = "SPARSE",
            kernelModel = rowModel,
            interpreterModel = rowDef,
            kernelDocument = RuntimeLaws.doc().field(QTY, "7", 1, 5, 1),
            interpreterDocument = rowDef.newDocument().field(QTY, "7", 1, 5, 1),
            expected = (1..4).mapTo(sortedSetOf()) { "OMISSION_ERROR@/Cart[1]/Lines[$it]/Qty" },
        )
    }

    @Test
    fun missingNestedBranchesContributeOneImplicitChildRowPerRepeatableLevel() {
        val nestedModel = TypedRules.inject(nestedJson, nestedRule)
        assertTriEngine(
            label = "three nested repeatable levels",
            code = "NESTED",
            kernelModel = nestedModel,
            interpreterModel = nestedDef,
            kernelDocument = RuntimeLaws.doc().group(NESTED_INNER, 1, 3, 2, 4),
            interpreterDocument = nestedDef.newDocument().group(NESTED_INNER, 1, 3, 2, 4),
            expected = sortedSetOf(
                "OMISSION_ERROR@/Root[1]/Outer[1]/Middle[1]/Inner[1]/Qty",
                "OMISSION_ERROR@/Root[1]/Outer[2]/Middle[1]/Inner[1]/Qty",
                "OMISSION_ERROR@/Root[1]/Outer[3]/Middle[1]/Inner[1]/Qty",
                "OMISSION_ERROR@/Root[1]/Outer[3]/Middle[2]/Inner[1]/Qty",
                "OMISSION_ERROR@/Root[1]/Outer[3]/Middle[2]/Inner[2]/Qty",
                "OMISSION_ERROR@/Root[1]/Outer[3]/Middle[2]/Inner[3]/Qty",
                "OMISSION_ERROR@/Root[1]/Outer[3]/Middle[2]/Inner[4]/Qty",
            ),
        )
        assertTriEngine(
            label = "absent outermost repetition",
            code = "NESTED",
            kernelModel = nestedModel,
            interpreterModel = nestedDef,
            kernelDocument = RuntimeLaws.doc().group(NESTED_ROOT, 1),
            interpreterDocument = nestedDef.newDocument().group(NESTED_ROOT, 1),
            expected = emptySet(),
        )
        assertTriEngine(
            label = "explicit parent with no nested child",
            code = "NESTED",
            kernelModel = nestedModel,
            interpreterModel = nestedDef,
            kernelDocument = RuntimeLaws.doc().group(NESTED_MIDDLE, 1, 1, 1),
            interpreterDocument = nestedDef.newDocument().group(NESTED_MIDDLE, 1, 1, 1),
            expected = setOf("OMISSION_ERROR@/Root[1]/Outer[1]/Middle[1]/Inner[1]/Qty"),
        )
    }

    @Test
    fun computationsRemainBoundedToConcreteNestedRows() {
        val definition = ModelLoader.load(nestedComputeJson)
        val kernelDocument = RuntimeLaws.doc().group(NESTED_INNER, 1, 3, 2, 4)
        val interpreterDocument = definition.newDocument().group(NESTED_INNER, 1, 3, 2, 4)
        val expected = sortedSetOf(
            "/Root[1]/Outer[3]/Middle[2]/Inner[1]/Qty|7",
            "/Root[1]/Outer[3]/Middle[2]/Inner[2]/Qty|7",
            "/Root[1]/Outer[3]/Middle[2]/Inner[3]/Qty|7",
            "/Root[1]/Outer[3]/Middle[2]/Inner[4]/Qty|7",
        )
        fun kernelSignatures(outcomes: List<RuntimeLaws.Computed>) = outcomes
            .filter { it.path() == NESTED_QTY && it.value().isPresent }
            .mapTo(sortedSetOf()) { "${it.pointer()}|${it.value().orElseThrow()}" }
        val dynamic = kernelSignatures(RuntimeLaws.compute(nestedComputeJson, kernelDocument))
        val java = RuntimeLaws.sharedKernelJavaEngine(nestedComputeJson)
        val static = kernelSignatures(java.compute(java.instance(kernelDocument)))
        val interpreter = DmInterpreter(definition).compute(interpreterDocument)
            .filter { it.path == NESTED_QTY && it.outcome == ComputeOutcome.VALUE }
            .mapTo(sortedSetOf()) { "${formatPointer(it.path, it.indices)}|${it.value}" }
        assertEquals(expected, dynamic, "dynamic kernel")
        assertEquals(expected, static, "static kernel")
        assertEquals(expected, interpreter, "interpreter")
    }

    @Test
    fun implicitRowsAreValidationAndRnuContextsButNotStoredGroupOrAutoCheckRows() {
        val definition = ModelLoader.load(nestedDomainJson)
        fun kernelDocument(): RuntimeLaws.Doc = RuntimeLaws.doc().group(NESTED_INNER, 1, 3, 2, 4).also { doc ->
            doc.field(NESTED_MARKER, "D", 1, 1, 1)
            doc.field(NESTED_MARKER, "D", 1, 2, 1)
            doc.field(NESTED_MARKER, "X", 1, 3, 1)
            for (inner in 1..4) {
                doc.field(NESTED_KEY, "K$inner", 1, 3, 2, inner, 1)
                doc.field(NESTED_OPTIONAL, "V$inner", 1, 3, 2, inner, 1)
            }
        }
        fun interpreterDocument() = definition.newDocument().group(NESTED_INNER, 1, 3, 2, 4).also { doc ->
            doc.field(NESTED_MARKER, "D", 1, 1, 1)
            doc.field(NESTED_MARKER, "D", 1, 2, 1)
            doc.field(NESTED_MARKER, "X", 1, 3, 1)
            for (inner in 1..4) {
                doc.field(NESTED_KEY, "K$inner", 1, 3, 2, inner, 1)
                doc.field(NESTED_OPTIONAL, "V$inner", 1, 3, 2, inner, 1)
            }
        }
        val codes = setOf("mandatoryField", "GROUP_SELF", "STAR_GROUP", "RNU_NESTED")
        fun kernelSignatures(messages: List<RuntimeLaws.Msg>) = messages.filter { it.code() in codes }
            .mapTo(sortedSetOf()) { "${it.code()}|${it.type()}@${it.field()}" }
        val dynamic = kernelSignatures(RuntimeLaws.messages(nestedDomainJson, kernelDocument()))
        val java = RuntimeLaws.sharedKernelJavaEngine(nestedDomainJson)
        val static = kernelSignatures(java.messages(java.instance(kernelDocument())))
        val interpreterResult = DmInterpreter(definition).validate(interpreterDocument())
        assertEquals(emptyList<Any>(), interpreterResult.unsupported, "interpreter must execute every typed rule")
        val interpreter = interpreterResult.messages
            .filter { it.code in codes }
            .mapTo(sortedSetOf()) { "${it.code}|${it.type}_ERROR@${it.errorPath}" }
        val actualRows = (1..4).flatMap { inner ->
            listOf(
                "mandatoryField|OMISSION_ERROR@/Root[1]/Outer[3]/Middle[2]/Inner[$inner]/Qty",
                "GROUP_SELF|VALUE_ERROR@/Root[1]/Outer[3]/Middle[2]/Inner[$inner]/Qty",
            )
        }
        val expected = (actualRows + listOf(
            "STAR_GROUP|VALUE_ERROR@/Root[1]/Outer[3]/Marker",
            "RNU_NESTED|OMISSION_ERROR@/Root[1]/Outer[1]/Middle[1]/Inner[1]/Optional",
            "RNU_NESTED|OMISSION_ERROR@/Root[1]/Outer[2]/Middle[1]/Inner[1]/Optional",
        )).toSortedSet()
        assertEquals(expected, dynamic, "dynamic kernel")
        assertEquals(expected, static, "static kernel")
        assertEquals(expected, interpreter, "interpreter")
    }

    private fun assertTriEngine(
        label: String,
        code: String,
        kernelModel: String,
        interpreterModel: ModelDef,
        kernelDocument: RuntimeLaws.Doc,
        interpreterDocument: Document,
        expected: Set<String>,
    ) {
        fun kernelSignatures(messages: List<RuntimeLaws.Msg>) = messages
            .filter { it.code() == code }
            .mapTo(sortedSetOf()) { "${it.type()}@${it.field()}" }
        val dynamic = kernelSignatures(RuntimeLaws.messages(kernelModel, kernelDocument))
        val java = RuntimeLaws.sharedKernelJavaEngine(kernelModel)
        val static = kernelSignatures(java.messages(java.instance(kernelDocument)))
        val interpreter = DmInterpreter(interpreterModel).validate(interpreterDocument).messages
            .filter { it.code == code }
            .mapTo(sortedSetOf()) { "${it.type}_ERROR@${it.errorPath}" }
        assertEquals(expected, dynamic, "$label: dynamic kernel")
        assertEquals(expected, static, "$label: static kernel")
        assertEquals(expected, interpreter, "$label: interpreter")
    }

    private companion object {
        const val CART = "/Cart"; const val LINES = "/Cart/Lines"
        const val QTY = "/Cart/Lines/Qty"; const val NOTE = "/Cart/Note"
        const val NESTED_ROOT = "/Root"
        const val NESTED_OUTER = "/Root/Outer"
        const val NESTED_MIDDLE = "/Root/Outer/Middle"
        const val NESTED_INNER = "/Root/Outer/Middle/Inner"
        const val NESTED_QTY = "/Root/Outer/Middle/Inner/Qty"
        const val NESTED_MARKER = "/Root/Outer/Marker"
        const val NESTED_KEY = "/Root/Outer/Middle/Inner/Key"
        const val NESTED_OPTIONAL = "/Root/Outer/Middle/Inner/Optional"
        val KINDS = mapOf(NOTE to Kind.NUMBER, QTY to Kind.NUMBER)
    }
}
