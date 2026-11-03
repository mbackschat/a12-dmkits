package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The ENTRY model's frame values A12 judges by their text: the name it derives for its generated code, the time zone
 * and the model version ([KERNEL-FINDINGS KF650], `adapter.laws.CheckerFrameValueAgreementTest`).
 *
 * Each is null where the member is absent or of a kind [frameDefects] reports, so a value here is always one A12
 * reads, a number or a Boolean as its text. A reached model's values are never asked for: A12 builds and checks the
 * entry with an included model's name, version, time zone and characters all refused, measured on each.
 *
 * @param generatedCodeName the name A12 derives for the entry's generated code, lower-cased and before A12 escapes the
 *     characters a Java name cannot carry, which leaves every character [frameValueFindings] reads in place.
 * @param timeZone `content.modelConfig.timeZone`.
 * @param modelVersion `header.modelVersion`.
 */
data class FrameValues(
    val generatedCodeName: String? = null,
    val timeZone: String? = null,
    val modelVersion: String? = null,
)

/** An entry model's [FrameValues], and a sentence for each value A12 fails on before it can judge the model. */
internal class EntryFrameValues(val values: FrameValues, val refusals: List<String>)

internal fun JsonObject?.entryFrameValues(): EntryFrameValues {
    val refusals = mutableListOf<String>()
    val version = (this?.get("header") as? JsonObject)?.get("modelVersion").frameText()
    val major = version?.let(VERSION::matchEntire)?.groupValues?.get(1)
    if (major != null && major.asciiIntOrNull() == null) {
        refusals += "In the entry model, `header.modelVersion` holds `$version`, whose major version A12 cannot read as " +
            "a Java `int`, so A12 builds the model but its consistency check cannot run"
    }
    val content = this?.get("content") as? JsonObject
    val values = FrameValues(
        generatedCodeName = (content?.get("modelInfo") as? JsonObject)?.generatedCodeName(refusals),
        // Kernel 31.1.1's expand copy omits an empty string, so an empty zone is the default one, never judged
        // (KERNEL-FINDINGS KF658, `adapter.laws.CheckerEmptyMemberAgreementTest`).
        timeZone = (content?.get("modelConfig") as? JsonObject)?.get("timeZone").frameText()?.ifEmpty { null },
        modelVersion = version,
    )
    return EntryFrameValues(values, refusals)
}

/**
 * The name A12 gives the entry's generated code (`DocumentModelServiceUtils.createGeneratedCodeId`): the model name,
 * less a trailing `_` and branch when the lower-cased name ends in them, joined by `_` to that branch, each part kept
 * only when it is not blank, and each lower-cased. So `Policy_V1` on the branch `v1` is `policy_v1`, `_xmlb` on `xmlb`
 * is `xmlb`, and an absent name is the empty name — unless a branch is declared, which A12 compares against the absent
 * name and fails on.
 *
 * Kernel 31.1.1 decides the branch on the lower-cased name and then removes `_` and branch from the ORIGINAL name with
 * a case-blind end match (`Strings.CI.removeEnd`), which compares character by character. Lower-casing can lengthen a
 * name, each `İ` (U+0130) becoming two units, so the two tests can part: `xmlİİ_b` on the branch `b` becomes `xmlİİ`
 * and is refused, and `İİİ_b` becomes `İİİ` and is admitted, where 30.8.1 cut at an index past its end and threw.
 * Both are measured. Lower-casing is the root locale's, as A12's is on a host whose default locale lower-cases like
 * it; a Turkish, Azeri or Lithuanian default locale lower-cases `I` differently, and no such host is measured.
 *
 * Null where A12 never derives it: a name or branch of a kind A12 cannot bind.
 */
private fun JsonObject.generatedCodeName(refusals: MutableList<String>): String? {
    val name = this["name"]
    val branch = this["branch"]?.takeIf { it !is JsonNull }
    if (name != null && name.frameText() == null || branch != null && branch.frameText() == null) {
        return null
    }
    val written = name?.frameText() ?: return if (branch == null) {
        ""
    } else {
        refusals += "In the entry model, `content.modelInfo` declares a `branch` and no `name`, which A12 compares " +
            "the branch against, so A12 builds the model but its consistency check cannot run"
        null
    }
    val version = branch?.frameText()?.javaLowercase()?.takeIf { written.javaLowercase().endsWith("_$it") }
    val suffix = version?.let { "_$it" }
    val modelName = if (suffix != null && written.javaEndsWithIgnoringCase(suffix)) written.dropLast(suffix.length) else written
    return listOfNotNull(modelName, version).filterNot { it.isA12Blank() }.joinToString("_") { it.javaLowercase() }
}

