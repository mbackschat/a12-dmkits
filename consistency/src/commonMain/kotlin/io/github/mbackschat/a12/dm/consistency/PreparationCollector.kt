package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The model currently being descended: its own header (an alias resolves against the model that WROTE it,
 * not against the entry model), the origin every element it declares carries, and the include chain so far.
 */
internal data class PreparationScope(
    val header: JsonObject?,
    val origin: DeclarationOrigin,
    val visiting: Set<String>,
    /**
     * The label of the SOURCE these declarations came from. A workspace check reads several files, so a
     * diagnostic naming only the entry model would send a reader to the wrong one — which is exactly the
     * failure the label exists to prevent.
     */
    val diagnosticLabel: String?,
    /** The mount exclusions in force here — [MountExclusions.NONE] in the entry model, which mounts nothing. */
    val exclusions: MountExclusions = MountExclusions.NONE,
    /** Whether these are the entry model's own declarations rather than a mounted provider's. */
    val isEntry: Boolean = false,
)

/**
 * An entry declaration's `name` written as an object or an array, which A12 reads as the EMPTY name: the build
 * fails where the declaration is kept, and a filter that removes it leaves the empty-name sentence alone
 * ([KERNEL-FINDINGS KF644], measured on fields, rules and computations). Only the entry is measured, so a mounted
 * provider's declaration stays with the not-well-formed diagnostic.
 */
private fun PreparationScope.entryNameRead(member: JsonElement?): String? =
    "".takeIf { isEntry && (member is JsonObject || member is JsonArray) }

/**
 * The executables an enclosing mount drops, as they reach the declaration being collected.
 *
 * A12 applies `excludeRules`/`excludeComputations` to the mount's FULLY EXPANDED provider, so they
 * ACCUMULATE down an include chain: a nested mount's content answers to its own flags and to every enclosing
 * mount's ([KERNEL-FINDINGS KF3]). Filtering an expanded tree and declining to collect the declaration in
 * the first place are the same filter one phase apart, and the second needs no second tree.
 *
 * An excluded declaration is ABSENT rather than silent, which is the distinction that matters here: A12 drops
 * it before it checks the model, so its defects are not A12's verdicts and reporting them would be this
 * checker speaking alone.
 */
internal data class MountExclusions(val rules: Boolean, val computations: Boolean) {

    fun excludes(kind: DeclaredKind): Boolean = when (kind) {
        DeclaredKind.RULE -> rules
        DeclaredKind.COMPUTATION -> computations
        DeclaredKind.FIELD, DeclaredKind.GROUP -> false
    }

    /** These, plus whatever the mount at [body] declares. */
    fun and(body: JsonObject) = MountExclusions(
        rules = rules || body.flagIsOn("excludeRules"),
        computations = computations || body.flagIsOn("excludeComputations"),
    )

    companion object {
        val NONE = MountExclusions(rules = false, computations = false)
    }
}

