package io.github.mbackschat.a12.dm.consistency

/**
 * A family of required checks, named so a report can say which ones actually ran.
 *
 * The enum is the coverage denominator's live half. Its other half — the pinned profile's full list of
 * required families — does not exist yet, which is exactly why every report currently carries a gap saying
 * so: "the reference check completed" must never read as "the analysis completed".
 */
enum class CheckFamily {
    /** A rule's error target and a computation's computed field resolve to a declared element. */
    DECLARED_REFERENCES,

    /** Every entity named in a rule's condition or a computation's operations resolves. */
    CONDITION_REFERENCES,

    /**
     * A rule's and a computation's own declaration slots — the reserved name prefix and the error messages —
     * and the `header.locales` set those messages answer to, which decides what A12 collects from them.
     */
    DECLARATION_SLOTS,

    /**
     * A computation's `computationAlternatives` — that it carries work at all, and that each of several
     * alternatives carries its own precondition.
     */
    COMPUTATION_ALTERNATIVES,

    /** A rule's `errorCondition` and `errorEntityRelPath` are present and carry text. */
    RULE_CONDITION_AND_TARGET,

    /** No group holds two children of the same name, whatever kinds they are. */
    ELEMENT_NAME_UNIQUENESS,

    /** A group's `indexFieldName` and `sortFields[].name` each name one of its own fields. */
    GROUP_ORDERING_REFERENCES,

    /**
     * …and the field each of them names is one A12 will accept as an ordering key — never a string declared
     * `noValueValidation`. Its own constant because resolving and being usable are different claims, and a
     * report that merged them would let a resolution fix read as a type approval.
     */
    GROUP_ORDERING_FIELD_TYPE,

    /**
     * A computation's `roundingMode` and each alternative's `toleranceRangeOp` — that both spell a value
     * A12 knows, and that the pair they form is one A12 permits.
     */
    COMPUTATION_ROUNDING,

    /**
     * A computation's `errorCodesToSuppress` — that it renders into a pragma A12's parser can read, and that
     * the one code it may carry is the one A12 lets a computation suppress.
     */
    SUPPRESSED_WARNINGS,

    /**
     * A `variantFilter` — that A12 can bind it, on every carrier that declares one, and WHICH declarations
     * it takes out of A12's check, which is a rules-and-computations question because on a field the same
     * spelling deletes the field instead. The family is unusual in that its main output is a coverage gap
     * rather than a finding, because A12 accepts the models that second half concerns.
     */
    VARIANT_FILTER,

    /**
     * A rule's `guiPatterns` and `variantErrorMessages` — that A12's reader can BIND them, and nothing else.
     * A12 checks neither member's CONTENT, so an undocumented `guiPatterns` key and a variant name no filter
     * mentions are both admitted; the family exists because the shapes their readers refuse make the whole
     * model unloadable, and a clean report for one of those would be read as a verdict.
     */
    RULE_PRESENTATION_MEMBERS,

    /**
     * That every member A12 binds to TEXT actually holds text — one law across all three carriers that own
     * such a member. A container there refuses A12's reader outright, so the model never loads; a non-text
     * scalar is coerced and is not this family's business, nor is a blank or absent member, which the
     * families owning those members already judge.
     */
    STRING_SLOT_BINDING,

    /**
     * That every field HAS a `fieldType`, that it names a type A12 declares, and that it carries the body
     * that type reads and constructs from — the discriminated union, never its contents. Two tiers ride one
     * constant because they are one member's law: a shape A12's reader refuses never loads, while a missing
     * member or an empty body loads and then fails as A12 builds. The message says which.
     */
    FIELD_TYPE_BINDING,

    /**
     * That a date field's declared CONFIG agrees with its declared FORMAT — the pre-1900 check needs a
     * year-bearing format and a partial `datePrecision` needs a complete pure date. Both are A12's own
     * consistency codes rather than reader refusals, and both are asked only over the format vocabulary A12
     * is measured to admit: an unrecognised format leaves this family silent, since the admitted set is a
     * probe's result and not a closed one.
     */
    TEMPORAL_CONFIG,

