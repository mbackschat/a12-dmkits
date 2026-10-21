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
 * **Both sides are compared TRIMMED, as Java's `String.trim` trims** ([KERNEL-FINDINGS KF569]): `" BASIC"`
 * and `"BASIC "` match the token `BASIC`, the token `" BASIC"` matches the `sValue` `BASIC`, and an `sValue`
 * that trims to nothing — a space, a tab — is unset, so A12 never asks the question. The no-break space is not
 * trimmed and stays a foreign value, which is the row that separates this from a whitespace-blind comparison;
 * the case of a letter and an inner space still count. An `sValue` carrying a supplementary-plane character is not
 * named: A12 answers it with the unsupported-signs sentence alone ([namedByA12], KERNEL-FINDINGS KF551).
 */
internal fun TrackedFactBase.enumSpecialValueFindings(): List<Finding> =
    defaultVariantFields().mapNotNull { field ->
        val body = field.enumerationBody() ?: return@mapNotNull null
        val special = body.string("sValue")?.takeIf { it.javaTrimmed().isNotEmpty() } ?: return@mapNotNull null
        val declared = (body["values"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.string("value") }
            ?: return@mapNotNull null
        if (!special.namedByA12() || special.javaTrimmed() in declared.map { it.javaTrimmed() }) {
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
