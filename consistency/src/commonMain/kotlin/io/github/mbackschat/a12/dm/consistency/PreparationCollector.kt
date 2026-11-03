package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The model currently being descended: its own header, the origin every element it declares carries, and the
 * include chain so far.
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
    /** Whether these lie beneath an entry root group whose `name` is written as the empty text ([emptyRootRefusal]). */
    val beneathEmptyRoot: Boolean = false,
)

/**
 * The reference member through which kernel 31.1.1's reader refuses a declaration beneath an empty-named root group,
 * or null when it has none ([KERNEL-FINDINGS KF652]).
 *
 * The reader resolves every non-empty relative reference from the declaring element's full name, and under a root
 * named `""` that name starts with two slashes, which its path arithmetic refuses as an illegal relative path. The
 * references are a rule's error field, a computation's computed field and a group's index and sort fields; an empty
 * one is not resolved, so it refuses nothing. The root's own index and sort fields resolve from `/` and pass, and
 * so does every reference under an absent or `null` root name, which A12 joins into the path as the text `null`
 * (all measured: `adapter.laws.CheckerConverterSentenceAgreementTest`).
 */
private fun emptyRootRefusal(body: JsonObject?, kind: DeclaredKind?): String? {
    fun written(member: String) = member.takeIf { !body?.scalarText(it).isNullOrEmpty() }
    return when (kind) {
        DeclaredKind.RULE -> written("errorEntityRelPath")
        DeclaredKind.COMPUTATION -> written("computedFieldRelPath")
        DeclaredKind.GROUP -> written("indexFieldName")
            ?: "sortFields[].name".takeIf {
                (body?.get("sortFields") as? JsonArray)?.any { !(it as? JsonObject)?.scalarText("name").isNullOrEmpty() } == true
            }
        DeclaredKind.FIELD, null -> null
    }
}

/** Whether this declaration writes its `name` as the empty text, as opposed to omitting it or writing `null`. */
private fun JsonObject.writesEmptyName(): Boolean = (this["name"] as? JsonPrimitive)?.let { it.isString && it.content.isEmpty() } == true

/**
 * An entry declaration's `name` that is absent or `null`, which kernel 31.1.1's reader binds as no name: A12's
 * converter states that and leaves the declaration out, and its basic sentence reads the name as empty, so the
 * preparation reads the EMPTY name ([KERNEL-FINDINGS KF652], measured on fields, rules, computations and groups).
 * Only the entry is measured, so a mounted provider's declaration stays with the not-well-formed diagnostic.
 */