    /**
     * That a declared GROUP or FIELD name is one A12's own alphabet takes. Two laws rather than one: a group
     * refuses a leading underscore a field admits and admits a non-ASCII letter a field refuses, so each
     * carrier is asked its own question and a carrier with no measurement is not asked. Non-ASCII characters
     * and the group's 60-character limit are left to A12.
     */
    ELEMENT_NAME_ALPHABET,

    /**
     * That an Enumeration's categories pair with its values. The pairing is POSITIONAL, so the law is equal
     * cardinality rather than set membership, and A12 names each unpaired element separately — a value with
     * no token and a token with no value are different codes, and both are reported once per element.
     */
    ENUM_CATEGORY_ALIGNMENT,

    /**
     * That every declared ENUM VALUE is spelled in the characters `content.modelConfig.supportedCharacters`
     * admits. The restriction is a runtime constraint A12 bakes into generated code, and `checkConsistency`
     * re-runs the generator — so it is an authoring gate too. Enum values are the only declared strings it
     * judges, measured against a name, a message, a category token and a string field's hint list that are
     * all admitted — the last of those reads as in scope from A12's source and is dead on `30.8.1`.
     */
    ENUM_VALUE_CHARACTERS,

    /**
     * That a declared string `pattern` passes A12's admission gate — its TEXTUAL half. The gate blacklists
     * source-character adjacencies rather than the expression they spell, so an escaped metacharacter
     * before a quantifier is refused like an unescaped one. Its other half, Java regex compilability,
     * belongs to one engine and is left to A12 with a gap saying so.
     */
    STRING_PATTERN_ADMISSION,

    /**
     * That a declared string type's `pattern` and its own `errorMessage` answer for each other — A12 refuses
     * either alone. A message counts only where it gives A12 a non-blank text for a DECLARED locale, so an
     * empty list, a blank text and a text for an undeclared locale are all no message at all; once some
     * declared locale is answered and another is not, the untranslated half is reported separately and
     * independently of the pattern.
     */
    STRING_PATTERN_MESSAGE,

    /**
     * That a declared string type's own parameters agree with each other — what `noValueValidation`
     * excludes, the hint list it forbids, the line-break flag it requires, and the two length laws beside
     * them. The members are read the way A12 encodes them, so the sentinel `-1` is NO bound rather than a
     * negative one, and the laws are reported independently because A12 reports them so.
     */
    STRING_TYPE_PARAMETERS,

    /**
     * That a declared number type's fractional-digit pair and its value range are ones A12 accepts. The
     * digit law is not a plain comparison of the declared members: an absent one reads as 0 and a negative
     * maximum takes the pair out of the check. The declared SCALE rides here too, as the build-tier verdict
     * A12 has no code for.
     */
    NUMBER_TYPE_DIGITS,

    /**
     * That an enumeration's VALUE tokens are distinct and that its per-locale labels are finished. Almost
     * nothing about a token's spelling is A12's business here — an empty one, a spaced one and a very long
     * one are all admitted — and the label law is that once ANY value carries an entry for a DECLARED
     * locale, every value owes a non-blank text for every declared locale.
     */
    ENUM_VALUE_LABELS,

    /**
     * That no condition NAMES a group carrying no field anywhere beneath it. Declaring one is legal, so this
     * judges the reference and not the declaration, and emptiness is over the subtree — a group whose only
     * content is a populated subgroup is admitted wherever an empty one is refused.
     */
    EMPTY_GROUP_OPERAND,

    /**
     * That a rule's condition NAMES the field its error is attached to. A resolvable target is necessary and
     * not sufficient; naming a group the target sits in counts, and naming a field inside a target group
     * does not.
     */
    ERROR_FIELD_REFERENCE,

    /**
     * That no group-list quantifier names one group twice, or names a group and something beneath it. The
     * gate is per CALL rather than per condition, so the family reads the parsed argument list; a starred
     * operand is outside it, and an operand that does not resolve pre-empts it.
     */
    GROUP_LIST_OPERANDS,

    /**
     * …and that no group-list quantifier names a ROOT group its carrier does not admit. Its own constant
     * because repeating an operand and naming a root are different claims about one list: three carriers
     * refuse a root outright and three admit it as the sole operand, and a starred root — exempt from the
     * duplicate comparison — is fully inside this one.
     */
    GROUP_LIST_ROOT_OPERAND,

