package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.adapter.KernelConsistencyOracle
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.core.Severity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Field declarations A12 admits that the portable checker refused, each beside the control A12 refuses
 * (KERNEL-FINDINGS KF569; a12-kernel-lean SPEC-2026-10-13-16 reported the first eight). Six readings, each in its
 * own test: the enumeration value-and-label family judges the default-variant model; a label counts only for a
 * declared locale; `sValue` is compared trimmed on both sides; the underscore reservation is `__meta` exactly or
 * a `__attachment_meta` prefix; Number bounds are rounded half away from zero when `maxFractionalDigits` is 0;
 * and the boolean members — the two String flags and `positivesOnly` — bind as A12's reader binds them. Every
 * row is pinned for A12 and compared against the checker, so each control holds the reading against its rival.
 *
 * Own domain (an insurance policy), built by editing the drafted model's JSON, because the typed builders cannot
 * spell most of these declarations.
 */
class CheckerDeclarationAdmissionAgreementTest {

    @Test
    fun theLabelFamilyJudgesTheDefaultVariantModel() = assertAgreement(linkedMapOf(
        "labels started, ONLY-filtered" to enumRow(started, ONLY) to admitted,
        "labels started, ALL_EXCEPT-filtered" to enumRow(started, ALL_EXCEPT) to setOf(COUNT),
        "labels started" to enumRow(started) to setOf(COUNT),
        "a blank label, ONLY-filtered" to enumRow(blank, ONLY) to admitted,
        "a blank label" to enumRow(blank) to setOf(INCOMPLETE),
        "a repeated label, ONLY-filtered" to enumRow(repeatedLabel, ONLY) to admitted,
        "a repeated label" to enumRow(repeatedLabel) to setOf("MVK_DISPLAY_VALUES_DUPLICATE"),
        // A12 still refuses the filtered duplicate, with its codeless repeated-value sentence alone (KF551).
        "a repeated token, ONLY-filtered" to enumRow(listOf(value("BASIC"), value("BASIC")), ONLY) to admitted,
        "a repeated token" to enumRow(listOf(value("BASIC"), value("BASIC"))) to setOf("MVK_INTERNAL_VALUES_DUPLICATE"),
    ))

    @Test
    fun aLabelCountsOnlyForADeclaredLocale() = assertAgreement(linkedMapOf(
        "one value labelled for an undeclared locale" to
            enumRow(listOf(value("BASIC", "fr_FR" to "Base"), value("PLUS"))) to admitted,
        "one value blank-labelled for an undeclared locale" to
            enumRow(listOf(value("BASIC", "fr_FR" to ""), value("PLUS"))) to admitted,
        "declared beside undeclared, against undeclared alone" to enumRow(listOf(
            value("BASIC", "en_US" to "Basic", "fr_FR" to "Base"), value("PLUS", "fr_FR" to "Plus"))) to setOf(COUNT),
        "one value labelled for the declared locale — the control" to
            enumRow(listOf(value("BASIC", "en_US" to "Basic"), value("PLUS"))) to setOf(COUNT),
        "a label repeated only in an undeclared locale" to enumRow(listOf(
            value("BASIC", "en_US" to "Basic", "fr_FR" to "Même"),
            value("PLUS", "en_US" to "Plus", "fr_FR" to "Même"))) to admitted,
        "a label repeated in the declared locale — the control" to enumRow(listOf(
            value("BASIC", "en_US" to "Même", "fr_FR" to "Base"),
            value("PLUS", "en_US" to "Même", "fr_FR" to "Plus"))) to setOf(DISPLAY_DUPLICATE),
    ))

