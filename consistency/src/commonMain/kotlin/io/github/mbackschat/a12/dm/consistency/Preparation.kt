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
 * the provider's own root groups — which no longer stand where they did in the host tree. [rehost] applies
 * the substitutions that fix that, and the Kernel does the same thing at expansion time rather than leaving
 * the reference to fail (`DocumentModelExpandService.RuleAndComputationConditionUpdaterVisitor` with
 * `EntityPathsRelativizer`, [KERNEL-FINDINGS KF3]). A relative downward reference needs no rehosting at all:
 * mounting preserves depth within the fragment, whether the provider's root group is replaced by the mount
 * group (`SINGLE_RG`) or placed beneath it (`MODEL_ROOT`).
 *
 * @param modelId the source that declares this element. It also scopes the model-wide short-name fallback:
 *     the Kernel binds a provider's unique cross-group short name BEFORE integration, so a name the host
 *     happens to reuse cannot retarget it.
 * @param mountedPrefixes each absolute prefix this model's own paths carried standalone, and the host prefix
 *     it became — EMPTY for the entry model, whose paths are already host paths. A `SINGLE_RG` mount
 *     contributes one entry, because the provider's single root disappears INTO the mount (`/Address` →
 *     `/Order/ShipTo`); a `MODEL_ROOT` mount contributes one per provider root group, each KEEPING its own
 *     name (`/Address` → `/Order/ShipTo/Address`), because that level adds the provider's roots as subgroups
 *     instead of replacing the mount with one of them (KF3). A path matching no entry is left as written:
 *     the Kernel rewrites a reference because its TARGET moved, and a provider path naming none of its own
 *     roots has no target that did — which is what lets a provider reference spelling a HOST path still bind
 *     to the host's element.
 * @param shortNameFallbackAllowed this model's OWN `fieldRefByShortNameAllowed`. For a provider it decides
 *     whether its bare names are bound before integration at all: with the flag on, `FieldFilled(Zip)`
 *     expands to `FieldFilled(/Order/ShipTo/Inner/Zip)` whatever the host declares, and with it off the name
 *     travels bare and is resolved afterwards like any host reference (measured 2026-09-12, varying the two
 *     models' flags independently). Meaningless for the entry model, whose own flag is
 *     [PreparedModel.fieldRefByShortNameAllowed].
 */
data class DeclarationOrigin(
    val modelId: String,
    val mountedPrefixes: Map<String, String> = emptyMap(),
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
    val isMounted: Boolean get() = mountedPrefixes.isNotEmpty()

    /**
     * [written] as it reads in the host tree. Any other spelling is returned unchanged.
     *
     * The first matching entry wins, and no two can match: a mount's prefixes are its provider's ROOT names,
     * one path segment each, and the `/` boundary below keeps `/Address` off `/AddressBook`.
     */
    fun rehost(written: String): String = mountedPrefixes.firstNotNullOfOrNull { (authored, hosted) ->
        when {
            written == authored -> hosted
            written.startsWith("$authored/") -> hosted + written.removePrefix(authored)
            else -> null
        }
    } ?: written
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
    /**
     * The [DeclaredKind] this declaration's `type` names, absent when it names none A12 admits.
     *
     * Internal because the kind set is the Kernel's, not this API's: a consumer reads [kind] verbatim, and
     * publishing the enum would make every future `type` A12 adds a breaking change here.
     *
     * Stored rather than computed per access, and the reason is measured: dozens of families filter on it, so
     * a `get()` re-ran [DeclaredKind.of]'s entry scan on every element of every filter and took 14 of the 60-odd
     * samples in the profile that followed the 2026-09-15 walk fix. Outside `equals`/`copy` like every other
     * derived value here, so a kind is a projection of [kind] and never a second place to change.
     */
    internal val declaredKind: DeclaredKind? = DeclaredKind.of(kind)

    /** The model this group mounts, when it declares one — `Group.modelAlias`. */
    val modelAlias: String?
        get() = (body?.get("modelAlias") as? JsonPrimitive)?.contentOrNull

    /**
     * The kind's payload as A12 binds it, and absent when A12's own reader refuses to bind it at all.
     *
     * The three cases are NOT two. An omitted envelope is legal — the Kernel default-constructs the element,
     * so every member simply reads as absent, which is an EMPTY object here rather than a refusal. A present
     * envelope that is not an object is a binding failure: the source never loads, so no member of it may be
     * judged and a family that reads `null` as "the member is missing" would publish a verdict about a model
     * A12 never examined.
     *
     * Every family that judges MEMBERS must therefore read this rather than casting `declaration[kind]`,
     * which collapses the legal case onto the refused one.
     */
    val body: JsonObject?
        get() = when (val envelope = declaration[kind]) {
            null -> EMPTY_BODY
            is JsonObject -> envelope
            else -> null
        }
}

private val EMPTY_BODY = JsonObject(emptyMap())

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
 * @param declaredLocales the ENTRY model's declared locales as [localeKeyOrNull] KEYS, in declaration order
 *     and without repeats. Keys rather than the authored spellings because A12 compares parsed locales, so
 *     `en-US` and `en_US` are one declared locale. It is the only set in play after expansion, for a mounted
 *     declaration as much as for a local one: A12 refuses to expand a provider that does not declare every
 *     locale of the entry model, so no mounted declaration can ever answer to a narrower set (measured
 *     2026-09-12, `IncludedDeclarationLocaleScopeLawsTest`).
 * @param unreadableLocaleCodes the declared codes that yielded no key, verbatim. Each makes the whole model
 *     unusable rather than narrowing the set — A12's reader refuses the spelling outright, or, for the empty
 *     code, reads it and then cannot build the model (measured 2026-09-12, `MessageSlotRawShapeLawsTest`) —
 *     so they are carried rather than dropped, and the declaration-slot family reports them.
 * @param supportedCharacters the ENTRY model's `content.modelConfig.supportedCharacters`, verbatim and in
 *     declaration order, empty when the model declares none. The entry model's list is the one in play for
 *     the whole EXPANDED tree — a mounted model's enum values answer to the host's restriction, measured
 *     2026-09-13 (`SupportedCharactersDeclarationGateLawsTest`) — which is why it is carried on the model
 *     rather than looked up per declaration.
 * @param hasBaseYear whether the ENTRY model's `content.modelInfo` holds a `baseYear` A12's reader binds to a
 *     value. A12's temporal gates read it as a presence (`modelSpecificSettings.getBaseYear().isPresent()`), and
 *     a declared base year SUPPLEMENTS the year component of every operand before they are compared. The empty
 *     text and the text `null` bind no value ([JavaIntBinding], KF593), so A12 reads them as no base year at
 *     all. A value the reader cannot bind leaves no model; it still counts as present here, so the temporal
 *     laws stay silent and [unbindableBaseYear] carries the refusal alone (the checker column of
 *     `adapter.laws.ModelTextReadingDiffTest` shows the two codes never together).
 * @param unbindableBaseYear the ENTRY model's `content.modelInfo.baseYear` as written, when A12's reader cannot
 *     bind it as an `Integer` ([JavaIntBinding.Unbindable]: a text `parseInt` refuses, a number outside `Int`,
 *     a Boolean, an object or an array); `null` otherwise. A12 then builds no model (KF591, KF593,
 *     `adapter.laws.ModelTextReadingDiffTest`), so the declaration-slot family reports it as
 *     [CK_MODEL_NOT_BUILDABLE]. A member of its own rather than a row of [unbindableBooleans], whose sentence
 *     names a boolean.
 * @param unexpandedMounts the mounts that left the fact base incomplete.
 * @param diagnostics what the checker could not read. Preparation reports and skips rather than failing, so
 *     one broken element cannot mask its siblings' findings — and no input makes [prepare] raise.
 * @param unbindableBooleans the model's own `boolean` members A12's reader cannot bind as written, one sentence
 *     each: `content.modelInfo.immutable`/`metaData`, `content.modelConfig.fieldRefByShortNameAllowed`, and every
 *     field-type member of a `content.typeDefinitions` entry in the closure, whether or not a field names it.
 *     Each refuses the whole source before any check runs, exactly as a field declaration's does
 *     ([KERNEL-FINDINGS KF569], `adapter.laws.CheckerDeclarationAdmissionAgreementTest`), so the declaration-slot
 *     family reports each as [CK_MODEL_NOT_BUILDABLE].
 */
data class PreparedModel(
    val elements: List<DeclaredElement>,
    val fieldRefByShortNameAllowed: Boolean,
    val conditionLanguage: ConditionLanguage = ConditionLanguage.DEFAULT,
    val unsupportedConditionLanguage: String? = null,
    val declaredLocales: List<String> = emptyList(),
    val unreadableLocaleCodes: List<String> = emptyList(),
    val supportedCharacters: List<String> = emptyList(),
    val hasBaseYear: Boolean = false,
    val unbindableBaseYear: String? = null,
    val unexpandedMounts: List<UnexpandedMount> = emptyList(),
    val diagnostics: List<Diagnostic> = emptyList(),
    /**
     * The ids `content.typeDefinitions` declares, which a field's `TypeDefType` reference must name.
     *
     * Carried on the model rather than resolved per field because the list lives OUTSIDE `modelRoot` and so
     * outside the element walk every other reference uses — the one reference in this checker whose
     * referents are not elements at all.
     */
    val declaredTypeDefinitionIds: Set<String> = emptySet(),
    /** Unambiguous authored definitions; a duplicate id stays unjudged on the index-key path. */
    val typeDefinitionBodies: Map<String, JsonObject> = emptyMap(),
    val unbindableBooleans: List<String> = emptyList(),
) {
    /**
     * The elements a reference may resolve to — [elements] minus everything A12 deletes at the default
     * variant.
     *
     * **`ONLY` is a DELETION, and the deletion is uniform across all three carriers even though what it
     * costs is not.** `ValidationModelConverter` walks one loop asking `fieldIsAcceptedByVariantFilter`,
     * `computationIsAcceptedByVariantFilter` and `ruleIsAcceptedByVariantFilter` of the elements in turn, and
     * keeps only those accepted. Deleting a rule or computation removes its own verdicts, which is why that
     * reads from the outside as a suppression ([KF299]); deleting a FIELD removes a referent other
     * declarations need, so A12 then says MORE about the model rather than less ([KERNEL-FINDINGS] KF305,
     * `rulekit.validate.laws.FieldVariantFilterLawsTest`). One mechanism, two consequences — so this list
     * models the mechanism and lets each consequence fall out.
     *
     * Narrowing this to fields would be modelling the consequence instead, and it is unobservable either way:
     * a reference targets a field or a group, never a rule, and a group carries no `variantFilter` at all.
     * The uniform rule is kept because it is the one that is true.
     *
     * **It keys on the filter TYPE alone**, measured on the field carrier rather than inherited from the
     * rule's: naming the default variant, an empty list and an absent `variants` member all leave the element
     * deleted, because `filterAcceptsVariant` computes its match under a `variant != null` guard that is
     * false at the default variant for every list. Any OTHER spelling — an unknown type, a non-string, an
     * absent one — leaves the element visible here, which under-reports a model A12 cannot build and is the
     * safe direction.
     *
     * Derived, so it is deliberately outside `equals`/`copy`, and computed once because the resolvers call it
     * per reference.
     */
    val resolvableElements: List<DeclaredElement> = elements.filterNot { it.isDeletedAtDefaultVariant() }

    /**
     * [resolvableElements] keyed by path, so resolving a reference is a lookup rather than a scan.
     *
     * **FIRST occurrence wins, which is the behaviour it replaces.** A model may declare two elements at one
     * path — A12 refuses it, and this checker has to report on it rather than fail — and every call site this
     * index replaced asked `firstOrNull`/`any`. `associateBy` keeps the LAST, so it would have changed which
     * duplicate a family reads. Nothing measured that difference, which is exactly why it is not introduced.
     *
     * Lazy because a model that never resolves a reference should not pay for the map, and because the whole
     * point is that it is built once per [PreparedModel] rather than per reference.
     */
    internal val resolvableByPath: Map<String, DeclaredElement> by lazy {
        val byPath = LinkedHashMap<String, DeclaredElement>(resolvableElements.size)
        resolvableElements.forEach { element -> byPath.getOrPut(element.path) { element } }
        byPath
    }

    /**
     * Every declaration's body keyed by path, for the cell store's per-path comparison ([CheckCell]).
     *
     * A LIST per path, and unfiltered by the variant deletion, because this index answers a different
     * question from [resolvableByPath]: that one resolves a reference and so keeps one element per path,
     * while this one has to reproduce what a family could have READ at a path. A model may declare two
     * elements there — A12 refuses it and this checker reports on it rather than failing — so keeping the
     * first would let an edit to the second look like no change at all.
     *
     * Lazy and built once, because validating the cells of one check asks about most of the model's paths
     * and a scan per fact would make validation quadratic where the walk is linear.
     */
    internal val bodiesByPath: Map<String, List<JsonObject?>> by lazy {
        elements.groupBy({ it.path }, { it.body })
    }

    /**
     * Every declaration's shape ROW keyed by path, for [FactRead.DeclarationRow].
     *
     * A list per path for the same reason [bodiesByPath] is one — a path may be declared twice — and the
     * ordinal a cell holds indexes into it. The path is the key rather than a member of the row, so what a
     * row carries is precisely what the path does not.
     */
    internal val rowsByPath: Map<String, List<List<Any?>>> by lazy {
        elements.groupBy({ it.path }, { listOf(it.name, it.kind, it.id, it.origin) })
    }

    /**
     * Each original declaration's shape and default-variant membership, without other body content.
     *
     * R4-V0's twin control moves the filter between equal paths: the surviving path list stays equal,
     * but the tracked default-variant view returns a different declaration. Carrying each deletion bit
     * preserves that distinction without making unrelated content invalidate shape-only cells.
     *
     * Memoized because cell validation compares this value repeatedly (PreparedModelDerivedValueTest).
     */
    internal val declarationSetShape: Any by lazy {
        elements.map { listOf(it.path, it.name, it.kind, it.id, it.origin, it.body.deletesAtDefaultVariant()) }
    }

    /**
     * Which groups repeat, as the value behind [ModelFact.REPEATABLE_GROUP_SET].
     *
     * Read off the UNTRACKED elements deliberately, which is the whole point rather than an optimization:
     * the same question asked through a [TrackedDeclaration] opens each group's body at the barrier, and
     * `TrackedFactBase`'s view of it is memoized, so those reads land on whichever producer forced the view
     * first and no later producer records them (measured 2026-09-18, `MemoizedViewChargeTest`).
     */
    internal val repeatableGroupPathList: List<String> by lazy {
        elements.filter { it.isRepeatableGroup }.map { it.path }
    }

    /** The groups declaring a count below 1, as the value behind [ModelFact.NON_POSITIVE_COUNT_GROUPS]; untracked as above. */
    internal val nonPositiveCountGroupPaths: Set<String> by lazy {
        elements.filter { it.declaredKind == DeclaredKind.GROUP && it.body.declaresNonPositiveCount() }.mapTo(HashSet()) { it.path }
    }

    /**
     * Every declaration's own target reference, computed ONCE per model.
     *
     * **This is where the checker's cost curve was, measured 2026-09-15.** `declaredReferences()` is a whole-
     * model walk that resolves one reference per element, and fifteen families call it — one of them,
     * `computationSelfReadFindings`, per element, which made the whole check cubic in declarations. A flight
     * recorder on a 400-program model put 95 % of its samples in that walk and its resolver. Cold checking of
     * 800 rules plus 800 computations took 8.6 s, with the per-doubling ratio still growing
     * ([results](../../../../../../../../../../docs/PORTABLE-CONSISTENCY-RESULTS.md#cold-2026-09-15)).
     *
     * The walk is a pure function of the model, so the fix is to compute it once here rather than to make each
     * caller remember to hoist it — a rule no guard can enforce, and the reason the cubic arrived unnoticed.
     *
     * **That last clause was TESTED before being trusted, and it holds (2026-09-18).** The obvious guard —
     * every parameterless pure whole-model walk on `PreparedModel` must be memoized — false-positives on
     * `carriersByShortName`, which is called twice per impact report but on two DIFFERENT models (`before`
     * and `after`), so one call per instance and nothing to memoize. Whether a walk is asked twice of the
     * same instance is not decidable from source, and a guard that fails honest code gets disabled. What
     * this class gets instead is the sibling sweep: [conditionReferenceList] was found and closed that way,
     * having been left behind when this one was fixed.
     */
    internal val declaredReferenceList: List<DeclaredReference> by lazy { declaredReferenceByDeclaration.values.toList() }

    /** R5 keeps occurrence identity before filtering: a field and rule may share a path. */
    internal val declarationsByKey: Map<DeclarationKey, DeclaredElement> by lazy {
        val seen = mutableMapOf<String, Int>()
        elements.associateBy { element ->
            val ordinal = seen.getOrElse(element.path) { 0 }
            seen[element.path] = ordinal + 1
            DeclarationKey(element.path, ordinal)
        }
    }

    /**
     * Every declaration's condition references, computed ONCE per model.
     *
     * The same treatment as [declaredReferenceList] and for the same stated reason — a pure whole-model walk
     * belongs here rather than in each caller's memory. It was NOT given that treatment when its sibling was
     * (2026-09-15), which is how three untracked readers came to repeat a DSL parse and a resolution per
     * call; found by sweeping the siblings of the fixed defect rather than by a profile.
     */
    internal val conditionReferenceList: List<ConditionReference> by lazy { computeConditionReferences() }

    /**
     * Every resolvable FIELD's path, keyed by its short name — the first-segment fallback's lookup.
     *
     * **The last scan on the resolver, which the sibling sweep reached rather than a profile.**
     * [resolvableByPath] says its job is to make resolving a reference "a lookup rather than a scan", and the
     * short-name arm of `PreparedModel.resolve` was still filtering every element and allocating a
     * `substringAfterLast` per one, on the resolver the [declaredReferenceList] note names as part of the
     * measured 95 %. It runs only when a relative path's first segment misses its own group AND the model turns
     * `fieldRefByShortNameAllowed` on, so it is a gated path rather than a hot one on every model — no timing
     * claim rides on this.
     *
     * Values keep `resolvableElements` order, because the fallback reports an AMBIGUITY by listing the paths
     * it found and a report's order should not depend on a map's. Built from `resolvableElements`, so the
     * variant deletion is honoured here once instead of in each of the fallback's two arms.
     */
    internal val fieldPathsByShortName: Map<String, List<String>> by lazy {
        val byName = LinkedHashMap<String, MutableList<String>>()
        resolvableElements.forEach { element ->
            if (element.declaredKind == DeclaredKind.FIELD) {
                byName.getOrPut(element.path.substringAfterLast('/')) { mutableListOf() } += element.path
            }
        }
        byName
    }

    /**
     * Targets retain their declaration occurrence, including when another kind shares the path (R5).
     *
     * **It is where the SECOND cost curve was, measured 2026-09-18.** The scan is per declaration and the list
     * is the whole model's, so the check was quadratic again: `node --cpu-prof` put that one `firstOrNull` at
     * **1 154 ms of an 800-program check, growing 19.5x against the check's own 5.5x** over the 4x scale-up —
     * the largest single frame in the profile. The 2026-09-15 note above hoisted the WALK out of fifteen
     * callers and left the per-caller LOOKUP linear, which is the same mistake one layer down.
     */
    internal val declaredReferenceByDeclaration: Map<DeclarationKey, DeclaredReference> by lazy { computeDeclaredReferences() }
}

/** Whether A12 drops this element before checking — see [PreparedModel.resolvableElements]. */
private fun DeclaredElement.isDeletedAtDefaultVariant(): Boolean = body.deletesAtDefaultVariant()

/**
 * Whether a declaration with this body is one A12's converter deletes from the default-variant element container,
 * which every check inside code generation reads ([PreparedModel.resolvableElements] carries the measurement).
 * One predicate for every reader, so the reference resolver and a family walking declarations cannot disagree
 * about which elements A12 examines there.
 */
internal fun JsonObject?.deletesAtDefaultVariant(): Boolean {
    val filter = this?.get("variantFilter") as? JsonObject ?: return false
    val type = filter["filterType"] as? JsonPrimitive ?: return false
    return type.isString && type.content == "ONLY"
}

/**
 * The four element kinds the Kernel's `ElementDeserializer` switch admits; any other `type` is its
 * `IllegalArgumentException` on the whole source. Only [GROUP] carries children, which is why descent is
 * decided by the discriminator and not by whichever envelope happens to hold an `elements` array.
 */
internal enum class DeclaredKind(val wire: String) {
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
 * Where the declarations LAND depends on the mount's include level, and the two are opposites
 * ([KERNEL-FINDINGS KF3], source-verified against `DocumentModelExpandService`). `SINGLE_RG` — the default —
 * appends the provider's root-group CHILDREN onto the mount group and discards the root itself, so an
 * included field lands at `/<mountGroupName>/<childName>` and the provider's own root name appears in no
 * path; `MODEL_ROOT` mounts every root group as a subgroup that KEEPS its name. Either way a provider is
 * fully expanded before it is mounted, so a nested mount's children arrive already placed, and nothing is
 * rewritten in the retained tree: descent simply continues with the mount's path as the parent, which is why
 * each declaration is still the node its own author wrote.
 */
fun prepare(
    admitted: Admission.Admitted,
    providers: Map<String, Admission.Admitted> = emptyMap(),
): PreparedModel {
    val content = (admitted.parsed as? JsonObject)?.get("content") as? JsonObject
    // Any present code is parsed as a locale, an empty or blank one included, and one A12 does not support refuses
    // the model (`CheckerUnicodeBlankAgreementTest`): blank is no absence here.
    val declaredCode = content.conditionLanguageCode()
    val declaredLanguage = declaredCode?.let { ConditionLanguage.of(it) }
    val entry = Scope(
        header = (admitted.parsed as? JsonObject)?.get("header") as? JsonObject,
        origin = DeclarationOrigin(admitted.modelId),
        visiting = setOf(admitted.modelId),
        diagnosticLabel = admitted.source.diagnosticLabel,
    )
    val locales = entry.header.declaredLocales()
    val collector = Collector(providers, locales.keys.toSet())
    val root = (content?.get("modelRoot") as? JsonObject)?.get("rootGroups")
    collector.each(root, at = null, what = "content.modelRoot.rootGroups", scope = entry) {
        collector.collect(it, "", null, entry)
    }
    val definitions = typeDefinitionsInClosure(admitted, providers)
    val baseYear = (content?.get("modelInfo") as? JsonObject)?.get("baseYear")
    return PreparedModel(
        elements = collector.elements,
        fieldRefByShortNameAllowed = collector.flag(content),
        conditionLanguage = declaredLanguage ?: ConditionLanguage.DEFAULT,
        unsupportedConditionLanguage = declaredCode.takeIf { it != null && declaredLanguage == null },
        declaredLocales = locales.keys,
        unreadableLocaleCodes = locales.unreadable,
        supportedCharacters = content.declaredSupportedCharacters(),
        hasBaseYear = baseYear?.let { it !is JsonPrimitive || it.bindJavaInt() != JavaIntBinding.NoValue } == true,
        unbindableBaseYear = baseYear?.takeIf { it !is JsonPrimitive || it.bindJavaInt() == JavaIntBinding.Unbindable }
            ?.toString(),
        unbindableBooleans = content.unbindableModelBooleans() + definitions.unbindableBooleans,
        unexpandedMounts = collector.unexpandedMounts,
        diagnostics = collector.diagnostics,
        declaredTypeDefinitionIds = definitions.ids,
        typeDefinitionBodies = definitions.bodies,
    )
}

/**
 * Every type-definition id the LOADED CLOSURE declares — the entry model's own and every source it
 * references, at any depth.
 *
 * A12's `TypeDefCache` keys definitions by their id across models and silently unifies two models sharing one
 * ([KERNEL-FINDINGS KF3]), so a field's `TypeDefType` resolves against every definition the load brought in
 * rather than against its own model's list. Reading the entry's list alone made TEN of the shared matrix's
 * twenty cases report `CK_MODEL_NOT_BUILDABLE` for a definition the Kernel had already resolved — measured
 * 2026-09-15 against that matrix's own kernel-generated effective field types, and invisible to its
 * placement comparison, which reads paths and kinds.
 *
 * **Both reference purposes contribute and the walk does not distinguish them.** An `include` provider's own
 * definitions reach the cache because the provider is loaded at all; a `typeDefinitions` reference exists
 * only to contribute them. KF3's "only if the base model declares no local type-definitions" governs what is
 * MERGED into the base's list, which is a question about the merged document and not about which ids
 * resolve — and reading it as a narrowing here would refuse models the Kernel builds. This set's only
 * consumer refuses a field whose id is ABSENT from it, so a member too many under-reports and a member too
 * few rejects a model A12 accepts.
 */
private data class TypeDefinitions(
    val ids: Set<String>,
    val bodies: Map<String, JsonObject>,
    val unbindableBooleans: List<String>,
)

/** The model's own two `boolean` holders' members, as [PreparedModel.unbindableBooleans] spells them. */
private fun JsonObject?.unbindableModelBooleans(): List<String> = MODEL_BOOLEAN_MEMBERS.mapNotNull { (owner, member) ->
    val holder = this?.get(owner) as? JsonObject ?: return@mapNotNull null
    "`content.$owner.$member` holds `${holder[member]}`".takeIf { holder.unbindableFlag(member) }
}

/** `DocumentModelInfo`'s and `DocumentModelConfig`'s primitive `boolean` members, from kernel `30.8.1`'s source. */
private val MODEL_BOOLEAN_MEMBERS = listOf(
    "modelInfo" to "immutable",
    "modelInfo" to "metaData",
    "modelConfig" to "fieldRefByShortNameAllowed",
)

/** The field-type members of one `content.typeDefinitions` entry that A12's reader cannot bind. */
private fun JsonObject.unbindableTypeDefinitionBooleans(): List<String> {
    val declared = this["fieldType"] as? JsonObject ?: return emptyList()
    val type = declared.namedFieldType() ?: return emptyList()
    val body = type.bodyIn(declared) ?: return emptyList()
    val id = this["id"]
    return type.booleanMembers.filter { body.unbindableFlag(it) }.map { member ->
        "the `content.typeDefinitions` entry $id declares `${type.wire}.$member` as `${body[member]}`"
    }
}

private fun typeDefinitionsInClosure(
    entry: Admission.Admitted,
    providers: Map<String, Admission.Admitted>,
): TypeDefinitions {
    val ids = mutableSetOf<String>()
    val bodies = mutableMapOf<String, JsonObject>()
    val ambiguous = mutableSetOf<String>()
    val unbindable = mutableListOf<String>()
    val visited = mutableSetOf(entry.modelId)
    val pending = ArrayDeque(listOf(entry))
    while (pending.isNotEmpty()) {
        val source = pending.removeFirst()
        val root = source.parsed as? JsonObject
        val definitions = ((root?.get("content") as? JsonObject)?.get("typeDefinitions") as? JsonArray)
            ?.filterIsInstance<JsonObject>().orEmpty()
        for (definition in definitions) {
            // Before the id check: A12's reader binds every entry, so an id-less or repeated one refuses too.
            unbindable += definition.unbindableTypeDefinitionBooleans()
            val id = (definition["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            ids += id
            if (id in ambiguous) continue
            if (id in bodies) {
                bodies.remove(id)
                ambiguous += id
            } else {
                bodies[id] = definition
            }
        }
        ((root?.get("header") as? JsonObject)?.get("modelReferences") as? JsonArray)
            ?.filterIsInstance<JsonObject>()
            ?.forEach { reference ->
                val referenced = reference.text("reference") ?: return@forEach
                if (visited.add(referenced)) {
                    providers[referenced]?.let { pending += it }
                }
            }
    }
    return TypeDefinitions(ids, bodies, unbindable)
}

/**
 * `content.modelConfig.supportedCharacters`, as declared.
 *
 * Entries are returned verbatim, including any this checker cannot interpret: deciding what an entry MEANS
 * belongs to the family that judges characters, and dropping an unreadable one here would turn a restriction
 * the checker cannot read into a narrower one it can. An entry that is not a string becomes the empty string
 * for the same reason — it is a place in the list, and no reading admits it, so the restriction as a whole
 * stays unreadable rather than quietly losing a member.
 */
private fun JsonObject?.declaredSupportedCharacters(): List<String> =
    ((this?.get("modelConfig") as? JsonObject)?.get("supportedCharacters") as? JsonArray)
        ?.map { (it as? JsonPrimitive)?.takeIf { entry -> entry.isString }?.content ?: "" }
        ?: emptyList()

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
    /** The mount exclusions in force here — [MountExclusions.NONE] in the entry model, which mounts nothing. */
    val exclusions: MountExclusions = MountExclusions.NONE,
)

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
private data class MountExclusions(val rules: Boolean, val computations: Boolean) {

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
    private fun descendGroup(body: JsonObject?, path: String, scope: Scope) {
        val elements = body?.get("elements")
        val alias = (body?.get("modelAlias") as? JsonPrimitive)?.contentOrNull
        if (alias != null && (elements == null || (elements as? JsonArray).isNullOrEmpty())) {
            // A12 refuses an empty `modelAlias` on a mount ("cannot be found", KF593), so it is a mount A12 never
            // expands rather than an ordinary empty group.
            if (alias.isEmpty()) return unexpanded(path, alias, "the `modelAlias` is empty, so A12 cannot find the included model")
            return mount(alias, path, body, scope)
        }
        val inner = if (body?.text("usageType") == ATTACHMENT_USAGE) {
            scope.copy(exclusions = MountExclusions.NONE)
        } else {
            scope
        }
        each(elements, path, "$path's `elements`", inner) { collect(it, path, path, inner) }
    }

    /** Expand the model [alias] names into the mount group at [path], or state why it stayed unexpanded. */
    private fun mount(alias: String, path: String, body: JsonObject, scope: Scope) {
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
        if (entry?.text("purpose") == TYPE_DEFINITIONS_PURPOSE) {
            return unexpanded(path, alias, "the `header.modelReferences` entry for this alias has purpose " +
                "`typeDefinitions`, and A12 refuses to expand a mount through one")
        }
        val reference = entry?.text("reference")
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
        val rootName = root.text("name")
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
            root.text("name")
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
     * It exists so each include level builds its [Scope] from one place: the two levels differ ONLY in which
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
        fun scope(mountedPrefixes: Map<String, String>) = Scope(
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
    fun each(value: JsonElement?, at: String?, what: String, scope: Scope, body: (JsonElement) -> Unit) {
        when (value) {
            null -> Unit
            is JsonArray -> value.forEach(body)
            else -> malformed(at, "$what must be a JSON array", scope)
        }
    }

    /**
     * `fieldRefByShortNameAllowed`, read through A12's own POJO binding — never a raised conversion.
     *
     * Every spelling A12 accepts must be accepted here, in both directions: reading a capitalized `"True"`
     * as off would report `MVK_INVALID_ENTITY` for every bare reference A12 resolves through the model-wide
     * tier, and reading `1.5` as anything at all would analyse a model that does not deserialize. [configFlag]
     * carries the measured table; the unreadable case is diagnosed here because it is a member of the SOURCE
     * rather than of a declaration.
     */
    fun flag(content: JsonObject?): Boolean {
        val declared = (content?.get("modelConfig") as? JsonObject)?.get("fieldRefByShortNameAllowed")
        return when (configFlag(declared)) {
            ConfigFlag.ON -> true
            ConfigFlag.OFF -> false
            // Reported as the source refusal it is, through [PreparedModel.unbindableBooleans].
            ConfigFlag.UNREADABLE -> false
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
    root.text("type") != DeclaredKind.GROUP.wire -> "the root element of `$reference` is " +
        "`${root.text("type")}` rather than a Group, which A12's own expansion refuses, so nothing here is " +
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
 */
private data class DeclaredLocales(val keys: List<String>, val unreadable: List<String>)

/**
 * Read a header's declared locales.
 *
 * Two members of the shape are measured rather than assumed ([KERNEL-FINDINGS KF290]). An entry may be an
 * OBJECT carrying `code` or a bare STRING — A12 reads both, and reading only the first leaves the declared
 * set empty, which silently switches off every per-locale demand. An entry of any other shape carries no
 * code at all and is skipped, which is what A12's own reader does with it.
 */
private fun JsonObject?.declaredLocales(): DeclaredLocales {
    val keys = mutableListOf<String>()
    val unreadable = mutableListOf<String>()
    for (entry in (this?.get("locales") as? JsonArray).orEmpty()) {
        val code = when (entry) {
            is JsonObject -> entry.text("code")
            is JsonPrimitive -> entry.contentOrNull?.takeIf { entry.isString }
            else -> null
        } ?: continue
        val key = localeKeyOrNull(code)
        when {
            key == null -> unreadable += code
            key !in keys -> keys += key
        }
    }
    return DeclaredLocales(keys, unreadable)
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
        ?.lastOrNull { it.text("alias") == alias }

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
private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

/**
 * The declared condition language code, or `null` when the model does not state one — read leniently on
 * purpose, because an unreadable code is not a reason to refuse the model when A12 itself defaults.
 */
private fun JsonObject?.conditionLanguageCode(): String? =
    ((this?.get("modelConfig") as? JsonObject)?.get("conditionLanguage") as? JsonObject)
        ?.get("code")?.let { (it as? JsonPrimitive)?.contentOrNull }