/**
 * A scalar as the text A12's reader binds to a `String` ([boundText]), and a number whose exact text is not known here
 * as written: no number's text, however A12 renders it, is empty, begins with `xml`, names a time zone or matches
 * `major.minor.patch`, so every law below gives a number one verdict whatever its rendering.
 */
private fun JsonElement?.frameText(): String? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.boundText() ?: it.content }

/** `VersionChecker`'s pattern, its digits ASCII as Java's `\d` is: a major, then a minor read as a decimal. */
private val VERSION = Regex("([0-9]+)\\.([0-9]+\\.[0-9]+)")

/** The version A12 31.1.1 writes and reads up to, `DocumentModelContent.VERSION` (KERNEL-UPGRADE). */
private const val A12_VERSION = "29.4.0"
private val A12_PARTS = VERSION.matchEntire(A12_VERSION)!!.groupValues
private val A12_MAJOR = A12_PARTS[1].toInt()
private val A12_MINOR = A12_PARTS[2].toDouble()

/**
 * What the entry's generated-code name and time zone cost it, each refusal A12's own and either withholding a
 * computation cycle beside it, as measured (`adapter.laws.CheckerFrameValueAgreementTest`).
 *
 * The generated-code name must not be empty and must not begin with `xml`, in any case (`NoMetaModelChecks`'s
 * name pattern; every character it escapes makes a valid name), drawing `MVK_INVALID_NAME_4_GENERATED_CODE`. The time
 * zone must be one A12 knows ([isA12TimeZone]), else `MVK_INVALID_TIME_ZONE`.
 */
internal fun TrackedFactBase.frameValueFindings(): List<Finding> {
    val values = frameValues
    val name = values.generatedCodeName?.takeIf { it.isEmpty() || it.startsWith("xml") }?.let {
        frameFinding("MVK_INVALID_NAME_4_GENERATED_CODE",
            "A12 names the model's generated code `$it` after `content.modelInfo`'s `name` and `branch`, and that name " +
                "must neither be empty nor begin with `xml` — give the model a name such as `policy`")
    }
    val zone = values.timeZone?.takeUnless(::isA12TimeZone)?.let {
        frameFinding("MVK_INVALID_TIME_ZONE",
            "`content.modelConfig.timeZone` holds `$it`, which is no time zone A12 knows — write an id such as " +
                "`Europe/Berlin` or `UTC` exactly, or an offset such as `GMT+01:00`")
    }
    return listOfNotNull(name, zone)
}

/**
 * The two codeless sentences A12's version rules state, published under this checker's codes: [RK_MODEL_VERSION_PATTERN]
 * when the entry's version is not `major.minor.patch` in ASCII digits, and [RK_MODEL_VERSION_INCOMPATIBLE] unless the
 * major is 29 and the minor and patch, read as one decimal, at most `4.0`, so `29.3.99` and `29.04.0` are compatible
 * and `29.4.1` is not. A text failing the pattern draws both, in that order, as A12 states both.
 *
 * A12 states them first and they withhold nothing, which is why they are asked at their own stage
 * ([ModelStage.VERSION]). A major past Java's `int` never reaches them: A12's check throws on it, a refusal of the
 * preparation ([EntryFrameValues.refusals]) that pre-empts every finding.
 */
internal fun TrackedFactBase.versionFindings(): List<Finding> {
    val version = frameValues.modelVersion
    val parts = version?.let(VERSION::matchEntire)?.groupValues
    return when {
        version == null -> emptyList()
        parts?.get(1)?.asciiIntOrNull() == A12_MAJOR && parts[2].toDouble() <= A12_MINOR -> emptyList()
        else -> listOfNotNull(
            frameFinding(RK_MODEL_VERSION_PATTERN,
                "`header.modelVersion` holds `$version`, which is not a version `major.minor.patch` in ASCII digits")
                .takeIf { parts == null },
            frameFinding(RK_MODEL_VERSION_INCOMPATIBLE,
                "`header.modelVersion` holds `$version`, which A12 $A12_VERSION cannot read — write `$A12_VERSION` " +
                    "or an earlier $A12_MAJOR version"),
        )
    }
}

private fun frameFinding(code: String, explain: String) = Finding(
    FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, code, "$explain, so A12 refuses the model",
)

internal const val RK_MODEL_VERSION_PATTERN = "RK_MODEL_VERSION_PATTERN"
internal const val RK_MODEL_VERSION_INCOMPATIBLE = "RK_MODEL_VERSION_INCOMPATIBLE"
