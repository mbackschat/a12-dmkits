package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of [KERNEL-FINDINGS KF308] — which `fieldType` shapes A12's reader refuses.
 *
 * The rows mirror `rulekit.validate.laws.FieldTypeBindingLawsTest`, which measured them against the real
 * kernel on a field no declaration references. Every row there that came back UNREADABLE must draw
 * [CK_MODEL_NOT_BUILDABLE] here, and every row that A12 read must draw nothing from this family — including
 * the rows A12 read and then could not BUILD, which belong to a later slice and whose silence here is
 * deliberate rather than unfinished.
 *
 * **The unknown-name rows are the ones that could do damage.** Refusing a spelling is a claim to know the
 * complete vocabulary, so the thirteen admitted names are each exercised positively: if the checker's list
 * were short by one, that name's row would fail here rather than in a consumer's model.
 */
class FieldTypeBindingTest {

    /** A model whose single field carries [fieldType] verbatim, or no such member when it is null. */
    private fun typed(fieldType: String?) = ModelSource(
        """
        {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Tier","Field":{${
            fieldType?.let { """"fieldType":$it""" } ?: ""
        }}}
          ]}}
         ]}}}
        """.trimIndent(),
    )

    private fun codes(fieldType: String?): List<String> =
        check(typed(fieldType)).findings.map { it.code }.distinct()

    private val unbuildable = listOf(CK_MODEL_NOT_BUILDABLE)

    @Test
    fun everyTypeA12DeclaresBindsAndDrawsNothing() {
        val actual = mutableMapOf<String, List<String>>()
        val expected = mutableMapOf<String, List<String>>()
        // The five whose reader guards a missing body: both spellings bind, and an empty body builds.
        for (type in listOf("StringType", "NumberType", "ConfirmType", "BooleanType", "UnspecifiedType")) {
            actual["$type, body present"] = codes("""{"type":"$type","$type":{}}""")
            actual["$type, body absent"] = codes("""{"type":"$type"}""")
            expected["$type, body present"] = emptyList()
            expected["$type, body absent"] = emptyList()
        }
        // The three temporal types, which read their body but build from an empty one — a format is
        // optional. They are what keep "reads the body" and "needs something in it" apart.
        for (type in listOf("DateType", "DateTimeType", "TimeType")) {
            actual["$type, body present"] = codes("""{"type":"$type","$type":{}}""")
            actual["$type, body absent"] = codes("""{"type":"$type"}""")
            expected["$type, body present"] = emptyList()
            expected["$type, body absent"] = unbuildable
        }
        // The five that read the body AND construct from a member of it: neither spelling gives a model.
        for (type in listOf("DateRangeType", "DateFragmentType", "EnumerationType", "TypeDefType",
            "CustomFieldType")) {
            actual["$type, body present"] = codes("""{"type":"$type","$type":{}}""")
            actual["$type, body absent"] = codes("""{"type":"$type"}""")
            // With a body PRESENT the custom type parts from the other four: A12 reads it, finds no usable
            // `name` and says so under its own class, where the rest die with nothing to report. With the
            // body ABSENT all five are alike — A12's reader refuses the declaration before any of it.
            expected["$type, body present"] =
                if (type == "CustomFieldType") listOf(codenameMissing) else unbuildable
            expected["$type, body absent"] = unbuildable
        }
        assertEquals(expected, actual, "what the checker says about each type A12 declares")
    }

    /**
     * A constructing type is judged on the MEMBER it builds from, not on whether its body is empty.
     *
     * Each type appears three times: with its required member, with an optional member instead, and with an
     * empty body. The middle row is what an emptiness test missed — a body carrying `alphabeticalSorting` and
     * no `values` leaves a model A12 cannot build and used to draw nothing
     * (`rulekit.validate.laws.FieldTypeBodyMemberLawsTest`, [KERNEL-FINDINGS KF312]).
     *
     * **The required-member rows carry the over-rejection risk and are all ADMITTED by the real kernel**, so
     * reporting one would refuse a model A12 takes. They are also the live control for the other two: without
     * them, a silent row would be indistinguishable from the check having stopped running.
     *
     * `TypeDefType` has no row here because its body is not judged by presence at all: it carries a
     * REFERENCE, resolved in its own test below against `content.typeDefinitions`.
     */
    /** A12's own class for a custom type with no usable `name` — unprefixed, like several of its siblings. */
    private val codenameMissing = "CUSTOM_FIELD_TYPE_CODENAME_MISSING"

