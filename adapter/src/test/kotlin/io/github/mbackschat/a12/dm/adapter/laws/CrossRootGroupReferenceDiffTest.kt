package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A relative reference may turn on the model root into a sibling root group since kernel 31.3.1 (A12K-4276,
 * [KF679](../../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf679)), and the portable checker must agree with
 * A12 on every role that walks a path: a condition operand, a message parameter, an error target, a computed
 * target and a computation operand. Each row pins A12's classes and requires the checker's to equal them; the
 * admitted rows are the ones the checker refused before. The runtime row compares both kernel strategies and the
 * interpreter on a rule that reads the sibling root group, with a document where the rule stays silent as the control.
 *
 * Own domain (an e-commerce order and its customer).
 */
class CrossRootGroupReferenceDiffTest {

    private val shop = DocumentModelDraft.named("shop").locale("en_US").locale("de_DE")
        .rootGroup("Order") { o -> o
            .stringField("Code") { }
            .group("Lines") { g -> g.repeatable(10).numberField("Amount") { }.stringField("Sku") { } }
        }
        .rootGroup("Customer") { c -> c.stringField("Name") { }.stringField("Tier") { } }
        .build().toJson()

    @Test
    fun theCheckerNamesA12sClassOnEveryRoleThatWalksAPath() {
        val expected = linkedMapOf<String, Set<String>>()
        val models = linkedMapOf<String, String>()
        fun row(label: String, model: String, vararg a12: String) {
            models[label] = model
            expected[label] = a12.toSet()
        }
        fun rootRule(operand: String) =
            RuntimeLaws.withRule(shop, "/Order", "ProbeRule", "../Code", "PROBE", "FieldFilled(Code) And $operand")
        fun lineRule(operand: String) =
            RuntimeLaws.withRule(shop, "/Order/Lines", "ProbeRule", "../Amount", "PROBE", "FieldFilled(Amount) And $operand")
        fun rootMessage(text: String) = RuntimeLaws.withRuleMessages(shop, "/Order", "ProbeRule", "../Code", "PROBE",
            "FieldFilled(Code) And FieldFilled(../Customer/Name)", text, text)
        fun lineMessage(text: String) = RuntimeLaws.withRuleMessages(shop, "/Order/Lines", "ProbeRule", "../Amount",
            "PROBE", "FieldFilled(Amount) And FieldFilled(../../Customer/Name)", text, text)
        fun computed(target: String, operation: String = "[Code]") = ModelWriter.addComputation(shop,
            DraftComputation.builder().groupPath("/Order").name("NameCalc").computedFieldRelPath(target)
            .alternatives(listOf(DraftComputation.Alternative(null, operation))).build(),
            listOf(Dsl.en("The name follows the code."), Dsl.de("Der Name folgt dem Code.")))

        row("condition from a root group: a sibling root group's field", rootRule("FieldFilled(../Customer/Name)"))
        row("… back into its own root group", rootRule("FieldFilled(../Order/Code)"))
        row("… a sibling root group itself", rootRule("GroupFilled(../Customer)"))
        row("… a missing field in a sibling", rootRule("FieldFilled(../Customer/Nope)"), "MVK_INVALID_ENTITY")
        row("… a name that is no root group", rootRule("FieldFilled(../Nope/Name)"), "MVK_INVALID_ROOT_GROUP")
        row("… a group name that is no root group", rootRule("GroupFilled(../Nope)"), "MVK_INVALID_ROOT_GROUP")
        row("… stopping on the model root", rootRule("GroupFilled(..)"), "MVK_TOO_MANY_PARENT_GROUPS")
        row("… a starred step onto the model root", rootRule("GroupFilled(..*)"), "MVK_INVALID_WILDCARD_REL")
        row("… a starred turning point", rootRule("FieldFilled(..*/Customer/Name)"), "MVK_INVALID_WILDCARD_REL")
        row("… past the model root", rootRule("GroupFilled(../..)"), "MVK_TOO_MANY_PARENT_GROUPS")
        row("… past it with a star on the step beyond", rootRule("GroupFilled(../..*)"), "MVK_TOO_MANY_PARENT_GROUPS")
        row("… past it on the way to a sibling", rootRule("FieldFilled(../../Customer/Name)"), "MVK_TOO_MANY_PARENT_GROUPS")
        row("condition from a nested group: a sibling root group's field", lineRule("FieldFilled(../../Customer/Name)"))
        row("… compared", lineRule("[../../Customer/Tier] == \"GOLD\""))
        row("… a name that is no root group", lineRule("FieldFilled(../../Nope/Name)"), "MVK_INVALID_ROOT_GROUP")
        row("… past the model root", lineRule("FieldFilled(../../../Customer/Name)"), "MVK_TOO_MANY_PARENT_GROUPS")
        row("message from a root group: a sibling root group's field", rootMessage("see \$../Customer/Name\$"))
        row("… its value", rootMessage("see \$../Customer/Name.value\$"))
        row("… a name that is no root group", rootMessage("see \$../Nope/Name\$"), "INVALID_ROOT_GROUP")
        row("… stopping on the model root", rootMessage("see \$..\$"), "ERROR_TEXT_PARAMETER_TOO_MANY_PARENT_GROUPS")
        row("… past the model root", rootMessage("see \$../..\$"), "ERROR_TEXT_PARAMETER_TOO_MANY_PARENT_GROUPS")
        row("message from a nested group: a sibling root group's field", lineMessage("see \$../../Customer/Name\$"))
        row("… a name that is no root group", lineMessage("see \$../../Nope\$"), "INVALID_ROOT_GROUP")
        row("… stopping on the model root", lineMessage("see \$../..\$"), "ERROR_TEXT_PARAMETER_TOO_MANY_PARENT_GROUPS")
        row("… past the model root", lineMessage("see \$../../../Customer/Name\$"), "ERROR_TEXT_PARAMETER_TOO_MANY_PARENT_GROUPS")
        row("error target in a sibling root group",
            RuntimeLaws.withRule(shop, "/Order", "ProbeRule", "../../Customer/Name", "PROBE", "FieldFilled(../Customer/Name)"))
        row("error target under a name that is no root group",
            RuntimeLaws.withRule(shop, "/Order", "ProbeRule", "../../Nope/Name", "PROBE", "FieldFilled(Code)"),
            "MVK_ERROR_FIELD_MISSING", "MVK_MISSING_ERROR_FIELD")
        row("computed target in a sibling root group", computed("../../Customer/Name"))
        row("computed target under a name that is no root group", computed("../../Nope/Name"), "RK_COMPUTED_FIELD_NOT_FOUND")
        row("computed target past the model root", computed("../../../Customer/Name"), "CK_MODEL_NOT_BUILDABLE")
        row("computation operand in a sibling root group", computed("../Code", "[../Customer/Name]"))
        row("computation operand under a name that is no root group", computed("../Code", "[../Nope/Name]"),
            "MVK_INVALID_ROOT_GROUP")

        assertEquals(expected, models.mapValues { (_, json) -> kernelModelClasses(json) }, "A12's classes")
        assertEquals(expected, models.mapValues { (_, json) -> rejectingCodes(json).toSet() }, "the checker's classes")
    }

