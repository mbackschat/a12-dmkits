package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A field's original type binding and the checks that require its default-variant declaration.
 *
 * A `fieldType` A12 cannot bind refuses the source outright: no diagnostic, no code, the model simply does
 * not load, so a clean report about it would present silence as a verdict. Measured across the whole
 * discriminator vocabulary and the shapes around it ([KERNEL-FINDINGS KF308],
 * `rulekit.validate.laws.FieldTypeBindingLawsTest`), on a field no declaration references — the ordering
 * family's rows all sit on a field some group orders by, so a refusal measured there cannot be attributed to
 * the type alone.
 *
 * **The admitted vocabulary is DERIVED, which is what makes reporting an unknown name safe.** A checker that
 * refuses a spelling it does not recognise is claiming to know the complete set, and a hand-assembled set
 * that is short by one member turns every field using that member into a false rejection — the direction
 * this checker must never take. [FieldType] is not assembled: it is the case list of the single `switch` in
 * `FieldtypeDeserializer.deriveFieldtype`, whose `default` raises, so the thirteen names are the vocabulary
 * by construction rather than by survey.
 *
 * Original binding and container checks share this family. KF494/KF498 distinguish them by filtering the
 * same defective declaration: absent/null types and an absent Date body still refuse, while missing
 * construction members can disappear. Missing and unresolved typedef IDs also differ under filtering.
 * Keep the original binding gates before the filter; the combined load/build class does not locate a stage.
 *
 * **The absent-member half changed this checker's answer about nearly every model, and that was the cost.**
 * A field with no declared type is not an untyped field — A12 refuses to build the model
 * ([KERNEL-FINDINGS KF308], measured as a within-model pair on a field added for the purpose). It landed
 * only after the checker's own fixtures were given types, because 73 of 74 field declarations in them
 * carried none, which means those fixtures had been models A12 could not have built.
 */
internal fun TrackedFactBase.fieldTypeBindingFindings(): List<Finding> =
    declarations().filter { it.declaredKind == DeclaredKind.FIELD }.flatMap { field ->
        val declared = field.body?.get("fieldType")
        val typeFinding = if (declared == null) {
            unbuildable(field, "this field declares no `fieldType`, which A12 binds as non-null with no default")
        } else {
            unbindableFieldType(field, declared, declaredTypeDefinitionIds)
        }
        val booleanFindings = unbindableBooleanMembers(field)
        // A source A12 cannot read reaches no verdict, so a range is judged only beside a type and members that bind.
        val rangeFindings = if (typeFinding == null && booleanFindings.isEmpty()) dateRangeFindings(field) else emptyList()
        listOfNotNull(typeFinding) + rangeFindings + booleanFindings
    }

/**
 * The primitive `boolean` members of one field declaration that A12's reader cannot bind as written.
 *
 * Jackson binds every such member with one law ([configFlag]; [KERNEL-FINDINGS KF569]), and a spelling it has
 * no binding for refuses the whole source, so the field's variant filter never runs — a field the default
 * variant deletes is refused the same way (`adapter.laws.CheckerDeclarationAdmissionAgreementTest`, measured on
 * every member below on both carriers).
 */
private fun unbindableBooleanMembers(field: TrackedDeclaration): List<Finding> {
    val fieldBody = field.body ?: return emptyList()
    val declared = fieldBody["fieldType"] as? JsonObject
    val type = declared?.namedFieldType()
    val typeBody = type?.bodyIn(declared)
    val onField = FIELD_BOOLEAN_MEMBERS.mapNotNull { unbindableMember(field, fieldBody, it, it) }
    val onType = typeBody?.let { body ->
        type.booleanMembers.mapNotNull { unbindableMember(field, body, it, "$type.$it") }
    }
    return onField + onType.orEmpty()
}

