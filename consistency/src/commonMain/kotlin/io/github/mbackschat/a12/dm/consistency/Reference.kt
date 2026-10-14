package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.CallOperand
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionLanguage
import io.github.mbackschat.a12.dm.consistency.syntax.SEMANTIC_INDEX
import io.github.mbackschat.a12.dm.consistency.syntax.categoryAccessesIn
import io.github.mbackschat.a12.dm.consistency.syntax.isName
import io.github.mbackschat.a12.dm.consistency.syntax.Token
import io.github.mbackschat.a12.dm.consistency.syntax.TokenKind
import io.github.mbackschat.a12.dm.consistency.syntax.referencesIn
import io.github.mbackschat.a12.dm.consistency.syntax.semanticIndexUsesIn
import io.github.mbackschat.a12.dm.consistency.syntax.tokenize
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * What a bare reference in a condition or operation resolved to.
 *
 * The three arms exist because A12 rejects two of them **differently**: an undeclared name is
 * `MVK_INVALID_ENTITY` and an ambiguous one is `MVK_FIELDNAME_NOT_UNIQUE`, and the corrective differs with
 * the code — declare the field, versus qualify the reference so it names one. The interpreter's existing
 * static helper reports both as the same unresolved path, which is the gap this classification closes
 * (PORTABLE-CONSISTENCY-PROPOSAL §3.4).
 */
sealed interface Reference {

    /** The reference names exactly one declared element, at [path]. */
    data class Resolved(val path: String) : Reference

    /**
     * Nothing declares the name. [candidate] is the path the declaring group would have given it.
     *
     * [rootUnresolved] says that the FIRST segment of [candidate] is not a declared root group, which A12
     * answers with a class of its own — it resolves a path segment by segment and reports the root-group
     * class when the root segment is the one that failed, whatever follows it
     * (`rulekit.validate.laws.ReferenceSegmentGateOrderLawsTest`). It is carried here rather than derived
     * from [candidate] because only the resolver knows which roots the model declares; counting separators
     * instead named the wrong class on every absolute path whose missing root had a child.
     */
    data class Undeclared(val candidate: String, val rootUnresolved: Boolean = false) : Reference

    /** Several fields carry the short [name], so the model-wide fallback cannot choose between them. */
    data class Ambiguous(val name: String, val candidates: List<String>) : Reference

    /**
     * [written] climbs above the model root, so it names no position at all rather than a missing element.
     * A12 says so with its own code (`MVK_TOO_MANY_PARENT_GROUPS`), which is why this is not [Undeclared]:
     * the corrective is to remove a `..`, not to declare anything.
     */
    data class AboveRoot(val written: String) : Reference

    /**
     * [written] carries an up-step outside a relative path's leading run, where A12's grammar has no place for
     * one, so it names nothing and A12 refuses it as `MVK_UNEXPECTED_TOKEN` ([KERNEL-FINDINGS KF465]).
     *
     * **[walkedPrefix] is what the segments before that step resolve to when a CONDITION walks them first**,
     * and null when it does not. A condition walks them when a named segment before the step is starred or
     * the path opens with an up-run, and then a prefix that fails answers with its own class: `Nope*` then
     * `..` is an entity miss while `Nope/..` is a token error. It is carried here rather than decided because
     * the condition role decides it. Message parameters instead resolve each reached prefix through their
     * own ordered reader (KF501), before a later misplaced step can be reached.
     */
    data class MisplacedUpStep(val written: String, val walkedPrefix: Reference?) : Reference
}

/**
 * Where a reference was written, which decides what A12 reports when it does not resolve.
 *
 * The roles are not interchangeable and the difference is measured, not stylistic: the same unresolvable
 * path draws `MVK_ERROR_FIELD_MISSING` as a rule's error target, no code at all as a computation's target
 * (the model cannot be built), and `MVK_INVALID_ENTITY` inside a condition.
 */
enum class ReferenceRole {
    /** A rule's `errorEntityRelPath`. */
    ERROR_TARGET,

    /** A computation's `computedFieldRelPath`. */
    COMPUTED_TARGET,

    /** An entity named inside condition or operation text. */
    CONDITION_ENTITY,

    /**
     * An entity named by a `$Name$` parameter of an error message.
     *
     * A separate role because A12 answers this surface with its OWN vocabulary: the same four outcomes that
     * draw `MVK_*` codes inside a condition draw unprefixed `FehlertextErrorType` members here, two of which
     * share a tail with the condition-side code and one of which does not resemble it at all
     * ([KERNEL-FINDINGS KF336]).
     */
    MESSAGE_PARAMETER,
}

/**
 * Resolve an entity reference [written] inside a condition declared in [declaringGroup].
 *
 * **A condition's relative path is resolved from the declaring GROUP, not from the declaring element** —
 * the opposite of `errorEntityRelPath`, which climbs from the rule itself. The two are one `..` apart and
 * both spellings look identical, so the asymmetry is measured rather than assumed: a rule in `/Order/Lines`
 * reaches `/Order/Code` as `../Code` in its condition and as `../../Code` in its error target.
 *
 * A leading `/` makes the path absolute. A relative path's FIRST segment keeps the two-tier treatment of
 * [resolve] — the declaring group first, then the model-wide field short name when the model allows it —
 * whether it is starred and whatever follows it, and the rest of the path is positional. A trailing `*` or
 * `.*` is a repetition wildcard rather than part of a name, so it is stripped before matching and kept in the
 * reported text.
 *
 * **The floor is the ROOT GROUP, not the model root.** A `..` that would leave the outermost group has no
 * position to name — the model root holds groups, not fields — and A12 says so with a distinct code rather
 * than reporting a missing element: `../Code` is above the root for a rule declared in a root group and
 * perfectly ordinary one level down (measured through `ModelLoader.loadAndCheck`).
 */
fun PreparedModel.resolveEntity(
    declaringGroup: String,
    written: String,
    origin: DeclarationOrigin = DeclarationOrigin(""),
): Reference = resolveEntity(declaringGroup, written, origin, LookupPosition.CONDITION)