    /**
     * …and that no group predicate that refuses a FIELD is handed one. Its own constant because its carrier
     * set is neither of the others': the five group-list quantifiers ADMIT a field operand, and only the
     * count and the two scalar presence predicates refuse one.
     */
    GROUP_LIST_OPERAND_KIND,

    /**
     * That no reference puts a repetition wildcard on something with no repetitions. A reference-level law
     * rather than an operand-level one, because A12 answers it ahead of both the slot's own wildcard gate and
     * the entity gate, and with a class fixed by WHAT the star lands on rather than by where it stands: a
     * name that names no repetition, the rule's own group, or a relative up-step.
     */
    REPETITION_WILDCARD_TARGET,

    /**
     * That a computation computes into a FIELD. A rule's error target resolves the same way and ADMITS a
     * group, so the two slots are measured apart rather than sharing a rule; the Kernel refuses a
     * group-valued computed field at the BUILD tier, so the report says the model is not buildable rather
     * than naming A12's key for it.
     */
    COMPUTED_TARGET_KIND,

    /**
     * What `Length` over a RAW-TYPE field may look like. A STRING declaring `noValueValidation` admits its
     * length only as the whole rule condition `Length(f) > c` or the mirror — the max-length declaration A12
     * lifts out of the generated validation — and a computation may not take it in any member. The other
     * value windows A12 closes on such a field are NOT reported: deciding them needs per-operator value
     * positions, which this checker does not reproduce.
     */
    RAW_TYPE_LENGTH,

    /**
     * That a computation does not compute into the field a repeatable group is INDEXED by. A12 answers with
     * a key of its own rather than refusing to build, and the law follows the resolved TARGET rather than
     * the computation's own group. A `sortFields` entry is named by the same kind of member and carries no
     * such restriction, which is the control that keeps the two apart.
     */
    COMPUTED_TARGET_INDEX_FIELD,

    /**
     * The predicates a COMPUTATION's precondition may not use. A12 normalizes a calculation rule's condition
     * and refuses an application condition or `RepetitionNotUnique` there, while a RULE condition uses both
     * freely. Its third prohibited feature has no English spelling, so this family does not report it.
     */
    COMPUTATION_PRECONDITION_FEATURES,

    /**
     * That the model's computations do not depend on each other in a CIRCLE. A12 sorts the calculated fields
     * topologically and refuses a graph it cannot order. A read in a PRECONDITION builds an edge just as an
     * operation read does, and the two ends may sit in different groups. A one-node circle — a computation
     * reading its own target — is A12's other law and is deliberately not reported here.
     */
    COMPUTATION_CYCLE,

    /**
     * That an error message only names entities its own declaration already reads. The membership set is the
     * carrier's — a rule's condition references, a computation's target plus what its own members read — and
     * only the PLAIN `$Name$` and `$Name.value$` forms are read, because every other parameter form has a
     * gate and a class of its own.
     */
    MESSAGE_PARAMETER_REFERENCE,

    /**
     * That an error message's TEXT carries no control character. A12 splits the law in two — a line break
     * has its own class — and runs them in ORDER, so a text carrying both draws only the line-break one.
     * A repertoire restriction is a different law and is not this family's.
     */
    MESSAGE_TEXT_CHARACTERS,

    /**
     * That every DSL-bearing member can be TOKENIZED — the syntax layer's first phase, and the only one
     * decidable without a grammar. Whether the tokens then form a condition A12's parser accepts is a
     * separate question this family does not answer.
     */
    CONDITION_LEXICALITY,

    /**
     * That no DSL-bearing member ENDS before it is finished, in the one shape decidable without a grammar: a
     * trailing infix operator or symbolic comparison in text whose delimiters are all closed, or a comma
     * inside an open argument list. Its own constant rather than part of the lexical family because it is a
     * grammar-adjacent claim about how the text ENDS, not a claim about its characters.
     *
     * Deliberately narrower than "ends on an operator": with an unclosed delimiter earlier in the text A12
     * answers the same trailing operator with a different code, so this family reports nothing there.
     */
    CONDITION_COMPLETENESS,

