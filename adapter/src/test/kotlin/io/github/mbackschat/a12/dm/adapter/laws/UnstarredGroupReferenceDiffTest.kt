package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check (groovy-dynamic == java-static == interpreter) — what a fired message's `referenced`
 * set contains when the condition's operand is an **UNSTARRED (nonrepeatable) group**.
 *
 * The lean project's `EXP-2026-08-11-01` asked this: `GroupStarValueAggregateDiffTest` established that a
 * **starred** group operand expands recursively to its contained fields and never contributes the group pointer
 * itself, but the star is what turns an operand into a field set in the first place — a fixed group might instead
 * be an entity reference contributing no field pointer at all. Three separate refusals in that project's
 * structural reference projection share this one witness, so one observation settles all three.
 *
 * Four accounts were on the table before the run: **recursive expansion** (every descendant field of the group),
 * **direct-child expansion** (dropping the deeper `Contact/Email`), **no contribution** (the empty set), and **a
 * group pointer**. A set that differed *between* the three operand positions would itself have been the finding.
 *
 * Both controls the request specified are here, on this same model, so a null result cannot be an instrument
 * artifact: [starredGroupOperandExpandsRecursively] reads the already-measured starred expansion through this
 * exact code path, and [plainFieldOperandsContributeThemselves] proves a retained set is the operand's contribution
 * rather than an echo of the error field.
 */
class UnstarredGroupReferenceDiffTest {

    private companion object {
        const val ERROR_FIELD = "../Billing/Street"

        /** Every rule sits on `/Order` and reports on the same error field, so only the operand differs. */
        val RULES = listOf(
            Triple("GRPFILLED", "GroupFilled(/Order/Billing)", "single-operand presence"),
            Triple("ANYFILLED", "AtLeastOneGroupFilled(/Order/Billing, /Order/Shipping)", "entity-list"),
            Triple("COUNTPOS", "NumberOfFilledGroups(/Order/Billing, /Order/Shipping) > 0", "numeric count atom"),
            Triple("STARSUM", "FieldFilled(/Order/Billing/Street) And Sum(/Order/Items*) > 0",
                "control: the measured starred expansion"),
            Triple("PLAINFIELD", "FieldFilled(/Order/Billing/Street) And FieldFilled(/Order/Reference)",
                "control: plain field operands only"),
        )
    }

    /**
     * An e-commerce order: a nonrepeatable `Billing` group with two own fields and a nonrepeatable descendant
     * group below it (so recursive and direct-child expansion differ), a disjoint nonrepeatable `Shipping` group
     * for the entity-list positions, a repeatable `Items` group for the starred control, and a plain field.
     */
    private val model: String = run {
        var json = DocumentModelDraft.named("order-reference").locale("en_US")
            .rootGroup("Order") { g ->
                g.stringField("Reference") { }
                g.group("Billing") { b ->
                    b.stringField("Street") { }
                    b.stringField("City") { }
                    b.group("Contact") { c -> c.stringField("Email") { } }
                }
                g.group("Shipping") { s -> s.stringField("Carrier") { } }
                g.group("Items") { i -> i.repeatable(10).numberField("Quantity") { it.fractionDigits(0, 0) } }
            }.build().toJson()
        RULES.forEach { (code, condition, _) ->
            json = RuntimeLaws.withRule(json, "/Order", "R$code", ERROR_FIELD, code, condition)
        }
        json
    }

    private val definition = ModelLoader.load(model)

    /**
     * One placement list, applied to both engines' documents — so the tri-check cannot silently compare two
     * different documents, which two hand-maintained copies of the same eleven lines eventually would.
     */
    private fun placements(kernel: RuntimeLaws.Doc, interpreter: Document) {
        fun group(path: String, vararg reps: Int) { kernel.group(path, *reps); interpreter.group(path, *reps) }
        fun field(path: String, value: String, vararg reps: Int) {
            kernel.field(path, value, *reps); interpreter.field(path, value, *reps)
        }
        group("/Order", 1)
        field("/Order/Reference", "ORD-7", 1, 1)
        group("/Order/Billing", 1, 1)
        field("/Order/Billing/Street", "12 Kingsway", 1, 1, 1)
        field("/Order/Billing/City", "Leeds", 1, 1, 1)
        group("/Order/Billing/Contact", 1, 1, 1)
        field("/Order/Billing/Contact/Email", "buyer@example.com", 1, 1, 1, 1)
        group("/Order/Shipping", 1, 1)
        field("/Order/Shipping/Carrier", "DPD", 1, 1, 1)
        group("/Order/Items", 1, 1)
        field("/Order/Items/Quantity", "3", 1, 1, 1)
    }

    /** Fill every operand so all five conditions are true and each rule contributes exactly one message. */
    private fun kernelDocument(): RuntimeLaws.Doc =
        RuntimeLaws.doc().also { placements(it, definition.newDocument()) }

    private fun interpreterDocument(): Document =
        definition.newDocument().also { placements(RuntimeLaws.doc(), it) }

    /** `code → the complete sorted referenced set`, from dynamic Groovy. */
    private fun dynamicReferenced(): Map<String, List<String>> =
        RuntimeLaws.messages(model, kernelDocument()).associate { it.code() to it.referenced().sorted() }

