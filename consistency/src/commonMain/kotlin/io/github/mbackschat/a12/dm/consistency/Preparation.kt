package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.ConditionLanguage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
 * @param supportedCharacters the ENTRY model's `content.modelConfig.supportedCharacters`, each entry the text A12
 *     binds and `null` where that text is not known here, in declaration order, empty when the model declares none. The entry model's list is the one in play for
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
 * @param reachedModelRefusals what leaves A12 unable to build or check the entry from a model it reads, one complete
 *     sentence each naming that model ([KERNEL-FINDINGS KF594], `adapter.WorkspaceReachedModelDiffTest`). From an
 *     included model or a type-definition library: a model-level member its reader cannot bind, a locale code it
 *     cannot parse, an entry locale it does not declare, or a library's root group; from an included model also a
 *     locale declared twice or the empty code. From the entry itself: a locale declared twice, which A12 builds but
 *     cannot check, and a declaration with no `name` or a type definition whose `name` is `null`, an object or an array,
 *     which its reader refuses (KF644). From any of them, a frame member A12 cannot use ([frameDefects], KF650): one
 *     its reader cannot bind in every model, and one it needs for the check in the entry alone; and from the entry, a
 *     frame value A12 fails on before it judges it ([EntryFrameValues.refusals]). The entry's own members and codes are
 *     [unbindableBaseYear], [unbindableBooleans] and [unreadableLocaleCodes].
 * @param frameValues the entry's name, time zone and version as A12 reads them ([FrameValues]).
 */