    /**
     * That no DSL-bearing member mixes `And` with `Or` inside one bracket PAIR, which A12 refuses outright
     * rather than resolving by precedence. Separate from the two families above because it is the only one of
     * the three whose verdict does not depend on where A12's parser stands: the refusal is a semantic action
     * on a region the token stream already delimits.
     *
     * A level is a pair and not a depth — two filters one parenthesis deep in different calls are
     * independent — and a filter's condition gets its own frame, since the grammar gives it no delimiter of
     * its own.
     */
    CONNECTIVE_AGREEMENT,

    /**
     * That every DSL-bearing member FORMS a condition — or, for a computation's `operation`, an expression.
     * The last of the syntax phases and the only one that needs a parser, which is why it reports the two
     * codes the earlier families cannot: a token the grammar cannot use where it stands, and a text that ends
     * with a bracket still open.
     *
     * Its parser is deliberately looser than A12's grammar, checking the shape the productions share rather
     * than each operator's own argument arity. A wrong-arity call is therefore not reported, which the
     * report's gap says: over-rejection is the one direction this checker may not take, and a per-operator
     * reproduction would be a second copy of the catalog.
     */
    CONDITION_GRAMMAR,
}

/**
 * A portion of the input that could not be examined, and why.
 *
 * There is deliberately no "which family" field. A gap is not one-to-one with a family — an unexpanded
 * mount blocks both reference families at once, and the unread declared members limit one without stopping
 * it — so a single slot could only ever be filled by guessing. [ConsistencyReport.checksCompleted] already
 * says which families ran; [reason] says what this particular gap costs.
 *
 * @param at the declaration it concerns, absent when the gap is the whole analysis.
 */
data class CoverageGap(val at: String?, val reason: String)

/**
 * What a consistency check produced.
 *
 * [findings] and [diagnostics] are deliberately separate: a finding is A12's verdict on a declaration, a
 * diagnostic is the checker saying it could not read something. Folding them together is the conflation
 * `ModelLoader.loadAndCheck` makes on the Java side, where an unreadable source arrives on the same channel
 * as an invalid declaration and a caller cannot tell them apart.
 *
 * @param modelId the source's own `header.id`, absent when admission could not read one.
 * @param checksCompleted the families that ran to completion — evidence, not a claim of sufficiency.
 */
data class ConsistencyReport(
    val modelId: String?,
    val status: ConsistencyStatus,
    val findings: List<Finding>,
    val diagnostics: List<Diagnostic>,
    val checksCompleted: Set<CheckFamily>,
    val gaps: List<CoverageGap>,
)

/**
 * Check one model source.
 *
 * The result can be INVALID or INCONCLUSIVE and never VALID, because no evidence exists that the pinned
 * profile's required checks ran — only that the one implemented family did. That bar lifts with the coverage
 * registry, not with an added enum constant (PORTABLE-CONSISTENCY-PROPOSAL §7, "the first one-check slice
 * cannot emit VALID by construction").
 *
 * An unreadable source yields a report rather than a raised exception, with its failure stated at the source
 * level rather than attributed to a rule that was never read.
 */
fun check(source: ModelSource): ConsistencyReport {
    val admitted = when (val admission = admit(source)) {
        is Admission.Rejected -> return ConsistencyReport(
            modelId = null,
            status = ConsistencyStatus.Inconclusive,
            findings = emptyList(),
            diagnostics = listOf(admission.diagnostic),
            checksCompleted = emptySet(),
            gaps = listOf(CoverageGap(null, "the source could not be admitted, so nothing was checked")),
        )
        is Admission.Admitted -> admission
    }
    return check(admitted, providers = emptyMap(), inherited = emptyList())
}

/**
 * Check one already-admitted entry model against the [providers] its includes may name.
 *
 * [inherited] are diagnostics the caller already holds about the source set itself — a source that could not
 * be admitted, an id supplied twice. They ride into the report because they are usually the CAUSE of a mount
 * this analysis then reports as unexpanded, and a consumer shown only the consequence cannot act on it.
 */