/** KF501's message lookup ignores the condition's short-name flag; the path and mount indexes stay shared. */
internal fun PreparedModel.resolveMessageEntity(declaringGroup: String, written: String, origin: DeclarationOrigin): Reference =
    resolveEntity(declaringGroup, written, origin, LookupPosition.MESSAGE)

private enum class LookupPosition { CONDITION, MESSAGE }

private fun PreparedModel.resolveEntity(
    declaringGroup: String,
    written: String,
    origin: DeclarationOrigin,
    position: LookupPosition,
): Reference {
    // A mounted declaration still spells its provider's own root, which the mount replaced. Rehosting the
    // written path is what the Kernel does before integration, so the reference names the element that
    // moved with it rather than one the host happens to have (KF3).
    val hosted = origin.rehost(written)
    val segments = hosted.split('/').filter { it.isNotEmpty() }
    val absolute = hosted.startsWith("/")
    val misplaced = segments.indices.firstOrNull { at ->
        segments[at].startsWith(UP) && (absolute || segments.take(at).any { !it.startsWith(UP) })
    } ?: return resolveSegments(declaringGroup, written, segments, absolute, origin, position)
    // Cancelling each `X/..` pair instead read `Claims/..` as the rule's own group, a model A12 refuses.
    val prefix = segments.take(misplaced)
    val walked = (!absolute && prefix.first().startsWith(UP)) ||
        prefix.any { !it.startsWith(UP) && it.endsWith("*") }
    val prefixText = (if (absolute) "/" else "") + prefix.joinToString("/")
    return Reference.MisplacedUpStep(
        written,
        if (walked) resolveSegments(declaringGroup, prefixText, prefix, absolute, origin, position) else null,
    )
}

/** [resolveEntity] for a path of [segments] whose up-steps, if any, all lead it. */
private fun PreparedModel.resolveSegments(
    declaringGroup: String,
    written: String,
    segments: List<String>,
    absolute: Boolean,
    origin: DeclarationOrigin,
    position: LookupPosition,
): Reference {
    val steps = segments.map { it.withoutRepetitionSuffix() }
    // A12 looks each segment up with the fallback gated on the path being relative ALONE, so the first
    // segment gets it however it is spelled (`CheckIdNameRefImpl.getEntityFromConditionText`): `Holder*` from a
    // nested rule lands on the root field, and `Remark/Nope` is not unique where a positional reading names a
    // miss (`rulekit.validate.laws.PathSegmentWalkLawsTest`). The fallback reaches only fields, which have
    // nothing below them, so after a first segment it bound the rest cannot resolve and positional is exact.
    val first = steps.firstOrNull()
    if (!absolute && first != null && !first.startsWith(UP)) {
        val tiered = resolve(declaringGroup, first, origin, position)
        if (steps.size == 1 || tiered is Reference.Ambiguous) {
            return tiered
        }
    }
    val path = if (absolute) mutableListOf() else declaringGroup.split('/').filter { it.isNotEmpty() }.toMutableList()
    var aboveRoot = false
    steps.forEach { step ->
        when {
            // `..` optionally carries a TURNING-POINT LABEL naming the ancestor it lands on
            // (`relativeUp: relUp? '..' idName?`). The label is not a path segment; A12 checks that it names
            // the right ancestor and this does not, which under-reports rather than inventing a rejection.
            step.startsWith(UP) -> if (path.size <= 1) aboveRoot = true else path.removeAt(path.size - 1)
            else -> path += step
        }
    }
    val candidate = "/" + path.joinToString("/")
    return when {
        aboveRoot -> Reference.AboveRoot(written)   // the AUTHORED text: that is what a reader has to edit
        candidate in resolvableByPath -> Reference.Resolved(candidate)
        else -> Reference.Undeclared(
            candidate,
            rootUnresolved = path.isNotEmpty() && ("/" + path.first()) !in resolvableByPath,
        )
    }
}

private const val UP = ".."

/** A per-segment gate that answers for an EARLIER segment of a path, ahead of a later one's resolution. */
internal enum class EarlierSegmentGate {
    /** The H-model `.*` suffix, `MVK_POINT_IN_A12_MODEL`, which outranks the landing site. */
    H_MODEL_SUFFIX,

    /** A star on a name that has no repetitions, `MVK_INVALID_WILDCARD`. */
    WILDCARD_LANDING,

    /** A star on a step of the leading up-run, `MVK_INVALID_WILDCARD_REL`. */
    UP_STEP_WILDCARD,
}

/**
 * The gate A12's walk answers at before it reaches the segment of [written] that does not resolve, or null
 * when the walk reaches that segment first.
 *
 * **A12 judges a path segment by segment, running resolution, then the `.*` suffix, then the star's landing
 * site on each** (`CheckIdNameRefImpl.checkIdNameRef`, [KERNEL-FINDINGS KF356]). So a reference that does not
 * resolve as a whole is not necessarily a resolution failure to A12: `Insured*` followed by `Nope` answers at
 * `Insured*`, because a fixed group is starred there, and never reaches `Nope`. Treating the whole path as one lookup
 * named `MVK_INVALID_ENTITY` for it, and withheld the landing class behind that (measured on group operands,
 * field paths and `RepetitionNotUnique` keys, `rulekit.validate.laws.GroupOperandAndKeyResolverClassLawsTest`).
 *
 * Each prefix is resolved through [resolves], the same resolver the reference itself went through, so this
 * adds no second reading of a path. [isRepeatableGroup] is asked only of a starred prefix that resolved,
 * which is what lets a charged caller pay for exactly the bodies it reads. A star on a named turning point
 * or a self-step is unmeasured and answers null, as it does in `repetitionWildcardFindings`.
 */