    @Test
    fun aRuleReadingASiblingRootGroupFiresAsBothKernelStrategiesDo() {
        val tier = RuntimeLaws.withRule(shop, "/Order/Lines", "TierRule", "../Amount", "TIER",
            "[Amount] > 0 And [../../Customer/Tier] == \"GOLD\"")
        val gold = listOf("TIER|/Order[1]/Lines[1]/Amount", "TIER|/Order[1]/Lines[2]/Amount")
        assertEquals(List(3) { gold }, observe(tier, "GOLD"), "a gold customer: every line, on every engine")
        assertEquals(List(3) { listOf<String>() }, observe(tier, "SILVER"), "the control: no line")
    }

    /** [dynamic Groovy, generated Java, interpreter] on an order with two lines for a customer of [tier]. */
    private fun observe(model: String, tier: String): List<List<String>> {
        val kDoc = RuntimeLaws.doc().group("/Order", 1).field("/Order/Code", "A1", 1, 1)
            .group("/Order/Lines", 1, 1).group("/Order/Lines", 1, 2)
            .field("/Order/Lines/Amount", "3", 1, 1, 1).field("/Order/Lines/Amount", "4", 1, 2, 1)
            .group("/Customer", 1).field("/Customer/Tier", tier, 1, 1)
        val dynamic = RuntimeLaws.messages(model, kDoc).map { "${it.code()}|${it.field()}" }
        val java = RuntimeLaws.kernelJavaEngine(model)
        val generated = java.messages(java.instance(kDoc)).map { "${it.code()}|${it.field()}" }
        val definition = ModelLoader.load(model)
        val iDoc = definition.newDocument().group("/Order", 1).field("/Order/Code", "A1", 1, 1)
            .group("/Order/Lines", 1, 1).group("/Order/Lines", 1, 2)
            .field("/Order/Lines/Amount", "3", 1, 1, 1).field("/Order/Lines/Amount", "4", 1, 2, 1)
            .group("/Customer", 1).field("/Customer/Tier", tier, 1, 1)
        val interpreted = DmInterpreter(definition).validateFull(iDoc).map { "${it.code}|${it.errorPath}" }
        return listOf(dynamic, generated, interpreted)
    }
}
