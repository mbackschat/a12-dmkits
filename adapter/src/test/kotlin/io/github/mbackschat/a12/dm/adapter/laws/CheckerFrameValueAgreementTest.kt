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
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
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
        for (name in listOf(null, "\"\"", "\"Policy\"", "\"_p\"", "\"1p\"", "\"p q\"", "\"Prämie\"", "\"xm\"", "\"xmlP\"", "\"XmLp\"", "5", "true",
                "\"  \"", "\"\\u00a0\"", "\"XML\\u0130\"")) {
            rows["name $name"] = named(name, branch = null)
        }
        for ((name, branch) in listOf("\"_xmlb\"" to "xmlb", "\"_xmlb\"" to "XMLB", "\"  _xmlb\"" to "xmlb", "\"x_xmlb\"" to "xmlb",
            "\"_\"" to "", "\"xml_v1\"" to "v1", "\"Policy_V1\"" to "v1", "\"a_xml\"" to "xml", "\"xmlP\"" to " ", null to "v1",
            "\"xml\\u0130\\u0130_b\"" to "b", "\"\\u0130\\u0130\\u0130_b\"" to "b")) {
            rows["name $name branch \"$branch\""] = named(name, branch)
        }
        assertAgreement(rows, refused = mapOf(
            "name null" to NAME, "name \"\"" to NAME, "name \"xmlP\"" to NAME, "name \"XmLp\"" to NAME,
            "name \"_xmlb\" branch \"xmlb\"" to NAME, "name \"_xmlb\" branch \"XMLB\"" to NAME,
            "name \"  _xmlb\" branch \"xmlb\"" to NAME, "name \"_\" branch \"\"" to NAME, "name \"xml_v1\" branch \"v1\"" to NAME,
            "name \"xmlP\" branch \" \"" to NAME, "name null branch \"v1\"" to UNBUILT,
            "name \"  \"" to NAME, "name \"XML\\u0130\"" to NAME, "name \"xml\\u0130\\u0130_b\" branch \"b\"" to NAME,
            // `İİİ_b` is admitted: kernel 31.1.1 removes `_b` by a case-blind end match on the original name, where
            // 30.8.1 cut it at an index from the lower-cased one, two units per U+0130, past its end, and threw.
        ))
    }

    /** `VersionChecker`: a major of 29 and a minor and patch, read as one decimal, at most `4.0`; ASCII digits only. */
    @Test
    fun theModelVersionIsJudgedAsA12ReadsIt() {
        val texts = listOf("29.4.0", "29.0.0", "29.3.99", "29.4.00", "29.04.0", "029.4.0", "29.4.0000000000000000001", "29.4.1",
            "30.0.0", "28.9.9", "29.5", "x", "29.4.0 ", "٢٩.4.0", "29.4.0.0", "-29.4.0", "99999999999.0.0", "2147483647.0.0", "2147483648.0.0")
        val rows = linkedMapOf<String, String>()
        texts.forEach { rows["version \"$it\""] = edited("/header/modelVersion", mapper.valueToTree(it)) }
        listOf("5", "true").forEach { rows["version $it"] = edited("/header/modelVersion", mapper.readTree(it)) }
        val both = PATTERN + INCOMPATIBLE
        assertAgreement(rows, refused = mapOf(
            "version \"29.4.1\"" to INCOMPATIBLE, "version \"30.0.0\"" to INCOMPATIBLE, "version \"28.9.9\"" to INCOMPATIBLE,
            "version \"29.5\"" to both, "version \"x\"" to both, "version \"29.4.0 \"" to both, "version \"٢٩.4.0\"" to both,
            "version \"29.4.0.0\"" to both, "version \"-29.4.0\"" to both, "version 5" to both, "version true" to both,
            // The major overflows Java's `int`, which A12 parses it into, so its check throws.
            "version \"99999999999.0.0\"" to UNBUILT, "version \"2147483647.0.0\"" to INCOMPATIBLE,
            "version \"2147483648.0.0\"" to UNBUILT,
        ))
        val ordered = rows.filterValues { a12(it).size == 2 }.mapValues { (_, json) -> a12(json).toList() to rejectingCodes(json) }
        assertEquals(ordered.mapValues { (_, a12AndOurs) -> a12AndOurs.first to a12AndOurs.first }, ordered,
            "the pattern's sentence first, as A12 states it")
    }

    /**
     * `NoMetaModelChecks.checkTimeZone`: `GMT`, an id of the JDK's registry exactly, or a custom offset up to `23:59`.
     * The empty zone is admitted on kernel 31.1.1, read as the absent one by the expand copy (KERNEL-FINDINGS KF658).
     */
    @Test
    fun theTimeZoneIsJudgedAsTheJdkKnowsIt() {
        val known = listOf("UTC", "GMT", "Europe/Berlin", "EST", "Etc/GMT+5", "GMT+1", "GMT+01:00", "GMT+0", "GMT+00:00", "GMT-0130", "")
        val unknown = listOf("utc", "europe/berlin", "x", "GMT+24", "UTC+1", " ", "GMT ", "Z", "+01:00")
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
            "[\"a-b-c\"]", "[\"a\",\"ab\"]", "[\"e\\u0301\\u0301\\u0301\"]", "[true]", "[12]", "[1.5]", "[-0.0]", "[1.50]")
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
     * A12 refuses two combined graphemes that can split one text two ways, here `ae\u0301` beside `e\u0301\u0302`
     * with `a` admitted, and two characters that are not one grapheme, here `\u03b1\u03b2`; only a grapheme segmenter
     * sees either. The checker therefore passes every check it has and names the gap, which is why `ALL_CHECKS_PASSED`
     * is never A12's acceptance (CONSISTENCY-API, `CheckOutcome`).
     */
    @Test
    fun anEntryOnlyAGraphemeSegmenterRefusesPassesEveryCheck() {
        val observed = listOf("[\"a\",\"ae\\u0301\",\"e\\u0301\\u0302\"]", "[\"\\u03b1\\u03b2\"]").associateWith { entries ->
            val json = edited("/content/modelConfig/supportedCharacters", mapper.readTree(entries))
            val report = check(ModelSource(json))
            Triple(a12(json), report.outcome, report.gaps.isNotEmpty())
        }
        assertEquals(observed.mapValues { Triple(UNBUILT, CheckOutcome.ALL_CHECKS_PASSED, true) }, observed,
            "as (A12, the checker's outcome, a gap named)")
    }

    /**
     * A reached model's values are never asked for: A12 builds and checks an entry whose included model carries each
     * value it refuses on the entry, and so does the checker. The untouched component is the control, and the same
     * values on the entry are the control that both sides can refuse here.
     */
    @Test
    fun anIncludedModelsValuesAreNeverAsked() {
        val component = DocumentModelDraft.named("component").locale("en_US")
            .rootGroup("Component") { c -> c.stringField("Description") {} }.build().toJson()
        val permit = DocumentModelDraft.named("permit").locale("en_US")
            .rootGroup("Permit") { p -> p.mount("Details", "component") }.build().toJson()
        val edits = listOf(
            null to null, "/content/modelInfo/name" to "\"xmlC\"", "/content/modelInfo/name" to "\"\"",
            "/header/modelVersion" to "\"x\"", "/header/modelVersion" to "\"30.0.0\"",
            "/content/modelConfig/timeZone" to "\"x\"", "/content/modelConfig/supportedCharacters" to "[\"ab\"]",
        )
        val observed = edits.associate { (pointer, value) ->
            val provider = pointer?.let { edited(component, it, mapper.readTree(value)) } ?: component
            val a12 = runCatching { ModelLoader.loadAndCheck(permit) { if (it == "component") provider else null } }.isSuccess
            val ours = ConsistencyWorkspace.fromSources(listOf(ModelSource(permit), ModelSource(provider))).check("permit")
            "$pointer $value" to (a12 to ours.findings.none { it.justifiesRejection })
        }
        assertEquals(observed.mapValues { true to true }, observed, "as (A12 builds and checks, the checker refuses nothing)")
        val onTheEntry = edits.mapNotNull { (pointer, value) -> pointer?.let { "$it $value" to edited(permit, it, mapper.readTree(value)) } }
            .associate { (row, entry) ->
                val a12 = runCatching { ModelLoader.loadAndCheck(entry) { if (it == "component") component else null } }.isSuccess
                val ours = ConsistencyWorkspace.fromSources(listOf(ModelSource(entry), ModelSource(component))).check("permit")
                row to (a12 to ours.findings.none { it.justifiesRejection })
            }
        assertEquals(onTheEntry.mapValues { false to false }, onTheEntry, "as (A12 refuses the entry, and so does the checker)")
    }

    /**
     * A frame value beside another defect. A version sentence is reported first and withholds nothing; the generated-code
     * name and the time zone each withhold a computation cycle; a refused or surrogate supported-character entry stops
     * A12's whole check, so nothing beside it is reported. Which step withholds the cycle is not traced (KF650).
     */
    @Test
    fun aFrameValueBesideAnotherDefectIsReportedAsA12ReportsIt() {
        val cyclic = run {
            val numbers = DocumentModelDraft.named("policy-frame").locale("en_US")
                .rootGroup("Policy") { p -> p.numberField("Premium") { }.numberField("Rebate") { } }.build().toJson()
            val policy = EditSession.read(numbers).group("/Policy")
            val (premium, rebate) = policy.num("Premium") to policy.num("Rebate")
            // Injected, because the editor refuses the cycle these rows need.
            listOf(Triple("ComputePremium", premium, rebate), Triple("ComputeRebate", rebate, premium))
                .map { (name, target, source) ->
                    policy.computation(target) { it.name(name).calc(source.asNumber()).message(Dsl.en("Check the premium.")).build() }
                }
                .fold(numbers) { model, computation -> TypedRules.inject(model, computation) }
        }
        val characters = "/content/modelConfig/supportedCharacters"
        val broken = RuntimeLaws.withRule(base, "/Policy", "RRef", "../Ref", "REF", "FieldFilled(Nope)")
        val rows = linkedMapOf(
            "cycle" to cyclic,
            "cycle, version \"30.0.0\"" to edited(cyclic, "/header/modelVersion", mapper.valueToTree("30.0.0")),
            "cycle, timeZone \"x\"" to edited(cyclic, "/content/modelConfig/timeZone", mapper.valueToTree("x")),
            "cycle, name \"xmlP\"" to edited(cyclic, "/content/modelInfo/name", mapper.valueToTree("xmlP")),
            "characters [\"ab\"], timeZone \"x\"" to edited(edited(characters, mapper.readTree("[\"ab\"]")),
                "/content/modelConfig/timeZone", mapper.valueToTree("x")),
            "characters [\"ab\"], version \"x\"" to edited(edited(characters, mapper.readTree("[\"ab\"]")),
                "/header/modelVersion", mapper.valueToTree("x")),
            "characters [surrogate], timeZone \"x\"" to edited(edited(characters, mapper.readTree("[\"\\ud83d\"]")),
                "/content/modelConfig/timeZone", mapper.valueToTree("x")),
            "version \"30.0.0\", timeZone \"x\"" to edited(edited("/header/modelVersion", mapper.valueToTree("30.0.0")),
                "/content/modelConfig/timeZone", mapper.valueToTree("x")),
            "broken reference" to broken,
            "broken reference, version \"30.0.0\"" to edited(broken, "/header/modelVersion", mapper.valueToTree("30.0.0")),
            "broken reference, timeZone \"x\"" to edited(broken, "/content/modelConfig/timeZone", mapper.valueToTree("x")),
            "cycle, version \"x\"" to edited(cyclic, "/header/modelVersion", mapper.valueToTree("x")),
            "broken reference, name \"xmlP\"" to edited(broken, "/content/modelInfo/name", mapper.valueToTree("xmlP")),
        )
        val cycle = setOf("MVK_ERROR_CALC_CYCLE")
        assertAgreement(rows, refused = mapOf(
            "cycle" to cycle, "cycle, version \"30.0.0\"" to INCOMPATIBLE + cycle, "cycle, timeZone \"x\"" to ZONE,
            "cycle, name \"xmlP\"" to NAME, "characters [\"ab\"], timeZone \"x\"" to UNBUILT,
            "characters [\"ab\"], version \"x\"" to UNBUILT, "characters [surrogate], timeZone \"x\"" to UNBUILT,
            "version \"30.0.0\", timeZone \"x\"" to INCOMPATIBLE + ZONE, "broken reference" to ENTITY,
            "broken reference, version \"30.0.0\"" to INCOMPATIBLE + ENTITY, "broken reference, timeZone \"x\"" to ENTITY + ZONE,
            "cycle, version \"x\"" to PATTERN + INCOMPATIBLE + cycle, "broken reference, name \"xmlP\"" to ENTITY + NAME,
        ))
        val ordered = rows.filterKeys { "version" in it && "characters" !in it }
            .mapValues { (_, json) -> a12(json).toList() to rejectingCodes(json) }
        assertEquals(ordered.mapValues { (_, a12AndOurs) -> a12AndOurs.first to a12AndOurs.first }, ordered,
            "the version's sentence first, as A12 states it")
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
        val ENTITY = setOf("MVK_INVALID_ENTITY")
    }
}
