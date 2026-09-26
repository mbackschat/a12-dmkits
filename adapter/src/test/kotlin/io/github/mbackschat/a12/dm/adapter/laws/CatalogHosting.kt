package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorEntry

/**
 * Where a catalog example becomes a rule: the fixture it is hosted in, the rule group, and the error field.
 *
 * The scheme follows the N7 guard (`rulekit.catalog.CatalogExamplesAreKernelValidTest`): an `Order` fixture,
 * and a two-level repeatable `Warehouse` for the multi-star examples. It is not N7's hosting, though. The
 * adapter's `models/order.dm.json` declares four fields the root copy lacks, and the error field is picked here
 * in model order rather than from N7's priority list. So N7's verdict does not carry over, and a consumer that
 * needs an admitted example asks A12 about it on this hosting.
 */
internal object CatalogHosting {

    /** The ordinary fixture, where every example not in [MULTI_STAR_IDS] is hosted under `/Order`. */
    val order: Fixture by lazy { Fixture(RuntimeLaws.model("models/order.dm.json"), "/Order") }

    /** The two-level repeatable fixture the multi-star examples need, hosted under `/Warehouse`. */
    val warehouse: Fixture by lazy {
        Fixture(
            DocumentModelDraft.named("warehouse").locale("en_US")
                .rootGroup("Warehouse") { warehouse ->
                    warehouse.group("Aisles") { aisles ->
                        aisles.repeatable(20)
                            .stringField("Code") { }
                            .group("Bins") { bins -> bins.repeatable(20).numberField("Quantity") { } }
                    }
                }
                .build().toJson(),
            "/Warehouse",
        )
    }

    /** Consumer-SPI operators whose generated-Java checks live in their dedicated differential. */
    val SPECIALISTS: Set<String> = setOf("CustomCondition", "ValidType", "InvalidType")

    /** The operators whose examples read two repeatable levels, which the `Order` fixture does not have. */
    val MULTI_STAR_IDS: Set<String> = setOf("Having", "CurrentRepetition")

    fun fixtureOf(entry: OperatorEntry): Fixture = if (entry.id() in MULTI_STAR_IDS) warehouse else order

    /** A hosting fixture: the model, its rule group, and its fields as the interpreter's loader reads them. */
    class Fixture(val json: String, val ruleGroup: String) {
        val def: ModelDef by lazy { ModelLoader.load(json) }

        /**
         * The first field the condition names, relative to [ruleGroup]; failing that, the first field under a group
         * the condition names. A12 refuses a rule whose condition does not reference its own error field
         * (`MVK_ERROR_FIELD_NOT_REFERENCED`), which is why the target is read off the condition at all.
         */
        fun errorFieldOf(condition: String): String {
            val relativeFields = def.fields.map { it.path.removePrefix("$ruleGroup/") }
            return relativeFields.firstOrNull { condition.references(it) }
                ?: relativeFields.firstOrNull { field ->
                    field.substringBefore('/').takeIf { it != field }?.let { condition.references(it) } == true
                }
                ?: error("no $ruleGroup field or group is referenced by catalog example: $condition")
        }

        /** [errorFieldOf] as a rule's `errorEntityRelPath`, which is relative to the rule element, one level down. */
        fun errorEntityRelPathOf(condition: String): String = "../${errorFieldOf(condition)}"
    }

    private fun String.references(pathOrName: String): Boolean =
        Regex("(?<![A-Za-z0-9_])${Regex.escape(pathOrName)}(?![A-Za-z0-9_])").containsMatchIn(this)
}