    @Test
    fun theSpecialValueIsComparedTrimmed() = assertAgreement(linkedMapOf(
        "a space" to special(" ") to admitted,
        "a tab" to special("\t") to admitted,
        "a token with a leading space" to special(" BASIC") to admitted,
        "a token with a trailing space" to special("BASIC ") to admitted,
        "a token padded on the value side" to special("BASIC", token = " BASIC") to admitted,
        "a foreign token" to special("GOLD") to setOf(S_VALUE),
        "a foreign token with a leading space" to special(" GOLD") to setOf(S_VALUE),
        "a token split by a space" to special("BA SIC") to setOf(S_VALUE),
        "a no-break space, which trimming keeps" to special("\u00A0") to setOf(S_VALUE),
        "a token in another case" to special("basic") to setOf(S_VALUE),
    ))

    @Test
    fun theUnderscoreReservationIsMetaExactlyOrAnAttachmentPrefix() = assertAgreement(
        listOf("__meta", "__attachment_meta", "__attachment_meta_photo", "__attachment_metadata", "__attachment_metaX")
            .associateWith { admitted } +
            listOf("__meta_x", "__metadata", "__META", "__Attachment_meta", "__attachment_me", "___attachment_meta")
                .associateWith { setOf("NAME_STARTS_WITH_UNDERSCORE_AND_WRONG_PATTERN") },
        ::groupNamed,
    )

    @Test
    fun boundsAreRoundedWhenNoFractionIsDeclared() = assertAgreement(linkedMapOf(
        "1.4 over 1, no fraction" to bounds(0, 1.4, 1.0) to admitted,
        "1.4 over 1.2, no fraction" to bounds(0, 1.4, 1.2) to admitted,
        "2.4 over 1.6, no fraction" to bounds(0, 2.4, 1.6) to admitted,
        "-1.5 over -1.6, no fraction — half away from zero" to bounds(0, -1.5, -1.6) to admitted,
        "-0.5 over -1, no fraction" to bounds(0, -0.5, -1.0) to admitted,
        "the double below one half over 0" to bounds(0, 0.49999999999999994, 0.0) to admitted,
        "1.4 over 1, maxFractionalDigits absent" to bounds(null, 1.4, 1.0) to admitted,
        "1.5 over 1.4, no fraction" to bounds(0, 1.5, 1.4) to setOf(MIN_ABOVE_MAX),
        "2.5 over 2, no fraction — not half to even" to bounds(0, 2.5, 2.0) to setOf(MIN_ABOVE_MAX),
        "1.04 over 1, one fraction digit — compared unrounded" to bounds(1, 1.04, 1.0) to setOf(MIN_ABOVE_MAX),
        "1.004 over 1.001, one fraction digit" to bounds(1, 1.004, 1.001) to setOf(MIN_ABOVE_MAX),
        "1.4 over 1, maxFractionalDigits -1" to bounds(-1, 1.4, 1.0) to setOf(MIN_ABOVE_MAX),
    ))

    @Test
    fun theStringFlagsBindAsA12ReadsThem() = assertAgreement(linkedMapOf(
        "line breaks 2" to flags(true, 2) to admitted,
        "line breaks 1" to flags(true, 1) to admitted,
        "line breaks -1" to flags(true, -1) to admitted,
        "line breaks \"true\"" to flags(true, "true") to admitted,
        "line breaks \"TRUE\"" to flags(true, "TRUE") to admitted,
        "line breaks 0" to flags(true, 0) to setOf(NO_LINE_BREAK),
        "line breaks \"false\"" to flags(true, "false") to setOf(NO_LINE_BREAK),
        "line breaks \"\"" to flags(true, "") to setOf(NO_LINE_BREAK),
        "no value validation 1" to flags(1, null) to setOf(NO_LINE_BREAK),
        "no value validation 2" to flags(2, null) to setOf(NO_LINE_BREAK),
        "no value validation \"True\"" to flags("True", null) to setOf(NO_LINE_BREAK),
        "no value validation \"\"" to flags("", null) to admitted,
        // The string arm trims as Java trims, and the text `null` is the default.
        "line breaks \" true\"" to flags(true, " true") to admitted,
        "line breaks \"null\"" to flags(true, "null") to setOf(NO_LINE_BREAK),
        "line breaks \" \"" to flags(true, " ") to setOf(NO_LINE_BREAK),
        "no value validation \" TRUE\"" to flags(" TRUE", null) to setOf(NO_LINE_BREAK),
        "no value validation \"null\"" to flags("null", null) to admitted,
    ))

