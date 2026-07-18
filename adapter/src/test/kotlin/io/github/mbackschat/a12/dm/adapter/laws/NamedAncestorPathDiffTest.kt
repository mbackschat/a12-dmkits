package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — the condition grammar's `relativeUp` **turning-point** form `..Name`
 * (`relUp? '..' idName? stern='*'?`). Derived clean-room from the kernel's `CheckRelativeUpImpl`: the number
 * of up-levels is set PURELY by the count of `..` tokens (`1 + relUp.oneUp().size()`); the `Name` label only
 * NAMES the ancestor the walk lands on (the kernel raises `MVK_INVALID_ENTITY` if it mismatches that group's
 * short name) and NEVER changes the resolved target. So `..Order/Buyer` resolves to `/Order/Buyer` exactly as
 * `../Buyer` would — it is NOT "ascend to the nearest ancestor named Order".
 *
 * This locks that reading against the REAL kernel: the kernel accepts `..Name`, the interpreter agrees on
 * firing, and — the crux — `..Name` behaves bit-identically to its plain `..` twin (single-level and
 * relUp+named-terminal). WI-P (SEMANTICS-FORMALIZATION-PROPOSAL, from Phase-0 S1; INTERPRETER-FINDINGS IF55).
 * Syntactic twin: [io.github.mbackschat.a12.dm.interpreter.NamedAncestorPathParseTest].
 */
class NamedAncestorPathDiffTest {

    // Own-domain e-commerce model, 3 levels deep so both forms run: a single named terminal (`..Order` from
    // Shipment) and a relUp step + named terminal (`../..Order` from Parcel).
    private val modelJson = DocumentModelDraft.named("nap").locale("en_US")
        .rootGroup("Order") { o -> o
            .field("Buyer", FieldKind.STRING)
            .group("Shipment") { s -> s
                .field("Carrier", FieldKind.STRING)
                .group("Parcel") { p -> p.field("Weight", FieldKind.STRING) } } }
        .build().toJson()

    private val fields = listOf(FieldDef(BUYER, Kind.STRING), FieldDef(CARRIER, Kind.STRING), FieldDef(WEIGHT, Kind.STRING))
    private val kinds = fields.associate { it.path to it.kind }

    private fun kDoc(buyer: String?): RuntimeLaws.Doc {
        val d = RuntimeLaws.doc().group("/Order", 1)
        if (buyer != null) d.field(BUYER, buyer, 1, 1)
        d.group("/Order/Shipment", 1, 1).field(CARRIER, "DHL", 1, 1, 1)
        d.group("/Order/Shipment/Parcel", 1, 1, 1).field(WEIGHT, "5", 1, 1, 1, 1)
        return d
    }

    private fun iDoc(buyer: String?): Document {
        val d = Document(kinds)
        if (buyer != null) d.field(BUYER, buyer, 1, 1)
        d.group("/Order/Shipment", 1, 1).field(CARRIER, "DHL", 1, 1, 1)
        d.group("/Order/Shipment/Parcel", 1, 1, 1).field(WEIGHT, "5", 1, 1, 1, 1)
        return d
    }

    private fun kFires(group: String, errorRel: String, cond: String, buyer: String?): Boolean =
        RuntimeLaws.messages(RuntimeLaws.withRule(modelJson, group, "R", errorRel, "NA", cond), kDoc(buyer)).any { it.code() == "NA" }

    private fun iFires(group: String, errorRel: String, cond: String, buyer: String?): Boolean =
        DmInterpreter(ModelDef(fields, listOf(RuleDef("$group/R", group, cond, "NA", errorRel)))).validateFull(iDoc(buyer)).any { it.code == "NA" }

    /** kernel==interpreter on [cond], AND [cond] behaves identically to its plain-`..` [twin]. */
    private fun agreeAndEquals(group: String, errorRel: String, cond: String, twin: String, buyer: String?, note: String) {
        val k = kFires(group, errorRel, cond, buyer)
        val i = iFires(group, errorRel, cond, buyer)
        assertEquals(k, i, "$note — kernel/interp disagree (k=$k i=$i) for \"$cond\"")
        assertEquals(kFires(group, errorRel, twin, buyer), k, "$note — `..Name` diverges from its plain-`..` twin \"$twin\"")
    }

    @Test
    fun singleLevelNamedTurningPointFiresLikePlainParent() {
        val cond = """FieldFilled(Carrier) And [..Order/Buyer] == "acme""""
        val twin = """FieldFilled(Carrier) And [../Buyer] == "acme""""
        agreeAndEquals("/Order/Shipment", "../Carrier", cond, twin, "acme", "buyer matches → fires")
        agreeAndEquals("/Order/Shipment", "../Carrier", cond, twin, "other", "buyer differs → no fire")
    }

    @Test
    fun relUpThenNamedTurningPointFiresLikePlainGrandparent() {
        val cond = """FieldFilled(Weight) And [../..Order/Buyer] == "acme""""
        val twin = """FieldFilled(Weight) And [../../Buyer] == "acme""""
        agreeAndEquals("/Order/Shipment/Parcel", "../Weight", cond, twin, "acme", "grandparent buyer matches → fires")
        agreeAndEquals("/Order/Shipment/Parcel", "../Weight", cond, twin, "other", "grandparent buyer differs → no fire")
    }

    private companion object {
        const val BUYER = "/Order/Buyer"
        const val CARRIER = "/Order/Shipment/Carrier"
        const val WEIGHT = "/Order/Shipment/Parcel/Weight"
    }
}
