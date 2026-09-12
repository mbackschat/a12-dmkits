package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.ConditionLanguage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The model a declaration was AUTHORED in, and how that model's own absolute paths read after mounting.
 *
 * A mounted declaration keeps naming the entities that moved with it, so its absolute references still spell
 * the provider's own root group — which no longer exists in the host tree. [rehost] applies the one
 * substitution that fixes that, and the Kernel does the same thing at expansion time rather than leaving the
 * reference to fail (`DocumentModelExpandService.RuleAndComputationConditionUpdaterVisitor` with
 * `EntityPathsRelativizer`, [KERNEL-FINDINGS KF3]). A relative downward reference needs no rehosting at all:
 * mounting preserves depth within the fragment, because the provider's root group is replaced by the mount
 * group rather than added above it.
 *
 * @param modelId the source that declares this element. It also scopes the model-wide short-name fallback:
 *     the Kernel binds a provider's unique cross-group short name BEFORE integration, so a name the host
 *     happens to reuse cannot retarget it.
 * @param authoredPrefix the absolute prefix this model's own paths carried standalone — `""` for the entry
 *     model, whose paths are already host paths.
 * @param mountedPrefix the host path that prefix became.
 * @param shortNameFallbackAllowed this model's OWN `fieldRefByShortNameAllowed`. For a provider it decides
 *     whether its bare names are bound before integration at all: with the flag on, `FieldFilled(Zip)`
 *     expands to `FieldFilled(/Order/ShipTo/Inner/Zip)` whatever the host declares, and with it off the name
 *     travels bare and is resolved afterwards like any host reference (measured 2026-09-12, varying the two
 *     models' flags independently). Meaningless for the entry model, whose own flag is
 *     [PreparedModel.fieldRefByShortNameAllowed].
 */
data class DeclarationOrigin(
    val modelId: String,
    val authoredPrefix: String = "",
    val mountedPrefix: String = "",
    val shortNameFallbackAllowed: Boolean = false,
) {
    /**
     * Whether this declaration ARRIVED through a mount rather than being written in the model being checked.
     *
     * It decides where the model-wide short-name fallback may look, and the two sides are measured. A
     * provider's own reference is bound INSIDE the provider before integration and emerges fully qualified —
     * `FieldFilled(Code)` in the provider expands to `FieldFilled(/Order/ShipTo/Inner/Code)` — so a host that
     * reuses the name cannot make it ambiguous. A reference written in the entry model is bound over the
     * whole EXPANDED tree, so a mounted field of the same name does make it ambiguous
     * (`MVK_FIELDNAME_NOT_UNIQUE`, measured 2026-09-12 through `checkConsistency` on the expanded model).
     */
    val isMounted: Boolean get() = authoredPrefix.isNotEmpty()

    /** [written] as it reads in the host tree. Any other spelling is returned unchanged. */
    fun rehost(written: String): String = when {
        authoredPrefix.isEmpty() -> written
        written == authoredPrefix -> mountedPrefix
        written.startsWith("$authoredPrefix/") -> mountedPrefix + written.removePrefix(authoredPrefix)
        else -> written
    }
}

/**
 * One declared element of a DocumentModel, as the model declares it.
 *
 * [path] is the element's canonical path — the join of the `name` segments from the root, which is the
 * Kernel's own `getFullName()` and the thing a condition references. It is deliberately NOT the element's
 * [id]: `id` is an **opaque identifier** in a disjoint namespace, equal to the path only in fixtures we
 * authored ourselves. Real models carry hash ids, and keying by `id` resolved ~0% of a foreign model's rule
 * references before `ModelIndex` was re-keyed by the name-path (KERNEL-INTEGRATION-NOTES, "`getId()` is an
 * *opaque identifier*, NOT the path").
 *
 * @param kind the `type` discriminator verbatim, matched case-sensitively wherever it is interpreted, since
 *     the Kernel's own reader switches on the exact spelling.
 * @param declaration the whole authored element node, retained unchanged — envelope, annotations and
 *     descriptions alike. Keeping the node rather than projecting per-kind fields is what lets an invalid
 *     declaration stay inspectable without inventing a parallel A12 model.
 * @param origin the model this declaration was authored in. For an element of a mounted provider that is not
 *     the model being checked, and its references must be read in the provider's terms.
 */
data class DeclaredElement(
    val path: String,
    val name: String,
    val kind: String,
    val id: String?,
    val declaration: JsonObject,
    val origin: DeclarationOrigin,
) {
    /** The model this group mounts, when it declares one — `Group.modelAlias`. */
    val modelAlias: String?
        get() = ((declaration[kind] as? JsonObject)?.get("modelAlias") as? JsonPrimitive)?.contentOrNull
}

/**
 * A mount the checker could not expand, and the reason.
 *
 * The fact base is then INCOMPLETE below that group: the declarations are real and live in a model this
 * analysis does not have, so reporting every reference into them as undeclared would reject a model A12
 * accepts. Each one becomes a coverage gap and suspends the reference families instead.
 */
data class UnexpandedMount(val at: String, val alias: String, val reason: String)

/**
 * An admitted source's declarations, in declaration order — the fact base every later phase reads.
 *
 * @param conditionLanguage the model's `content.modelConfig.conditionLanguage.code`, English when the model
 *     declares none. It decides which names in a condition are operator keywords and which are entity
 *     references, so no condition can be read without it.
 * @param unsupportedConditionLanguage the declared code when A12 does not support it — the model is then
 *     refused outright rather than read in some fallback language, so no condition may be analysed at all.
 * @param fieldRefByShortNameAllowed the model's `content.modelConfig.fieldRefByShortNameAllowed`. It decides
 *     whether a bare reference the declaring group does not have may resolve to a unique model-wide field
 *     short name, so reference resolution cannot be done without it.
 * @param declaredLocales the ENTRY model's `header.locales[].code`, in declaration order. It is the only set
 *     in play after expansion, for a mounted declaration as much as for a local one: A12 refuses to expand a
 *     provider that does not declare every locale of the entry model, so no mounted declaration can ever
 *     answer to a narrower set (measured 2026-09-12, `IncludedDeclarationLocaleScopeLawsTest`).
 * @param unexpandedMounts the mounts that left the fact base incomplete.
 * @param diagnostics what the checker could not read. Preparation reports and skips rather than failing, so
 *     one broken element cannot mask its siblings' findings — and no input makes [prepare] raise.
 */
data class PreparedModel(
    val elements: List<DeclaredElement>,
    val fieldRefByShortNameAllowed: Boolean,
    val conditionLanguage: ConditionLanguage = ConditionLanguage.DEFAULT,
    val unsupportedConditionLanguage: String? = null,
    val declaredLocales: List<String> = emptyList(),
    val unexpandedMounts: List<UnexpandedMount> = emptyList(),
    val diagnostics: List<Diagnostic> = emptyList(),
)

/**
 * The four element kinds the Kernel's `ElementDeserializer` switch admits; any other `type` is its
 * `IllegalArgumentException` on the whole source. Only [GROUP] carries children, which is why descent is
 * decided by the discriminator and not by whichever envelope happens to hold an `elements` array.
 */
private enum class DeclaredKind(val wire: String) {
    FIELD("Field"), GROUP("Group"), RULE("Rule"), COMPUTATION("Computation");

    companion object {
        /** Matched on the wire spelling, because the Kernel's switch is case-SENSITIVE: `"group"` hits its
         *  `default:` arm and makes the whole source unreadable, where `"Group"` is a group. */
        fun of(type: String): DeclaredKind? = entries.firstOrNull { it.wire == type }
    }
}

/**
 * Read [Admission.Admitted]'s retained tree into the declarations the checker works from, mounting any
 * include it can resolve in [providers] (keyed by each source's own `header.id`).
 *
 * Mounting appends the provider's root-group CHILDREN onto the mount group and discards the provider's root
 * group itself, so an included field lands at `/<mountGroupName>/<childName>` and the provider's own root
 * name never appears in a path ([KERNEL-FINDINGS KF3], source-verified against
 * `DocumentModelExpandService`). A provider is fully expanded before it is mounted, so a nested mount's
 * children arrive already placed. Nothing is rewritten in the retained tree: descent simply continues with
 * the mount's path as the parent, which is why each declaration is still the node its own author wrote.
 */
fun prepare(
    admitted: Admission.Admitted,
    providers: Map<String, Admission.Admitted> = emptyMap(),
): PreparedModel {
    val content = (admitted.parsed as? JsonObject)?.get("content") as? JsonObject
    val declaredCode = content.conditionLanguageCode()?.takeIf { it.isNotBlank() }
    val declaredLanguage = declaredCode?.let { ConditionLanguage.of(it) }
    val entry = Scope(
        header = (admitted.parsed as? JsonObject)?.get("header") as? JsonObject,
        origin = DeclarationOrigin(admitted.modelId),
        visiting = setOf(admitted.modelId),
        diagnosticLabel = admitted.source.diagnosticLabel,
    )
    val collector = Collector(providers, entry.header.declaredLocales().toSet())
    val root = (content?.get("modelRoot") as? JsonObject)?.get("rootGroups")
    collector.each(root, at = null, what = "content.modelRoot.rootGroups", scope = entry) {
        collector.collect(it, "", null, entry)
    }
    return PreparedModel(
        elements = collector.elements,
        fieldRefByShortNameAllowed = collector.flag(content, entry),
        conditionLanguage = declaredLanguage ?: ConditionLanguage.DEFAULT,
        unsupportedConditionLanguage = declaredCode.takeIf { it != null && declaredLanguage == null },
        declaredLocales = entry.header.declaredLocales(),
        unexpandedMounts = collector.unexpandedMounts,
        diagnostics = collector.diagnostics,
    )
}

/**
 * The model currently being descended: its own header (an alias resolves against the model that WROTE it,
 * not against the entry model), the origin every element it declares carries, and the include chain so far.
 */
private data class Scope(
    val header: JsonObject?,
    val origin: DeclarationOrigin,
    val visiting: Set<String>,
    /**
     * The label of the SOURCE these declarations came from. A workspace check reads several files, so a
     * diagnostic naming only the entry model would send a reader to the wrong one — which is exactly the
     * failure the label exists to prevent.
     */
    val diagnosticLabel: String?,
)

private class Collector(
    private val providers: Map<String, Admission.Admitted>,
    /** The ENTRY model's declared locales — every provider mounted anywhere must cover this same set. */
    private val entryLocales: Set<String>,
) {
    val elements = mutableListOf<DeclaredElement>()
    val diagnostics = mutableListOf<Diagnostic>()
    val unexpandedMounts = mutableListOf<UnexpandedMount>()

    /**
     * @param parentPath the enclosing element's canonical path, which this element's `name` extends.
     * @param enclosing the nearest path that could be derived, used to locate a declaration too broken to
     *     name itself.
     */
    fun collect(node: JsonElement, parentPath: String, enclosing: String?, scope: Scope) {
        val element = node as? JsonObject
            ?: return malformed(enclosing, "a declaration must be a JSON object", scope)
        val kind = element.text("type")
        val name = element.text("name")
        // `type` names the envelope and `name` places the subtree, so a declaration missing either cannot be
        // descended into. `id` is not in that set: it identifies the element but no longer locates it.
        if (kind == null || name == null) {
            return malformed(enclosing, "a declaration must carry both `type` and `name`", scope)
        }
        val path = "$parentPath/$name"
        val id = element.text("id")
        if (id == null) {
            malformed(path, "a declaration must carry an `id`", scope)
        }
        elements += DeclaredElement(path, name, kind, id, element, scope.origin)
        // The envelope is optional — the Kernel default-constructs the element when it is absent — but a
        // present one must be an object, since the Kernel binds it to the kind's type.
        val envelope = element[kind]
        if (envelope != null && envelope !is JsonObject) {
            return malformed(path, "`$kind` must be a JSON object", scope)
        }
        when (DeclaredKind.of(kind)) {
            DeclaredKind.GROUP -> descendGroup(envelope as? JsonObject, path, scope)
            // An unrecognized `type` makes the whole source unreadable to the Kernel, so it is reported here
            // rather than passing as an inert declaration this build merely does not handle.
            null -> malformed(path, "`$kind` is not an A12 element type", scope)
            DeclaredKind.FIELD, DeclaredKind.RULE, DeclaredKind.COMPUTATION -> Unit
        }
    }

    /**
     * A group either mounts another model or holds its own elements — never both.
     *
     * The test is the Kernel's own idempotency guard: an alias with elements already beside it is an
     * ALREADY-EXPANDED mount, and its local elements win while the reference is silently ignored (KF3's
     * malformed-input matrix). So a group with elements is descended, whatever its alias says.
     */
    private fun descendGroup(body: JsonObject?, path: String, scope: Scope) {
        val elements = body?.get("elements")
        val alias = (body?.get("modelAlias") as? JsonPrimitive)?.contentOrNull
        if (!alias.isNullOrEmpty() && (elements == null || (elements as? JsonArray).isNullOrEmpty())) {
            return mount(alias, path, body, scope)
        }
        each(elements, path, "$path's `elements`", scope) { collect(it, path, path, scope) }
    }

    /** Expand the model [alias] names into the mount group at [path], or state why it stayed unexpanded. */
    private fun mount(alias: String, path: String, body: JsonObject, scope: Scope) {
        // Everything the mount can carry BESIDES the plain SINGLE_RG case changes which declarations survive
        // or where they land, so an unimplemented one suspends the analysis rather than producing a tree that
        // is confidently wrong. Each is a named landing rather than an open question (KF3 has the semantics).
        unsupportedMountOption(body)?.let { return unexpanded(path, alias, it) }
        val reference = scope.header.referenceForAlias(alias)
            ?: return unexpanded(path, alias, "no `header.modelReferences` entry declares this alias")
        if (reference in scope.visiting) {
            return unexpanded(path, alias, "`$reference` is already being included, so the mount is a cycle")
        }
        val provider = providers[reference]
            ?: return unexpanded(path, alias, "no source with `header.id` `$reference` was supplied")
        val providerHeader = (provider.parsed as? JsonObject)?.get("header") as? JsonObject
        // A12 refuses to expand a provider that does not declare every locale of the entry model — a
        // DocumentModelExpansionException raised before any declaration is read, so the whole model is
        // unusable rather than this one mount (measured 2026-09-12,
        // `IncludedDeclarationLocaleScopeLawsTest`). Stated as a gap for the same reason as an unreadable
        // `includeLevel` above: reaching CK_MODEL_NOT_BUILDABLE from the mount path would need it to carry
        // findings as well as gaps, and this UNDER-reports deliberately rather than mounting a tree A12
        // never builds.
        val uncovered = entryLocales - providerHeader.declaredLocales().toSet()
        if (uncovered.isNotEmpty()) {
            return unexpanded(path, alias, "`$reference` does not declare the entry model's " +
                "${uncovered.joinToString(", ")}, so A12 refuses to expand this include at all and " +
                "nothing here is a verdict about the model")
        }
        val providerContent = (provider.parsed as? JsonObject)?.get("content") as? JsonObject
        val roots = ((providerContent?.get("modelRoot") as? JsonObject)?.get("rootGroups") as? JsonArray)
            ?.filterIsInstance<JsonObject>()
            .orEmpty()
        // SINGLE_RG requires exactly one root group in the provider, and the Kernel refuses zero or several
        // rather than choosing (KF3). Choosing here would mount a tree A12 would not have built.
        val root = roots.singleOrNull()
            ?: return unexpanded(path, alias, "a SINGLE_RG include needs exactly one root group in " +
                "`$reference`, which declares ${roots.size}")
        val rootName = root.text("name")
            ?: return unexpanded(path, alias, "the root group of `$reference` carries no `name`")
        val nested = Scope(
            header = (provider.parsed as? JsonObject)?.get("header") as? JsonObject,
            // The provider's root group DISAPPEARS into the mount, so its own absolute paths — which all
            // begin with that root — now begin with the mount's path instead.
            origin = DeclarationOrigin(
                modelId = reference,
                authoredPrefix = "/$rootName",
                mountedPrefix = path,
                shortNameFallbackAllowed = providerContent.declaresShortNameFallback(),
            ),
            visiting = scope.visiting + reference,
            diagnosticLabel = provider.source.diagnosticLabel,
        )
        // The provider is FULLY EXPANDED before it is mounted (KF3), so its root group gets the same
        // treatment as any other: when it is itself an unexpanded mount, that include is expanded into this
        // same path. Reading its `elements` directly instead would find the empty array a mount group carries
        // and mount nothing at all — silently, since the inner alias is never visited and so never reported
        // (measured against the Kernel by the `single-provider-root-include` case of the shared matrix).
        descendGroup(root["Group"] as? JsonObject, path, nested)
    }

    /**
     * Why this mount is beyond the implemented include semantics, or null when it is the plain case.
     *
     * Each of these is measured Kernel behaviour ([KERNEL-FINDINGS KF3]) that this slice does not yet
     * reproduce — not an unknown. Reading them as absent would place declarations A12 drops, or place them
     * one level off, so the analysis is suspended instead.
     */
    private fun unsupportedMountOption(body: JsonObject): String? = when {
        (body["includeLevel"] as? JsonPrimitive)?.contentOrNull == "MODEL_ROOT" ->
            "a MODEL_ROOT include mounts every top-level group of the provider and is not expanded here"
        // `includeLevel` is a strict two-valued enum: any other spelling — `model_root`, a number, a blank —
        // makes the model UNREADABLE ("No enum constant …IncludeLevel.x", measured 2026-09-12), so A12 never
        // judges it. Expanding it as the default would invent a verdict for a source the Kernel refuses.
        // This states it as a gap rather than as the rejection A12 issues, which under-reports deliberately:
        // the checker owns no code for an unbuildable model beyond `CK_MODEL_NOT_BUILDABLE`, and reaching
        // that from here would need the mount path to carry findings as well as gaps.
        body.declaresUnreadableIncludeLevel() ->
            "`includeLevel` carries a spelling A12's own reader refuses, so the model does not load at all " +
                "and nothing here is a verdict about it"
        body.flagIsOn("excludeRules") ->
            "`excludeRules` drops the provider's rules before mounting and is not applied here"
        body.flagIsOn("excludeComputations") ->
            "`excludeComputations` drops the provider's computations before mounting and is not applied here"
        else -> null
    }

    private fun unexpanded(path: String, alias: String, reason: String) {
        unexpandedMounts += UnexpandedMount(path, alias, reason)
    }

    /** Iterate a member that must be an array, diagnosing rather than raising when it is not. */
    fun each(value: JsonElement?, at: String?, what: String, scope: Scope, body: (JsonElement) -> Unit) {
        when (value) {
            null -> Unit
            is JsonArray -> value.forEach(body)
            else -> malformed(at, "$what must be a JSON array", scope)
        }
    }

    /**
     * `fieldRefByShortNameAllowed`, absent or unreadable meaning off — never a raised conversion.
     *
     * The QUOTED form is accepted, because the Kernel accepts it: a model declaring `"true"` enables the
     * model-wide short-name tier (measured, `rule check`). Refusing it here would flip every short-name
     * verdict on a model A12 reads — the read-path half of the over-strictness rule, and the same coercion
     * [text] applies to `type`/`id`/`name`.
     */
    fun flag(content: JsonObject?, entry: Scope): Boolean {
        val declared = (content?.get("modelConfig") as? JsonObject)?.get("fieldRefByShortNameAllowed")
            ?: return false
        return when ((declared as? JsonPrimitive)?.contentOrNull) {
            "true" -> true
            "false" -> false
            else -> {
                malformed(null, "`fieldRefByShortNameAllowed` must be a boolean, or a string spelling one", entry)
                false
            }
        }
    }


    private fun malformed(at: String?, explain: String, scope: Scope) {
        // The identity is the SOURCE the declaration was written in, not the model being checked. A mounted
        // provider's broken element is the provider's, and attributing it to the entry model sends a reader
        // to a file that does not contain it — which is the whole reason the label exists.
        diagnostics += Diagnostic(
            DiagnosticCode.DECLARATION_NOT_WELL_FORMED,
            scope.origin.modelId,
            explain,
            at,
            scope.diagnosticLabel,
        )
    }
}

private fun JsonArray?.isNullOrEmpty(): Boolean = this == null || this.isEmpty()

/** The two spellings A12's `IncludeLevel` enum admits; absence means `SINGLE_RG`. */
private val INCLUDE_LEVELS = setOf("SINGLE_RG", "MODEL_ROOT")

private fun JsonObject.declaresUnreadableIncludeLevel(): Boolean {
    val declared = this["includeLevel"] ?: return false
    return (declared as? JsonPrimitive)?.contentOrNull !in INCLUDE_LEVELS
}

/**
 * A PROVIDER's own `fieldRefByShortNameAllowed`, read without diagnosing: an unreadable value in a model that
 * is only being mounted is the entry model's problem to report if it is ever checked itself, and treating it
 * as off matches [Collector.flag]'s own fallback.
 */
private fun JsonObject?.declaresShortNameFallback(): Boolean =
    ((this?.get("modelConfig") as? JsonObject)?.get("fieldRefByShortNameAllowed") as? JsonPrimitive)
        ?.contentOrNull == "true"

/**
 * The locale codes a header declares, matched as authored. A12 compares a message's locale against this set
 * by exact string — a bare `en` is a different code from `en_US` and is not a supported validation locale at
 * all (KERNEL-FINDINGS §13) — so normalizing here would resolve a coverage gap the Kernel reports.
 */
private fun JsonObject?.declaredLocales(): List<String> =
    (this?.get("locales") as? JsonArray)
        ?.filterIsInstance<JsonObject>()
        ?.mapNotNull { it.text("code")?.takeIf(String::isNotBlank) }
        .orEmpty()

/**
 * The model reference an alias names. `modelAlias` is only a header lookup key — never a tree node — and the
 * header it is looked up in is the one belonging to the model that WROTE the mount, which is what makes a
 * nested include resolve against its own provider's references rather than the entry model's (KF3).
 */
private fun JsonObject?.referenceForAlias(alias: String): String? =
    (this?.get("modelReferences") as? JsonArray)
        ?.filterIsInstance<JsonObject>()
        ?.firstOrNull { it.text("alias") == alias }
        ?.text("reference")
        ?.takeIf { it.isNotBlank() }

/**
 * Whether a mount flag is ON. Only the JSON BOOLEAN `true` counts: the Kernel reads every non-Boolean
 * `excludeRules`/`excludeComputations` as FALSE and lets the content survive (KF3's coercion matrix, and
 * re-measured 2026-09-12 over `true`, `"true"`, `"yes"`, `1` and `{}` — only the boolean drops the rule), so
 * a malformed value must not suspend an include that A12 would expand in full. The quoted `"true"` is the one
 * that looks harmless and is not, which is why `isString` decides rather than the text.
 *
 * Deliberately NOT the coercion [Collector.flag] applies: `fieldRefByShortNameAllowed` DOES accept its quoted
 * spelling (measured separately). Two members, two Jackson bindings, and assuming one rule for both is how
 * this was wrong in the first place.
 */
private fun JsonObject.flagIsOn(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true

/**
 * A member read the way the Kernel reads it: `ElementDeserializer` takes `type`, `id` and `name` through
 * Jackson's `asText()`, which COERCES a non-string scalar rather than refusing it, so `{"id": 5}` is an
 * element the Kernel reads with id `"5"`. Refusing it here would drop a declaration A12 accepts — the read
 * path is where an over-strict check makes a legal model unreadable rather than merely unauthorable.
 *
 * JSON `null` is treated as absent instead: its Jackson coercion is not measured here, and reporting the
 * member as missing is the conservative arm, since that is a finding rather than a silent substitution.
 */
private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

/**
 * The declared condition language code, or `null` when the model does not state one — read leniently on
 * purpose, because an unreadable code is not a reason to refuse the model when A12 itself defaults.
 */
private fun JsonObject?.conditionLanguageCode(): String? =
    ((this?.get("modelConfig") as? JsonObject)?.get("conditionLanguage") as? JsonObject)
        ?.get("code")?.let { (it as? JsonPrimitive)?.contentOrNull }