internal fun check(
    admitted: Admission.Admitted,
    providers: Map<String, Admission.Admitted>,
    inherited: List<Diagnostic>,
): ConsistencyReport {
    val prepared = prepare(admitted, providers)
    prepared.unsupportedConditionLanguage?.let { code ->
        // A12 refuses the model rather than falling back to a language, so there is no reading of its
        // conditions to have — every keyword question would be answered in a language A12 never applies.
        return ConsistencyReport(
            modelId = admitted.modelId,
            status = ConsistencyStatus.Invalid,
            findings = listOf(
                Finding(
                    FindingCategory.A12_CONSTRAINT,
                    FindingSeverity.ERROR,
                    CK_MODEL_NOT_BUILDABLE,
                    "`$code` is not a condition language A12 supports, so the model cannot be used at all",
                ),
            ),
            diagnostics = inherited + prepared.diagnostics,
            checksCompleted = emptySet(),
            gaps = prepared.gaps() + UNIMPLEMENTED_FAMILIES,
        )
    }
    val unmounted = prepared.unexpandedMounts.map {
        CoverageGap(
            at = it.at,
            reason = "this group mounts `${it.alias}` and the mount is not expanded here (${it.reason}), so " +
                "the fact base is incomplete and no reference check can run over this source",
        )
    }
    if (unmounted.isNotEmpty()) {
        // The fact base is INCOMPLETE, so "no declared element matches" is not a verdict this checker may
        // reach: the elements are declared, in a model this source does not carry. Reporting the references
        // anyway would reject a model A12 accepts, which is the one direction a checker must never take.
        return ConsistencyReport(
            modelId = admitted.modelId,
            status = ConsistencyStatus.Inconclusive,
            findings = emptyList(),
            diagnostics = inherited + prepared.diagnostics,
            checksCompleted = emptySet(),
            gaps = prepared.gaps() + unmounted + UNIMPLEMENTED_FAMILIES,
        )
    }
    val declared = prepared.declaredReferences()
    // A12 examines a rule's error TARGET first and stops the whole declaration when it does not resolve —
    // the condition's own class and both error-text classes are withheld, leaving the target's two codes
    // alone (KF339). Applied here rather than inside each family because it is A12 declining to examine the
    // declaration, not a rule any one family owns; a family-local version would have to be repeated into
    // every family added later, and the one that forgot would over-reject silently.
    val stopped = prepared.declarationsWithABrokenErrorTarget(declared)
    // …and inside a condition A12 resolves before it judges syntax, answering an unresolvable name with its
    // own class and withholding every syntax one (KF340). Four families read that same text, so without this
    // any condition carrying two defects — the normal state of one being written — draws a class A12
    // withheld.
    val resolvedFirst = prepared.declarationsWithAnUnresolvableConditionReference()
    val judged = (declared.mapNotNull { it.target.asFinding(it.role, it.declaredAt) } +
        prepared.conditionReferences().mapNotNull {
            it.target.asFinding(ReferenceRole.CONDITION_ENTITY, it.declaredAt, it.member)
        } + prepared.declarationSlotFindings() + prepared.computationAlternativeFindings() +
        prepared.computationRoundingFindings() + prepared.suppressedWarningFindings() +
        prepared.ruleConditionAndTargetFindings() + prepared.variantFilterFindings() +
        prepared.conditionLexicalFindings() + prepared.conditionCompletenessFindings() +
        prepared.connectiveAgreementFindings() + prepared.conditionGrammarFindings() +
        prepared.elementNameFindings() + prepared.groupOrderingFindings() +
        prepared.presentationMemberFindings() + prepared.stringSlotFindings() +
        prepared.fieldTypeBindingFindings() + prepared.temporalConfigFindings() +
        prepared.elementNameAlphabetFindings() + prepared.enumCategoryFindings() +
        prepared.supportedCharacterFindings() + prepared.declaredPatternFindings() +
        prepared.emptyGroupOperandFindings() + prepared.errorFieldReferenceFindings() +
        prepared.groupListOperandFindings() + prepared.repetitionWildcardFindings() +
        prepared.computedTargetKindFindings() + prepared.computedTargetIndexFieldFindings() +
        prepared.rawTypeLengthFindings() + prepared.computationPreconditionFeatureFindings() +
        prepared.computationCycleFindings() +
        prepared.messageParameterFindings() +
        prepared.messageTextCharacterFindings() +
        prepared.stringTypeMessageFindings() + prepared.stringTypeParameterFindings() +
        prepared.numberTypeDigitFindings() + prepared.enumValueLabelFindings())
        .filterNot { it.at in stopped && it.code !in ERROR_TARGET_CODES }
        .filterNot { it.at in resolvedFirst && it.code in CONDITION_SYNTAX_CODES }
    val findings = judged.notSuppressedBy(prepared.variantFilterSuppressed())
    return ConsistencyReport(
        modelId = admitted.modelId,
        status = statusOf(findings),
        findings = findings,
        diagnostics = inherited + prepared.diagnostics,
        checksCompleted = setOf(CheckFamily.DECLARED_REFERENCES, CheckFamily.CONDITION_REFERENCES,
            CheckFamily.DECLARATION_SLOTS, CheckFamily.COMPUTATION_ALTERNATIVES,
            CheckFamily.RULE_CONDITION_AND_TARGET, CheckFamily.ELEMENT_NAME_UNIQUENESS,
            CheckFamily.GROUP_ORDERING_REFERENCES, CheckFamily.GROUP_ORDERING_FIELD_TYPE,
            CheckFamily.COMPUTATION_ROUNDING, CheckFamily.SUPPRESSED_WARNINGS,
            CheckFamily.VARIANT_FILTER, CheckFamily.CONDITION_LEXICALITY,
            CheckFamily.CONDITION_COMPLETENESS, CheckFamily.CONNECTIVE_AGREEMENT,
            CheckFamily.CONDITION_GRAMMAR, CheckFamily.RULE_PRESENTATION_MEMBERS,
            CheckFamily.STRING_SLOT_BINDING, CheckFamily.FIELD_TYPE_BINDING,
            CheckFamily.TEMPORAL_CONFIG, CheckFamily.ELEMENT_NAME_ALPHABET,
            CheckFamily.ENUM_CATEGORY_ALIGNMENT, CheckFamily.ENUM_VALUE_CHARACTERS,
            CheckFamily.STRING_PATTERN_ADMISSION, CheckFamily.STRING_PATTERN_MESSAGE,
            CheckFamily.STRING_TYPE_PARAMETERS, CheckFamily.NUMBER_TYPE_DIGITS,
            CheckFamily.ENUM_VALUE_LABELS, CheckFamily.EMPTY_GROUP_OPERAND,
            CheckFamily.ERROR_FIELD_REFERENCE, CheckFamily.GROUP_LIST_OPERANDS,
            CheckFamily.GROUP_LIST_ROOT_OPERAND, CheckFamily.GROUP_LIST_OPERAND_KIND,
            CheckFamily.REPETITION_WILDCARD_TARGET, CheckFamily.COMPUTED_TARGET_KIND,
            CheckFamily.RAW_TYPE_LENGTH, CheckFamily.COMPUTED_TARGET_INDEX_FIELD,
            CheckFamily.COMPUTATION_PRECONDITION_FEATURES, CheckFamily.COMPUTATION_CYCLE,
            CheckFamily.MESSAGE_PARAMETER_REFERENCE, CheckFamily.MESSAGE_TEXT_CHARACTERS),
        gaps = prepared.gaps() + prepared.emptyFactBase() + findings.buildBlocked() +
            prepared.variantFilterGaps() + prepared.enumCategoryGaps() +
            prepared.supportedCharacterGaps() + prepared.fieldTypeBindingGaps() +
            prepared.typeDefinitionBodyGaps() + prepared.declaredPatternGaps() + UNREAD_MEMBERS + UNIMPLEMENTED_FAMILIES,
    )
}