private fun unbindableMember(field: TrackedDeclaration, owner: JsonObject, member: String, spelled: String) =
    if (owner.unbindableFlag(member)) {
        unbindable(
            field,
            "`$spelled` holds `${owner[member]}`, which A12 cannot bind as a boolean — write `true` or `false`",
        )
    } else {
        null
    }

/**
 * The primitive `boolean` members each type's body declares — enumerated from kernel `30.8.1`'s
 * `kernel-md-a12internal` classes (`fieldtypes.*`), which `FieldtypeDeserializer` binds with `treeToValue`.
 * Exhaustive, so a type added to [FieldType] has to say whether it carries any.
 */
internal val FieldType.booleanMembers: List<String>
    get() = when (this) {
        FieldType.STRING -> listOf("lineBreaksPermitted", "noValueValidation", "alphabeticalSorting")
        FieldType.NUMBER -> listOf("zeroNotAllowed", "positivesOnly", "leadingZerosAllowed")
        FieldType.DATE, FieldType.DATE_RANGE, FieldType.DATE_FRAGMENT -> listOf("youngerThan1900Check")
        FieldType.ENUMERATION -> listOf("alphabeticalSorting")
        FieldType.DATE_TIME, FieldType.TIME, FieldType.TYPE_DEF, FieldType.CUSTOM_FIELD, FieldType.CONFIRM,
        FieldType.BOOLEAN, FieldType.UNSPECIFIED,
        -> emptyList()
    }

/** `Field`'s own two (`isGlobal`, `isTransient` in the same package), under their JSON names. */
private val FIELD_BOOLEAN_MEMBERS = listOf("global", "transient")

/**
 * The fields whose type body this family stayed silent about, so silence is never read as agreement.
 *
 * Two kinds, each at the field it concerns. The range pair table is a probe's universe rather than a law, so a
 * format or a separator outside it is UNDISCOVERED rather than admitted — under-reporting, and safe. And a field
 * typed by a type definition takes its body from `content.typeDefinitions`, which this family does not read, while
 * A12 builds that body as the field's own (a blank date `format` there leaves the model unbuilt, KF584). What is not
 * safe is leaving either unsaid while the same report's `checksCompleted` names this family: a consumer would read
 * "examined, nothing found" for a declaration the family declined to judge.
 */
internal fun TrackedFactBase.fieldTypeBindingGaps(): List<CoverageGap> =
    defaultVariantFields().mapNotNull { field ->
        val declared = field.body?.get("fieldType") as? JsonObject ?: return@mapNotNull null
        when (declared.namedFieldType()) {
            FieldType.DATE_RANGE -> {
                val body = FieldType.DATE_RANGE.bodyIn(declared) ?: return@mapNotNull null
                val unmeasured = body.unmeasuredRangePair() ?: return@mapNotNull null
                CoverageGap(field.path, "this range's (format, separator) pair was left to A12: $unmeasured")
            }
            FieldType.TYPE_DEF -> field.boundTypeDefinitionId()?.let {
                CoverageGap(field.path, "this field takes its type from the type definition `$it`, whose body " +
                    "this family does not read, so what A12 builds from that body here was left to A12")
            }
            else -> null
        }
    }

/** Whether a declared pair lies inside what was put to the kernel, so its range-format verdict is known. */
private fun JsonObject.rangePairMeasured(): Boolean = unmeasuredRangePair() == null

/** Why a declared pair falls outside what was measured, or null when the pair was judged. */
private fun JsonObject.unmeasuredRangePair(): String? {
    val format = rangeMember("format", ABSENT_RANGE_FORMAT) ?: return "its `format` is present and is not a string"
    val separator = rangeMember("rangeSeparator", DEFAULT_RANGE_SEPARATOR)
        ?: return "its `rangeSeparator` is present and is not a string"
    return when {
        !(format + separator).namedByA12() ->
            "it carries a supplementary character, which A12 answers with its charset sentence alone"
        format != ABSENT_RANGE_FORMAT && !format.isA12Blank() && format !in ADMITTED_RANGE_SEPARATORS ->
            "the format `$format` was never put to the kernel as a RANGE format, so its admission there is " +
                "undiscovered rather than refused"
        separator !in PROBED_RANGE_SEPARATORS && separator !in MEASURED_RANGE_SEPARATORS ->
            "the separator `$separator` was never put to the kernel beside `$format`"
        else -> null
    }
}