data class PreparedModel(
    val elements: List<DeclaredElement>,
    val fieldRefByShortNameAllowed: Boolean,
    val conditionLanguage: ConditionLanguage = ConditionLanguage.DEFAULT,
    val unsupportedConditionLanguage: String? = null,
    val declaredLocales: List<String> = emptyList(),
    val unreadableLocaleCodes: List<String> = emptyList(),
    val supportedCharacters: List<String?> = emptyList(),
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
    val reachedModelRefusals: List<String> = emptyList(),
    val frameValues: FrameValues = FrameValues(),
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
    val content = admitted.content
    // Any present code is parsed as a locale, an empty or blank one included, and one A12 does not support refuses
    // the model (`CheckerUnicodeBlankAgreementTest`): blank is no absence here.
    val declaredCode = content.conditionLanguageCode()
    val declaredLanguage = declaredCode?.let { ConditionLanguage.of(it) }
    val entry = PreparationScope(
        header = admitted.header,
        origin = DeclarationOrigin(admitted.modelId),
        visiting = setOf(admitted.modelId),
        diagnosticLabel = admitted.source.diagnosticLabel,
        isEntry = true,
    )
    val locales = entry.header.declaredLocales()
    val collector = Collector(providers, locales.keys.toSet())
    val root = (content?.get("modelRoot") as? JsonObject)?.get("rootGroups")
    collector.each(root, at = null, what = "content.modelRoot.rootGroups", scope = entry) {
        collector.collect(it, "", null, entry)
    }
    val definitions = readClosure(admitted, providers)
    val baseYear = (content?.get("modelInfo") as? JsonObject)?.get("baseYear")
    val frame = (admitted.parsed as? JsonObject).entryFrameValues()
    val characters = content.declaredSupportedCharacters()
    return PreparedModel(
        elements = collector.elements,
        fieldRefByShortNameAllowed = content.fieldRefByShortNameAllowed(),
        conditionLanguage = declaredLanguage ?: ConditionLanguage.DEFAULT,
        unsupportedConditionLanguage = declaredCode.takeIf { it != null && declaredLanguage == null },
        declaredLocales = locales.keys,
        unreadableLocaleCodes = locales.unreadable,
        supportedCharacters = characters,
        hasBaseYear = baseYear?.let { it !is JsonPrimitive || it.bindJavaInt() != JavaIntBinding.NoValue } == true,
        unbindableBaseYear = content.unbindableBaseYear()?.toString(),
        unbindableBooleans = content.unbindableModelBooleans() + definitions.unbindableBooleans,
        reachedModelRefusals = definitions.reachedModelRefusals + collector.readerRefusals + frame.refusals +
            listOfNotNull(characters.unbuildableCharacterRefusal()),
        frameValues = frame.values,
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
private data class ClosureReading(
    val ids: Set<String>,
    val bodies: Map<String, JsonObject>,
    val unbindableBooleans: List<String>,
    val reachedModelRefusals: List<String>,
)

/** A model's own members A12's reader cannot bind: its base year, then its three `boolean`s. */
private fun JsonObject?.unbindableModelMembers(): List<String> =
    listOfNotNull(unbindableBaseYear()?.let { "`content.modelInfo.baseYear` holds `$it`" }) + unbindableModelBooleans()

/** The model's base year where it is present and A12's reader cannot bind it, for the entry and a reached model alike. */
private fun JsonObject?.unbindableBaseYear(): JsonElement? = (this?.get("modelInfo") as? JsonObject)?.get("baseYear")
    ?.takeIf { it !is JsonPrimitive || it.bindJavaInt() == JavaIntBinding.Unbindable }

/** An admitted source's `header`, or null where it holds no object there. */
internal val Admission.Admitted.header: JsonObject? get() = (parsed as? JsonObject)?.get("header") as? JsonObject

/** An admitted source's `content`, or null where it holds no object there. */
internal val Admission.Admitted.content: JsonObject? get() = (parsed as? JsonObject)?.get("content") as? JsonObject

/**
 * `fieldRefByShortNameAllowed`, read through A12's own POJO binding — never a raised conversion.
 *
 * Every spelling A12 accepts must be accepted here, in both directions: reading a capitalized `"True"` as off
 * would report `MVK_INVALID_ENTITY` for every bare reference A12 resolves through the model-wide tier, and
 * reading `1.5` as anything at all would analyse a model that does not deserialize. [configFlag] carries the
 * measured table. An unreadable value reads as off here, because it is reported as the source refusal it is,
 * through [PreparedModel.unbindableBooleans].
 */
internal fun JsonObject?.fieldRefByShortNameAllowed(): Boolean =
    configFlag((this?.get("modelConfig") as? JsonObject)?.get("fieldRefByShortNameAllowed")) == ConfigFlag.ON

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

/**
 * One walk over every model the entry reaches, the entry included: the type definitions they declare, and what
 * leaves A12 unable to build or check the entry ([PreparedModel.reachedModelRefusals], KF594). A model reached by
 * both purposes meets both purposes' demands. The locale demand is measured one reference deep and applied to every
 * depth, as the mount path applies it.
 */
private fun readClosure(
    entry: Admission.Admitted,
    providers: Map<String, Admission.Admitted>,
): ClosureReading {
    val ids = mutableSetOf<String>()
    val bodies = mutableMapOf<String, JsonObject>()
    val ambiguous = mutableSetOf<String>()
    val entryIds = mutableSetOf<String>()
    val unbindable = mutableListOf<String>()
    val reachedRefusals = mutableListOf<String>()
    val entryDeclared = entry.header.declaredLocales()
    val entryLocales = entryDeclared.keys.toSet()
    // A12 gathers the entry's locales into one map for its check, so a repeat leaves no verdict to mirror.
    if (entryDeclared.repeated.isNotEmpty()) {
        reachedRefusals += "The entry model `${entry.modelId}` declares the locale " +
            "${entryDeclared.repeated.joinToString(", ")} twice, so A12 builds it but its consistency check cannot run"
    }
    fun unbuilt(sentence: String) = "$sentence. A12 reads every model the entry reaches before it builds the entry, " +
        "so the entry model never loads"
    val libraries = linkedSetOf<String>()
    val includes = linkedSetOf<String>()
    val visited = mutableSetOf(entry.modelId)
    val pending = ArrayDeque(listOf(entry))
    while (pending.isNotEmpty()) {
        val source = pending.removeFirst()
        val content = source.content
        val frame = source.parsed.frameDefects()
        val isEntry = source === entry
        if (isEntry) {
            reachedRefusals += frame.map {
                when (it.tier) {
                    FrameTier.READ -> "In the entry model, ${it.sentence}, which A12's reader cannot bind, so the model never loads"
                    FrameTier.CHECK -> "In the entry model, ${it.sentence}, so A12 builds the model but its consistency check cannot run"
                }
            }
        } else {
            reachedRefusals += frame.filter { it.tier == FrameTier.READ }
                .map { unbuilt("In the model `${source.modelId}`, ${it.sentence}, which A12 cannot bind") }
            reachedRefusals += content.unbindableModelMembers().map { unbuilt("In the model `${source.modelId}`, $it, which A12 cannot bind") }
            val declared = source.header.declaredLocales()
            // The empty code is read and costs only an include, which the include pass below answers.
            reachedRefusals += declared.unreadable.filter { it.isNotEmpty() }.map {
                unbuilt("In the model `${source.modelId}`, `header.locales` declares `$it`, which A12 cannot use as a locale")
            }
            val uncovered = entryLocales - declared.keys.toSet()
            if (uncovered.isNotEmpty()) {
                reachedRefusals += unbuilt("The model `${source.modelId}` does not declare the entry model's locale " +
                    "${uncovered.joinToString(", ")}, so A12 does not expand it")
            }
        }
        val definitions = (content?.get("typeDefinitions") as? JsonArray)
            ?.filterIsInstance<JsonObject>().orEmpty()
        for (definition in definitions) {
            // Before the id check: A12's reader binds every entry, so an id-less or repeated one refuses too.
            unbindable += definition.unbindableTypeDefinitionBooleans()
            // Its reader also refuses a definition's name written as `null`, an object or an array (KF644, entry
            // measured) — unlike an element's, whose `null` name it admits.
            val name = definition["name"]
            if (isEntry) {
                if (name is JsonNull || name is JsonObject || name is JsonArray) {
                    reachedRefusals += "The `content.typeDefinitions` entry ${definition["id"]} holds `$name` as its " +
                        "`name`, which A12's reader refuses, so the model never loads"
                }
                reachedRefusals += definition.typeDefinitionTextRefusals().map {
                    "In the `content.typeDefinitions` entry ${definition["id"]}, $it, so A12's reader refuses the source and the model never loads"
                }
            }
            val id = (definition["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            // A12's expansion refuses a repeated id, the empty one included, and builds no model (KF647).
            if (isEntry) {
                if (id in entryIds) {
                    reachedRefusals += "The `content.typeDefinitions` id `$id` is declared twice, which A12's expansion refuses, so the model never loads"
                }
                entryIds += id
            }
            ids += id
            if (id in ambiguous) continue
            if (id in bodies) {
                bodies.remove(id)
                ambiguous += id
            } else {
                bodies[id] = definition
            }
        }
        (source.header?.get("modelReferences") as? JsonArray)
            ?.filterIsInstance<JsonObject>()
            ?.forEach { reference ->
                val referenced = reference.scalarText("reference") ?: return@forEach
                when (reference.scalarText("purpose")) {
                    "typeDefinitions" -> libraries += referenced
                    "include" -> includes += referenced
                }
                if (visited.add(referenced)) {
                    providers[referenced]?.let { pending += it }
                }
            }
    }
    for (library in libraries) {
        val content = providers[library]?.content
        val rootGroups = (content?.get("modelRoot") as? JsonObject)?.get("rootGroups") as? JsonArray
        if (!rootGroups.isNullOrEmpty()) {
            reachedRefusals += unbuilt("The type-definition library `$library` declares a root group, so A12 cannot " +
                "expand the import")
        }
    }
    // An included model's locales are gathered into one map keyed by locale, which a repeat breaks and the empty
    // code, read as no locale, breaks too; a library is expanded without that map.
    for (include in includes) {
        val declared = providers[include]?.header.declaredLocales()
        if (declared.repeated.isNotEmpty() || "" in declared.unreadable) {
            reachedRefusals += unbuilt("The included model `$include` declares a locale twice or an empty locale " +
                "code, so A12 cannot expand the include")
        }
    }
    return ClosureReading(ids, bodies, unbindable, reachedRefusals)
}

/**
 * `content.modelConfig.supportedCharacters`, each entry as the text A12 binds it.
 *
 * Entries are returned verbatim, including any this checker cannot interpret: deciding what an entry MEANS
 * belongs to the family that judges characters, and dropping an unreadable one here would turn a restriction
 * the checker cannot read into a narrower one it can. A12 binds a Boolean and a number as their text, so `true`
 * is the entry `true` and `-0` the entry `0` (`adapter.laws.CheckerFrameValueAgreementTest`). An entry whose text is
 * not known here is `null`: a number in exponent form, whose text depends on how A12's reader holds numbers, and an
 * entry that is no scalar, which [frameDefects] reports. It is a place in the list that no reading admits, so the
 * restriction as a whole stays unreadable rather than quietly losing a member.
 */
private fun JsonObject?.declaredSupportedCharacters(): List<String?> =
    ((this?.get("modelConfig") as? JsonObject)?.get("supportedCharacters") as? JsonArray)
        ?.map { entry -> (entry as? JsonPrimitive)?.takeIf { it !is JsonNull }?.boundText() }
        ?: emptyList()

/** A text, a Boolean and a number written without an exponent, as A12's reader binds them to a `String`. */
internal fun JsonPrimitive.boundText(): String? = when {
    isString || booleanOrNull != null -> content
    content == "-0" -> "0"
    PLAIN_NUMBER.matches(content) -> content
    else -> null
}

private val PLAIN_NUMBER = Regex("-?[0-9]+(\\.[0-9]+)?")