/**
 * Drops the findings A12 never reaches, because a variant filter took the declaration out of its check.
 *
 * Reporting them would reject a model the Kernel ADMITS, which is the one direction this checker must never
 * take — and it is a direction no family can see on its own, since each is correct about the declaration it
 * read and wrong only about whether A12 read it too. Hence the filter applies once, to the composed list.
 *
 * **[CK_MODEL_NOT_BUILDABLE] survives deliberately**, on a measured row rather than on symmetry: an
 * `ONLY`-filtered rule with an empty `errorCode` still refuses the model ([variantFilterSuppressed]). A filter
 * hides what A12 would SAY ABOUT a declaration and cannot rescue a model that fails to load, so a finding of
 * the second kind is not this filter's to hide. A model-level finding has no declaration to be filtered and
 * is kept for the same reason.
 */
private fun List<Finding>.notSuppressedBy(suppressed: Set<String>): List<Finding> = filter {
    it.code == CK_MODEL_NOT_BUILDABLE || it.at == null || it.at !in suppressed
}

/**
 * A source that declares nothing produces a report indistinguishable from one whose references all resolve,
 * so it says which of the two it is.
 *
 * This is a GAP and not a rejection because A12 accepts such a model: a type-def-only library is exactly an
 * empty root and is Kernel-valid standalone, and the Kernel reads an absent `rootGroups` and an absent
 * `modelRoot` too (measured 2026-09-12, `ModelRootReadabilityLawsTest`). The one shape it cannot read —
 * a missing `content` — is refused a phase earlier, at admission.
 */
