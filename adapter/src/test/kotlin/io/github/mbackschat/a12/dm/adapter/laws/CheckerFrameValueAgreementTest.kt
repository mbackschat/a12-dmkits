package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.consistency.CheckOutcome
import io.github.mbackschat.a12.dm.consistency.ConsistencyWorkspace
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.consistency.outcome
import io.github.mbackschat.a12.dm.core.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The checker against A12 on the frame values A12 judges by their text (KERNEL-FINDINGS KF650): the name it derives
 * for the generated code from `content.modelInfo`'s `name` and `branch`, `content.modelConfig.timeZone`,
 * `header.modelVersion`, and the entries of `content.modelConfig.supportedCharacters`.
 *
 * Every row is A12's measured class set first, written out, and the checker's second, which must equal it. The
 * expected column is what makes a row a measurement rather than an agreement two wrong readers could share: a checker
 * law and the harness's sentence keys agreeing on a wrong class would still fail on it. Own domain (an insurance policy
 * with a reference).
 */
class CheckerFrameValueAgreementTest {

    private val mapper = ObjectMapper()

    private val base = DocumentModelDraft.named("policy-frame").locale("en_US")
        .rootGroup("Policy") { p -> p.stringField("Ref") { } }
        .build().toJson()

    /**
     * The generated-code name is the lower-cased model name, less a trailing `_` and branch when it ends in them, joined
     * to that branch, blank parts dropped; it must be neither empty nor begin with `xml`. The branch rows are the
     * discriminators against reading the name alone: `_xmlb` is refused only beside the branch `xmlb`, and `_` only beside
     * the empty branch. A branch beside no name leaves no verdict, because A12 compares the branch against the absent name.
     */
    @Test
    fun theGeneratedCodeNameIsJudgedAsA12DerivesIt() {
        val rows = linkedMapOf<String, String>()
        for (name in listOf(null, "\"\"", "\"Policy\"", "\"_p\"", "\"1p\"", "\"p q\"", "\"Prämie\"", "\"xm\"", "\"xmlP\"", "\"XmLp\"", "5", "true")) {
            rows["name $name"] = named(name, branch = null)
        }
        for ((name, branch) in listOf("\"_xmlb\"" to "xmlb", "\"_xmlb\"" to "XMLB", "\"  _xmlb\"" to "xmlb", "\"x_xmlb\"" to "xmlb",
            "\"_\"" to "", "\"xml_v1\"" to "v1", "\"Policy_V1\"" to "v1", "\"a_xml\"" to "xml", "\"xmlP\"" to " ", null to "v1")) {
            rows["name $name branch \"$branch\""] = named(name, branch)
        }
        assertAgreement(rows, refused = mapOf(
            "name null" to NAME, "name \"\"" to NAME, "name \"xmlP\"" to NAME, "name \"XmLp\"" to NAME,
            "name \"_xmlb\" branch \"xmlb\"" to NAME, "name \"_xmlb\" branch \"XMLB\"" to NAME,
            "name \"  _xmlb\" branch \"xmlb\"" to NAME, "name \"_\" branch \"\"" to NAME, "name \"xml_v1\" branch \"v1\"" to NAME,
            "name \"xmlP\" branch \" \"" to NAME, "name null branch \"v1\"" to UNBUILT,
        ))
    }

    /** `VersionChecker`: a major of 28 and a minor and patch, read as one decimal, at most `4.0`; ASCII digits only. */
    @Test
    fun theModelVersionIsJudgedAsA12ReadsIt() {
        val texts = listOf("28.4.0", "28.0.0", "28.3.99", "28.4.00", "28.04.0", "028.4.0", "28.4.0000000000000000001", "28.4.1",
            "29.0.0", "27.9.9", "28.5", "x", "28.4.0 ", "٢٨.4.0", "28.4.0.0", "-28.4.0", "99999999999.0.0")
        val rows = linkedMapOf<String, String>()
        texts.forEach { rows["version \"$it\""] = edited("/header/modelVersion", mapper.valueToTree(it)) }
        listOf("5", "true").forEach { rows["version $it"] = edited("/header/modelVersion", mapper.readTree(it)) }
        val both = PATTERN + INCOMPATIBLE
        assertAgreement(rows, refused = mapOf(
            "version \"28.4.1\"" to INCOMPATIBLE, "version \"29.0.0\"" to INCOMPATIBLE, "version \"27.9.9\"" to INCOMPATIBLE,
            "version \"28.5\"" to both, "version \"x\"" to both, "version \"28.4.0 \"" to both, "version \"٢٨.4.0\"" to both,
            "version \"28.4.0.0\"" to both, "version \"-28.4.0\"" to both, "version 5" to both, "version true" to both,
            // The major overflows Java's `int`, which A12 parses it into, so its check throws.
            "version \"99999999999.0.0\"" to UNBUILT,
        ))
    }