internal fun earlierSegmentGate(
    written: String,
    resolves: (String) -> Reference,
    isRepeatableGroup: (String) -> Boolean,
): EarlierSegmentGate? {
    val segments = written.split('/')
    for (at in segments.indices) {
        val segment = segments[at]
        if (segment.isEmpty()) {
            // The leading slash of an absolute path, whose root segment is the next one.
            continue
        }
        val resolved = resolves(segments.take(at + 1).joinToString("/")) as? Reference.Resolved ?: return null
        if (!segment.endsWith("*")) {
            continue
        }
        val stepped = segment.removeSuffix("*")
        val gate = when {
            stepped == UP -> EarlierSegmentGate.UP_STEP_WILDCARD.takeIf { segments.take(at).all { it == UP } }
            stepped.isEmpty() || stepped.startsWith(".") -> null
            stepped.endsWith(".") -> EarlierSegmentGate.H_MODEL_SUFFIX
            !isRepeatableGroup(resolved.path) -> EarlierSegmentGate.WILDCARD_LANDING
            else -> continue
        }
        return gate
    }
    return null
}

/** Drop a repetition wildcard (`Lines*`, `Lines.*`) without touching the `..` step, whose text ends in `.`. */
private fun String.withoutRepetitionSuffix(): String {
    val withoutStar = removeSuffix("*")
    return if (withoutStar == UP) withoutStar else withoutStar.removeSuffix(".")
}

/**
 * Resolve a bare [name] written inside [declaringGroup] — the Kernel's `getEntity`-first rule.
 *
 * The declaring group is tried first and wins outright, so a field the group actually has is never displaced
 * by a model-wide candidate. Only when that misses does the model-wide short-name fallback apply, and only
 * when the model turns it on: `fieldRefByShortNameAllowed` gates the second tier alone, which is why the
 * flag changes the verdict for a name the declaring group does not have and changes nothing for one it does.
 *
 * The fallback indexes **fields**, not every element, matching the Kernel's own `CheckIdNameRefImpl`
 * (KERNEL-FINDINGS §10; the tiers are locked by `ShortNameTierLawsTest`).
 */
fun PreparedModel.resolve(
    declaringGroup: String,
    name: String,
    origin: DeclarationOrigin = DeclarationOrigin(""),
): Reference = resolve(declaringGroup, name, origin, LookupPosition.CONDITION)

private fun PreparedModel.resolve(
    declaringGroup: String,
    name: String,
    origin: DeclarationOrigin,
    position: LookupPosition,
): Reference {
    val candidate = "$declaringGroup/$name"
    if (candidate in resolvableByPath) {
        return Reference.Resolved(candidate)
    }
    // WHICH model's flag applies, and WHICH elements the fallback may see, are one question with two
    // answers. A mounted declaration whose own model turns the flag on is bound INSIDE that model before
    // integration and emerges fully qualified, so a host reusing the name cannot retarget it. Every other
    // reference — one in the entry model, or one from a provider that leaves the flag off, which travels
    // bare — is bound over the whole EXPANDED tree under the entry model's flag, so a mounted field of the
    // same name does make it ambiguous. Both halves measured; see `DeclarationOrigin.shortNameFallbackAllowed`.
    val boundInProvider = origin.isMounted && origin.shortNameFallbackAllowed
    if (position == LookupPosition.CONDITION && !boundInProvider && !fieldRefByShortNameAllowed) {
        return Reference.Undeclared(candidate)
    }
    // The denominator is this MOUNT INSTANCE's subtree, not the provider's own declarations. The provider is
    // fully expanded before it is bound (KF3), so a field its own nested include contributed is inside its
    // index; and one provider mounted at two points is two separate indexes, so the second copy must not make
    // the first ambiguous. Keying on the authoring model instead got both cases wrong, in the direction that
    // rejects a model A12 accepts — measured 2026-09-12 through `ModelLoader.loadAndCheck` on both shapes.
    // The variant deletion is honoured at ONE site — [PreparedModel.fieldPathsByShortName] is built from
    // `resolvableElements` — so neither arm below can stop honouring it. That property used to rest on
    // naming the list once and narrowing it, because a two-arm `if` let one arm keep the deletion while the
    // other dropped it, and a mutant doing exactly that survived every control here (2026-09-13); the
    // provider-bound arm needs a mounted model with its own flag on to be reached at all. The control it
    // lacked now exists (`ShortNameFallbackScopeTest`) and kills that mutant, and the index makes the
    // divergence unrepresentable rather than merely measured.
    // A MODEL_ROOT mount places several prefixes and is still ONE instance, so the denominator is the union
    // of what this mount placed rather than a single subtree.
    val named = fieldPathsByShortName[name].orEmpty()
    val matches = if (boundInProvider) {
        named.filter { path -> origin.mountedPrefixes.values.any { path.startsWith("$it/") } }
    } else {
        named
    }
    return when (matches.size) {
        0 -> Reference.Undeclared(candidate)
        1 -> Reference.Resolved(matches.single())
        else -> Reference.Ambiguous(name, matches)
    }
}



/**
 * Whether this model path names a ROOT group of the model A12 builds: one step below the model, so one
 * separator deep.
 *
 * Depth is the whole test, and correctly so across includes: a mounted provider's own root group DISAPPEARS
 * into the mounting group, so an expanded include contributes no root group of its own and depth is what
 * A12 sees.
 */
internal fun String.isRootGroup(): Boolean = count { it == '/' } == 1

/**
 * The repeatable groups [path] lies inside, outermost first — the levels A12 iterates for a plain reference.
 *
 * A reference's index vector carries one entry per level, and every repeatable level it crosses without a
 * marker is A12's `ITERATION`. That is the quantity three families ask for, so it is stated once here.
 *
 * Each group's repeatability charges its own declaration.
 *
 * [TrackedFactBase.repeatableGroups], not `declarations()`, and the distinction is load-bearing rather than
 * stylistic: this asks which groups the MODEL repeats above a path, so under a scoped pass `declarations()`
 * would answer with the one declaration the pass is about and report that the path crosses nothing. Measured
 * 2026-09-18 — three families invented a verdict through exactly this reading. It charges the set's shape
 * exactly as `allDeclarations` does; what the narrower accessor saves is the linear filter, which this call
 * pays per operand.
 */
internal fun TrackedFactBase.crossedRepetitions(path: String): List<TrackedDeclaration> = repeatableGroups()
    .filter { path.startsWith("${it.path}/") }
    .sortedBy { it.path.length }