private fun PreparedModel.emptyFactBase(): List<CoverageGap> = if (elements.isNotEmpty()) {
    emptyList()
} else {
    listOf(
        CoverageGap(
            at = null,
            reason = "this source declares no elements, so no rule or computation was examined — a report " +
                "with no findings here means nothing was found to check, not that nothing was wrong",
        ),
    )
}

/**
 * A model's `content.typeDefinitions` declare field types that no family examines.
 *
 * Preparation reads them for their IDS alone, so a `TypeDefType` reference can be resolved — but the type
 * each definition declares produces no element, and the enum, character and range-pair families are all
 * keyed on a field's inline `fieldType`. The carrier is visibly in scope elsewhere in the same report, which
 * is exactly why its absence here has to be said rather than inferred.
 */
private fun PreparedModel.typeDefinitionBodyGaps(): List<CoverageGap> = if (declaredTypeDefinitionIds.isEmpty()) {
    emptyList()
} else {
    listOf(
        CoverageGap(
            at = null,
            reason = "this model declares ${declaredTypeDefinitionIds.size} type definition(s) under " +
                "`content.typeDefinitions`, read here for their ids alone — the field type each one " +
                "declares was examined by no family, so a law this checker applies to an inline `fieldType` " +
                "was not applied inside them",
        ),
    )
}

/** A declaration the checker could not read was not checked either, so each one is also a coverage gap. */
private fun PreparedModel.gaps(): List<CoverageGap> = diagnostics.map {
    CoverageGap(it.at, "this declaration could not be read, so no check ran over it: ${it.explain}")
}

/**
 * The findings that stop A12 from producing a verdict at all, so every OTHER finding in the same report is
 * this checker's own reading rather than something A12 would have said about this model.
 *
 * They are recognized by their code, because the class is wider than any one family: an unresolvable
 * computed field and a target climbing past the root stop the Kernel from BUILDING the model (measured
 * through `ModelLoader.loadAndCheck`), while a message entry or a locale code its READER refuses stops it
 * one tier earlier still. A consumer told only the findings would assume the list is A12's; the gap says
 * which it is.
 */
private fun List<Finding>.buildBlocked(): List<CoverageGap> =
    filter { it.code == CK_MODEL_NOT_BUILDABLE }.map {
        CoverageGap(
            at = it.at,
            reason = "this makes the model unusable, so A12 reports that and stops; every other finding " +
                "here is this checker's own reading of a model A12 never got far enough to judge",
        )
    }

/**
 * What this checker reads only PART of, and the one law it reads fully and deliberately does not reproduce.
 *
 * Stated on every report rather than only in the plan, because a consumer cannot otherwise tell a clean
 * result from an unexamined one. An entry earns its place by naming what would settle it, so a later session
 * can close it rather than rediscover why it is open.
 */