/**
 * Whether `FieldtypeDeserializer.deriveFieldtype`'s case for this type guards a missing body.
 *
 * `true` means the case reads `if (typeNode == null) …builder().build()` and binds without the body; `false`
 * means it dereferences the body behind a `// typeNode is never null` comment, which is a claim rather than
 * a guard — and the measurement confirms all eight of those refuse the reader when the body is absent.
 * Source-read of kernel `30.8.1`, 2026-09-13; the split is measured row by row against its control grid.
 */
private val FieldType.guardsItsBody: Boolean
    get() = when (this) {
        FieldType.STRING, FieldType.NUMBER, FieldType.CONFIRM, FieldType.BOOLEAN, FieldType.UNSPECIFIED -> true
        FieldType.DATE, FieldType.DATE_TIME, FieldType.DATE_RANGE, FieldType.DATE_FRAGMENT, FieldType.ENUMERATION,
        FieldType.TIME, FieldType.TYPE_DEF, FieldType.CUSTOM_FIELD,
        -> false
    }

/**
 * One field's `fieldType`, judged as A12's reader binds it.
 *
 * The order mirrors the reader's own: the member must be an object, that object must carry a `type` the
 * deserializer switches on, and a case that does not guard its body must find one.
 */
private fun unbindableFieldType(
    field: TrackedDeclaration,
    declared: JsonElement,
    declaredTypeDefinitionIds: Set<String>,
): Finding? {
    // An explicit `null` is not an absent member: Jackson reaches `Field.fieldType`'s Lombok `@NonNull`
    // setter with it and the reader raises, where an omitted member never calls the setter at all. The two
    // land in different tiers, which is why this arm cannot simply treat null as nothing declared.
    if (declared is JsonNull) {
        return unbindable(field, "`fieldType` is explicitly null, which A12's reader refuses")
    }
    if (declared !is JsonObject) {
        return unbindable(field, "`fieldType` holds a value rather than a typed object")
    }
    val discriminator = declared["type"]?.asBoundText()
        ?: return unbindable(field, "`fieldType` names no `type`, so A12 binds nothing and refuses the null")
    val type = FieldType.of(discriminator)
        ?: return unbindable(
            field,
            "`fieldType` names the type `$discriminator`, which A12 does not declare — the vocabulary is " +
                "case-sensitive and holds exactly: ${FieldType.entries.map { it.wire }.sorted().joinToString(", ")}",
        )
    val body = declared[discriminator]
    if (!type.guardsItsBody && body == null) {
        return unbindable(
            field,
            "`fieldType` names `$discriminator` but carries no `$discriminator` body, which that type " +
                "reads without checking",
        )
    }
    if (type == FieldType.TYPE_DEF) {
        return unresolvedTypeDefinition(field, declaredTypeDefinitionIds)
    }
    // KF494/KF498: reader failures above survive filtering; the following type checks do not.
    if (field.body.deletesAtDefaultVariant()) return null
    // The one type refused for BEING itself: it binds like any other and A12 then has no code to generate
    // for it, in both body spellings, on a root and a nested field, and once per declaration rather than
    // once for the model (`FieldTypeBindingLawsTest`). So the verdict is the type's, not the body's, and
    // nothing about the body can lift it.
    if (type == FieldType.UNSPECIFIED) {
        return Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            "MVK_UNSPECIFIED_TYPE_NO_CODEGEN",
            "this field declares `UnspecifiedType`, which A12 reads and then refuses because it can " +
                "generate no validation code for it — the declaration needs a real type, and no member of " +
                "the body changes this",
            field.path,
        )
    }
    // Check the required member rather than body emptiness: optional members do not satisfy it (KF308).
    // KF498 measures the filter-sensitive absent-member carriers; explicit null remains unjudged.
    val required = REQUIRED_BODY_MEMBER[type]
    if (required != null && body is JsonObject && body.lacks(required)) {
        val explain = "`fieldType` names `$discriminator` without the `$required` A12 constructs that " +
            "type from"
        // The custom type is the one of the four that reaches a VERDICT rather than dying silently at the
        // build tier, so it carries A12's own class and the others carry this checker's.
        return VERDICT_FOR_ABSENT_MEMBER[type]
            ?.let { Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, it, explain, field.path) }
            ?: unbuildable(field, explain)
    }
    val defaulted = DEFAULTED_BUILD_MEMBER[type]
    if (defaulted != null && body is JsonObject && body.holdsBlank(defaulted)) {
        return unbuildable(
            field,
            "`fieldType` names `$discriminator` with a blank `$defaulted`, which A12 cannot construct that " +
                "type from where an omitted `$defaulted` takes the default",
        )
    }
    return null
}