/**
 * The repeatable levels above [path], plus [path] itself when it names a repeatable group.
 *
 * A reference that ENDS at a repeatable group iterates it too — `CurrentRepetition(Legs)` rides the same
 * marker a field beneath `Legs` does — so a walk that only looks at ancestors misses the group carrier.
 *
 * **The charge falls out of using the cursor rather than being written down**, which is what makes it right:
 * the repeatable-group list is built through [TrackedDeclaration.isRepeatable], so every group's body this
 * walk actually reads is charged, and a future change to what it reads charges itself. A hand-written charge
 * beside a delegating call is the shape that goes stale and under-charges — the unsound direction.
 */
internal fun TrackedFactBase.iteratedLevels(path: String): List<String> = repeatableGroupPaths()
    .filter { it == path || path.startsWith("$it/") }
    .sortedBy { it.length }

// The whole model's repeatable groups: a SET question, so it reads through
// [TrackedFactBase.repeatableGroups] and never narrows with a scoped pass. Under `declarations()` a scoped
// pass saw an empty level list and `addressedTargetIn` then handed back a target it used to refuse
// (measured 2026-09-18).
private fun TrackedFactBase.repeatableGroupPaths(): List<String> = repeatableGroups().map { it.path }

/**
 * The resolved paths [written] stars, or null when a star lands anywhere but on a repeatable group.
 *
 * A star on a non-repeatable level is `MVK_INVALID_WILDCARD` and outruns every slot gate ([KF328]), so such
 * a reference belongs to [repetitionWildcardFindings] and is not judged by a scope law at all — including
 * the case where the starred prefix does not resolve, which is the reference family's.
 *
 * Both of its reads charge themselves through the cursor, and the repeatability lookup is asked per starred
 * segment so it stays a direct lookup rather than a list of every repeatable group — the common reference
 * carries no star at all and must pay for no walk. [TrackedFactBase.declarationAt] takes the set's shape and
 * then one declaration's content; narrower than what is read would be unsound, and this is not that.
 */
internal fun TrackedFactBase.starredLevelsOf(
    written: String,
    declaringGroup: String,
    element: TrackedDeclaration,
): Set<String>? = starredLevelsIn(
    written,
    resolve = { prefix -> resolveEntity(declaringGroup, prefix, element.origin) },
    repeats = { path -> declarationAt(path)?.isRepeatableGroup == true },
)

/**
 * The repeatable levels on the way to [path] that [written] leaves UNSTARRED, outermost first, or null when a
 * star lands on a level that does not repeat.
 *
 * This is the question a star COUNT only approximates, and gets wrong exactly where it matters: a star on a
 * field or a fixed group is stripped by resolution yet still counted, so `Lines/LineDate*` looks as starred as
 * the same path with the star on `Lines`, and a family that then judges the read speaks beside
 * `MVK_INVALID_WILDCARD`, which A12 answers alone ([KF328]; `rulekit.validate.laws.PartialStarContainmentLawsTest`,
 * `…DateArgumentSlotLawsTest`, `…ScalarValueListGateLawsTest`). Null hands such a reference to
 * [repetitionWildcardFindings].
 */
internal fun TrackedFactBase.unstarredRepeatableLevels(
    written: String,
    path: String,
    declaringGroup: String,
    element: TrackedDeclaration,
): List<String>? {
    val starred = starredLevelsOf(written, declaringGroup, element) ?: return null
    return iteratedLevels(path).filterNot { it in starred }
}

/**
 * Whether A12 iterates [level] JOINTLY with [element]'s target: a parallel level both branches key by one index
 * field of one name and type ([parallelVerdictOf]).
 *
 * The walk admits such a level exactly as it admits the one the target sits in, and the carrier's own gate
 * answers next — measured on the six group carriers that have one and on a rounding, a date and a value-list
 * slot (`rulekit.validate.laws.KeyedParallelGroupOperandLawsTest`), and ASSUMED for the other operand gates,
 * which read their operand after the same walk. A target outside the declaring group is never joint here,
 * because `MVK_ERROR_FIELD_NOT_IN_RULEGROUP` answers ahead of the walk ([operandScopeContributions]).
 */
internal fun TrackedFactBase.iteratesJointly(level: String, element: TrackedDeclaration): Boolean {
    val targetGroup = iterationGroupOf(element) ?: return false
    val declaringGroup = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    val parallel = !targetGroup.isAtOrBelow(level) && !level.isAtOrBelow(targetGroup)
    if (!parallel || !targetGroup.isAtOrBelow(declaringGroup)) {
        return false
    }
    val declared = declarationAt(level) ?: return false
    return parallelVerdictOf(declared, targetGroup) == ParallelVerdict.Joint
}

internal fun String.isAtOrBelow(ancestor: String): Boolean = this == ancestor || startsWith("$ancestor/")

private inline fun starredLevelsIn(
    written: String,
    resolve: (String) -> Reference,
    repeats: (String) -> Boolean,
): Set<String>? {
    val segments = written.split('/')
    val starred = mutableSetOf<String>()
    segments.indices.filter { segments[it].endsWith("*") }.forEach { at ->
        val prefix = segments.take(at + 1).joinToString("/").replace("*", "")
        val resolved = resolve(prefix) as? Reference.Resolved ?: return null
        if (!repeats(resolved.path)) {
            return null
        }
        starred += resolved.path
    }
    return starred
}

/**
 * The `$` that marks a reference as reaching OUT of a filter into the enclosing rule.
 *
 * A marked reference is checked with the rule's own error field restored (`CheckEntitySpecImpl.checkCommon`),
 * the one every read outside a filter is checked against, so the families that judge the rule's reads take it
 * as one ([io.github.mbackschat.a12.dm.consistency.syntax.rulesOwnReadsIn]). A family that still sets a marked
 * text aside says why where it does.
 */
internal const val OUTER_MARKER = "$"

/** The index field [group] declares, or null when it declares none — A12 reads an empty name as none. */
internal fun indexFieldNameOf(group: DeclaredElement): String? = indexFieldNameIn(group.body)

