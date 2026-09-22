package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.Requiredness
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Differential — **requiredness** (`mandatoryField`, KERNEL-FINDINGS §4). A required-but-empty field
 * raises `mandatoryField` and becomes **"unknown"**: a rule referencing it resolves the operand to
 * `Val.Unknown`, so the 3VL propagates (a bare comparison goes UNKNOWN, but `… Or <true>` still fires) —
 * the kernel's operand-level behaviour, NOT a whole-rule skip. Covers both modes
 * (`absoluteOrRelativeToNextRepAncestor` / `relativeToParent`), the repeatable-row gate, the
 * unconditional-absolute case, and the auto-required **index field** (`indexFieldName`).
 *
 * Each case compares the full set of `code@pointer` pairs the kernel and the interpreter raise (both render
 * the pointer identically via `formatPointer`), so it locks the codes AND their field instances.
 */
class RequirednessDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")

    // Rule conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed
    // to BOTH engines (kernel injection + the interpreter) — no free-text condition literals. The requiredness
    // SETUP (the `require(...)` model rewrite + the `Requiredness` field configs) is unchanged.
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = RuntimeLaws.render(c)

    @Test
    fun fieldOwnedRequirednessTextPreservesLiteralDollarOnBothKernelStrategies() {
        val base = DocumentModelDraft.named("required-message").locale("en_US")
            .rootGroup("Order") { order ->
                order.stringField("Code") { it.required() }
            }
            .build().toJson()
        val edit = EditSession.read(base)
        edit.field("/Order/Code").setRequiredErrorMessages(mapOf("en_US" to "Cost \$\$ \$field\$"))
        val model = edit.toJson()
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model), "requiredness template is accepted")
        val document = RuntimeLaws.doc().group("/Order", 1).emptyField("/Order/Code", 1, 1)
        val dynamic = RuntimeLaws.errorTextOf(model, "mandatoryField", document, Locale.US).orElseThrow()
        val java = RuntimeLaws.kernelJavaEngine(model)
        val generated = java.errorTextOf(java.instance(document), "mandatoryField", Locale.US).orElseThrow()
        val definition = ModelLoader.load(model)
        val interpreterDocument = definition.newDocument()
            .group("/Order", 1)
            .emptyField("/Order/Code", 1, 1)
        val interpreter = DmInterpreter(definition).validate(interpreterDocument).messages
            .single { it.code == "mandatoryField" }.text

        assertEquals("Cost \$\$ Code", dynamic, "dynamic Groovy")
        assertEquals(dynamic, generated, "generated Java")
        assertEquals(dynamic, interpreter, "dynamic Groovy vs interpreter")
    }

    // ---- absolute requiredness on a root-level NUMBER (Quantity) -----------------------------------

    @Test
    fun absoluteRequiredEmptyNumberIsUnknownNotZero() {
        // [Quantity] < 100 would fire on an empty (→0) number — but a REQUIRED-empty number is unknown, so
        // only mandatoryField fires (the comparison is suppressed via the unknown operand).
        val cond = render(m.num(Q).lt(100))
        val kModel = RuntimeLaws.withRule(require(order, "/Order/Quantity", "absoluteOrRelativeToNextRepAncestor"),
            "/Order", "Cap", "../Quantity", "QTY_LOW", cond)
        val def = ModelDef(listOf(FieldDef(Q, Kind.NUMBER, required = Requiredness.ABSOLUTE), FieldDef(CN, Kind.STRING)),
            listOf(RuleDef("/Order/Cap", "/Order", cond, "QTY_LOW", "../Quantity")))

        agree(kModel, def, "required-empty Quantity → only mandatoryField") {
            it.group("/Order", 1).field(CN, "Acme", 1, 1).emptyField(Q, 1, 1)
        }
        // a filled required value evaluates normally: 50 < 100 fires, no mandatoryField
        agree(kModel, def, "filled required Quantity=50 → only QTY_LOW") {
            it.group("/Order", 1).field(CN, "Acme", 1, 1).field(Q, "50", 1, 1)
        }
    }

    @Test
    fun requiredEmptyOperandPropagatesAsUnknownThroughOr() {
        // unknown Or <true> = true → the author rule STILL fires (alongside mandatoryField). This is the
        // operand-level-unknown behaviour, not a whole-rule skip (which would drop QTY_LOW).
        val cond = render(m.num(Q).lt(100).or(m.field(CN).filled()))
        val kModel = RuntimeLaws.withRule(require(order, "/Order/Quantity", "absoluteOrRelativeToNextRepAncestor"),
            "/Order", "Cap", "../Quantity", "QTY_LOW", cond)
        val def = ModelDef(listOf(FieldDef(Q, Kind.NUMBER, required = Requiredness.ABSOLUTE), FieldDef(CN, Kind.STRING)),
            listOf(RuleDef("/Order/Cap", "/Order", cond, "QTY_LOW", "../Quantity")))
        agree(kModel, def, "required-empty operand in an Or with a true branch → both fire") {
            it.group("/Order", 1).field(CN, "Acme", 1, 1).emptyField(Q, 1, 1)
        }
    }

    @Test
    fun absoluteRequiresEvenWhenParentSubGroupNotInstantiated() {
        // absolute Street is required unconditionally — even with ShippingAddress not instantiated at all.
        val kModel = require(order, "/Order/ShippingAddress/Street", "absoluteOrRelativeToNextRepAncestor")
        val def = ModelDef(listOf(FieldDef(CN, Kind.STRING),
            FieldDef(STREET, Kind.STRING, required = Requiredness.ABSOLUTE), FieldDef(CITY, Kind.STRING)), emptyList())
        agree(kModel, def, "absolute Street fires with no ShippingAddress instance") {
            it.group("/Order", 1).field(CN, "Acme", 1, 1)
        }
    }

    // ---- relativeToParent on a non-repeatable sub-group (Street under ShippingAddress) --------------

    @Test
    fun relativeToParentGatedByParentDescendantFill() {
        val kModel = require(order, "/Order/ShippingAddress/Street", "relativeToParent")
        val def = ModelDef(listOf(FieldDef(CN, Kind.STRING),
            FieldDef(STREET, Kind.STRING, required = Requiredness.RELATIVE_TO_PARENT), FieldDef(CITY, Kind.STRING)), emptyList())

        // parent has no filled descendant → NOT required
        agree(kModel, def, "relativeToParent Street, empty parent → no mandatoryField") {
            it.group("/Order", 1).field(CN, "Acme", 1, 1)
                .group("/Order/ShippingAddress", 1, 1).emptyField(STREET, 1, 1, 1).emptyField(CITY, 1, 1, 1)
        }
        // a sibling (City) fills the parent → Street is now required
        agree(kModel, def, "relativeToParent Street, City fills parent → mandatoryField") {
            it.group("/Order", 1).field(CN, "Acme", 1, 1)
                .group("/Order/ShippingAddress", 1, 1).field(CITY, "Town", 1, 1, 1).emptyField(STREET, 1, 1, 1)
        }
    }

    // ---- repeatable group: per-row requiredness + the auto-required index field --------------------

    @Test
    fun repeatableRequiredFieldPerRowPlusAutoRequiredIndexField() {
        // Count required (absolute → relative to the repeatable /Order/Items); Sku is the index field, so the
        // kernel auto-requires it (relativeToParent). Items declares 50 rows.
        val kModel = require(order, "/Order/Items/Count", "absoluteOrRelativeToNextRepAncestor")
        val def = ModelDef(
            listOf(FieldDef(CN, Kind.STRING), FieldDef(IS, Kind.STRING),
                FieldDef(IC, Kind.NUMBER, required = Requiredness.ABSOLUTE)),
            emptyList(), repeatableGroups = setOf("/Order/Items"),
            declaredReps = mapOf("/Order/Items" to 50), indexFields = setOf(IS),
        )

        // row1 Sku filled, Count empty → only Count's mandatoryField
        agree(kModel, def, "row with Sku filled, Count empty → mandatoryField(Count)") {
            it.group("/Order", 1).field(CN, "Acme", 1, 1)
                .group("/Order/Items", 1, 1).field(IS, "S1", 1, 1, 1).emptyField(IC, 1, 1, 1)
        }
        // a created all-empty row → BOTH Count and the index Sku fire mandatoryField (the row exists)
        agree(kModel, def, "all-empty created row → mandatoryField(Count) + mandatoryField(Sku)") {
            it.group("/Order", 1).field(CN, "Acme", 1, 1)
                .group("/Order/Items", 1, 1).emptyField(IS, 1, 1, 1).emptyField(IC, 1, 1, 1)
        }
        // no Items row at all → nothing
        agree(kModel, def, "no Items row → no mandatoryField") {
            it.group("/Order", 1).field(CN, "Acme", 1, 1)
        }
        // two rows: row1 complete, row2 missing Count → mandatoryField only on row2/Count
        agree(kModel, def, "second row missing Count → mandatoryField on row2 only") {
            it.group("/Order", 1).field(CN, "Acme", 1, 1)
                .group("/Order/Items", 1, 1).field(IS, "S1", 1, 1, 1).field(IC, "5", 1, 1, 1)
                .group("/Order/Items", 1, 2).field(IS, "S2", 1, 2, 1).emptyField(IC, 1, 2, 1)
        }
    }

    @Test
    fun relativeToParentIsFilledByAnInstantiatedRepeatableChildRow() {
        // Quantity required relativeToParent → its parent /Order (a non-repeatable root). An INSTANTIATED
        // repeatable child row (Items) makes /Order "filled" — the same content notion the `GroupFilled` operator
        // and the rule-eval gate use (IF53) — so Quantity IS required even with no /Order *field* filled. With no
        // Items row /Order has no content → Quantity not required. (Sku is auto-required as the index field, so an
        // instantiated Item row also fires mandatoryField(Sku) — asserted as part of the full code set.)
        val kModel = require(order, "/Order/Quantity", "relativeToParent")
        val def = ModelDef(
            listOf(FieldDef(Q, Kind.NUMBER, required = Requiredness.RELATIVE_TO_PARENT),
                FieldDef(CN, Kind.STRING), FieldDef(IS, Kind.STRING), FieldDef(IC, Kind.NUMBER)),
            emptyList(), repeatableGroups = setOf("/Order/Items"),
            declaredReps = mapOf("/Order/Items" to 50), indexFields = setOf(IS),
        )
        // no Items row, no /Order field → /Order not filled → Quantity NOT required
        agree(kModel, def, "empty /Order → Quantity not required") {
            it.group("/Order", 1).emptyField(Q, 1, 1)
        }
        // an instantiated (empty) Items row makes /Order content-bearing → Quantity required
        agree(kModel, def, "instantiated Items row fills /Order → mandatoryField(Quantity)") {
            it.group("/Order", 1).emptyField(Q, 1, 1)
                .group("/Order/Items", 1, 1).emptyField(IS, 1, 1, 1).emptyField(IC, 1, 1, 1)
        }
    }

    /** Build the doc both ways (kernel + interpreter) from one description, and assert identical code@pointer sets. */
    private fun agree(kModel: String, def: ModelDef, msg: String, build: (DocLike) -> Unit) {
        val kDoc = RuntimeLaws.doc()
        val iDoc = def.newDocument()
        build(DocLike(kDoc, iDoc))
        val kernel = RuntimeLaws.messages(kModel, kDoc).map { "${it.code}@${it.field}" }
            .filter { !it.startsWith("MVK_") }.toSortedSet()
        val interp = DmInterpreter(def).validate(iDoc).messages.map { "${it.code}@${it.errorPath}" }.toSortedSet()
        assertEquals(kernel, interp, "$msg\n  kernel=$kernel\n  interp=$interp")
    }

    /** A thin adapter that mirrors each placement onto both the kernel [RuntimeLaws.Doc] and the [Document]. */
    private class DocLike(private val k: RuntimeLaws.Doc, private val i: Document) {
        fun group(path: String, vararg reps: Int): DocLike { k.group(path, *reps); i.group(path, *reps); return this }
        fun field(path: String, v: String, vararg reps: Int): DocLike { k.field(path, v, *reps); i.field(path, v, *reps); return this }
        fun emptyField(path: String, vararg reps: Int): DocLike { k.emptyField(path, *reps); i.emptyField(path, *reps); return this }
    }

    private fun require(modelJson: String, fieldPath: String, mode: String): String {
        val id = modelJson.indexOf("\"id\": \"$fieldPath\"")
        require(id >= 0) { "field not found: $fieldPath" }
        val brace = modelJson.indexOf('{', modelJson.indexOf("\"Field\":", id))
        return modelJson.substring(0, brace + 1) +
            "\n\"requirednessConfig\": {\"mode\": \"$mode\"}," + modelJson.substring(brace + 1)
    }

    private companion object {
        const val Q = "/Order/Quantity"
        const val CN = "/Order/CustomerName"
        const val STREET = "/Order/ShippingAddress/Street"
        const val CITY = "/Order/ShippingAddress/City"
        const val IS = "/Order/Items/Sku"
        const val IC = "/Order/Items/Count"
    }
}
