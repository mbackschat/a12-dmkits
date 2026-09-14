package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * An enumeration's `sValue` against the values it declares.
 *
 * A12 requires the special value to BE one of the enumeration's own tokens
 * ([KERNEL-FINDINGS KF366], `rulekit.validate.laws.EnumDeclarationShapeLawsTest`). This is the law modelkit's
 * `EnumSpec` already pre-checks as its one documented over-strictness (API-FINDINGS 96); measuring it here
 * gives that refusal its kernel basis and closes the same gap on the stored carrier, where another tool may
 * have written the declaration.
 *
 * **A BLANK `sValue` is not a value outside the domain** — A12 reads an empty member as unset and never asks
 * the question, so reporting one would refuse a model A12 admits.
 */
fun PreparedModel.enumSpecialValueFindings(): List<Finding> =
    elements.filter { it.declaredKind == DeclaredKind.FIELD }.mapNotNull { field ->
        val body = field.enumerationBody() ?: return@mapNotNull null
        val special = body.string("sValue")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val declared = (body["values"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.string("value") }
            ?: return@mapNotNull null
        if (special in declared) {
            return@mapNotNull null
        }
        Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            "MVK_S_VALUE_INVALID",
            "this enumeration's `sValue` is `$special`, which is not one of the values it declares — it " +
                "admits ${declared.joinToString { "`$it`" }}",
            field.path,
        )
    }