/** The same reading through the read barrier, sharing one implementation so the two cannot drift. */
internal fun indexFieldNameOf(group: TrackedDeclaration): String? = indexFieldNameIn(group.body)

private fun indexFieldNameIn(body: JsonObject?): String? =
    (body?.get("indexFieldName") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

/** The type a field declares, or null where it declares none A12 does. */
internal fun DeclaredElement.declaredFieldType(): FieldType? = declaredFieldTypeIn(body)

/** The same reading through the read barrier, sharing one implementation so the two cannot drift. */
internal fun TrackedDeclaration.declaredFieldType(): FieldType? = declaredFieldTypeIn(body)

private fun declaredFieldTypeIn(body: JsonObject?): FieldType? =
    (body?.get("fieldType") as? JsonObject)?.namedFieldType()

/**
 * Which [BaseTypeClass] this field's declaration falls into, or null where this reader cannot say.
 *
 * Null covers `TypeDefType` and `UnspecifiedType`, whose effective type is a definition body this checker
 * declares as a coverage gap rather than reads — so a law over base types leaves such a field unjudged
 * rather than guessing at it.
 */
internal fun DeclaredElement.baseTypeClass(): BaseTypeClass? = declaredFieldType()?.baseTypeClass

/** The same reading through the read barrier — it rides [declaredFieldType], which already has both arms. */
internal fun TrackedDeclaration.baseTypeClass(): BaseTypeClass? = declaredFieldType()?.baseTypeClass

/**
 * The declared types this reader is CERTAIN A12 does not give the NUMBER operand type.
 *
 * An allow-list rather than the complement of `NumberType`, for the same reason [PLAINLY_NON_TEMPORAL_TYPES]
 * is one: the complement would sweep in `TypeDefType` and `UnspecifiedType`, whose effective type this
 * reader does not resolve, and refusing those would be an over-rejection. Every member is measured — the six
 * non-temporal ones in this family's own grid, the four temporal ones behind a number leader in
 * `rulekit.validate.laws.ExtremumOperandKindGridLawsTest`, and the custom one on all five carriers that ask
 * the question separately in `rulekit.validate.laws.CustomFieldTypeOperandLawsTest`.
 *
 * The custom member is spelled `CustomFieldType` because that is the discriminator A12 STORES; it read
 * `CustomType` until 2026-09-14, a token no model carries, so every carrier here was silently blind to a
 * custom declaration (`CheckerFieldTypeTokenGuardTest` now derives the legal spellings).
 */
internal val PLAINLY_NON_NUMBER_TYPES = setOf(
    FieldType.STRING,
    FieldType.BOOLEAN,
    FieldType.CONFIRM,
    FieldType.ENUMERATION,
    FieldType.CUSTOM_FIELD,
    FieldType.DATE_RANGE,
) + TEMPORAL_DECLARED_TYPES

/** The token kinds one entity spec may be made of; anything else makes the text an expression. */
private val REFERENCE_TOKENS = setOf(TokenKind.NAME, TokenKind.QUOTED_NAME, TokenKind.PUNCTUATION)

/**
 * The punctuation an entity spec may carry: its brackets and its path steps, the climbing `..` included.
 *
 * A climb names the level it lands on and is judged as that level's read would be: `../Memo` from a fixed
 * subgroup of `Claims` draws each operand gate's own class, keyed or not, and `../../Riders/Memo` the
 * unaddressed read's `MVK_NO_WILDCARD` (`rulekit.validate.laws.KeyedOperandFamilyGateLawsTest`, all
 * twenty-seven gates; [KERNEL-FINDINGS KF480]). Without `..` here, every family typing through
 * [bareReferenceField], and the key reader, took a climb for an expression and stood down.
 *
 * **No repetition star, and that is measured rather than intended.** This set listed `"*"` and could never
 * admit one: the tokenizer classifies a star as `OPERATOR_SYMBOL` (measured 2026-09-17,
 * `BareEntitySpecStarTest`), which [isBareEntitySpec] refuses before any punctuation is examined. So the
 * entry was dead data behind a comment that promised the opposite, and every family typing an operand
 * through [bareReferenceField] is SILENT on a starred operand.
 *
 * The silence UNDER-reports and is therefore safe, so it is not widened here: admitting the star would make
 * a dozen families judge an operand shape none of them has been measured against, which is the one direction
 * that needs a kernel basis first
 * (`docs/archived/PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md` "a starred operand is never typed").
 */
private val SPEC_PUNCTUATION = setOf("[", "]", "/", ".", "..")

/**
 * The field declaration [text] names on its own, or null wherever this reader will not say.
 *
 * Null is the common answer and the safe one, and it covers three different reasons to stay quiet. **A text
 * that is not ONE entity spec** — arithmetic, a nested call, an aggregate — carries a result type A12
 * computes from the whole expression, so typing it from its most visible operand would be a claim with
 * nothing behind it; the test is that every token belongs to a spec, because reducing by "it names exactly
 * one entity" instead types `{[Qty] + 1}` from the `Qty` in it. **A name that does not resolve** belongs to
 * the reference family. And **a repetition the reference crosses that neither its own star nor the declaring
 * group's iteration addresses** is `MVK_NO_WILDCARD` or `MVK_WILDCARDS_REQUIRED` from the reference reader
 * before any later gate is asked — a family typing such a side would name its own class on a model A12
 * refuses for something else, which is the defect corpus mutation found in the arity gates
 * ([KERNEL-FINDINGS KF381]). A soundly KEYED spec is one entity spec too, read through [operandReferenceIn].
 */
internal fun TrackedFactBase.bareReferenceField(
    text: String,
    declaringGroup: String,
    element: TrackedDeclaration,
    inAFilterBody: Boolean = false,
): TrackedDeclaration? {
    val read = operandReferenceIn(text, declaringGroup, element) {
        bareReferenceIn(text, conditionLanguage)?.let { OperandReference(it) }
    } ?: return null
    val target = addressedTargetOf(read.reference, declaringGroup, element, read.indexSelected,
        everyLevelAddressed = inAFilterBody) ?: return null
    return declarationAt(target)?.takeIf { it.declaredKind == DeclaredKind.FIELD }
}

/**
 * A written operand reduced to the reference an operand gate reads.
 *
 * @param indexSelected whether a semantic-index key the chain admits stands on the reference, which is what
 *     [addressedTargetOf] asks.
 * @param category the category a `->` projects the reference onto, where the reader keeps one.
 * @param key the admitted key as written, which is part of the operand's identity in an entity list: A12 keys
 *     an operand by path, category, index and outer flag (KERNEL-FINDINGS kf439), so two reads of one path
 *     under different keys are different operands.
 */
internal data class OperandReference(
    val reference: String,
    val indexSelected: Boolean = false,
    val category: String? = null,
    val key: String? = null,
)

/**
 * [text] reduced to the reference an operand gate reads, or null wherever this reader may not reduce it.
 *
 * A text that writes no semantic index is [plain]'s to reduce, so each family keeps the reduction it was
 * measured with. One that writes an index is read HERE and nowhere else, because A12 judges the key before any
 * operand gate: a defective key answers with its own class, and a sound one addresses the level it keys
 * exactly as the declaration's iteration would, in every operand family measured
 * ([KERNEL-FINDINGS KF476], `rulekit.validate.laws.KeyedOperandFamilyGateLawsTest`). So the key is honoured
 * only where the whole text, brackets aside, is ONE index whose value is a bare spec, whose key is a bare spec
 * or a string, and which the semantic-index chain admits ([admitsSemanticIndex]); every other index-carrying
 * text yields. A family that dropped the key instead read the bare value, and on a level the rule iterates
 * named its own class where A12 names the key's.
 */
internal fun TrackedFactBase.operandReferenceIn(
    text: String,
    declaringGroup: String,
    element: TrackedDeclaration,
    plain: () -> OperandReference?,
): OperandReference? = keyedReferenceIn(text, declaringGroup, element, keepsCategory = false, plain)

/**
 * [operandReferenceIn], where [keepsCategory] says whether the caller reads [OperandReference.category]. A keyed
 * operand projecting a category reaches only such a caller: a family that never asks for the category would
 * judge the enumerated field where A12 judges its category ([KERNEL-FINDINGS KF568]).
 */
private fun TrackedFactBase.keyedReferenceIn(
    text: String,
    declaringGroup: String,
    element: TrackedDeclaration,
    keepsCategory: Boolean,
    plain: () -> OperandReference?,
): OperandReference? {
    val keyword = CONDITION_OPERATOR_KEYWORDS[conditionLanguage]?.get(SEMANTIC_INDEX) ?: return plain()
    val inner = text.withoutEnclosingBrackets()
    val uses = semanticIndexUsesIn(inner, conditionLanguage, keyword)
    if (uses.isEmpty()) {
        return plain()
    }
    val use = uses.singleOrNull() ?: return null
    // The indexed operand may project a category, which `entitySpec` writes ahead of the keyword (KF568).
    val head = tokenize(inner.substring(0, use.keywordAt))
    val arrow = head.indexOfFirst { it.text == "->" }
    val category = if (arrow < 0) null else head.drop(arrow + 1).singleOrNull()?.takeIf { it.isName() }?.text
        ?: return null
    if (category != null && !keepsCategory) return null
    val valueText = if (arrow < 0) inner.substring(0, use.keywordAt) else inner.substring(0, head[arrow].start)
    val value = bareReferenceIn(valueText, conditionLanguage) ?: return null
    val key = inner.substring(use.start, use.end)
    val bareKey = if (use.keyLiteral != null) {
        tokenize(key).size == 1
    } else {
        bareReferenceIn(key, conditionLanguage) != null
    }
    val sound = bareKey && tokenize(inner.substring(use.end)).isEmpty() &&
        admitsSemanticIndex(use, declaringGroup, element)
    return if (sound) OperandReference(value, indexSelected = true, category = category, key = key.trim()) else null
}

/** Whether [text] writes a semantic index anywhere, which is what routes it to [operandReferenceIn]. */
internal fun TrackedFactBase.writesASemanticIndex(text: String): Boolean {
    val keyword = CONDITION_OPERATOR_KEYWORDS[conditionLanguage]?.get(SEMANTIC_INDEX) ?: return false
    return semanticIndexUsesIn(text, conditionLanguage, keyword).isNotEmpty()
}

/** [this] inside the one bracket pair that encloses all of it, or unchanged where no pair does. */
private fun String.withoutEnclosingBrackets(): String {
    val tokens = tokenize(this)
    val first = tokens.firstOrNull() ?: return this
    val last = tokens.last()
    if (first.text != "[" || last.text != "]" || tokens.size < 2) {
        return this
    }
    var depth = 0
    tokens.forEachIndexed { at, token ->
        when (token.text) {
            "[" -> depth++
            "]" -> if (--depth == 0 && at != tokens.lastIndex) return this
        }
    }
    return substring(first.end, last.start)
}

/** The declaration one written operand names, whatever kind it is, or null where nothing resolves it. */
internal fun TrackedFactBase.declaredTargetOf(
    operand: String,
    declaringGroup: String,
    element: TrackedDeclaration,
): TrackedDeclaration? {
    val reference = singleReferenceOf(operand, declaringGroup, element) ?: return null
    val resolved = resolveEntity(declaringGroup, reference.replace("*", ""), element.origin)
    val target = (resolved as? Reference.Resolved)?.path ?: return null
    return declarationAt(target)
}

/**
 * The one reference [operand] names, stars kept, or null where it names none or several.
 *
 * Read through [operandReferenceIn], so a keyed operand names its VALUE only where the chain vouches for the
 * key. Counting references instead read `Claims/Amount For "X"` as `Claims/Amount` whatever the key was, and
 * `Claims/Amount For Code` as no operand at all, so a family judging the value named its own class past a key
 * A12 refuses first and stayed silent on a sound one (`adapter.laws.CheckerKeyedOperandAgreementTest`).
 */
internal fun TrackedFactBase.singleReferenceOf(
    operand: String,
    declaringGroup: String,
    element: TrackedDeclaration,
): String? = operandReferenceIn(operand, declaringGroup, element) {
    referencesIn(operand, conditionLanguage).singleOrNull()?.let { OperandReference(it) }
}?.reference

/** The one reference a bare entity spec reduces to, or null for text that is not one — no model read. */
private fun bareReferenceIn(text: String, language: ConditionLanguage): String? {
    if (!isBareEntitySpec(text)) {
        return null
    }
    return referencesIn(text, language).singleOrNull()
}

/**
 * Whether every token of [text] belongs to ONE entity spec, so a reader may reduce it to a reference.
 *
 * The test is deliberately over the TOKENS rather than "it names exactly one entity": the latter reduces
 * `{[Qty] + 1}` to the `Qty` in it, and a family that then typed the operand from that field would make a
 * claim about a result A12 computes from the whole expression. It is also what keeps a clause — a filter, a
 * scope, a semantic-index key, a category arrow — out of a reader that cannot compare one.
 */
internal fun isBareEntitySpec(text: String): Boolean {
    val tokens = tokenize(text)
    return tokens.isNotEmpty() && tokens.none {
        it.kind !in REFERENCE_TOKENS || (it.kind == TokenKind.PUNCTUATION && it.text !in SPEC_PUNCTUATION)
    }
}

/**
 * The model path [reference] names, or null when nothing resolves it or it crosses an unaddressed repetition.
 *
 * A repetition that neither the reference's own star nor the DECLARATION'S ITERATION addresses is
 * `MVK_NO_WILDCARD` or `MVK_WILDCARDS_REQUIRED` from the reference reader before any later gate is asked, so
 * a family that judged such an operand would name its own class on a model A12 refuses for something else —
 * the defect the corpus mutation found in the arity gates ([KERNEL-FINDINGS KF381]).
 *
 * **A SEMANTIC INDEX addresses a repetition exactly as a star does**, which is what [indexSelected] says. The
 * two differ in what they do with the level — a star iterates it, a key pins one row ([KF429]) — but both
 * leave the reference addressed, so a keyed read is a resolvable operand rather than a wildcard defect. The
 * discriminating pair is within one model: the same field named twice is `MVK_NO_WILDCARD` bare and
 * `MVK_DUPLICATE_PARAM1` with a key on both sides (`validate.laws.DuplicateOverlapCarrierSweepLawsTest`).
 *
 * @param indexSelected whether the operand carries a semantic-index key the chain admits. It addresses the
 *     level of the group declaring the index and no other ([KERNEL-FINDINGS KF478]): an unindexed repetition
 *     above it is addressed only by the rule's iteration, a star there being `MVK_SEMANTIC_INDEX_BELOW_WILDCARD`
 *     ([KERNEL-FINDINGS KF351]), and an index group that is the root or a fixed group addresses no repetition
 *     at all. A path with a second indexed group is never admitted.
 * @param everyLevelAddressed whether the read sits in a `Having` body, where the repetitions it crosses are the
 *     filter chain's question, asked after everything in the body ([ConditionComparison.inAFilterBody]).
 *
 * Neither flag has a default: a caller that forgot one read a keyed or a body read as unaddressed and stood
 * down silently, which is how two whole classes of operand gate went unjudged (DEV-API-GAPS DG92, DG95).
 */
internal fun TrackedFactBase.addressedTargetOf(
    reference: String,
    declaringGroup: String,
    element: TrackedDeclaration,
    indexSelected: Boolean,
    everyLevelAddressed: Boolean,
): String? {
    val target = (resolveEntity(declaringGroup, reference.replace("*", ""), element.origin)
        as? Reference.Resolved)?.path ?: return null
    if (everyLevelAddressed) {
        return target
    }
    val starred = starredLevelsOf(reference, declaringGroup, element) ?: return null
    val levels = iteratedLevels(target)
    // The key addresses the level of the group that declares the index, and no other: an index group that is
    // the root or a fixed group above the repetition leaves it unaddressed ([KERNEL-FINDINGS KF478]).
    val pinned = if (indexSelected) setOfNotNull(indexGroupOf(target)?.takeIf { it in levels }) else emptySet()
    // The TARGET's own group, not the DECLARING one: A12 asks whether the target's coordinates address the
    // level, and a rule's error field can reach into a repetition the rule is not declared in. Keying this on
    // the declaring group made every family behind it yield on an operand A12 admits there.
    val iterated = iterationGroupOf(element) ?: declaringGroup
    val unaddressed = levels.any {
        it !in starred + pinned && !iterated.isAtOrBelow(it) && !iteratesJointly(it, element)
    }
    return if (unaddressed) null else target
}

/**
 * The declared types this reader is CERTAIN A12 does not treat as string-LIKE.
 *
 * `FieldUtils.isLikeString` admits `IStringType` and `IPredefinedType`, our `StringType` and `CustomFieldType`;
 * this is the allow-list of the rest, so a typedef or an unspecified type is left unjudged. Two families ask
 * it of two different kernel methods that each call that one predicate.
 */
internal val PLAINLY_NON_STRING_TYPES = setOf(
    FieldType.NUMBER,
    FieldType.BOOLEAN,
    FieldType.CONFIRM,
    FieldType.ENUMERATION,
    FieldType.DATE_RANGE,
) + TEMPORAL_DECLARED_TYPES

/**
 * The declared types this reader is CERTAIN are not TEMPORAL at all, every member measured.
 *
 * An allow-list rather than the complement of [TEMPORAL_DECLARED_TYPES], so a `TypeDefType` or
 * `UnspecifiedType` — whose effective type this checker does not resolve — is left unjudged rather than
 * refused, which would be the one direction this checker may not take. Two families ask it: the shared date
 * ARGUMENT slot, whose kind gate stops here, and `ValueAsDate`, which adds the temporal kinds back because
 * its own gate wants one precision rather than one interface.
 */
internal val PLAINLY_NON_DATE_TYPES = setOf(
    FieldType.STRING, FieldType.NUMBER, FieldType.BOOLEAN, FieldType.CONFIRM, FieldType.ENUMERATION,
    FieldType.CUSTOM_FIELD, FieldType.DATE_RANGE,
)

/** The reference one call operand names, with its category, read through [operandReferenceIn]. */
internal fun TrackedFactBase.operandSpec(
    operand: CallOperand,
    declaringGroup: String,
    element: TrackedDeclaration,
): OperandReference? = categorySpecIn(operand.text, declaringGroup, element) { operand.path }

/**
 * The reference [text] names, with the category a `->` projects it onto, read through [operandReferenceIn] — so a
 * keyed text never reaches the category reader, which would read it with its key dropped.
 *
 * @param bare the reference a text writing neither an index nor a category names.
 */
internal fun TrackedFactBase.categorySpecIn(
    text: String,
    declaringGroup: String,
    element: TrackedDeclaration,
    bare: () -> String? = { bareReferenceIn(text, conditionLanguage) },
): OperandReference? = keyedReferenceIn(text, declaringGroup, element, keepsCategory = true) {
    categoryAccessesIn(text, conditionLanguage).singleOrNull()
        ?.let { (path, category) -> OperandReference(path, category = category) }
        ?: bare()?.let { OperandReference(it) }
}

/**
 * Whether reaching [path] from [declaringGroup] enters a repeating group the declaring group is not inside.
 *
 * Such a level is one A12 iterates while reading the reference, and the class it draws there pre-empts every
 * gate the operator carrying the reference would otherwise run. The declaring group is what BINDS a level:
 * a rule written inside a repetition reads that repetition's own row, so the same reference iterates or does
 * not depending on where the rule sits — which is why a reader that only counted repeating levels would call
 * an anchored read opaque and lose every class behind it.
 *
 * The charge rides the cursor, as on [iteratedLevels].
 */
internal fun TrackedFactBase.crossesARepetitionOutside(declaringGroup: String, path: String): Boolean =
    repeatableGroupPaths().any { candidate ->
        path.startsWith("$candidate/") &&
            !(declaringGroup == candidate || declaringGroup.startsWith("$candidate/"))
    }

internal fun TrackedFactBase.repeatingLevelsOnThePathTo(path: String): Int =
    repeatableGroupPaths().count { it == path || path.startsWith("$it/") }

/**
 * Whether this STRING declaration waives A12's per-cell value validation.
 *
 * `FieldUtils.isNoValueValidation` is a STRING-only member — every other declared type answers false — and
 * the gate that reads it, `checkIsValueValidation`, refuses the whole call with `MVK_INVALID_RAW_TYPE`. Two
 * families ask it, each at a different point of its own construct's order.
 */
internal fun DeclaredElement.waivesValueValidation(): Boolean = waivesValueValidationIn(body)

/** The same reading through the read barrier, sharing one implementation so the two cannot drift. */
internal fun TrackedDeclaration.waivesValueValidation(): Boolean = waivesValueValidationIn(body)

private fun waivesValueValidationIn(body: JsonObject?): Boolean {
    val declared = body?.get("fieldType") as? JsonObject ?: return false
    if (declared.namedFieldType() != FieldType.STRING) {
        return false
    }
    val string = FieldType.STRING.bodyIn(declared) ?: return false
    return (string["noValueValidation"] as? JsonPrimitive)?.booleanOrNull == true
}

/** Whether [tokens] spell one written-out number. */
internal fun isNumberLiteral(tokens: List<Token>): Boolean = numberLiteralScale(tokens) != null

/**
 * The fractional digits [tokens] spell as one written-out number, or null when they are not one.
 *
 * The shape rather than a token COUNT is what decides, because the tokenizer's number terminal is a digit
 * RUN and its sign is separate: `1` is one token, `1.00` three, `-1.00` four. A count-based test capped at
 * three — which this was — read a negative decimal as no number at all.
 */
internal fun numberLiteralScale(tokens: List<Token>): Int? {
    val unsigned = if (tokens.firstOrNull()?.text == "-") tokens.drop(1) else tokens
    if (unsigned.firstOrNull()?.kind != TokenKind.NUMBER) {
        return null
    }
    if (unsigned.size == 1) {
        return 0
    }
    if (unsigned.size != 3 || unsigned[1].text != "." || unsigned[2].kind != TokenKind.NUMBER) {
        return null
    }
    return unsigned[2].text.length
}

/**
 * A12's `expandToFieldList` for ONE declaration — the field itself, or every field under a group, in
 * declaration order. Null where the declaration is neither a field nor a group, which is the operand A12
 * refuses before any list law applies.
 *
 * **An empty list is a group with no field beneath it, and the callers do not agree on what that means**, so
 * this reports it as empty rather than as null: the operand-list families let such a group contribute nothing
 * and keep walking their other operands, while [expandToFields] turns it into A12's own refusal. Collapsing
 * the two here would make one operand's emptiness drop a verdict about the rest.
 *
 * [TrackedFactBase.allDeclarations] rather than `declarations`, and that is load-bearing: the subtree belongs
 * to a DIFFERENT declaration than the one a scoped pass is about, so a scoped `declarations()` expands the
 * group against that single declaration, finds nothing, and the family reads the empty result as "no field
 * beneath it" and drops its verdict (measured 2026-09-18, `ScopedWalkBranchDiscriminatorsTest`).
 */
internal fun TrackedFactBase.fieldsUnder(declared: TrackedDeclaration): List<TrackedDeclaration>? =
    when (declared.declaredKind) {
        DeclaredKind.FIELD -> listOf(declared)
        DeclaredKind.GROUP -> allDeclarations()
            .filter { it.declaredKind == DeclaredKind.FIELD && it.path.startsWith("${declared.path}/") }
        else -> null
    }

/**
 * The fields A12's `expandToFieldList` would take for one operand, addressed by path.
 *
 * Null where the operand names a group with no field beneath it, which A12 refuses under its own class — the
 * one caller of [fieldsUnder] that reads an empty expansion as a refusal rather than as nothing to say.
 */
internal fun TrackedFactBase.expandToFields(path: String): List<TrackedDeclaration>? =
    declarationAt(path)?.let { fieldsUnder(it) }?.takeIf { it.isNotEmpty() }