    /** `NoMetaModelChecks.checkTimeZone`: `GMT`, an id of the JDK's registry exactly, or a custom offset up to `23:59`. */
    @Test
    fun theTimeZoneIsJudgedAsTheJdkKnowsIt() {
        val known = listOf("UTC", "GMT", "Europe/Berlin", "EST", "Etc/GMT+5", "GMT+1", "GMT+01:00", "GMT+0", "GMT+00:00", "GMT-0130")
        val unknown = listOf("utc", "europe/berlin", "x", "GMT+24", "UTC+1", "", " ", "GMT ", "Z", "+01:00")
        val rows = linkedMapOf<String, String>()
        (known + unknown).forEach { rows["timeZone \"$it\""] = edited("/content/modelConfig/timeZone", mapper.valueToTree(it)) }
        listOf("5", "true").forEach { rows["timeZone $it"] = edited("/content/modelConfig/timeZone", mapper.readTree(it)) }
        assertAgreement(rows, refused = (unknown.map { "timeZone \"$it\"" } + "timeZone 5" + "timeZone true").associateWith { ZONE })
    }

    /**
     * `CharsetUtil`: one unit, a range `x-y` running upwards, or a combined grapheme of at most three units that Java's
     * `\X` finds fewer clusters in than it has units. A Boolean or number entry is its text, `-0` being `0`. The
     * combined graphemes and the CR LF pair are admitted, which the checker leaves to A12 as a coverage gap.
     */
    @Test
    fun aSupportedCharacterEntryIsReadAsA12ParsesIt() {
        val admitted = listOf("[\"a\"]", "[\"a-z\"]", "[\"-\"]", "[\"---\"]", "[\" \"]", "[5]", "[-0]", "[\"\\u00e9-\\u017f\"]",
            "[\"e\\u0301\"]", "[\"e\\u0301x\"]", "[\"ae\\u0301\"]", "[\"a\\u0301\\u0301\"]", "[\"\\r\\n\"]")
        val unbuilt = listOf("[\"\"]", "[\"ab\"]", "[\"  \"]", "[\"--\"]", "[\"a-\"]", "[\"z-a\"]", "[\"a--\"]", "[\"\\u00e9\\u00e9\"]",
            "[\"a-b-c\"]", "[\"a\",\"ab\"]", "[\"e\\u0301\\u0301\\u0301\"]", "[true]", "[12]", "[1.5]")
        val rows = linkedMapOf<String, String>()
        (admitted + unbuilt).forEach { rows["characters $it"] = edited("/content/modelConfig/supportedCharacters", mapper.readTree(it)) }
        assertAgreement(rows, refused = unbuilt.associate { "characters $it" to UNBUILT })
    }

    /**
     * A number entry restricts as its text: `[7]` admits the character `7`, so an enumeration value `beta` is spelled
     * outside it, once per distinct character. `["a-z"]` is the control.
     */
    @Test
    fun aNumberEntryRestrictsTheEnumerationAsItsText() {
        val enumerated = DocumentModelDraft.named("policy-frame").locale("en_US")
            .rootGroup("Policy") { p -> p.enumField("Tier") { e -> e.value("beta") } }
            .build().toJson()
        val observed = listOf("[7]", "[\"a-z\"]").associateWith { entries ->
            val json = edited(enumerated, "/content/modelConfig/supportedCharacters", mapper.readTree(entries))
            a12(json) to rejectingCodes(json)
        }
        val invalid = "MVK_ENUM_VALUE_WITH_INVALID_CHARACTER"
        assertEquals(
            mapOf("[7]" to (setOf(invalid) to List(4) { invalid }), "[\"a-z\"]" to (emptySet<String>() to emptyList())),
            observed,
            "as (A12, ours)",
        )
    }