    /** The same reader binds `positivesOnly`, which moves the Number length budget by the sign's one place. */
    @Test
    fun theSignFlagBindsTheSameWay() = assertAgreement(linkedMapOf(
        "positivesOnly 1" to signedLength(1) to setOf(MAX_LENGTH_TOO_BIG),
        "positivesOnly \"TRUE\"" to signedLength("TRUE") to setOf(MAX_LENGTH_TOO_BIG),
        "positivesOnly true — the control" to signedLength(true) to setOf(MAX_LENGTH_TOO_BIG),
        "positivesOnly 0" to signedLength(0) to admitted,
        "positivesOnly \"\"" to signedLength("") to admitted,
    ))

    /**
     * A12's model reader cannot bind these (Jackson refuses the token for a `boolean` member), so the model is
     * never built: the checker says exactly that, and no coded class may stand in for it.
     */
    @Test
    fun anUnbindableFlagDrawsNoCodedClass() {
        val unbindable = listOf<Any>(1.0, 1.5, "1", "tRUE", "NULL", "\u00A0true", emptyList<String>(), emptyMap<String, String>())
        val wrong = unbindable.flatMap { raw -> listOf(flags(raw, null), flags(true, raw)) }.mapNotNull { json ->
            val built = runCatching { oracle.checkConsistency(json, ReferenceResolvers.selfContained()) }
            val ours = ours(json)
            when {
                !built.refusedBinding() -> "A12 bound $json"
                ours != listOf(NOT_BUILDABLE) -> "ours $ours on $json"
                else -> null
            }
        }
        assertEquals(emptyList<String>(), wrong)
    }

    /**
     * Every primitive `boolean` member of a field declaration takes the same binding (IF404), so the same
     * spellings leave the model unbuilt — enumerated from the kernel's `a12internal` model classes: the field
     * types' members and `Field.global`/`transient`. Each is put on a field the default variant keeps and on one
     * it deletes, because binding precedes the variant filter. The `" TRUE"` row per member is the control: A12
     * binds it, so the refusal belongs to the spelling and not to the member.
     */
    @Test
    fun everyBooleanMemberOfADeclarationIsUnbindableTheSameWay() {
        val members = listOf(
            "StringType" to "noValueValidation", "StringType" to "lineBreaksPermitted",
            "StringType" to "alphabeticalSorting", "NumberType" to "zeroNotAllowed",
            "NumberType" to "positivesOnly", "NumberType" to "leadingZerosAllowed",
            "DateType" to "youngerThan1900Check", "DateRangeType" to "youngerThan1900Check",
            "DateFragmentType" to "youngerThan1900Check", "EnumerationType" to "alphabeticalSorting",
            "Field" to "global", "Field" to "transient",
        )
        val carriers = members.flatMap { (type, member) ->
            listOf(Triple(type, member, null), Triple(type, member, ONLY))
        }
        val wrong = carriers.flatMap { (type, member, filter) ->
            listOf<Any>("tRUE", 1.5, " TRUE").mapNotNull { raw ->
                val json = member(type, member, raw, filter)
                val refused = runCatching { oracle.checkConsistency(json, ReferenceResolvers.selfContained()) }
                    .refusedBinding()
                val ours = ours(json)
                val row = "$type.$member=${mapper.writeValueAsString(raw)}" + if (filter == null) "" else " (deleted)"
                when {
                    raw == " TRUE" && refused -> "A12 refused $row"
                    raw == " TRUE" && NOT_BUILDABLE in ours -> "ours $ours on $row"
                    raw != " TRUE" && !refused -> "A12 bound $row"
                    raw != " TRUE" && ours != listOf(NOT_BUILDABLE) -> "ours $ours on $row"
                    else -> null
                }
            }
        }
        assertEquals(emptyList<String>(), wrong)
    }

