package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CallOperand
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionLanguage
import io.github.mbackschat.a12.dm.consistency.syntax.categoryAccessesIn
import io.github.mbackschat.a12.dm.consistency.syntax.Token
import io.github.mbackschat.a12.dm.consistency.syntax.TokenKind
import io.github.mbackschat.a12.dm.consistency.syntax.referencesIn
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
 * A leading `/` makes the path absolute. A single bare name keeps the two-tier treatment of [resolve] —
 * the declaring group first, then the model-wide field short name when the model allows it — while a
 * multi-segment path is positional and gets no fallback. A trailing `*` or `.*` is a repetition wildcard
 * rather than part of a name, so it is stripped before matching and kept in the reported text.
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
): Reference {
    // A mounted declaration still spells its provider's own root, which the mount replaced. Rehosting the
    // written path is what the Kernel does before integration, so the reference names the element that
    // moved with it rather than one the host happens to have (KF3).
    val hosted = origin.rehost(written)
    val steps = hosted.split('/').filter { it.isNotEmpty() }.map { it.withoutRepetitionSuffix() }
    val absolute = hosted.startsWith("/")
    // The two-tier short-name treatment is for a single BARE LEAF. A starred segment names a repeatable
    // group's iteration rather than a leaf, and the Kernel gates its fallback per `idNameRef`, so `Lines*`
    // must not reach a model-wide field index that could resolve it to an unrelated field of that name.
    if (!absolute && steps.size == 1 && steps.single() != UP && !hosted.contains('*')) {
        return resolve(declaringGroup, steps.single(), origin)
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
    if (!boundInProvider && !fieldRefByShortNameAllowed) {
        return Reference.Undeclared(candidate)
    }
    // The denominator is this MOUNT INSTANCE's subtree, not the provider's own declarations. The provider is
    // fully expanded before it is bound (KF3), so a field its own nested include contributed is inside its
    // index; and one provider mounted at two points is two separate indexes, so the second copy must not make
    // the first ambiguous. Keying on the authoring model instead got both cases wrong, in the direction that
    // rejects a model A12 accepts — measured 2026-09-12 through `ModelLoader.loadAndCheck` on both shapes.
    // Named ONCE and then narrowed, rather than as two arms each naming the list: written as a two-arm `if`,
    // one arm can silently stop honouring the variant deletion while the other keeps it, and a mutant doing
    // exactly that survived every control here (2026-09-13) because the provider-bound arm needs a mounted
    // model with its own flag on to be reached at all. One expression cannot diverge from itself.
    // A MODEL_ROOT mount places several prefixes and is still ONE instance, so the denominator is the union
    // of what this mount placed rather than a single subtree.
    val scope = resolvableElements.let { visible ->
        if (boundInProvider) {
            visible.filter { element -> origin.mountedPrefixes.values.any { element.path.startsWith("$it/") } }
        } else {
            visible
        }
    }
    val matches = scope.filter { it.declaredKind == DeclaredKind.FIELD && it.path.substringAfterLast('/') == name }
    return when (matches.size) {
        0 -> Reference.Undeclared(candidate)
        1 -> Reference.Resolved(matches.single().path)
        else -> Reference.Ambiguous(name, matches.map { it.path })
    }
}



/**
 * The repeatable groups [path] lies inside, outermost first — the levels A12 iterates for a plain reference.
 *
 * A reference's index vector carries one entry per level, and every repeatable level it crosses without a
 * marker is A12's `ITERATION`. That is the quantity three families ask for, so it is stated once here.
 */
internal fun PreparedModel.crossedRepetitions(path: String): List<DeclaredElement> = elements
    .filter { it.isRepeatableGroup && path.startsWith("${it.path}/") }
    .sortedBy { it.path.length }

/**
 * The same levels plus [path] itself when it names a repeatable group.
 *
 * A reference that ENDS at a repeatable group iterates it too — `CurrentRepetition(Legs)` rides the same
 * marker a field beneath `Legs` does — so a walk that only looks at ancestors misses the group carrier.
 */
internal fun PreparedModel.iteratedLevels(path: String): List<String> =
    iteratedLevelsIn(repeatableGroupPaths(), path)

/**
 * The same walk through the read barrier.
 *
 * **The charge falls out of using the cursor rather than being written down**, which is what makes it right:
 * the repeatable-group list is built through [TrackedDeclaration.isRepeatable], so every group's body this
 * walk actually reads is charged, and a future change to what it reads charges itself. A hand-written charge
 * beside a delegating call is the shape that goes stale and under-charges — the unsound direction.
 */
internal fun TrackedFactBase.iteratedLevels(path: String): List<String> =
    iteratedLevelsIn(repeatableGroupPaths(), path)

private fun iteratedLevelsIn(repeatableGroups: List<String>, path: String): List<String> = repeatableGroups
    .filter { it == path || path.startsWith("$it/") }
    .sortedBy { it.length }

private fun PreparedModel.repeatableGroupPaths(): List<String> = elements
    .filter { it.isRepeatableGroup }
    .map { it.path }

private fun TrackedFactBase.repeatableGroupPaths(): List<String> = declarations()
    .filter { it.isRepeatableGroup }
    .map { it.path }

/**
 * The resolved paths [written] stars, or null when a star lands anywhere but on a repeatable group.
 *
 * A star on a non-repeatable level is `MVK_INVALID_WILDCARD` and outruns every slot gate ([KF328]), so such
 * a reference belongs to [repetitionWildcardFindings] and is not judged by a scope law at all — including
 * the case where the starred prefix does not resolve, which is the reference family's.
 */
internal fun PreparedModel.starredLevelsOf(
    written: String,
    declaringGroup: String,
    element: DeclaredElement,
): Set<String>? = starredLevelsIn(
    written,
    resolve = { prefix -> resolveEntity(declaringGroup, prefix, element.origin) },
    // Asked per starred segment, so it stays a direct lookup rather than a list of every repeatable group:
    // the common reference carries no star at all and must pay for no walk.
    repeats = { path -> elements.any { it.path == path && it.isRepeatableGroup } },
)

/**
 * The same walk through the read barrier; both of its reads charge themselves through the cursor.
 *
 * The repeatability lookup charges TIGHTER here than the untracked arm reads: [TrackedFactBase.declarationAt]
 * takes the set's shape and then one declaration's content, where a repeatable-group list would have charged
 * every group's body. Narrower than what is read would be unsound; narrower than what the other ARM reads is
 * just a better question.
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
 * A marked reference re-enters the walk with the enclosing rule's error field restored
 * (`CheckEntitySpecImpl.checkCommon`), which no reader here can reproduce from text, so a text carrying one
 * is left alone rather than judged against the wrong target. Three families make that decision, which is why
 * the spelling is stated once.
 */
internal const val OUTER_MARKER = "$"

/** The index field [group] declares, or null when it declares none — A12 reads an empty name as none. */
internal fun indexFieldNameOf(group: DeclaredElement): String? = indexFieldNameIn(group.body)

/** The same reading through the read barrier, sharing one implementation so the two cannot drift. */
internal fun indexFieldNameOf(group: TrackedDeclaration): String? = indexFieldNameIn(group.body)

private fun indexFieldNameIn(body: JsonObject?): String? =
    (body?.get("indexFieldName") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

/** The `fieldType.type` discriminator a field declares, or null where it declares none this reader can see. */
internal fun DeclaredElement.declaredFieldType(): String? = declaredFieldTypeIn(body)

/** The same reading through the read barrier, sharing one implementation so the two cannot drift. */
internal fun TrackedDeclaration.declaredFieldType(): String? = declaredFieldTypeIn(body)

private fun declaredFieldTypeIn(body: JsonObject?): String? =
    ((body?.get("fieldType") as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull

/**
 * The base-type classes `IFieldType.matchBaseType` sorts declarations into.
 *
 * Text and Custom share one because `IStringType` and `IPredefinedType` each accept the other; every other
 * class accepts only its own. The names are the ones a finding prints, so they read as a model author would
 * say them rather than as the wire discriminator does.
 */
internal enum class BaseTypeClass(val spoken: String) {
    TEXT("text"),
    NUMBER("number"),
    ENUMERATION("enumeration"),
    DATE("date"),
    YES("confirm"),
    YES_NO("boolean"),
    DATE_RANGE("date range"),
    DATE_FRAGMENT("date fragment"),
}

/**
 * No `CustomFieldType` row, deliberately: A12 gives a custom declaration the TEXT base type, but whether
 * each comparison arm behind [baseTypeClass] then treats it as a string is unmeasured on those carriers, and
 * the three consumers all document that silence. The row was present spelled `CustomType`, a token no stored
 * model carries, so the silence held by accident; leaving it out states it.
 */
private val BASE_TYPE_OF = mapOf(
    "StringType" to BaseTypeClass.TEXT,
    "NumberType" to BaseTypeClass.NUMBER,
    "EnumerationType" to BaseTypeClass.ENUMERATION,
    "DateType" to BaseTypeClass.DATE,
    "ConfirmType" to BaseTypeClass.YES,
    "BooleanType" to BaseTypeClass.YES_NO,
    "DateRangeType" to BaseTypeClass.DATE_RANGE,
    "DateFragmentType" to BaseTypeClass.DATE_FRAGMENT,
)

/**
 * Which base-type class this field's declaration falls into, or null where this reader cannot say.
 *
 * Null covers `TypeDefType` and `UnspecifiedType`, whose effective type is a definition body this checker
 * declares as a coverage gap rather than reads — so a law over base types leaves such a field unjudged
 * rather than guessing at it.
 */
internal fun DeclaredElement.baseTypeClass(): BaseTypeClass? = BASE_TYPE_OF[declaredFieldType()]

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
    "StringType",
    "BooleanType",
    "ConfirmType",
    "EnumerationType",
    "CustomFieldType",
    "DateRangeType",
) + TEMPORAL_DECLARED_TYPES

/** The token kinds one entity spec may be made of; anything else makes the text an expression. */
private val REFERENCE_TOKENS = setOf(TokenKind.NAME, TokenKind.QUOTED_NAME, TokenKind.PUNCTUATION)

/**
 * The punctuation an entity spec may carry: its brackets and its path steps.
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
 * (`docs/PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md` "a starred operand is never typed").
 */
private val SPEC_PUNCTUATION = setOf("[", "]", "/", ".")

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
 * ([KERNEL-FINDINGS KF381]).
 */
internal fun PreparedModel.bareReferenceField(
    text: String,
    declaringGroup: String,
    element: DeclaredElement,
): DeclaredElement? {
    val reference = bareReferenceIn(text, conditionLanguage) ?: return null
    val target = addressedTargetOf(reference, declaringGroup, element) ?: return null
    return elements.firstOrNull { it.path == target && it.declaredKind == DeclaredKind.FIELD }
}

/** The same reading through the read barrier, over the tracked arm of the chain it composes. */
internal fun TrackedFactBase.bareReferenceField(
    text: String,
    declaringGroup: String,
    element: TrackedDeclaration,
): TrackedDeclaration? {
    val reference = bareReferenceIn(text, conditionLanguage) ?: return null
    val target = addressedTargetOf(reference, declaringGroup, element) ?: return null
    return declarationAt(target)?.takeIf { it.declaredKind == DeclaredKind.FIELD }
}

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
 */
internal fun PreparedModel.addressedTargetOf(
    reference: String,
    declaringGroup: String,
    element: DeclaredElement,
): String? {
    val target = (resolveEntity(declaringGroup, reference.replace("*", ""), element.origin)
        as? Reference.Resolved)?.path ?: return null
    val starred = starredLevelsOf(reference, declaringGroup, element) ?: return null
    return addressedTargetIn(target, starred, iterationGroupOf(element) ?: declaringGroup, iteratedLevels(target))
}

/** The same question through the read barrier, over the tracked arm of each helper it composes. */
internal fun TrackedFactBase.addressedTargetOf(
    reference: String,
    declaringGroup: String,
    element: TrackedDeclaration,
): String? {
    val target = (resolveEntity(declaringGroup, reference.replace("*", ""), element.origin)
        as? Reference.Resolved)?.path ?: return null
    val starred = starredLevelsOf(reference, declaringGroup, element) ?: return null
    return addressedTargetIn(target, starred, iterationGroupOf(element) ?: declaringGroup, iteratedLevels(target))
}

/**
 * @param iterated the TARGET's own group, not the DECLARING one: A12 asks whether the target's coordinates
 *     address the level, and a rule's error field can reach into a repetition the rule is not declared in.
 *     Keying this on the declaring group made every family behind it yield on an operand A12 admits there.
 */
private fun addressedTargetIn(
    target: String,
    starred: Set<String>,
    iterated: String,
    levels: List<String>,
): String? {
    val unaddressed = levels.any { it !in starred && !(iterated == it || iterated.startsWith("$it/")) }
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
    "NumberType",
    "BooleanType",
    "ConfirmType",
    "EnumerationType",
    "DateRangeType",
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
    "StringType", "NumberType", "BooleanType", "ConfirmType", "EnumerationType",
    "CustomFieldType", "DateRangeType",
)

/**
 * The operand reduced to its written spec and the category it names, or null where neither can be read.
 *
 * A category access is not a path with a suffix — the reader that finds one returns the path and the category
 * separately — so a family that only asked for [CallOperand.path] would see nothing at all where A12 sees a
 * category and refuses the call.
 */
internal fun PreparedModel.operandSpec(operand: CallOperand): Pair<String, String?>? =
    operandSpecIn(operand, conditionLanguage)

/** The same reading through the read barrier — it reads the condition language and nothing else. */
internal fun TrackedFactBase.operandSpec(operand: CallOperand): Pair<String, String?>? =
    operandSpecIn(operand, conditionLanguage)

private fun operandSpecIn(operand: CallOperand, language: ConditionLanguage): Pair<String, String?>? {
    categoryAccessesIn(operand.text, language).singleOrNull()?.let { (path, category) ->
        return path to category
    }
    return (operand.path ?: return null) to null
}

/**
 * Whether reaching [path] from [declaringGroup] enters a repeating group the declaring group is not inside.
 *
 * Such a level is one A12 iterates while reading the reference, and the class it draws there pre-empts every
 * gate the operator carrying the reference would otherwise run. The declaring group is what BINDS a level:
 * a rule written inside a repetition reads that repetition's own row, so the same reference iterates or does
 * not depending on where the rule sits — which is why a reader that only counted repeating levels would call
 * an anchored read opaque and lose every class behind it.
 */
internal fun PreparedModel.crossesARepetitionOutside(declaringGroup: String, path: String): Boolean =
    crossesARepetitionOutsideIn(repeatableGroupPaths(), declaringGroup, path)

/** The same question through the read barrier; the charge rides the cursor, as on [iteratedLevels]. */
internal fun TrackedFactBase.crossesARepetitionOutside(declaringGroup: String, path: String): Boolean =
    crossesARepetitionOutsideIn(repeatableGroupPaths(), declaringGroup, path)

private fun crossesARepetitionOutsideIn(
    repeatableGroups: List<String>,
    declaringGroup: String,
    path: String,
): Boolean = repeatableGroups.any { candidate ->
    path.startsWith("$candidate/") &&
        !(declaringGroup == candidate || declaringGroup.startsWith("$candidate/"))
}

/**
 * How many groups on the path to [path], itself included, repeat — one star each is what A12 wants.
 *
 * Two families need it to decide whether an operand's STARRING is already right, because both of A12's
 * wildcard-validity classes outrun the gate they are about to report: a repetition read without a star is
 * `MVK_NO_WILDCARD` and a star on a level that does not repeat is `MVK_INVALID_WILDCARD`, so naming any
 * later class on such an operand would send the author to the wrong repair.
 */
internal fun PreparedModel.repeatingLevelsOnThePathTo(path: String): Int =
    elements.count {
        it.isRepeatableGroup &&
            (it.path == path || path.startsWith("${it.path}/"))
    }

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
    if ((declared["type"] as? JsonPrimitive)?.contentOrNull != "StringType") {
        return false
    }
    val string = declared["StringType"] as? JsonObject ?: return false
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
 * The fields A12's `expandToFieldList` would take for one operand — the field itself, or every field under a
 * group, in declaration order.
 *
 * Null where the operand names a group with no field beneath it, which A12 refuses under its own class.
 */
internal fun PreparedModel.expandToFields(path: String): List<DeclaredElement>? {
    val target = elements.firstOrNull { it.path == path } ?: return null
    if (target.declaredKind == DeclaredKind.FIELD) {
        return listOf(target)
    }
    return elements
        .filter { it.declaredKind == DeclaredKind.FIELD && it.path.startsWith("${target.path}/") }
        .takeIf { it.isNotEmpty() }
}
