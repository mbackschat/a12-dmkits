package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationAlternative
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.PredefinedTypeRegistry
import io.github.mbackschat.a12.dm.interpreter.model.MessageLabelProvider
import io.github.mbackschat.a12.dm.interpreter.model.LegalCharset
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PreparationWorkCounterTest {

    /**
     * NFR-F3's structural half: a SECOND evaluator over the same model-static preparation must do none of the
     * model-static work, so a P-SERVICE cache entry is per model rather than per (model × configuration). The
     * kernel differential cannot be this claim's oracle — moving plan ownership has no semantic effect — so this
     * counter is the enforcement locus.
     */
    @Test
    fun siblingEngineOverSharedPlansDoesNoModelStaticWork() {
        val model = serviceShapedModel()
        val shared = ModelStaticPlans.of(model)
        val observed = mutableListOf<PreparationEvent>()
        val doc = model.newDocument().apply { field("/Invoice/Base", "1", 1, 1) }

        // The FIRST evaluator pays the model-static work.
        val first = engineSharing(shared, model)
        shared.preparationObserver = PreparationObserver(observed::add)
        first.supportAudit()
        first.validate(doc)
        first.compute(doc)
        first.computeField("/Invoice/Net", doc)
        val afterFirst = observed.toList()
        assertEquals(
            PreparationWork.entries.toSet(),
            afterFirst.map { it.work }.toSet(),
            "the first engine must exercise EVERY model-static work class, or 'zero for the sibling' is a weak claim",
        )

        // A SECOND evaluator over the same shared preparation must add nothing.
        val second = engineSharing(shared, model)
        second.supportAudit()
        second.validate(doc)
        second.compute(doc)
        second.computeField("/Invoice/Net", doc)

        assertEquals(
            afterFirst,
            observed,
            "a sibling engine over one shared model-static preparation must add no model-static work",
        )
        assertEquals(
            first.validate(doc).messages.map { it.code to it.errorPath },
            second.validate(doc).messages.map { it.code to it.errorPath },
            "sharing preparation must not change what a sibling engine reports",
        )
    }

    /**
     * The positive control for the guard above: an evaluator that does NOT share the preparation repeats every
     * work class, so the assertion is not vacuously satisfied by an observer that never fires.
     */
    @Test
    fun anUnsharedSiblingEngineRepeatsTheWork() {
        val model = serviceShapedModel()
        val shared = ModelStaticPlans.of(model)
        val observed = mutableListOf<PreparationEvent>()
        val doc = model.newDocument().apply { field("/Invoice/Base", "1", 1, 1) }

        val first = engineSharing(shared, model)
        shared.preparationObserver = PreparationObserver(observed::add)
        first.supportAudit()
        first.computeField("/Invoice/Net", doc)
        val shape = observed.groupingBy { it.work }.eachCount()
        assertTrue(shape.isNotEmpty(), "the shared engine must have performed work to compare against")

        // A private preparation, observed on its own: the same classes are paid again.
        val ownPlans = ModelStaticPlans.of(model)
        val repeated = mutableListOf<PreparationEvent>()
        ownPlans.preparationObserver = PreparationObserver(repeated::add)
        val unshared = engineSharing(ownPlans, model)
        unshared.supportAudit()
        unshared.computeField("/Invoice/Net", doc)

        assertEquals(
            shape,
            repeated.groupingBy { it.work }.eachCount(),
            "an unshared engine must repeat exactly the work classes sharing avoids",
        )
    }


    /**
     * The spec's sentence is about a second `Interpreter` over the same `PreparedModel`, so it is tested through
     * that public boundary and not only through a hand-built [ModelStaticPlans]. Without this arm, replacing
     * `PreparedModel.plans()` with a fresh instance per call would leave every other test green.
     *
     * The observer is attached BEFORE the first interpreter exists, because `createInterpreter` eagerly audits and
     * would otherwise have forced every plan already — leaving the comparison empty-to-empty and vacuous.
     */
    @Test
    fun siblingInterpreterOverOnePreparedModelDoesNoModelStaticWork() {
        val prepared = PreparedModel.loadExpandedJson(COMPUTED_MODEL_JSON)
        val observed = mutableListOf<PreparationEvent>()
        prepared.plans().preparationObserver = PreparationObserver(observed::add)
        val document = prepared.documents.seedDocument()

        val first = prepared.createInterpreter()
        first.validateFull(document)
        first.compute(document)
        val afterFirst = observed.toList()
        assertTrue(
            afterFirst.isNotEmpty(),
            "the first interpreter must perform model-static work, or the sibling comparison is vacuous",
        )

        val second = prepared.createInterpreter()
        second.validateFull(document)
        second.compute(document)

        assertEquals(
            afterFirst,
            observed,
            "a sibling interpreter over one PreparedModel must add no model-static work",
        )
    }

    private fun engineSharing(plans: ModelStaticPlans, model: ModelDef): DmInterpreter =
        DmInterpreter.sharing(
            model = model,
            clock = { null },
            predefinedTypes = PredefinedTypeRegistry.DEFAULT,
            customConditions = emptyMap(),
            customFieldTypes = emptyMap(),
            legalCharset = LegalCharset.BMP,
            messageLabelProvider = MessageLabelProvider.MODEL_LABEL_OR_NAME,
            plans = plans,
        )

    private fun serviceShapedModel(): ModelDef = ModelDef(
        fields = listOf(
            FieldDef("/Invoice/Base", Kind.NUMBER),
            FieldDef("/Invoice/Net", Kind.NUMBER),
        ),
        rules = listOf(
            RuleDef("/Invoice/Positive", "/Invoice", "[Base] > 0", "POS", "../Base"),
        ),
        computations = listOf(
            ComputationDef(
                "/Invoice/Net",
                "/Invoice",
                listOf(ComputationAlternative(null, "[Base] + 1")),
                name = "NetComp",
            ),
        ),
    )

    @Test
    fun modelStaticWorkRunsOncePerPreparedEngine() {
        val model = ModelDef(
            fields = listOf(
                FieldDef("/Invoice/Base", Kind.NUMBER),
                FieldDef("/Invoice/Net", Kind.NUMBER),
            ),
            rules = listOf(
                RuleDef("/Invoice/Positive", "/Invoice", "[Base] > 0", "POS", "../Base"),
            ),
            computations = listOf(
                ComputationDef(
                    "/Invoice/Net",
                    "/Invoice",
                    listOf(ComputationAlternative(null, "[Base] + 1")),
                    name = "NetComp",
                ),
            ),
        )
        val observed = mutableListOf<PreparationEvent>()
        val engine = DmInterpreter(model).also {
            it.preparationObserver = PreparationObserver(observed::add)
        }
        val first = model.newDocument().apply { field("/Invoice/Base", "1", 1, 1) }
        val second = model.newDocument().apply { field("/Invoice/Base", "2", 1, 1) }

        engine.supportAudit()
        engine.validate(first)
        engine.compute(first)
        engine.computeField("/Invoice/Net", first)
        val prepared = observed.toList()
        engine.validate(second)
        engine.compute(second)
        engine.computeField("/Invoice/Net", second)

        assertEquals(prepared, observed, "row count, document identity, and repeated calls must add no model-static work")
        assertEquals(
            mapOf(
                PreparationWork.RULE_AST to 1,
                PreparationWork.COMPUTATION_AST to 1,
                PreparationWork.IMPLICIT_RULE_AST to 1,
                PreparationWork.RULE_PLAN to 2,
                PreparationWork.COMPUTATION_PLAN to 1,
                PreparationWork.ALL_COMPUTATION_ORDER to 1,
                PreparationWork.COMPUTATION_CLOSURE to 1,
            ),
            observed.groupingBy { it.work }.eachCount(),
        )
    }

    internal companion object {
        /** A self-contained computed model, so the public boundary exercises the computation classes. */
        const val COMPUTED_MODEL_JSON = """{"header":{"id":"subscription","modelType":"document","modelVersion":"29.4.0","locales":[{"code":"en_US"},{"code":"de_DE"}],"labels":[],"annotations":[],"modelReferences":[]},"content":{"modelInfo":{"name":"subscription","variant":"cockpit","revision":"Arbeitsversion","immutable":false},"modelConfig":{"decimalSeparator":".","timeZone":"Europe/Berlin","conditionLanguage":{"code":"en_US"},"fieldRefByShortNameAllowed":true},"modelRoot":{"rootGroups":[{"type":"Group","id":"/Subscription","name":"Subscription","Group":{"repeatability":1,"elements":[{"type":"Field","id":"/Subscription/Tier","name":"Tier","Field":{"fieldType":{"type":"EnumerationType","EnumerationType":{"values":[{"value":"BASIC","label":[{"locale":"en_US","text":"Basic"},{"locale":"de_DE","text":"Basis"}]},{"value":"PRO","label":[{"locale":"en_US","text":"Pro"},{"locale":"de_DE","text":"Pro"}]},{"value":"ENTERPRISE","label":[{"locale":"en_US","text":"Enterprise"},{"locale":"de_DE","text":"Enterprise"}]}],"categories":[{"name":"Pricing","values":["FREE","PAID","PAID"]}]}}}},{"type":"Field","id":"/Subscription/AutoRenew","name":"AutoRenew","Field":{"fieldType":{"type":"BooleanType"}}},{"type":"Field","id":"/Subscription/TermsAccepted","name":"TermsAccepted","Field":{"fieldType":{"type":"ConfirmType"}}},{"type":"Group","id":"/Subscription/Addons","name":"Addons","Group":{"repeatability":20,"elements":[{"type":"Field","id":"/Subscription/Addons/Name","name":"Name","Field":{"fieldType":{"type":"StringType"}}},{"type":"Field","id":"/Subscription/Addons/MonthlyFee","name":"MonthlyFee","Field":{"fieldType":{"type":"NumberType","NumberType":{"maxFractionalDigits":2}}}}]}},{"type":"Group","id":"/Subscription/Billing","name":"Billing","Group":{"elements":[{"type":"Field","id":"/Subscription/Billing/InvoiceEmail","name":"InvoiceEmail","Field":{"fieldType":{"type":"StringType"}}},{"type":"Field","id":"/Subscription/Billing/BaseFee","name":"BaseFee","Field":{"fieldType":{"type":"NumberType","NumberType":{"maxFractionalDigits":2}}}},{"type":"Field","id":"/Subscription/Billing/EffectiveFee","name":"EffectiveFee","Field":{"fieldType":{"type":"NumberType","NumberType":{"maxFractionalDigits":2}}}},{"type":"Computation","id":"/Subscription/Billing/EffectiveFeeComp","name":"EffectiveFeeComp","Computation":{"computedFieldRelPath":"../EffectiveFee","computationAlternatives":[{"operation":"[BaseFee]"}],"errorMessage":[{"locale":"en_US","text":"The effective fee equals the base fee."},{"locale":"de_DE","text":"Die Effektivgebühr entspricht der Basisgebühr."}]}}]}}]}}]}}}"""
    }
}