    /**
     * A12 refuses two combined graphemes that can split one text two ways, here `aé` beside `é̂`
     * with `a` admitted, and only a grapheme segmenter sees that. The checker therefore passes every check it has and
     * names the gap, which is why `ALL_CHECKS_PASSED` is never A12's acceptance (CONSISTENCY-API, `CheckOutcome`).
     */
    @Test
    fun anOverlapOfCombinedGraphemesPassesEveryCheckA12Refuses() {
        val json = edited("/content/modelConfig/supportedCharacters", mapper.readTree("[\"a\",\"ae\\u0301\",\"e\\u0301\\u0302\"]"))
        val report = check(ModelSource(json))
        assertEquals(
            Triple(UNBUILT, CheckOutcome.ALL_CHECKS_PASSED, true),
            Triple(a12(json), report.outcome, report.gaps.isNotEmpty()),
            "as (A12, the checker's outcome, a gap named)",
        )
    }

    /**
     * A reached model's values are never asked for: A12 builds and checks an entry whose included model carries each
     * value it refuses on the entry, and so does the checker. The untouched component is the control.
     */
    @Test
    fun anIncludedModelsValuesAreNeverAsked() {
        val component = DocumentModelDraft.named("component").locale("en_US")
            .rootGroup("Component") { c -> c.stringField("Description") {} }.build().toJson()
        val permit = DocumentModelDraft.named("permit").locale("en_US").include("component", "component")
            .rootGroup("Permit") { p -> p.mount("Details", "component") }.build().toJson()
        val edits = listOf(
            null to null, "/content/modelInfo/name" to "\"xmlC\"", "/content/modelInfo/name" to "\"\"",
            "/header/modelVersion" to "\"x\"", "/header/modelVersion" to "\"29.0.0\"",
            "/content/modelConfig/timeZone" to "\"x\"", "/content/modelConfig/supportedCharacters" to "[\"ab\"]",
        )
        val observed = edits.associate { (pointer, value) ->
            val provider = pointer?.let { edited(component, it, mapper.readTree(value)) } ?: component
            val a12 = runCatching { ModelLoader.loadAndCheck(permit) { if (it == "component") provider else null } }.isSuccess
            val ours = ConsistencyWorkspace.fromSources(listOf(ModelSource(permit), ModelSource(provider))).check("permit")
            "$pointer $value" to (a12 to ours.findings.none { it.justifiesRejection })
        }
        assertEquals(observed.mapValues { true to true }, observed, "as (A12 builds and checks, the checker refuses nothing)")
    }

    private fun assertAgreement(rows: Map<String, String>, refused: Map<String, Set<String>>) {
        val observed = rows.mapValues { (_, json) -> a12(json) to rejectingCodes(json).toSet() }
        val expected = rows.mapValues { (row, _) -> refused[row].orEmpty().let { it to it } }
        assertEquals(expected, observed, "as (A12, ours)")
        assertEquals(emptySet<String>(), refused.keys - rows.keys, "every refused row is a row")
    }

    /** A12's classes, a Kernel exception escaping its typed channels read as the model it never built. */
    private fun a12(json: String): Set<String> = try {
        kernelModelClasses(json)
    } catch (escaped: RuntimeException) {
        UNBUILT
    }

    private fun named(name: String?, branch: String?): String {
        val root = mapper.readTree(edited("/content/modelInfo/name", name?.let(mapper::readTree))) as ObjectNode
        branch?.let { (root.at("/content/modelInfo") as ObjectNode).put("branch", it) }
        return root.toString()
    }

    private fun edited(pointer: String, value: JsonNode?): String = edited(base, pointer, value)

    private fun edited(model: String, pointer: String, value: JsonNode?): String {
        val root = mapper.readTree(model) as ObjectNode
        val parent = root.at(pointer.substringBeforeLast('/')) as ObjectNode
        val member = pointer.substringAfterLast('/')
        if (value == null) parent.remove(member) else parent.set<JsonNode>(member, value)
        return root.toString()
    }

    private companion object {
        val NAME = setOf("MVK_INVALID_NAME_4_GENERATED_CODE")
        val ZONE = setOf("MVK_INVALID_TIME_ZONE")
        val PATTERN = setOf("RK_MODEL_VERSION_PATTERN")
        val INCOMPATIBLE = setOf("RK_MODEL_VERSION_INCOMPATIBLE")
        val UNBUILT = setOf("CK_MODEL_NOT_BUILDABLE")
    }
}