internal class Collector(
    private val providers: Map<String, Admission.Admitted>,
    /** The ENTRY model's declared locales — every provider mounted anywhere must cover this same set. */
    private val entryLocales: Set<String>,
) {
    val elements = mutableListOf<DeclaredElement>()
    val diagnostics = mutableListOf<Diagnostic>()
    val unexpandedMounts = mutableListOf<UnexpandedMount>()

    /** The entry's declarations A12's reader refuses, one sentence each ([PreparedModel.reachedModelRefusals]). */
    val readerRefusals = mutableListOf<String>()

    /**
     * @param parentPath the enclosing element's canonical path, which this element's `name` extends.
     * @param enclosing the nearest path that could be derived, used to locate a declaration too broken to
     *     name itself.
     */
    fun collect(node: JsonElement, parentPath: String, enclosing: String?, scope: PreparationScope) {
        val element = node as? JsonObject
            ?: return malformed(enclosing, "a declaration must be a JSON object", scope)
        val kind = element.scalarText("type")
        val name = element.scalarText("name") ?: scope.entryNameRead(element["name"])
        // `type` names the envelope and `name` places the subtree, so a declaration missing either cannot be
        // descended into. `id` is not in that set: it identifies the element but no longer locates it.
        if (kind == null || name == null) {
            // An ABSENT name is refused by A12's reader, so no model loads (KF644); a `null` one it admits.
            if (scope.isEntry && kind != null && "name" !in element) {
                readerRefusals += "A declaration under `${enclosing ?: "the model root"}` has no `name`, which A12's reader " +
                    "refuses, so the model never loads"
            }
            return malformed(enclosing, "a declaration must carry both `type` and `name`", scope)
        }
        val path = "$parentPath/$name"
        val id = element.scalarText("id")
        if (id == null) {
            malformed(path, "a declaration must carry an `id`", scope)
        }
        val declared = DeclaredKind.of(kind)
        // An enclosing mount's exclusion drops this declaration from the model A12 checks, so it is not
        // collected at all.
        val excluded = declared != null && scope.exclusions.excludes(declared)
        // The envelope is optional — the Kernel default-constructs the element when it is absent — but a
        // present one must be an object, since the Kernel binds it to the kind's type. Diagnosed BEFORE the
        // exclusion, for the reason the `id` check above states: A12's reader binds every declaration before
        // expansion drops any of them, so either unreadable member makes the source unreadable whether or
        // not this mount keeps the declaration.
        val envelope = element[kind]
        val unreadableEnvelope = envelope != null && envelope !is JsonObject
        if (unreadableEnvelope) {
            malformed(path, "`$kind` must be a JSON object", scope)
        }
        if (scope.isEntry) {
            readerRefusals += element.declarationTextRefusals(kind).map { "In `$path`, $it, so A12's reader refuses the source and the model never loads" }
        }
        if (excluded) {
            return
        }
        elements += DeclaredElement(path, name, kind, id, element, scope.origin)
        if (unreadableEnvelope) {
            return
        }
        when (declared) {
            DeclaredKind.GROUP -> descendGroup(envelope, path, scope)
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
     *
     * An `attachment`-usage group SHIELDS its subtree from every exclusion applied from above it (KF3), which
     * is why the shield is read HERE — from the body being descended — rather than from the mount group that
     * named the provider. That placement is what reproduces the two halves A12 measures: a `SINGLE_RG`
     * mount's slot is REPLACED by its provider root's, so an `attachment` marker on the mount is cleared by a
     * provider that declares none, while the provider root's own marker shields the subtree that landed on
     * the mount's path.
     */
    private fun descendGroup(body: JsonObject?, path: String, scope: PreparationScope) {
        val elements = body?.get("elements")
        val alias = (body?.get("modelAlias") as? JsonPrimitive)?.contentOrNull
        if (alias != null && (elements == null || (elements as? JsonArray).isNullOrEmpty())) {
            // A12 refuses an empty `modelAlias` on a mount ("cannot be found", KF593), so it is a mount A12 never
            // expands rather than an ordinary empty group.
            if (alias.isEmpty()) return unexpanded(path, alias, "the `modelAlias` is empty, so A12 cannot find the included model")
            return mount(alias, path, body, scope)
        }
        val inner = if (body?.scalarText("usageType") == ATTACHMENT_USAGE) {
            scope.copy(exclusions = MountExclusions.NONE)
        } else {
            scope
        }
        each(elements, path, "$path's `elements`", inner) { collect(it, path, path, inner) }
    }

    /** Expand the model [alias] names into the mount group at [path], or state why it stayed unexpanded. */
    private fun mount(alias: String, path: String, body: JsonObject, scope: PreparationScope) {
        // `includeLevel` is a strict two-valued enum: any other spelling — `model_root`, a number, a blank —
        // makes the model UNREADABLE ("No enum constant …IncludeLevel.x", measured 2026-09-12), so A12 never
        // judges it, and expanding it as the default would invent a verdict for a source the Kernel refuses.
        // This states it as a gap rather than as the rejection A12 issues, which under-reports deliberately:
        // the checker owns no code for an unbuildable model beyond `CK_MODEL_NOT_BUILDABLE`, and reaching
        // that from here would need the mount path to carry findings as well as gaps.
        val level = IncludeLevel.declaredIn(body) ?: return unexpanded(
            path, alias,
            "`includeLevel` carries a spelling A12's own reader refuses, so the model does not load at all " +
                "and nothing here is a verdict about it",
        )
        val entry = scope.header.entryForAlias(alias)
        if (entry?.scalarText("purpose") == TYPE_DEFINITIONS_PURPOSE) {
            return unexpanded(path, alias, "the `header.modelReferences` entry for this alias has purpose " +
                "`typeDefinitions`, and A12 refuses to expand a mount through one")
        }
        val reference = entry?.scalarText("reference")
            // StringUtils.isBlank, so a no-break-space reference names a model (measured: WorkspaceBlankTextDiffTest).
            ?.takeUnless { it.isA12Blank() }
            ?: return unexpanded(path, alias, "no `header.modelReferences` entry declares this alias with a non-blank " +
                "`reference`, so A12 cannot find the included model")
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
        val providerLocales = providerHeader.declaredLocales()
        if (providerLocales.unreadable.isNotEmpty()) {
            return unexpanded(path, alias, "`$reference` declares the locale code " +
                "${providerLocales.unreadable.joinToString(", ")}, which A12 cannot use, so that source " +
                "never loads and nothing here is a verdict about the model")
        }
        val uncovered = entryLocales - providerLocales.keys.toSet()
        if (uncovered.isNotEmpty()) {
            return unexpanded(path, alias, "`$reference` does not declare the entry model's " +
                "${uncovered.joinToString(", ")}, so A12 refuses to expand this include at all and " +
                "nothing here is a verdict about the model")
        }
        val providerContent = (provider.parsed as? JsonObject)?.get("content") as? JsonObject
        val declaredRoots = (providerContent?.get("modelRoot") as? JsonObject)?.get("rootGroups") as? JsonArray
        val roots = declaredRoots?.filterIsInstance<JsonObject>().orEmpty()
        // A non-object root element is a measured refusal of A12's own expansion (KF3), so it may not be
        // filtered out and mounted around: at MODEL_ROOT that silently placed the remaining roots and at
        // SINGLE_RG it silently satisfied the single-root count.
        if (roots.size != declaredRoots?.size ?: 0) {
            return unexpanded(path, alias, "a root element of `$reference` is not a JSON object, which " +
                "A12's own expansion refuses, so nothing here is a verdict about the model")
        }
        val target = MountTarget(
            reference = reference,
            header = (provider.parsed as? JsonObject)?.get("header") as? JsonObject,
            shortNameFallbackAllowed = providerContent.declaresShortNameFallback(),
            diagnosticLabel = provider.source.diagnosticLabel,
            visiting = scope.visiting + reference,
            // This mount's own exclusions on top of whatever it was mounted under. The mount group's own
            // `usageType` is deliberately not consulted: A12 filters the mounted CHILDREN, so the shield is
            // always some descended group's, never the mount's (KF3).
            exclusions = scope.exclusions.and(body),
        )
        when (level) {
            IncludeLevel.SINGLE_RG -> mountSingleRoot(alias, path, roots, target)
            IncludeLevel.MODEL_ROOT -> mountModelRoots(alias, path, roots, target)
        }
    }

    /** The default level: the provider's sole root group is REPLACED by the mount group (KF3). */
    private fun mountSingleRoot(alias: String, path: String, roots: List<JsonObject>, target: MountTarget) {
        // SINGLE_RG requires exactly one root group in the provider, and the Kernel refuses zero or several
        // rather than choosing (KF3). Choosing here would mount a tree A12 would not have built.
        val root = roots.singleOrNull()
            ?: return unexpanded(path, alias, "a SINGLE_RG include needs exactly one root group in " +
                "`${target.reference}`, which declares ${roots.size}")
        unmountableRoot(root, target.reference)?.let { return unexpanded(path, alias, it) }
        val rootName = root.scalarText("name")
            ?: return unexpanded(path, alias, "the root group of `${target.reference}` carries no `name`")
        // SINGLE_RG additionally demands a NON-REPEATABLE root, which this level cannot express any other
        // way: the provider root becomes the mount group, and the mount's own repeatability is the host's.
        (root["Group"] as? JsonObject).singleRootRepeatabilityRefusal()?.let { refusal ->
            return unexpanded(path, alias, "the root group of `${target.reference}` $refusal, so nothing " +
                "here is a verdict about the model")
        }
        // The provider's root group DISAPPEARS into the mount, so its own absolute paths — which all begin
        // with that root — now begin with the mount's path instead.
        val nested = target.scope(mapOf("/$rootName" to path))
        // The provider is FULLY EXPANDED before it is mounted (KF3), so its root group gets the same
        // treatment as any other: when it is itself an unexpanded mount, that include is expanded into this
        // same path. Reading its `elements` directly instead would find the empty array a mount group carries
        // and mount nothing at all — silently, since the inner alias is never visited and so never reported
        // (measured against the Kernel by the `single-provider-root-include` case of the shared matrix).
        descendGroup(root["Group"] as? JsonObject, path, nested)
    }

    /**
     * `MODEL_ROOT`: every top-level group of the provider is mounted as a subgroup of the mount and KEEPS its
     * own name, so no root disappears and the provider's absolute paths simply move one level down (KF3,
     * placement locked by the `model-root-multiple-groups` and `reference-family-model-root` cases of the
     * shared matrix).
     *
     * A nameless root suspends the WHOLE mount rather than being skipped. A12 refuses such a provider
     * outright, so placing its siblings would mount a tree the Kernel never builds — and dropping one root
     * silently is exactly the confidently-wrong tree this module refuses to produce.
     */
    private fun mountModelRoots(alias: String, path: String, roots: List<JsonObject>, target: MountTarget) {
        // Whether A12 admits a provider with no root group at all is not measured; mounting nothing is the
        // one reading this module refuses either way, because it leaves no facts AND no gap saying so.
        if (roots.isEmpty()) {
            return unexpanded(path, alias, "a MODEL_ROOT include has nothing to mount from " +
                "`${target.reference}`, which declares no root group")
        }
        roots.forEach { root -> unmountableRoot(root, target.reference)?.let { return unexpanded(path, alias, it) } }
        val names = roots.map { root ->
            root.scalarText("name")
                ?: return unexpanded(path, alias, "a root group of `${target.reference}` carries no `name`")
        }
        val nested = target.scope(names.associate { name -> "/$name" to "$path/$name" })
        // Each root is collected as an ordinary declaration, which places it at `$path/$name` and descends —
        // including into a root that is itself an unexpanded mount, for the reason the SINGLE_RG arm states.
        roots.forEach { root -> collect(root, path, path, nested) }
    }

    /**
     * The provider a mount resolved to, and the include-chain state its declarations inherit.
     *
     * It exists so each include level builds its [PreparationScope] from one place: the two levels differ ONLY in which
     * prefix substitutions the mount applies, and handing the other four members to each arm separately is
     * where a divergence between them would hide.
     */
    private class MountTarget(
        val reference: String,
        private val header: JsonObject?,
        private val shortNameFallbackAllowed: Boolean,
        private val diagnosticLabel: String?,
        private val visiting: Set<String>,
        private val exclusions: MountExclusions,
    ) {
        fun scope(mountedPrefixes: Map<String, String>) = PreparationScope(
            header = header,
            origin = DeclarationOrigin(reference, mountedPrefixes, shortNameFallbackAllowed),
            visiting = visiting,
            diagnosticLabel = diagnosticLabel,
            exclusions = exclusions,
        )
    }

    private fun unexpanded(path: String, alias: String, reason: String) {
        unexpandedMounts += UnexpandedMount(path, alias, reason)
    }

    /** Iterate a member that must be an array, diagnosing rather than raising when it is not. */
    fun each(value: JsonElement?, at: String?, what: String, scope: PreparationScope, body: (JsonElement) -> Unit) {
        when (value) {
            null -> Unit
            is JsonArray -> value.forEach(body)
            else -> malformed(at, "$what must be a JSON array", scope)
        }
    }


    private fun malformed(at: String?, explain: String, scope: PreparationScope) {
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

/**
 * Why A12's own expansion refuses to mount this provider root, or null when it will mount it.
 *
 * Both shapes are measured refusals — a known non-Group root element, and a Group root without its object
 * body ([KERNEL-FINDINGS KF3], `adapter.WorkspaceIncludeStructureKernelTest`). Reading them as an empty
 * subtree instead is the quieter failure and the worse one: the mount contributes nothing, no gap says so,
 * and every reference into it becomes a finding against a model A12 never built.
 *
 * KF3 measures both on a `SINGLE_RG` mount. They are applied at `MODEL_ROOT` as well, where the direction is
 * what settles it: the two levels reach the same deserializer, so suspending costs coverage if A12 turns out
 * to admit such a root there and invents nothing if it does not.
 */
private fun unmountableRoot(root: JsonObject, reference: String): String? = when {
    root.scalarText("type") != DeclaredKind.GROUP.wire -> "the root element of `$reference` is " +
        "`${root.scalarText("type")}` rather than a Group, which A12's own expansion refuses, so nothing here is " +
        "a verdict about the model"
    root["Group"] !is JsonObject -> "the root group of `$reference` carries no `Group` body, so A12's " +
        "expansion has nothing to mount and refuses the model"
    else -> null
}

/**
 * Why A12 refuses this provider root's `repeatability` under `SINGLE_RG`, or null when it admits it.
 *
 * **Two refusals, not one, and two matrices rather than one rule.** A value A12's own READER cannot bind
 * makes the model never load; a bound value greater than one meets the non-repeatable test that `SINGLE_RG`
 * applies. Both suspend the mount here, and they are stated apart because a gap reading "is repeatable" about
 * `{}` sends its reader to the wrong question.
 *
 * The admitted sets are measured and are NOT the member's obvious reading. A NUMBER truncates toward zero, so
 * `1.9`, `1.5`, `1.1`, `0.5`, `0`, `-1`, `-1.5` are admitted while `2` and `2.1` are not ([KERNEL-FINDINGS
 * KF3]). A STRING binds as an INTEGER instead: `"1"`, `" 1 "`, `"01"`, `"0"`, `"-1"` and the empty string are
 * admitted, `"1.5"`, `"1f"`, `"NaN"` and `"one"` are reader refusals, and `"2"` reaches the repeatable test
 * (measured 2026-09-15, `adapter.WorkspaceIncludeStructureKernelTest`, after this function had generalized
 * the number rule to strings and so mounted providers A12 refuses).
 */
private fun JsonObject?.singleRootRepeatabilityRefusal(): String? {
    val declared = this?.get("repeatability") ?: return null
    val primitive = declared as? JsonPrimitive ?: return UNREADABLE_REPEATABILITY
    // The group reader's own binding (KF590, KF593); no value, a JSON or textual null included, is admitted.
    return when (val binding = primitive.bindJavaInt()) {
        is JavaIntBinding.Bound -> if (binding.value > 1) REPEATABLE_ROOT else null
        JavaIntBinding.NoValue -> null
        JavaIntBinding.Unbindable -> UNREADABLE_REPEATABILITY
    }
}

private const val UNREADABLE_REPEATABILITY =
    "carries a `repeatability` value A12's own reader refuses, so the model never loads at all"

private const val REPEATABLE_ROOT =
    "is repeatable, and a SINGLE_RG include needs a non-repeatable root, so A12 refuses the expansion"

/** The `usageType` whose group shields its subtree from an outer mount exclusion (KF3). */
private const val ATTACHMENT_USAGE = "attachment"

/** The two levels A12's `IncludeLevel` enum admits, each placing the provider's roots differently (KF3). */
private enum class IncludeLevel {
    SINGLE_RG, MODEL_ROOT;

    companion object {
        /**
         * The level [body] declares — an absent member is [SINGLE_RG], A12's own default — and null for any
         * other spelling, including a non-string one, which A12's reader refuses outright.
         */
        fun declaredIn(body: JsonObject): IncludeLevel? {
            val declared = body["includeLevel"] ?: return SINGLE_RG
            val spelling = (declared as? JsonPrimitive)?.contentOrNull
            return entries.firstOrNull { it.name == spelling }
        }
    }
}

/**
 * A PROVIDER's own `fieldRefByShortNameAllowed`, read without diagnosing: an unreadable value in a model that
 * is only being mounted is the entry model's problem to report if it is ever checked itself, and treating it
 * as off matches [Collector.flag]'s own fallback. The READING is the same one, which is the point — a
 * provider's flag decides whether its bare names are bound before integration, so a spelling read differently
 * here retargets references the entry model cannot see.
 */
private fun JsonObject?.declaresShortNameFallback(): Boolean =
    configFlag((this?.get("modelConfig") as? JsonObject)?.get("fieldRefByShortNameAllowed")) == ConfigFlag.ON

/**
 * What a header's `locales` declares: the locale [keys][localeKeyOrNull] it covers, and the codes it spells
 * in a way A12 cannot use.
 *
 * @param keys in declaration order and without repeats. KEYS rather than the authored text because A12
 *     compares parsed locales — `en-US` and `en_US` are one declared locale, and comparing text would report
 *     a coverage gap for a model A12 admits.
 * @param unreadable the codes that yielded no key, verbatim and in declaration order. Dropping them silently
 *     would analyse a model that never loads; each makes the model unusable and is reported as such.
 * @param repeated the keys declared more than once, in one spelling or two, each once.
 */
internal data class DeclaredLocales(val keys: List<String>, val unreadable: List<String>, val repeated: List<String>)

/**
 * Read a header's declared locales.
 *
 * Two members of the shape are measured rather than assumed ([KERNEL-FINDINGS KF290]). An entry may be an
 * OBJECT carrying `code` or a bare STRING — A12 reads both, and reading only the first leaves the declared
 * set empty, which silently switches off every per-locale demand. An entry of any other shape carries no
 * code at all and is skipped, which is what A12's own reader does with it.
 */
internal fun JsonObject?.declaredLocales(): DeclaredLocales {
    val keys = mutableListOf<String>()
    val unreadable = mutableListOf<String>()
    val repeated = linkedSetOf<String>()
    for (entry in (this?.get("locales") as? JsonArray).orEmpty()) {
        val code = when (entry) {
            is JsonObject -> entry.scalarText("code")
            is JsonPrimitive -> entry.contentOrNull?.takeIf { entry.isString }
            else -> null
        } ?: continue
        val key = localeKeyOrNull(code)
        when {
            key == null -> unreadable += code
            key in keys -> repeated += key
            else -> keys += key
        }
    }
    return DeclaredLocales(keys, unreadable, repeated.toList())
}

/**
 * The model reference entry an alias names. `modelAlias` is only a header lookup key — never a tree node — and the
 * header it is looked up in is the one belonging to the model that WROTE the mount, which is what makes a
 * nested include resolve against its own provider's references rather than the entry model's (KF3). The LAST entry
 * carrying the alias, whatever its purpose, is the one A12 keys it to (KF593).
 */
private fun JsonObject?.entryForAlias(alias: String): JsonObject? =
    (this?.get("modelReferences") as? JsonArray)
        ?.filterIsInstance<JsonObject>()
        ?.lastOrNull { it.scalarText("alias") == alias }

private const val TYPE_DEFINITIONS_PURPOSE = "typeDefinitions"

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
internal fun JsonObject.scalarText(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

/**
 * The declared condition language code, or `null` when the model does not state one — read leniently on
 * purpose, because an unreadable code is not a reason to refuse the model when A12 itself defaults.
 */
internal fun JsonObject?.conditionLanguageCode(): String? =
    ((this?.get("modelConfig") as? JsonObject)?.get("conditionLanguage") as? JsonObject)
        ?.get("code")?.let { (it as? JsonPrimitive)?.contentOrNull }