/**
 * A `DateRangeType`'s `format` and `rangeSeparator`, answered as A12 answers them: with three verdicts on the one
 * range format it composes (KERNEL-FINDINGS KF581).
 *
 * A12 does not judge the two members, it judges `format + rangeSeparator + format`, the range format
 * `FieldDataCollector.addDateRangeType` writes, and an absent format composes as the text `null`. A composite that
 * is blank once Java-trimmed is `MVK_FORMAT_DATE_RANGE_MISSING`. Otherwise the separator draws a sentence of its own
 * when, trimmed, it is not empty, `-` or `/` — on every format and every one of the [MEASURED_RANGE_SEPARATORS] put
 * beside it, a letter, a symbol and two characters among them, so it is stated for any — and the pair draws
 * the range-format sentence when it is not one A12 allowlists. Every half of a refused pair appears in an admitted
 * pair, so neither half can be judged alone (KF316), and the pair is judged on the raw separator: `/ ` is refused
 * beside a format `/` is admitted with.
 *
 * **An ABSENT `rangeSeparator` is `/`**, the kernel's `FieldTypeConstants.A12_DATE_RANGE_SEPARATOR`
 * pre-initialised by `DateRangeTypeBuilder`, and measured as such on a format `/` admits and one it does
 * not. An explicit `""` is a different declaration with admitted pairs of its own, so the two cannot be
 * folded. A member that is present and not a string, and a value carrying a supplementary character (which A12
 * answers with its charset sentence alone, KF551), are left to A12.
 */
private fun dateRangeFindings(field: TrackedDeclaration): List<Finding> {
    if (field.body.deletesAtDefaultVariant()) return emptyList()
    val declared = field.body?.get("fieldType") as? JsonObject ?: return emptyList()
    if (declared.namedFieldType() != FieldType.DATE_RANGE) return emptyList()
    val body = FieldType.DATE_RANGE.bodyIn(declared) ?: return emptyList()
    val format = body.rangeMember("format", ABSENT_RANGE_FORMAT) ?: return emptyList()
    val separator = body.rangeMember("rangeSeparator", DEFAULT_RANGE_SEPARATOR) ?: return emptyList()
    val composite = format + separator + format
    if (!composite.namedByA12()) return emptyList()
    if (composite.javaTrimmed().isEmpty()) {
        return listOf(Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, "MVK_FORMAT_DATE_RANGE_MISSING",
            "this range composes the format `$composite` from its format and separator, which is blank", field.path))
    }
    val separatorFinding = separator.takeIf { it.javaTrimmed() !in RANGE_SEPARATOR_ALPHABET }?.let {
        sentence(field, RK_DATE_RANGE_SEPARATOR_INVALID, "the range separator `$it` is not `-` or `/`")
    }
    val formatFinding = if (separator in ADMITTED_RANGE_SEPARATORS[format].orEmpty() || !body.rangePairMeasured()) {
        null
    } else {
        val admitted = ADMITTED_RANGE_SEPARATORS[format]
        val admits = when {
            admitted == null -> "`$format` is in no pair A12 admits"
            admitted.isEmpty() -> "A12 admits `$format` on a range with none of the separators it has been measured with"
            else -> "A12 admits `$format` on a range only with " + admitted.sorted().joinToString(" and ") {
                if (it.isEmpty()) "no separator at all" else "`$it`"
            }
        }
        sentence(field, RK_DATE_RANGE_FORMAT_INVALID,
            "this range composes the format `$composite`, and A12 allowlists the PAIR rather than either half — $admits")
    }
    return listOfNotNull(separatorFinding, formatFinding)
}