    /** The same, from the kernel's own validator compiled as Java. */
    private fun javaStaticReferenced(): Map<String, List<String>> =
        RuntimeLaws.kernelJavaEngine(model)
            .messagesModern(kernelDocument(), null, java.util.Locale.US)
            .associate { it.code() to it.referenced().sorted() }

    private fun interpreterReferenced(): Map<String, List<String>> =
        DmInterpreter(definition).validateFull(interpreterDocument())
            .associate { it.code to it.referenced.sorted() }

    /**
     * Every rule must fire EXACTLY once, or a "no contribution" reading would be an instrument artifact — and the
     * count is asserted rather than inferred from the keys, because the maps below are built with `associate`,
     * which keeps only the last pair per key and would hide a second message.
     */
    @Test
    fun everyOperandPositionFiresExactlyOnce() {
        val byCode = RuntimeLaws.messages(model, kernelDocument()).groupBy { it.code() }
        assertEquals(RULES.map { it.first }.sorted(), byCode.keys.sorted(),
            "each of the three operand positions and both controls must contribute a message")
        assertEquals(RULES.associate { it.first to 1 }, byCode.mapValues { (_, messages) -> messages.size },
            "exactly one message per rule, so a single referenced set per position is the whole contribution")
    }

    @Test
    fun bothKernelStrategiesAgreeOnEveryReferencedSet() {
        val dynamic = dynamicReferenced()
        val javaStatic = javaStaticReferenced()
        RULES.forEach { (code, condition, position) ->
            assertEquals(dynamic.getValue(code), javaStatic.getValue(code),
                "dynamic Groovy and generated Java must agree on '$condition' ($position)")
        }
    }

    @Test
    fun theInterpreterAgreesWithBothKernelStrategies() {
        val dynamic = dynamicReferenced()
        val interpreter = interpreterReferenced()
        RULES.forEach { (code, condition, position) ->
            assertEquals(dynamic.getValue(code), interpreter.getValue(code),
                "the interpreter must agree with the kernel on '$condition' ($position)")
        }
    }

    /** Control 1: the starred expansion, read through this exact code path. */
    @Test
    fun starredGroupOperandExpandsRecursively() {
        assertEquals(
            listOf("/Order[1]/Billing[1]/Street", "/Order[1]/Items[0]/Quantity"),
            dynamicReferenced().getValue("STARSUM"),
            "the measured starred expansion must still be visible here, or a null result above proves nothing",
        )
    }

    /**
     * The coordinate difference the two shapes make, which is the request's supplementary cell: a **starred**
     * group's expanded field carries the wildcard repetition `Items[0]`, while an **unstarred** group's expanded
     * fields are **concrete at the firing row** — `Billing[1]`, and `Contact[1]` for the deeper level.
     */
    @Test
    fun starredExpansionWildcardsTheRepetitionWhileUnstarredExpansionIsConcrete() {
        val referenced = dynamicReferenced()
        assertEquals(listOf("/Order[1]/Items[0]/Quantity"),
            referenced.getValue("STARSUM").filter { "Items" in it },
            "a starred group's expansion wildcards its repetition coordinate")
        assertEquals(
            listOf("/Order[1]/Billing[1]/City", "/Order[1]/Billing[1]/Contact[1]/Email", "/Order[1]/Billing[1]/Street"),
            referenced.getValue("GRPFILLED"),
            "an unstarred group's expansion is concrete at every level, including the deeper nonrepeatable one")
    }

    /**
     * Control 2: every operand of a plain-field condition contributes itself, so the channel carries operands and
     * not a fixed shape. (The kernel refuses a rule whose error field the condition never references —
     * `MVK_ERROR_FIELD_NOT_REFERENCED` — so both controls must name the error field as one of their operands;
     * that is why the request's "references only a plain field" form is not authorable.)
     */
    @Test
    fun plainFieldOperandsContributeThemselves() {
        assertEquals(
            listOf("/Order[1]/Billing[1]/Street", "/Order[1]/Reference"),
            dynamicReferenced().getValue("PLAINFIELD"),
            "both plain-field operands appear, and nothing else does",
        )
    }

    /**
     * The measurement this class exists for. **Recursive expansion**, identically in all three operand positions:
     * every descendant field of the unstarred group, including the deeper nonrepeatable `Contact/Email` that
     * direct-child expansion would have dropped; no group pointer; and for the two entity-list positions, both
     * operand groups expand. "No contribution" is refuted because `City` and `Contact/Email` are neither the error
     * field nor named anywhere in the condition, so they can only come from the operand.
     */
    @Test
    fun anUnstarredGroupOperandExpandsRecursivelyInEveryPosition() {
        val billing = listOf(
            "/Order[1]/Billing[1]/City", "/Order[1]/Billing[1]/Contact[1]/Email", "/Order[1]/Billing[1]/Street",
        )
        val billingAndShipping = (billing + "/Order[1]/Shipping[1]/Carrier").sorted()
        val dynamic = dynamicReferenced()
        assertEquals(
            mapOf("GRPFILLED" to billing, "ANYFILLED" to billingAndShipping, "COUNTPOS" to billingAndShipping),
            RULES.take(3).associate { it.first to dynamic.getValue(it.first) },
            "the presence position, the entity-list position and the numeric count atom expand alike",
        )
    }
}