    @Test
    fun aConstructingTypeIsJudgedOnTheMemberItBuildsFrom() {
        val bodies = mapOf(
            "DateRangeType" to Triple("""{"format":"yyyy-MM-dd"}""", """{"rangeSeparator":"-"}""", "{}"),
            "DateFragmentType" to Triple(
                """{"formatOfFragment":"dd.MM."}""", """{"youngerThan1900Check":true}""", "{}",
            ),
            "EnumerationType" to Triple(
                """{"values":[{"value":"A"}]}""", """{"alphabeticalSorting":true}""", "{}",
            ),
            "CustomFieldType" to Triple("""{"name":"Signature"}""", """{"displayName":"Sig"}""", "{}"),
        )
        val actual = mutableMapOf<String, List<String>>()
        val expected = mutableMapOf<String, List<String>>()
        for ((type, row) in bodies) {
            val (required, optional, empty) = row
            actual["$type with the member it builds from"] = codes("""{"type":"$type","$type":$required}""")
            actual["$type with an optional member only"] = codes("""{"type":"$type","$type":$optional}""")
            actual["$type with an empty body"] = codes("""{"type":"$type","$type":$empty}""")
            expected["$type with the member it builds from"] = emptyList()
            // Three of the four die at the BUILD tier with no class of A12's own; the custom type is the one
            // that reaches a verdict, so it is reported under the class A12 answers with rather than under
            // this checker's own "not buildable".
            val absent = if (type == "CustomFieldType") listOf(codenameMissing) else unbuildable
            expected["$type with an optional member only"] = absent
            expected["$type with an empty body"] = absent
        }
        assertEquals(expected, actual, "a constructing type judged on the member A12 builds from")
    }

