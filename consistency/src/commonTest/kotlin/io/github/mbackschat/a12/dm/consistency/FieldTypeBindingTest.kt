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
            expected["$type, body present"] = unbuildable
            expected["$type, body absent"] = unbuildable
        }
        assertEquals(expected, actual, "what the checker says about each type A12 declares")
    }

    /**
     * The types an EMPTY body defeats must stay silent once the body supplies what they need.
     *
     * Without this, "an empty body is unbuildable" and "this TYPE is unbuildable" are indistinguishable from
     * the checker's side, and widening the emptiness test to any body of those types goes unnoticed —
     * measured as a surviving mutant. Every row is a body the real kernel ADMITS
     * (`rulekit.validate.laws.FieldTypeBindingLawsTest`), so reporting one would refuse a model A12 takes.
     *
     * `TypeDefType` has no row: a buildable one needs a type definition the model declares, and an `id`
     * naming nothing refuses A12's reader outright — a class this checker does not cover, named in the gap.
     */
    @Test
    fun aConstructingTypeWithARealBodyDrawsNothing() {
        val actual = mutableMapOf<String, List<String>>()
        actual["DateRangeType with a format and a separator"] =
            codes("""{"type":"DateRangeType","DateRangeType":{"format":"dd.MM.yyyy","rangeSeparator":"-"}}""")
        actual["EnumerationType with one value"] =
            codes("""{"type":"EnumerationType","EnumerationType":{"values":[{"value":"A"}]}}""")
        actual["DateFragmentType with a fragment format"] =
            codes("""{"type":"DateFragmentType","DateFragmentType":{"formatOfFragment":"dd.MM."}}""")
        actual["CustomFieldType with a name"] =
            codes("""{"type":"CustomFieldType","CustomFieldType":{"name":"Signature"}}""")
        // The live control for the pair: the same types with an empty body DO draw, so a green row above is
        // silence about a body that supplies something and not the check having stopped running.
        actual["EnumerationType with an empty body — the live control"] =
            codes("""{"type":"EnumerationType","EnumerationType":{}}""")

        val expected = mutableMapOf<String, List<String>>()
        expected["DateRangeType with a format and a separator"] = emptyList()
        expected["EnumerationType with one value"] = emptyList()
        expected["DateFragmentType with a fragment format"] = emptyList()
        expected["CustomFieldType with a name"] = emptyList()
        expected["EnumerationType with an empty body — the live control"] = unbuildable
        assertEquals(expected, actual, "a constructing type whose body supplies what A12 builds from")
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
