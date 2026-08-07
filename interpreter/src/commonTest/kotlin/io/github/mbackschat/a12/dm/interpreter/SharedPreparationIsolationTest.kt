package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldType
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldTypeDeclaration
import io.github.mbackschat.a12.dm.interpreter.model.CustomTypeRejection
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.LegalCharset
import io.github.mbackschat.a12.dm.interpreter.model.MessageLabelProvider
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.PredefinedTypeRegistry
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Model-static preparation is shared between evaluators (NFR-F3), so the question this class answers is whether a
 * WARM shared instance can carry anything from one evaluator's run into another's. It must not: the shared store
 * holds only model-derived, write-once state, while everything a *configuration* decides stays per evaluator.
 *
 * The discriminator is deliberately the boundary the sharing split created — the support-audit verdict. Two
 * evaluators over ONE shared preparation differ only in whether a custom field type is registered, which changes
 * both the audit and the validation outcome. If the verdict or any plan-adjacent state leaked, an interleaved
 * sequence would return the other evaluator's answer.
 */
class SharedPreparationIsolationTest {

    private val model = ModelDef(
        fields = listOf(
            FieldDef(
                "/Order/Reference",
                Kind.STRING,
                customType = CustomFieldTypeDeclaration("Checksum"),
            ),
            FieldDef("/Order/Quantity", Kind.NUMBER),
        ),
        rules = listOf(
            RuleDef("/Order/QuantityPositive", "/Order", "[Quantity] > 0", "QTY", "../Quantity"),
        ),
    )

    /** Rejects any reference not ending in "-OK", so a registered validator is observable in the outcome. */
    private val checksum = CustomFieldType { value, _ ->
        if (value.endsWith("-OK")) null else CustomTypeRejection("BAD_CHECKSUM")
    }

    /** A private-preparation engine when [plans] is null, otherwise one sharing the supplied preparation. */
    private fun engine(plans: ModelStaticPlans?, types: Map<String, CustomFieldType>): DmInterpreter =
        if (plans == null) {
            DmInterpreter(model = model, customFieldTypes = types)
        } else {
            DmInterpreter.sharing(
                model = model,
                clock = null,
                predefinedTypes = PredefinedTypeRegistry.DEFAULT,
                customConditions = emptyMap(),
                customFieldTypes = types,
                legalCharset = LegalCharset.BMP,
                messageLabelProvider = MessageLabelProvider.MODEL_LABEL_OR_NAME,
                plans = plans,
            )
        }

    private fun document() = model.newDocument().apply {
        field("/Order/Reference", "REF-1-BAD", 1, 1)
        field("/Order/Quantity", "5", 1, 1)
    }

    @Test
    fun interleavedEvaluatorsOverOneSharedPreparationKeepTheirOwnAnswers() {
        // Baselines from FRESH, UNSHARED engines — what each configuration means on its own.
        val registeredBaseline = engine(null, mapOf("Checksum" to checksum)).let {
            it.supportAudit().map(::describe) to it.validate(document()).describe()
        }
        val unregisteredBaseline = engine(null, emptyMap()).let {
            it.supportAudit().map(::describe) to it.validate(document()).describe()
        }
        assertTrue(
            registeredBaseline != unregisteredBaseline,
            "the two configurations must differ observably, or this test cannot detect contamination",
        )

        // Now both over ONE shared preparation, interleaved and repeated so a warm instance has every chance
        // to carry the previous run's verdict or state into the next.
        val shared = ModelStaticPlans.of(model)
        val registered = engine(shared, mapOf("Checksum" to checksum))
        val unregistered = engine(shared, emptyMap())

        repeat(3) { round ->
            assertEquals(
                registeredBaseline,
                registered.supportAudit().map(::describe) to registered.validate(document()).describe(),
                "round $round: the registered evaluator must keep its own answer on a warm shared preparation",
            )
            assertEquals(
                unregisteredBaseline,
                unregistered.supportAudit().map(::describe) to unregistered.validate(document()).describe(),
                "round $round: the unregistered evaluator must keep its own answer on a warm shared preparation",
            )
        }
    }

    @Test
    fun aFreshDocumentOnAWarmSharedPreparationIsNotAffectedByEarlierDocuments() {
        val shared = ModelStaticPlans.of(model)
        val first = engine(shared, mapOf("Checksum" to checksum))
        val second = engine(shared, mapOf("Checksum" to checksum))

        val cleanDoc = model.newDocument().apply {
            field("/Order/Reference", "REF-9-OK", 1, 1)
            field("/Order/Quantity", "7", 1, 1)
        }
        val cleanBaseline = engine(null, mapOf("Checksum" to checksum)).validate(cleanDoc).describe()
        val dirtyBaseline = engine(null, mapOf("Checksum" to checksum)).validate(document()).describe()
        assertTrue(cleanBaseline != dirtyBaseline, "the two documents must differ observably")

        // Warm the shared preparation with the failing document through one evaluator, then read the clean one
        // through the other, and back again.
        assertEquals(dirtyBaseline, first.validate(document()).describe())
        assertEquals(cleanBaseline, second.validate(cleanDoc).describe())
        assertEquals(dirtyBaseline, second.validate(document()).describe())
        assertEquals(cleanBaseline, first.validate(cleanDoc).describe())
    }

    private fun describe(rule: io.github.mbackschat.a12.dm.interpreter.model.UnsupportedRule): String =
        "${rule.subject}|${rule.rulePath}|${rule.reason}"

    private fun io.github.mbackschat.a12.dm.interpreter.model.ValidationOutcome.describe(): String =
        messages.sortedBy { "${it.code}${it.errorPath}" }
            .joinToString(";") { "${it.code}@${it.errorPath}:${it.type}" } +
            "//" + unsupported.map(::describe).sorted().joinToString(";")
}