    /**
     * The header's two `boolean` members (`DocumentModelInfo.immutable`/`metaData`) bind by the same law: A12
     * alone, because the checker's verdict on them is the open CONSISTENCY-GAPS DG139.
     */
    @Test
    fun theHeaderBooleansBindTheSameWay() {
        val refused = listOf("immutable", "metaData").flatMap { member ->
            listOf<Any>("tRUE", 1.5, " TRUE").map { raw ->
                "$member=${mapper.writeValueAsString(raw)}" to runCatching {
                    oracle.checkConsistency(header(member, raw), ReferenceResolvers.selfContained())
                }.refusedBinding()
            }
        }
        assertEquals(refused.map { (row, _) -> row to !row.endsWith("\" TRUE\"") }, refused)
    }

    // ---- the instrument ----

    private fun Result<*>.refusedBinding() =
        generateSequence(exceptionOrNull()) { it.cause }.any { it is MismatchedInputException }

    private val oracle = KernelConsistencyOracle()
    private val mapper = ObjectMapper()
    private val nodes = JsonNodeFactory.instance
    private val admitted = emptySet<String>()

    private fun assertAgreement(rows: Map<Pair<String, String>, Set<String>>) {
        val observed = rows.entries.associate { (row, _) -> row.first to (kernel(row.second) to ours(row.second).toSet()) }
        assertEquals(rows.entries.associate { (row, expected) -> row.first to (expected to expected) }, observed,
            "(A12, ours)")
    }

    private fun assertAgreement(rows: Map<String, Set<String>>, build: (String) -> String) =
        assertAgreement(rows.entries.associate { (id, expected) -> (id to build(id)) to expected })

    private fun kernel(json: String): Set<String> = oracle.checkConsistency(json, ReferenceResolvers.selfContained())
        .filter { it.severity() == Severity.ERROR }
        .mapNotNull { Regex("\\[(\\w+)]$").find(it.message().trim())?.groupValues?.get(1) }.toSet()

    private fun ours(json: String): List<String> =
        check(ModelSource(json)).findings.filter { it.justifiesRejection }.map { it.code }

    private fun policy(): ObjectNode = mapper.readTree(DocumentModelDraft.named("policy-declarations").locale("en_US")
        .rootGroup("Policy") { p -> p.stringField("Number") { } }.build().toJson()) as ObjectNode

    private fun elements(model: ObjectNode) =
        model.path("content").path("modelRoot").path("rootGroups")[0].path("Group").path("elements") as ArrayNode

    private fun field(model: ObjectNode, name: String, type: String, body: ObjectNode, filter: ObjectNode? = null) {
        val field = elements(model).addObject().put("type", "Field").put("id", "/Policy/$name").put("name", name)
            .putObject("Field")
        field.putObject("fieldType").put("type", type).set<ObjectNode>(type, body)
        filter?.let { field.set<ObjectNode>("variantFilter", it) }
    }

    private fun value(token: String, vararg labels: Pair<String, String>): ObjectNode = nodes.objectNode().also { v ->
        v.put("value", token)
        if (labels.isNotEmpty()) {
            val entries = v.putArray("label")
            labels.forEach { (locale, text) -> entries.addObject().put("locale", locale).put("text", text) }
        }
    }

    private fun enumRow(values: List<ObjectNode>, filter: ObjectNode? = null, sValue: String? = null): String =
        policy().also { model ->
            val body = nodes.objectNode()
            body.putArray("values").addAll(values)
            sValue?.let { body.put("sValue", it) }
            field(model, "Tier", "EnumerationType", body, filter)
        }.toString()

    private fun special(sValue: String, token: String = "BASIC") =
        enumRow(listOf(value(token), value("PLUS")), sValue = sValue)

    private fun groupNamed(name: String): String = policy().also { model ->
        elements(model).addObject().put("type", "Group").put("id", "/Policy/$name").put("name", name)
            .putObject("Group").put("repeatability", 1).putArray("elements").addObject().put("type", "Field")
            .put("id", "/Policy/$name/Note").put("name", "Note").putObject("Field").putObject("fieldType")
            .put("type", "StringType")
    }.toString()