/** The member as A12 reads it, [absent] when it is omitted, or null for a present non-string nobody has measured. */
private fun JsonObject.rangeMember(name: String, absent: String): String? = when (val declared = this[name]) {
    null -> absent
    is JsonPrimitive -> declared.takeIf { it.isString }?.content
    else -> null
}

private fun sentence(field: TrackedDeclaration, code: String, explain: String) =
    Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, code, explain, field.path)

/**
 * A `TypeDefType`'s reference — the one reference in this checker whose referent is not an element.
 *
 * A field typed by a type definition names it through **`typeDefinitionId`**, resolved against
 * `content.typeDefinitions`. The wire name is worth stating because the Java field is called `id` and taking
 * that for the wire name is what made an earlier probe measure an unknown member and read the result as a
 * dangling-reference law ([KERNEL-FINDINGS KF308], corrected). It was settled against a shipped model, which
 * is the only oracle for a wire name.
 *
 * On unfiltered fields both failures are classified as load/build refusals: `EntityReferenceResolverVisitor.visitField`
 * resolves the id and deliberately sets the reference to null when it cannot, so the source loads and the
 * model then fails to build — measured for a dangling reference, for an absent member, and against a
 * declared-definition control that is ADMITTED.
 */
private fun unresolvedTypeDefinition(
    field: TrackedDeclaration,
    declaredTypeDefinitionIds: Set<String>,
): Finding? {
    val named = field.boundTypeDefinitionId()
        ?: return unbuildable(
            field,
            "`fieldType` names `TypeDefType` without a `typeDefinitionId`, so A12 has no type definition to " +
                "resolve",
        )
    // KF498 distinguishes a missing id above from a present unresolved id on a filtered field.
    if (field.body.deletesAtDefaultVariant() || named in declaredTypeDefinitionIds) {
        return null
    }
    return unbuildable(
        field,
        "`fieldType` names the type definition `$named`, which `content.typeDefinitions` does not declare",
    )
}

/**
 * The type definition a field binds BY ID, or null when it types inline or names no usable id.
 *
 * Extracted so the dependency graph reads the id from the same place this family judges it: `TypeDefType` and
 * `typeDefinitionId` are wire names, and a second copy of them is the copy the two would disagree about. The
 * kernel's own Java field is called `id`, and taking THAT for the wire name is what once made a probe measure
 * an unknown member and mistake the result for a law ([KERNEL-FINDINGS KF308], corrected).
 */
internal fun TrackedDeclaration.boundTypeDefinitionId(): String? = boundTypeDefinitionIdIn(body)

/** The same reading off an untracked declaration, sharing one implementation so the two cannot drift. */
internal fun DeclaredElement.boundTypeDefinitionId(): String? = boundTypeDefinitionIdIn(body)