private val UNREAD_MEMBERS = listOf(
    CoverageGap(
        at = null,
        reason = "a field type's body is judged on the member A12 constructs from and on the two CONTENT " +
            "laws measured beyond it — a `DateRangeType`'s (format, separator) pair, and an " +
            "`EnumerationType`'s values against its categories. What a member otherwise holds is left to " +
            "A12: a declared `format` outside the admitted vocabulary is refused there and cannot be " +
            "reported here, since the admitted set is explicitly not closed and the refused one is a " +
            "probe's universe rather than a law; a `CustomFieldType`'s `name` has no measured content law " +
            "at all",
    ),
    CoverageGap(
        at = null,
        reason = "the condition parser checks the SHAPE the grammar's productions share — a call is a name " +
            "and a parenthesised argument list — and not each operator's own argument arity or operand " +
            "kinds, so a call with the wrong number or kind of arguments is left to A12. Reproducing those " +
            "per operator would be a second copy of the catalog, and every place the copy disagreed would " +
            "refuse a condition A12 admits. A12's illegal-CHARACTER class IS reported, derived from the " +
            "lexer's terminals, with one exception: a lone `#` is measured illegal and left alone, because " +
            "`#Zeile`'s other language spellings are unknown here and refusing an unpaired `#` could " +
            "reject text A12 accepts",
    ),
    CoverageGap(
        at = null,
        reason = "two entity filters sharing ONE argument list are treated as separate connective levels " +
            "without a measurement saying they are — `SumOfProducts` is the only operator whose argument " +
            "list takes two entity specs and it refuses a filter on either for a reason of its own, so no " +
            "oracle for that shape exists. This is silence rather than a wrong verdict",
    ),
    // A measured law deliberately not reproduced, and the reason is what a consumer needs rather than the
    // omission: both of A12's pre-emptions here are decidable only with machinery this checker does not
    // have, so reproducing the law would state a class A12 did not issue on two shapes out of three.
    CoverageGap(
        at = null,
        reason = "a computation that READS its own computed field is refused by A12 and is not reported " +
            "here: a projected read draws a different class than a plain one, and a derived-scale mismatch " +
            "in the same operation pre-empts both — neither is decidable without inferring an operation's " +
            "scale, which this checker does not do. Re-open when it can",
    ),
)

private val UNIMPLEMENTED_FAMILIES = listOf(
    CoverageGap(
        at = null,
        reason = "only the families named by `CheckFamily` are implemented; every other required family of " +
            "the pinned profile was not examined",
    ),
)

/**
 * What survives at a declaration A12 stopped examining — the codes it still reports about the target itself.
 *
 * Both error-target codes are named because both are A12's verdict for that input: the checker emits the
 * pair for a BLANK target (`RuleConditionAndTarget`) and the first alone for an unresolvable one.
 *
 * **`CK_MODEL_NOT_BUILDABLE` belongs here or the gate erases its own trigger.** An error target climbing
 * above the root group is unresolved like any other, so it enters the withheld set — and it reports under
 * that code rather than [ERROR_FIELD_MISSING], so leaving it out silenced the rule completely, the target's
 * own finding included. Nothing in the suite drove an above-root target through the report, so nothing went
 * red (`RuleMemberWithholdingTest.anAboveRootErrorTargetStillReportsItsOwnFinding` now does).
 *
 * It also keeps the measured variant-filter exemption reachable: a filter cannot rescue a model that fails
 * to BUILD, and that finding is anchored at the rule whose target may be the broken one.
 */
private val ERROR_TARGET_CODES = setOf(ERROR_FIELD_MISSING, MISSING_ERROR_FIELD, CK_MODEL_NOT_BUILDABLE)

/**
 * The classes A12 withholds from a condition whose names do not all resolve.
 *
 * Listed as the codes to DROP rather than as the codes to keep, the mirror of [ERROR_TARGET_CODES] and for
 * the opposite reason: a family added later that reports a new syntax class is an under-report until its
 * code is added here, where a keep-list would silently start dropping it. Each is measured firing alone and
 * withheld beside an unresolvable name (`ConditionMultipleDefectLawsTest`).
 */
private val CONDITION_SYNTAX_CODES =
    setOf("MVK_INCOMPLETE_INPUT", "MVK_EXPECTED_TOKEN_NOT_FOUND", "MVK_UNEXPECTED_TOKEN")