    /**
     * The custom type's `name`, which is judged on its CONTENT and not on the key's presence.
     *
     * An empty and a blank `name` both draw the same class as no `name` at all, measured — so a checker
     * testing for the key alone stays silent on two models A12 refuses.
     */
    @Test
    fun aCustomTypesNameIsJudgedOnWhatItCarries() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a name — the control"] = codes("""{"type":"CustomFieldType","CustomFieldType":{"name":"Sig"}}""")
        actual["an EMPTY name"] = codes("""{"type":"CustomFieldType","CustomFieldType":{"name":""}}""")
        actual["a BLANK name"] = codes("""{"type":"CustomFieldType","CustomFieldType":{"name":"   "}}""")

        val expected = mutableMapOf<String, List<String>>()
        expected["a name — the control"] = emptyList()
        expected["an EMPTY name"] = listOf(codenameMissing)
        expected["a BLANK name"] = listOf(codenameMissing)
        assertEquals(expected, actual, "a custom type's name judged on what it carries")
    }

    /**
     * The `DateRangeType` pair, which is the measured case saying the required member is not sufficient.
     *
     * Every row mirrors one in `rulekit.validate.laws.DateRangePairAdmissionLawsTest`
     * ([KERNEL-FINDINGS KF316]). The two silent tail rows carry the over-rejection risk: a format or a
     * separator the sweep never put to the kernel is undiscovered rather than refused, so reporting one
     * would rest a refusal on the limit of a probe.
     */
    @Test
    fun aDateRangeIsJudgedOnItsFormatAndSeparatorTogether() {
        val actual = mutableMapOf<String, List<String>>()
        val expected = mutableMapOf<String, List<String>>()
        fun row(name: String, body: String, verdict: List<String>) {
            actual[name] = codes("""{"type":"DateRangeType","DateRangeType":$body}""")
            expected[name] = verdict
        }
        row("ISO with its separator — a control", """{"format":"yyyy-MM-dd","rangeSeparator":"/"}""", emptyList())
        row("German with its separator — a control",
            """{"format":"dd.MM.yyyy","rangeSeparator":"-"}""", emptyList())
        // Each half of these two appears in a control above, which is what makes the pair the subject.
        row("ISO with the German pair's separator",
            """{"format":"yyyy-MM-dd","rangeSeparator":"-"}""", unbuildable)
        row("German with the ISO pair's separator",
            """{"format":"dd.MM.yyyy","rangeSeparator":"/"}""", unbuildable)
        row("ISO, separator omitted", """{"format":"yyyy-MM-dd"}""", emptyList())
        row("German, separator omitted", """{"format":"dd.MM.yyyy"}""", unbuildable)
        // The empty separator is a declaration of its own, so omission cannot be read as it.
        row("ISO with an explicit empty separator",
            """{"format":"yyyy-MM-dd","rangeSeparator":""}""", unbuildable)
        row("month with an explicit empty separator", """{"format":"MM","rangeSeparator":""}""", emptyList())
        // The two directions in which the range domain is not the field vocabulary.
        row("a field-admitted format with no admitted pair", """{"format":"yyyyMM","rangeSeparator":"/"}""",
            unbuildable)
        row("a field-REFUSED format admitted on a range", """{"format":"dd.MM","rangeSeparator":"-"}""",
            emptyList())
        row("a format the sweep never put to the kernel",
            """{"format":"MM.yyyy","rangeSeparator":"/"}""", emptyList())
        row("a separator the sweep never put to the kernel",
            """{"format":"yyyy-MM-dd","rangeSeparator":"~"}""", emptyList())
        row("a separator that is not a string at all",
            """{"format":"yyyy-MM-dd","rangeSeparator":7}""", emptyList())
        assertEquals(expected, actual, "a DATERANGE's format and separator are allowlisted as a pair")
    }

    /**
     * The three silent tail rows above are PUBLISHED, because the report names this family as completed.
     *
     * Their silence is the right verdict and the wrong report on its own: a pair outside the sweep is
     * undiscovered, not admitted, and a consumer cannot tell the two apart from an empty finding list.
     */
    @Test
    fun aPairOutsideTheSweepIsPublishedAsACoverageGap() {
        fun gapsFor(body: String) = check(typed("""{"type":"DateRangeType","DateRangeType":$body}"""))
            .gaps.filter { it.reason.contains("was left to A12") }.map { it.at }

        val actual = mutableMapOf<String, List<String?>>()
        actual["a format the sweep never put to the kernel"] =
            gapsFor("""{"format":"MM.yyyy","rangeSeparator":"/"}""")
        actual["a separator the sweep never put to the kernel"] =
            gapsFor("""{"format":"yyyy-MM-dd","rangeSeparator":"~"}""")
        actual["a separator that is not a string at all"] =
            gapsFor("""{"format":"yyyy-MM-dd","rangeSeparator":7}""")
        // The controls: a judged pair says nothing either way, whether it was admitted or refused.
        actual["an admitted pair — the control"] =
            gapsFor("""{"format":"yyyy-MM-dd","rangeSeparator":"/"}""")
        actual["a refused pair — the control"] =
            gapsFor("""{"format":"yyyy-MM-dd","rangeSeparator":"-"}""")

        val expected = mutableMapOf<String, List<String?>>()
        expected["a format the sweep never put to the kernel"] = listOf("/Catalog/Tier")
        expected["a separator the sweep never put to the kernel"] = listOf("/Catalog/Tier")
        expected["a separator that is not a string at all"] = listOf("/Catalog/Tier")
        expected["an admitted pair — the control"] = emptyList()
        expected["a refused pair — the control"] = emptyList()
        assertEquals(expected, actual, "an unmeasured pair is disclosed rather than silently dropped")
    }

    @Test
    fun aFieldTypeThatIsNotAUsableTypedObjectIsUnbindable() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a plain string type — the control"] = codes("""{"type":"StringType"}""")
        actual["no fieldType member at all"] = codes(null)
        actual["an EMPTY fieldType object"] = codes("{}")
        actual["a body with no discriminator"] = codes("""{"StringType":{}}""")
        actual["a type name A12 does not have"] = codes("""{"type":"NotAType","NotAType":{}}""")
        actual["the discriminator in the wrong case"] = codes("""{"type":"stringtype"}""")
        actual["fieldType as text"] = codes(""""StringType"""")
        actual["fieldType as a number"] = codes("7")
        actual["fieldType as a list"] = codes("""[{"type":"StringType"}]""")
        actual["fieldType as null"] = codes("null")
        actual["the discriminator as a number"] = codes("""{"type":7}""")

        val expected = mutableMapOf<String, List<String>>()
        expected["a plain string type — the control"] = emptyList()
        // Not an untyped field: A12 binds `fieldType` as non-null with no default, so a field without one
        // leaves a model that reads and never builds. The control above is what makes this attributable.
        expected["no fieldType member at all"] = unbuildable
        expected["an EMPTY fieldType object"] = unbuildable
        expected["a body with no discriminator"] = unbuildable
        expected["a type name A12 does not have"] = unbuildable
        expected["the discriminator in the wrong case"] = unbuildable
        expected["fieldType as text"] = unbuildable
        expected["fieldType as a number"] = unbuildable
        expected["fieldType as a list"] = unbuildable
        expected["fieldType as null"] = unbuildable
        expected["the discriminator as a number"] = unbuildable
        assertEquals(expected, actual, "what the checker says about a fieldType that is not a typed object")
    }

    /**
     * The member is read on FIELDS only, because A12 reads it on fields only.
     *
     * A group DROPS a member it does not declare, so the identical fragment that makes a field unreadable is
     * ignored there and the model loads — measured, with a live field control on the same model. A checker
     * scanning every element would refuse it, which is the one direction nothing downstream can catch.
     *
     * The rule carrier deliberately has no row: A12 refuses ANY member it does not declare on a rule, so
     * that fragment is unreadable there too — but for the carrier's strictness rather than for anything
     * `fieldType`-shaped, and a row here would read as this family covering a law it does not own.
     */
    @Test
    fun aFieldTypeOnAGroupIsIgnoredTheWayA12IgnoresIt() {
        val withGroup = ModelSource(
            """
            {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Catalog","Group":{
                "fieldType":{"type":"NotAType","NotAType":{}},
                "elements":[{"type":"Field","id":"f0","name":"Tier","Field":{
                  "fieldType":{"type":"StringType"}}}]}}
             ]}}}
            """.trimIndent(),
        )
        assertEquals(emptyList(), check(withGroup).findings.map { it.code },
            "a group's stray fieldType is A12's to ignore, so reporting it would refuse a model A12 loads")
    }

    /**
     * A `TypeDefType`'s reference resolves against `content.typeDefinitions`, not against the element tree.
     *
     * The wire member is `typeDefinitionId`; the kernel's Java field is called `id`, and taking that for the
     * wire name is what made an earlier probe measure an unknown member and mistake the result for a
     * dangling-reference law ([KERNEL-FINDINGS KF308], corrected). The declared row is the live control: it
     * must draw nothing, or a green dangling row would only mean the check never ran.
     */
    @Test
    fun aTypeDefTypeReferenceResolvesAgainstTheModelsTypeDefinitions() {
        fun typed(fieldType: String, typeDefinitions: String?) = ModelSource(
            """
            {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
             "content":{${typeDefinitions?.let { """"typeDefinitions":$it,""" } ?: ""}
              "modelRoot":{"rootGroups":[
               {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                 {"type":"Field","id":"f0","name":"Tier","Field":{"fieldType":$fieldType}}
               ]}}
              ]}}}
            """.trimIndent(),
        )

        val money = """[{"id":"Money","name":"Money","fieldType":{"type":"StringType"}}]"""
        val actual = mutableMapOf<String, List<String>>()
        actual["naming a DECLARED type definition — the control"] = check(
            typed("""{"type":"TypeDefType","TypeDefType":{"typeDefinitionId":"Money"}}""", money),
        ).findings.map { it.code }
        actual["naming nothing, with one declared"] = check(
            typed("""{"type":"TypeDefType","TypeDefType":{"typeDefinitionId":"NoSuch"}}""", money),
        ).findings.map { it.code }
        actual["naming nothing, with none declared"] = check(
            typed("""{"type":"TypeDefType","TypeDefType":{"typeDefinitionId":"NoSuch"}}""", null),
        ).findings.map { it.code }
        actual["no typeDefinitionId at all"] = check(
            typed("""{"type":"TypeDefType","TypeDefType":{}}""", money),
        ).findings.map { it.code }

        val expected = mutableMapOf<String, List<String>>()
        expected["naming a DECLARED type definition — the control"] = emptyList()
        expected["naming nothing, with one declared"] = unbuildable
        expected["naming nothing, with none declared"] = unbuildable
        expected["no typeDefinitionId at all"] = unbuildable
        assertEquals(expected, actual, "what a TypeDefType's reference must name")

        // The definitions themselves are read for their IDS alone, so every content law this checker
        // applies to an inline `fieldType` skips whatever they declare. That carrier is visibly in scope in
        // the same report — the rows above resolve against it — which is why its absence has to be said.
        assertEquals(
            listOf<String?>(null),
            check(typed("""{"type":"TypeDefType","TypeDefType":{"typeDefinitionId":"Money"}}""", money))
                .gaps.filter { it.reason.contains("type definition") }.map { it.at },
        )
        assertEquals(
            emptyList(),
            check(typed("""{"type":"StringType"}""", null))
                .gaps.filter { it.reason.contains("type definition") },
            "a model declaring none must not carry the gap",
        )
    }

    /**
     * One unbindable field draws ONE finding across the whole report, not one per family that reads it.
     *
     * Counted through `check()` rather than off this family, because the mistake it guards against is a
     * second family reporting the same unusable model — which is invisible from inside either one.
     */
    @Test
    fun anUnbindableFieldTypeIsReportedOnceAcrossTheWholeReport() {
        val report = check(typed("""{"type":"NotAType","NotAType":{}}"""))
        assertEquals(1, report.findings.size, "findings for one unbindable fieldType: ${report.findings}")
        assertEquals(CK_MODEL_NOT_BUILDABLE, report.findings.single().code)
        assertEquals("/Catalog/Tier", report.findings.single().at)
    }
}