/**
 * A field's type with a type definition resolved to the type it defines, and the `fieldType` object that
 * type's settings sit in; [viaTypeDefinition] says the resolution happened.
 *
 * A12 judges a type-defined field by the definition's own type wherever it compares types: an index joins or
 * refuses by it, and a key is consistent with an index by it ([KERNEL-FINDINGS KF602],
 * `adapter.laws.CheckerOuterMarkedResidueAgreementTest`). Null where the definition does not resolve, or
 * defines another definition.
 */
internal class ResolvedFieldType(val type: FieldType, val declared: JsonObject?, val viaTypeDefinition: Boolean)

internal fun TrackedFactBase.resolvedFieldTypeOf(field: TrackedDeclaration): ResolvedFieldType? {
    val fieldType = field.declaredFieldType() ?: return null
    if (fieldType != FieldType.TYPE_DEF) {
        return ResolvedFieldType(fieldType, field.body?.get("fieldType") as? JsonObject, viaTypeDefinition = false)
    }
    val id = field.boundTypeDefinitionId() ?: return null
    val declared = typeDefinitionBody(id)?.get("fieldType") as? JsonObject ?: return null
    val defined = declared.namedFieldType()?.takeIf { it != FieldType.TYPE_DEF } ?: return null
    return ResolvedFieldType(defined, declared, viaTypeDefinition = true)
}