private fun PreparationScope.entryNameRead(member: JsonElement?): String? =
    "".takeIf { isEntry && (member == null || member is JsonNull) }

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

    /**
     * These, plus whatever the mount's [includeConfig] declares; null when A12's reader refuses a flag, which DM 29.4
     * binds through A12's shared boolean law ([KERNEL-FINDINGS KF651]).
     */
    fun and(includeConfig: JsonObject): MountExclusions? {
        val excludesRules = includeConfig.flag("excludeRules") ?: return null
        val excludesComputations = includeConfig.flag("excludeComputations") ?: return null
        return MountExclusions(rules = rules || excludesRules, computations = computations || excludesComputations)
    }

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
            // Kernel 31.1.1's reader cannot bind an object or an array as a name, so no model loads (KF652).
            val written = element["name"]
            if (scope.isEntry && kind != null && (written is JsonObject || written is JsonArray)) {
                readerRefusals += "A declaration under `${enclosing ?: "the model root"}` has a `name` that is not text, " +
                    "which A12's reader refuses, so the model never loads"
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
        // A present envelope must be an object, since the Kernel binds it to the kind's type; a Group's is
        // read as an empty group when it is missing or `null` (KF3). Diagnosed BEFORE the exclusion, for the
        // reason the `id` check above states: A12's reader binds every declaration before expansion drops any
        // of them, so either unreadable member makes the source unreadable whether or not this mount keeps the
        // declaration.
        val envelope = element[kind]
        val noValueGroupBody = declared == DeclaredKind.GROUP && (envelope == null || envelope is JsonNull)
        val unreadableEnvelope = envelope != null && envelope !is JsonObject && !noValueGroupBody
        if (unreadableEnvelope) {
            malformed(path, "`$kind` must be a JSON object", scope)
        }
        if (scope.isEntry) {
            readerRefusals += element.declarationTextRefusals(kind).map { "In `$path`, $it, so A12's reader refuses the source and the model never loads" }
        }
        if (scope.beneathEmptyRoot) {
            emptyRootRefusal(envelope as? JsonObject, declared)?.let { member ->
                readerRefusals += "`$path` resolves its `$member` from beneath a root group whose name is empty, which " +
                    "A12's reader cannot do, so the model never loads"
            }
        }
        if (excluded) {
            return
        }
        elements += DeclaredElement(path, name, kind, id, element, scope.origin)
        if (unreadableEnvelope) {
            return
        }
        when (declared) {
            DeclaredKind.GROUP -> descendGroup(envelope as? JsonObject, path,
                if (scope.isEntry && enclosing == null && element.writesEmptyName()) scope.copy(beneathEmptyRoot = true) else scope)
            // An unrecognized `type` makes the whole source unreadable to the Kernel, so it is reported here
            // rather than passing as an inert declaration this build merely does not handle.
            null -> malformed(path, "`$kind` is not an A12 element type", scope)
            DeclaredKind.FIELD, DeclaredKind.RULE, DeclaredKind.COMPUTATION -> Unit
        }
    }

    /**
     * A group either mounts another model or holds its own elements, and a mount wins: A12 31.1.1 replaces a
     * mount group's local elements with the included content ([KERNEL-FINDINGS KF651]). A DM 28 member (`modelAlias`
     * or a mount option beside `includeConfig`) is ignored by A12, which reads the group as plain, so it is read
     * as plain here too.
     *
     * An `attachment`-usage group SHIELDS its subtree from every exclusion applied from above it (KF3). On kernel
     * 31.1.1 a mount group keeps its OWN `usageType`, so its marker is read here, before the mount dispatches, and
     * shields its content from every enclosing mount's exclusions. The one group whose marker shields nothing above
     * it is a SINGLE_RG provider root ([discardedRoot]): it disappears into its mount, and its marker shields only
     * that mount's own exclusions, which [mountSingleRoot] has already dropped (measured by
     * `adapter.MountUsageTypeKernelTest`; 30.8.1 instead copied the root's slot onto the mount).
     */
    private fun descendGroup(body: JsonObject?, path: String, scope: PreparationScope, discardedRoot: Boolean = false) {
        val inner = if (!discardedRoot && body?.scalarText("usageType") == ATTACHMENT_USAGE) {
            scope.copy(exclusions = MountExclusions.NONE)
        } else {
            scope
        }
        when (val config = body?.get("includeConfig")) {
            null, is JsonNull -> Unit
            is JsonObject -> return mount(config, path, inner)
            else -> return unexpanded(path, null, "`includeConfig` is not an object, so A12 builds no model from " +
                "the source and nothing here is a verdict about it")
        }
        each(body?.get("elements"), path, "$path's `elements`", inner) { collect(it, path, path, inner) }
    }

    /**
     * Expand the model [config] names into the mount group at [path], or state why it stayed unexpanded.
     *
     * Each refusal of A12's own reader below is stated as a gap rather than as the rejection A12 issues, which
     * under-reports deliberately: the checker owns no code for an unbuildable model beyond
     * `CK_MODEL_NOT_BUILDABLE`, and reaching that from here would need the mount path to carry findings as well
     * as gaps.
     */
    private fun mount(config: JsonObject, path: String, scope: PreparationScope) {
        val declared = config["reference"]
        val reference = (declared as? JsonPrimitive)?.contentOrNull
        if (declared != null && reference == null) {
            return unexpanded(path, null, "`includeConfig.reference` is not a text, so A12 builds no model from " +
                "the source and nothing here is a verdict about it")
        }
        // StringUtils.isBlank, so a no-break-space reference names a model (KF651).
        if (reference == null || reference.isA12Blank()) {
            return unexpanded(path, reference, "`includeConfig.reference` names no model, so A12 refuses to " +
                "expand the mount")
        }
        val level = IncludeLevel.declaredIn(config) ?: return unexpanded(
            path, reference,
            "`includeLevel` carries a value A12's own reader refuses, so the model does not load at all " +
                "and nothing here is a verdict about it",
        )
        val exclusions = scope.exclusions.and(config) ?: return unexpanded(
            path, reference,
            "an exclusion flag carries a value A12's own reader refuses, so the model does not load at all " +
                "and nothing here is a verdict about it",
        )
        if (reference in scope.visiting) {
            return unexpanded(path, reference, "`$reference` is already being included, so the mount is a cycle")
        }
        val provider = providers[reference]
            ?: return unexpanded(path, reference, "no source with `header.id` `$reference` was supplied")
        val providerHeader = provider.header
        // A12 refuses to expand a provider that does not declare every locale of the entry model — a
        // DocumentModelExpansionException raised before any declaration is read, so the whole model is
        // unusable rather than this one mount (measured 2026-09-12,
        // `IncludedDeclarationLocaleScopeLawsTest`). Stated as a gap for the same reason as an unreadable
        // `includeLevel` above: reaching CK_MODEL_NOT_BUILDABLE from the mount path would need it to carry
        // findings as well as gaps, and this UNDER-reports deliberately rather than mounting a tree A12
        // never builds.
        val providerLocales = providerHeader.declaredLocales()
        if (providerLocales.unreadable.isNotEmpty()) {
            return unexpanded(path, reference, "`$reference` declares the locale code " +
                "${providerLocales.unreadable.joinToString(", ")}, which A12 cannot use, so that source " +
                "never loads and nothing here is a verdict about the model")
        }
        val uncovered = entryLocales - providerLocales.keys.toSet()
        if (uncovered.isNotEmpty()) {
            return unexpanded(path, reference, "`$reference` does not declare the entry model's " +
                "${uncovered.joinToString(", ")}, so A12 refuses to expand this include at all and " +
                "nothing here is a verdict about the model")
        }
        val providerContent = provider.content
        val declaredRoots = (providerContent?.get("modelRoot") as? JsonObject)?.get("rootGroups") as? JsonArray
        val roots = declaredRoots?.filterIsInstance<JsonObject>().orEmpty()
        // A non-object root element is a measured refusal of A12's own expansion (KF3), so it may not be
        // filtered out and mounted around: at MODEL_ROOT that silently placed the remaining roots and at
        // SINGLE_RG it silently satisfied the single-root count.
        if (roots.size != declaredRoots?.size ?: 0) {
            return unexpanded(path, reference, "a root element of `$reference` is not a JSON object, which " +
                "A12's own expansion refuses, so nothing here is a verdict about the model")
        }
        val target = MountTarget(
            reference = reference,
            header = providerHeader,
            // A provider's flag is read without diagnosing it: an unreadable value is the provider's own refusal
            // to report if it is ever checked itself, and the same reading matters because the flag decides
            // whether the provider's bare names bind before integration.
            shortNameFallbackAllowed = providerContent.fieldRefByShortNameAllowed(),
            diagnosticLabel = provider.source.diagnosticLabel,
            visiting = scope.visiting + reference,
            // This mount's own exclusions on top of whatever it was mounted under, kept apart from the outer ones
            // because the provider root's marker shields only the former (see [descendGroup]).
            outerExclusions = scope.exclusions,
            exclusions = exclusions,
        )
        when (level) {
            IncludeLevel.SINGLE_RG -> mountSingleRoot(path, roots, target)
            IncludeLevel.MODEL_ROOT -> mountModelRoots(path, roots, target)
        }
    }

    /** The default level: the provider's sole root group is REPLACED by the mount group (KF3). */
    private fun mountSingleRoot(path: String, roots: List<JsonObject>, target: MountTarget) {
        // SINGLE_RG requires exactly one root group in the provider, and the Kernel refuses zero or several
        // rather than choosing (KF3). Choosing here would mount a tree A12 would not have built.
        val root = roots.singleOrNull()
            ?: return unexpanded(path, target.reference, "a SINGLE_RG include needs exactly one root group in " +
                "`${target.reference}`, which declares ${roots.size}")
        unmountableRoot(root, target.reference)?.let { return unexpanded(path, target.reference, it) }
        val rootName = root.scalarText("name")
            ?: return unexpanded(path, target.reference, "the root group of `${target.reference}` carries no `name`")
        // SINGLE_RG additionally demands a NON-REPEATABLE root, which this level cannot express any other
        // way: the provider root becomes the mount group, and the mount's own repeatability is the host's.
        val rootBody = when (val body = root["Group"]) {
            null, is JsonNull -> null
            is JsonObject -> body
            // A reader refusal, stated as a gap for the reason [mount] gives ([KERNEL-FINDINGS KF3],
            // `adapter.ElementBodyReadingDiffTest`); a MODEL_ROOT root is a collected declaration, reported by its family.
            else -> return unexpanded(path, target.reference, "the root group of `${target.reference}` has a " +
                "`Group` body that is not an object, which A12's reader refuses, so nothing here is a verdict about the model")
        }
        rootBody.singleRootRepeatabilityRefusal()?.let { refusal ->
            return unexpanded(path, target.reference, "the root group of `${target.reference}` $refusal, so nothing " +
                "here is a verdict about the model")
        }
        // The discarded root's attachment marker shields this mount's own exclusions and nothing above them.
        val exclusions = if (rootBody?.scalarText("usageType") == ATTACHMENT_USAGE) target.outerExclusions else null
        // The provider's root group DISAPPEARS into the mount, so its own absolute paths — which all begin
        // with that root — now begin with the mount's path instead.
        val nested = target.scope(mapOf("/$rootName" to path), exclusions)
        // The provider is FULLY EXPANDED before it is mounted (KF3), so its root group gets the same
        // treatment as any other: when it is itself an unexpanded mount, that include is expanded into this
        // same path. Reading its `elements` directly instead would find the empty array a mount group carries
        // and mount nothing at all — silently, since the inner mount is never visited and so never reported
        // (measured against the Kernel by the `single-provider-root-include` case of the shared matrix).
        descendGroup(rootBody, path, nested, discardedRoot = true)
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
    private fun mountModelRoots(path: String, roots: List<JsonObject>, target: MountTarget) {
        // Kernel 31.1.1 builds and checks a provider with no root group here, and the mount holds nothing
        // ([KERNEL-FINDINGS KF3], `adapter.ElementBodyReadingDiffTest`), so there is nothing to collect.
        if (roots.isEmpty()) {
            return
        }
        roots.forEach { root -> unmountableRoot(root, target.reference)?.let { return unexpanded(path, target.reference, it) } }
        val names = roots.map { root ->
            root.scalarText("name")
                ?: return unexpanded(path, target.reference, "a root group of `${target.reference}` carries no `name`")
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
        /** What the enclosing mounts exclude, without this mount's own flags. */
        val outerExclusions: MountExclusions,
        private val exclusions: MountExclusions,
    ) {
        /** The scope this mount's declarations are collected in; [shieldedExclusions] replaces its exclusions. */
        fun scope(mountedPrefixes: Map<String, String>, shieldedExclusions: MountExclusions? = null) = PreparationScope(
            header = header,
            origin = DeclarationOrigin(reference, mountedPrefixes, shortNameFallbackAllowed),
            visiting = visiting,
            diagnosticLabel = diagnosticLabel,
            exclusions = shieldedExclusions ?: exclusions,
        )
    }

    private fun unexpanded(path: String, reference: String?, reason: String) {
        unexpandedMounts += UnexpandedMount(path, reference, reason)
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
 * A known non-Group root element is a measured refusal ([KERNEL-FINDINGS KF3], `adapter.WorkspaceIncludeStructureKernelTest`).
 * Reading it as an empty subtree instead is the quieter failure and the worse one: the mount contributes nothing,
 * no gap says so, and every reference into it becomes a finding against a model A12 never built. A root's `Group`
 * body is not judged here: a missing or `null` one is an empty group, and any other non-object one is a reader
 * refusal each include level states where it reads the body.
 */
private fun unmountableRoot(root: JsonObject, reference: String): String? =
    if (root.scalarText("type") != DeclaredKind.GROUP.wire) {
        "the root element of `$reference` is `${root.scalarText("type")}` rather than a Group, which A12's own " +
            "expansion refuses, so nothing here is a verdict about the model"
    } else {
        null
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
 * KF3]). A STRING binds as an INTEGER instead: `"1"`, `" 1 "`, `"01"`, `"0"` and `"-1"` are admitted, `"1.5"`,
 * `"1f"`, `"NaN"` and `"one"` are reader refusals, and `"2"` reaches the repeatable test (measured 2026-09-15,
 * `adapter.WorkspaceIncludeStructureKernelTest`, after this function had generalized the number rule to strings and
 * so mounted providers A12 refuses). The member is a primitive `int`, so on kernel 31 a JSON `null` and the empty
 * string are reader refusals too (KF593).
 */
private fun JsonObject?.singleRootRepeatabilityRefusal(): String? {
    val declared = this?.get("repeatability") ?: return null
    val primitive = declared as? JsonPrimitive ?: return UNREADABLE_REPEATABILITY
    // The group reader's own primitive binding (KF590, KF593): on kernel 31 a JSON or textual null is unbindable.
    val count = primitive.bindJavaPrimitiveInt() ?: return UNREADABLE_REPEATABILITY
    return if (count > 1) REPEATABLE_ROOT else null
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
         * The level [includeConfig] declares, bound as A12 binds its enum, and null where A12's reader refuses it
         * ([KERNEL-FINDINGS KF651]). An absent member is [SINGLE_RG]; a constant's name or its ordinal binds, as an
         * integer token or as the ordinal's digits, all after a Java `trim()`.
         */
        fun declaredIn(includeConfig: JsonObject): IncludeLevel? {
            val declared = includeConfig["includeLevel"] ?: return SINGLE_RG
            val primitive = declared as? JsonPrimitive ?: return null
            return when {
                primitive is JsonNull -> null
                primitive.isString -> primitive.content.trim { it <= ' ' }.let { text ->
                    entries.firstOrNull { it.name == text || it.ordinal.toString() == text }
                }
                else -> ordinalLiteral(primitive.content)?.let { ordinal -> entries.getOrNull(ordinal) }
            }
        }

        /** An integer token's value when it is a small ordinal; `-0` is 0. */
        private fun ordinalLiteral(literal: String): Int? {
            val negative = literal.startsWith("-")
            val digits = if (negative) literal.drop(1) else literal
            if (digits.isEmpty() || !digits.all { it in '0'..'9' }) {
                return null
            }
            val magnitude = digits.trimStart('0').ifEmpty { "0" }
            return magnitude.takeIf { it.length == 1 && (!negative || it == "0") }?.toInt()
        }
    }
}

/**
 * What a header's `locales` declares: the locale [keys][headerLocaleKeyOrNull] it covers, and the codes it spells
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
        val key = headerLocaleKeyOrNull(code)
        when {
            key == null -> unreadable += code
            key in keys -> repeated += key
            else -> keys += key
        }
    }
    return DeclaredLocales(keys, unreadable, repeated.toList())
}

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

/**
 * The declared condition language code, or `null` when the model does not state one. A12 binds the member as a
 * locale, so a bare text declares it as an object's `code` does: `"de_DE"` reads German conditions (KF650). A shape
 * its binding refuses is [frameDefects]' to report, and reads here as no code.
 */
internal fun JsonObject?.conditionLanguageCode(): String? =
    when (val declared = (this?.get("modelConfig") as? JsonObject)?.get("conditionLanguage")) {
        is JsonObject -> (declared["code"] as? JsonPrimitive)?.contentOrNull
        is JsonPrimitive -> declared.takeIf { it.isString }?.content
        else -> null
    }