    private fun bounds(maxFractionalDigits: Int?, min: Double, max: Double): String = policy().also { model ->
        val body = nodes.objectNode().put("minValue", min).put("maxValue", max)
        maxFractionalDigits?.let { body.put("maxFractionalDigits", it) }
        field(model, "Premium", "NumberType", body)
    }.toString()

    private fun flags(noValueValidation: Any, lineBreaksPermitted: Any?): String = policy().also { model ->
        val body = nodes.objectNode()
        body.set<ObjectNode>("noValueValidation", mapper.valueToTree(noValueValidation))
        lineBreaksPermitted?.let { body.set<ObjectNode>("lineBreaksPermitted", mapper.valueToTree(it)) }
        field(model, "Remark", "StringType", body)
    }.toString()

    private fun signedLength(positivesOnly: Any): String = policy().also { model ->
        val body = nodes.objectNode().put("maxFractionalDigits", 0).put("maxLength", 16)
        body.set<ObjectNode>("positivesOnly", mapper.valueToTree(positivesOnly))
        field(model, "Instalments", "NumberType", body)
    }.toString()

    private fun member(type: String, member: String, raw: Any, filter: ObjectNode?): String =
        policy().also { model -> declare(model, type, member, raw, filter) }.toString()

    private fun declare(model: ObjectNode, type: String, member: String, raw: Any, filter: ObjectNode?) {
        val fieldType = if (type == "Field") "StringType" else type
        val body = nodes.objectNode()
        when (fieldType) {
            "EnumerationType" -> body.putArray("values").add(value("BASIC")).add(value("PLUS"))
            "DateFragmentType" -> body.put("formatOfFragment", "yyyy")
            "DateRangeType" -> body.put("format", "dd.MM.yyyy").put("rangeSeparator", "-")
        }
        if (type != "Field") body.set<ObjectNode>(member, mapper.valueToTree(raw))
        field(model, "Cover", fieldType, body, filter)
        if (type == "Field") {
            (elements(model).last().path("Field") as ObjectNode).set<ObjectNode>(member, mapper.valueToTree(raw))
        }
    }

    private fun header(member: String, raw: Any): String = policy().also { model ->
        (model.path("content").path("modelInfo") as ObjectNode).set<ObjectNode>(member, mapper.valueToTree(raw))
    }.toString()

    private val started = listOf(value("BASIC", "en_US" to "Basic"), value("PLUS"))
    private val blank = listOf(value("BASIC", "en_US" to "Basic"), value("PLUS", "en_US" to " "))
    private val repeatedLabel = listOf(value("BASIC", "en_US" to "Same"), value("PLUS", "en_US" to "Same"))

    private val ONLY = nodes.objectNode().put("filterType", "ONLY").also { it.putArray("variants").add("Broker") }
    private val ALL_EXCEPT = nodes.objectNode().put("filterType", "ALL_EXCEPT").also { it.putArray("variants").add("Broker") }

    private companion object {
        const val COUNT = "MVK_COUNT_OF_VALUES_INVALID"
        const val INCOMPLETE = "MVK_INTERNAL_VALUES_AND_DISPLAY_VALUES"
        const val S_VALUE = "MVK_S_VALUE_INVALID"
        const val MIN_ABOVE_MAX = "MVK_MIN_VALUE_BIGGER_THAN_MAX_VALUE"
        const val NO_LINE_BREAK = "MVK_STRING_TYPE_NO_VAL_AND_NO_LINE_BREAK"
        const val MAX_LENGTH_TOO_BIG = "MVK_MAX_LENGTH_TOO_BIG"
        const val DISPLAY_DUPLICATE = "MVK_DISPLAY_VALUES_DUPLICATE"
        const val NOT_BUILDABLE = "CK_MODEL_NOT_BUILDABLE"
    }
}