private fun boundTypeDefinitionIdIn(body: JsonObject?): String? {
    val declared = body?.get("fieldType") as? JsonObject ?: return null
    if (declared.namedFieldType() != FieldType.TYPE_DEF) {
        return null
    }
    val typeDef = FieldType.TYPE_DEF.bodyIn(declared) ?: return null
    return (typeDef["typeDefinitionId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/**
 * The one body member each constructing type needs — DERIVED, then measured row by row.
 *
 * Each of these classes takes its arguments through a Lombok `@Builder` on a private constructor, and an
 * argument is optional exactly where the hand-written `…Builder` class pre-initialises its field. So the
 * required member is the `@NonNull` constructor parameter the builder leaves alone, and there is exactly one
 * per type — a derivation rather than a survey, which is what makes naming the member safe where guessing it
 * would refuse models A12 takes. Source-read of kernel `30.8.1`, 2026-09-13
 * (`kernel-md-a12internal/…/fieldtypes/`), measured against its own grid in
 * `rulekit.validate.laws.FieldTypeBodyMemberLawsTest` ([KERNEL-FINDINGS KF312]).
 *
 * `EnumerationType` is the entry a reading could get wrong: its `values` FIELD carries an initialiser, so it
 * looks defaulted, and the builder — which is what decides — defaults every other `@NonNull` parameter and
 * not that one. The measurement agrees with the builder.
 *
 * **The member is NECESSARY and not sufficient**, which is why its absence is what gets reported and its
 * presence, on its own, draws nothing. A measured row has the member and is still refused: the
 * `DateFragmentType` one — a 1900 check on a format with no year — is A12's own consistency verdict and belongs to
 * [temporalConfigFindings].
 *
 * `DateRangeType` is absent although its builder needs `format`: A12 composes an omitted format as the text `null`
 * and answers it with a verdict rather than a build failure, so [dateRangeFindings] judges it (KF581).
 *
 * `TypeDefType` is the fifth constructing type and is absent here: its member is a REFERENCE, which
 * [unresolvedTypeDefinition] resolves rather than merely finds.
 */
private val REQUIRED_BODY_MEMBER = mapOf(
    FieldType.DATE_FRAGMENT to "formatOfFragment",
    FieldType.ENUMERATION to "values",
    FieldType.CUSTOM_FIELD to "name",
)

/**
 * The member each date type defaults when it is absent and refuses to construct from when it is blank.
 *
 * The three builders pre-initialise `format`, so an omitted member is the type's default and draws nothing; a
 * present string Java's `isBlank` accepts — the em space included, the no-break space not — leaves the model
 * unbuilt, and on a field the default variant deletes it does not (`adapter.laws.CheckerBlankDateFormatAgreementTest`,
 * [KERNEL-FINDINGS KF584]). A present member that is not a string was never measured, so it is not judged.
 */
private val DEFAULTED_BUILD_MEMBER = mapOf(
    FieldType.DATE to "format",
    FieldType.DATE_TIME to "format",
    FieldType.TIME to "format",
)

/** Whether this body holds [member] as a string A12's construction reads as blank. */
private fun JsonObject.holdsBlank(member: String): Boolean =
    (this[member] as? JsonPrimitive)?.takeIf { it.isString }?.content?.isA12Blank() == true

/**
 * The types whose absent member A12 answers with a CLASS rather than a silent build refusal.
 *
 * One of the four does, measured ([KERNEL-FINDINGS KF323], `FieldTypeBodyMemberLawsTest`), and its class
 * carries no `MVK_` prefix — which is why a checker keying on that prefix would have read it as no verdict
 * at all. The other three are absent on purpose: reporting A12's class where A12 issues none would put a
 * label on a refusal it did not make.
 */
private val VERDICT_FOR_ABSENT_MEMBER = mapOf(FieldType.CUSTOM_FIELD to "CUSTOM_FIELD_TYPE_CODENAME_MISSING")

/**
 * Whether this body leaves the member A12 builds from unusable, which is not the same as absent.
 *
 * A string member is judged on its CONTENT: an empty and a blank `name` both draw the custom type's class
 * exactly as no `name` does, so testing for the key alone stays silent on two models A12 refuses. Any other
 * shape — a number, an array, an object — is judged on presence only, because a member present but not a
 * string is a shape nobody has run and guessing could refuse a model A12 takes.
 */
private fun JsonObject.lacks(member: String): Boolean = when (val declared = this[member]) {
    null -> true
    is JsonPrimitive -> declared.isString && declared.content.isA12Blank()
    else -> false
}

/** The kernel's `FieldTypeConstants.A12_DATE_RANGE_SEPARATOR`, source-read of `30.8.1` and measured. */
private const val DEFAULT_RANGE_SEPARATOR = "/"

/** How A12 composes an omitted format: `getFormatOfDate()` concatenated as `null` (KF581, measured). */
private const val ABSENT_RANGE_FORMAT = "null"

/** The trimmed separators the separator sentence admits (KF581). */
private val RANGE_SEPARATOR_ALPHABET = setOf("", "-", "/")

/**
 * Separators put to the kernel beside every [ADMITTED_RANGE_SEPARATORS] format besides the sweep's own axis, by
 * `adapter.laws.CheckerDateRangeSentenceAgreementTest` (KF581); none admits a pair.
 */
private val MEASURED_RANGE_SEPARATORS = setOf(" ", "\t", "x", "\u2013", "//", "/ ", " /", "/\t", "\u00A0", "|", "\u00E4", "ab")

internal const val RK_DATE_RANGE_FORMAT_INVALID = "RK_DATE_RANGE_FORMAT_INVALID"
internal const val RK_DATE_RANGE_SEPARATOR_INVALID = "RK_DATE_RANGE_SEPARATOR_INVALID"

/**
 * The separators the derived sweep put to the kernel beside every candidate format. A pair naming any other
 * separator was never measured, so this checker leaves it alone.
 *
 * **It is one fact with [ADMITTED_RANGE_SEPARATORS], and the guard binds them together.** This set is the
 * silence boundary and that one is the verdict, so they go stale in opposite directions: a member lost here
 * only silences a pair, while a member this set keeps after the sweep drops it turns an UNMEASURED pair into
 * a reported one — the over-rejection the pair table is guarded against, arriving through the other half.
 */
// RANGE-PROBE-GRID-START — the guard reads only this delimited region and requires it to be the sweep's own
// separator axis; a separator named elsewhere in this file does not count as probed.
private val PROBED_RANGE_SEPARATORS = setOf("/", "-", ".", "", " - ", ":", "_")
// RANGE-PROBE-GRID-END

/**
 * Which separators A12 admits each range format with — the DERIVED pair domain, restricted to the formats
 * this checker may name.
 *
 * **Two measured sources, and the empty entries are as measured as the others.** The non-empty entries are
 * the admitted `(format, separator)` pairs of [TEMPORAL-FORMAT-VOCABULARY]; the empty ones are the admitted
 * FIELD formats that appear in no admitted pair, which means the sweep put each of them to the kernel at
 * every probed separator and the kernel refused all seven. A format outside this table is one whose range
 * admission is undiscovered rather than refused, so it draws nothing.
 *
 * **It is guard-registered, and it has to be**: this is the one table in the checker whose going stale
 * produces an OVER-rejection — a pair the kernel gains would be reported as unbuildable — so
 * `rulekit.validate.laws.TemporalFormatVocabularyGuardTest` re-derives it from the kernel and fails on any
 * disagreement, in either direction.
 */
// RANGE-PAIR-GRID-START — the guard reads only this delimited region and compares it to the kernel's own
// answer, entry for entry. A pair named elsewhere in this file does not count as carried.
private val ADMITTED_RANGE_SEPARATORS = mapOf(
    "MM" to setOf("", "/"),
    "MM-dd" to setOf("/"),
    "dd.MM" to setOf("-"),
    "dd.MM.yyyy" to setOf("-"),
    "yyyy" to setOf("/"),
    "yyyy-MM" to setOf("/"),
    "yyyy-MM-dd" to setOf("/"),
    "HH:mm:ss" to emptySet(),
    "MMdd" to emptySet(),
    "ddMM" to emptySet(),
    "dd.MM." to emptySet(),
    "yyyy-MM-dd'T'HH:mm:ss" to emptySet(),
    "yyyyMM" to emptySet(),
    "yyyyMMdd" to emptySet(),
)
// RANGE-PAIR-GRID-END

/**
 * The discriminator as A12 reads it — `JsonNode.asText()`, which stringifies any scalar rather than demanding
 * a JSON string, so a numeric or null `type` becomes a name that simply is not in the vocabulary and falls to
 * the same raising default. A container has no such reading and is reported as the missing discriminator it
 * is. Excluding null here would be a branch with no verdict of its own, since both paths end unbindable.
 */
private fun JsonElement.asBoundText(): String? = (this as? JsonPrimitive)?.content

/** Tier one: A12's reader never got past the bytes, so no declaration was examined at all. */
private fun unbindable(field: TrackedDeclaration, complaint: String) = finding(
    field,
    "$complaint — A12's own reader refuses the source outright, so the model never loads and no " +
        "consistency verdict about it exists.",
)

/**
 * Tier two: A12 READ this and then could not build the model from it.
 *
 * The same code, deliberately — both leave a consumer with no verdict — and a different sentence, because a
 * model that loads and fails to build has told A12 something, where one the reader refused has not.
 */
private fun unbuildable(field: TrackedDeclaration, complaint: String) = finding(
    field,
    "$complaint, so A12 reads the model and then fails to build it — no consistency verdict about it exists.",
)

private fun finding(field: TrackedDeclaration, explain: String) = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    CK_MODEL_NOT_BUILDABLE,
    explain,
    field.path,
)

/**
 * The `StringType` body this field declares, or null when it declares another type or none.
 *
 * Lives here because this file owns the `fieldType` union; two families past this one read INTO one arm of
 * it ([declaredPatternFindings], [stringTypeMessageFindings]) and a second copy of the unwrapping is how the
 * arms would drift apart.
 */
internal fun TrackedDeclaration.stringTypeBody(): JsonObject? {
    val declared = body?.get("fieldType") as? JsonObject ?: return null
    if (declared.namedFieldType() != FieldType.STRING) {
        return null
    }
    return FieldType.STRING.bodyIn(declared)
}
