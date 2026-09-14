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
     * …and that the group declaring sort entries REPEATS at all. Its own constant because it is a claim
     * about the carrier rather than about any entry, so a report merging it would let a group that cannot
     * be sorted read as a group whose sort fields are wrong.
     */
    GROUP_ORDERING_CARRIER,

    /**
     * A group's `repeatability` against A12's cap on it, which differs between a root and a nested group.
     * Its own constant because it is a claim about the declaration's SIZE rather than about any reference,
     * and it is the only family that reads the member for its value rather than for repeatability.
     */
    GROUP_REPETITION_COUNT,

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
     * An enumeration's `sValue` names one of the values it declares. Its own constant because it is a claim
     * about a single distinguished member rather than about the value list, and a report merging it would
     * let a special value pointing nowhere read as a defect in the values themselves.
     */
    ENUM_S_VALUE_DOMAIN,

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
     * That a `RepetitionNotUnique` key has a repetition to be asked about — a repeatable group strictly
     * between the rule's own group and the key. Its own constant rather than part of the wildcard family
     * because the demand is on the KEY's placement rather than on a spelling: the class collapses three
     * shapes that read as different defects, and a key outside the rule group hands the verdict to a
     * different class entirely, where this family stays silent.
     */
    REPETITION_KEY_PLACEMENT,

    /**
     * That a `RepetitionNotUnique` `@From` scope names a REPEATABLE group. Its own constant rather than part
     * of [REPETITION_KEY_PLACEMENT] because the demand is on the SCOPE rather than on where the keys sit,
     * and the two are independent: a legal scope admits an illegal key placement and the reverse.
     */
    REPETITION_SCOPE_GROUP,

    /**
     * Where a `RepetitionNotUnique` key has to sit relative to its `@From` scope or to the rule's own group.
     * Its own constant rather than part of [REPETITION_KEY_PLACEMENT] because A12 gates containment BEFORE
     * the repetition demand that family reports, and the two answer on disjoint shapes: a key inside the
     * rule group with no repetition between, against a key outside the bound entirely.
     */
    REPETITION_KEY_CONTAINMENT,

    /**
     * That the temporal operands of an entity-list aggregate declare the same date COMPONENTS. Its own
     * constant rather than part of [FIELD_OPERAND_KIND] because the demand is not on any operand's kind: the
     * list is typed from its leader and every later operand is then judged on what its FORMAT spells, so two
     * fields of one kind can disagree and two of different kinds can agree.
     */
    TEMPORAL_AGGREGATE_OPERANDS,

    /**
     * That a `Having` filter carries no repetition wildcard. Its own constant rather than part of
     * [REPETITION_WILDCARD_TARGET] because the filter is a fourth POSITION and position picks the class
     * there: the same star on the same repeatable group is admitted in the aggregate's own operand.
     */
    FILTER_WILDCARD,

    /**
     * Which repeatable levels a `Having` filter's BODY may name, and the one it must. Its own constant
     * rather than part of [FILTER_WILDCARD] because that family judges a STAR in the body while this one
     * judges the levels the body's unmarked references ride — two gates of one kernel method, with the star
     * refused before either is asked.
     */
    FILTER_BODY_ITERATION,

    /**
     * That no GROUP-CONDITION operand carries a repetition wildcard where its operator bars one. Its own
     * constant rather than part of [REPETITION_WILDCARD_TARGET] or [FILTER_WILDCARD] because here the star
     * lands on a group that legitimately repeats and the OPERATOR alone picks the class: A12 splits the seven
     * group conditions three ways, and two of them admit the very star the other five refuse.
     */
    GROUP_CONDITION_WILDCARD,

    /**
     * That `SumOfProducts`' two operands are PARALLEL: one owning group, and each starring exactly the
     * lowest repeatable level between the rule's own group and its field. One constant for four codes
     * because A12 decides them in one operator's gate chain and their ORDER is what a reproduction has to
     * get right — three of the cells that break two of the laws at once are answered by the earlier gate.
     */
    SUM_OF_PRODUCTS_OPERANDS,

    /**
     * That one numeric operand-list `Min`/`Max` call lists at most one literal. Per CALL and never
     * flattened: a literal inside a nested call or inside arithmetic belongs to that operand and not to the
     * outer budget, so a family counting literals over the text would refuse two shapes A12 admits.
     */
    OPERAND_LIST_CONSTANT_BUDGET,

    /**
     * What a semantic index may CARRY and where the group it indexes has to be — six classes of one chain,
     * ordered as A12 decides them. Its own constant rather than part of [SEMANTIC_INDEX_KEY] because that
     * family owns the chain's FIRST gate and reports it wherever the key is a group, while these six are
     * reached only once the key is a legal field.
     */
    SEMANTIC_INDEX_STRUCTURE,

    /**
     * That a semantic index's KEY names a field rather than a group. Its own constant rather than part of
     * [REPETITION_KEY_PLACEMENT] because the key of an index selects one repetition by VALUE, where a
     * repetition-uniqueness key declares what makes rows distinct — different laws over the same word.
     */
    SEMANTIC_INDEX_KEY,

    /**
     * That a condition calls `RepetitionNotUnique` at most once. Its own constant rather than part of
     * [REPETITION_SCOPE_GROUP] because it is a whole-RULE gate counting calls, where every other family here
     * judges one construct's own operands — and A12 runs it after the construct gates have had their say.
     */
    REPETITION_NOT_UNIQUE_COMBINATION,

    /**
     * That `RepetitionNotUnique` does not sit in a condition that PARALLEL-iterates. Its own constant rather
     * than part of [REPETITION_NOT_UNIQUE_COMBINATION] because the demand is on the condition's ITERATION
     * shape rather than on how many calls it carries, and the two gates are independent.
     */
    PARALLEL_ITERATION_UNIQUENESS,

    /**
     * That no `Having` filter hangs off an operand A12 marked as parallel-iterated. Its own constant rather
     * than part of [PARALLEL_ITERATION_UNIQUENESS] because the two are separate gates of the same method
     * with separate preconditions — one needs a uniqueness call, this one a filter — and A12 runs the
     * uniqueness gate first, which is what makes the boundary between them observable.
     */
    FILTERED_ENTITY_ITERATION,

    /**
     * What an arithmetic expression's operands may BE, how many divisions and powers it may carry, and what
     * an exponent has to be. One constant for the five classes because `CheckOperationImpl.check` asks them
     * of one construct — an operation context — and the type walk PRE-EMPTS both arity gates inside it, so
     * splitting them would let a checker name a second class on a model A12 answers once.
     */
    ARITHMETIC_OPERATION_GATES,

    /**
     * Which field declarations each constant KIND of `NumberOfValueInFields` admits in its list. One
     * constant for the four classes because `CheckAnzahlWertOpImpl.check` is one switch on the constant, so
     * which of them answers is decided by what stands to the left of the `In` rather than by a property a
     * family could split by.
     */
    VALUE_IN_FIELDS_CONSTANT_KIND,

    /**
     * What a value-list quantifier's field list may carry, and what may stand opposite it. One constant for
     * the three classes because `CheckFeldListeUndWerteListenBedingungImpl.check` asks them in a fixed
     * order over the same two lists, so which answers depends on where the walk stops rather than on a
     * property a family could split by.
     */
    VALUE_LIST_CONDITION_GATES,

    /**
     * How many entities each entity-list operator's argument list needs. One constant for the six classes
     * because they are six calls to the same handful of gates in `CheckEntityListenUtils`, differing only
     * in which operator's switch reaches them — the operator decides the class, so nothing else could.
     */
    ENTITY_LIST_ARITY,

    /**
     * The duplicate and root-group gates an entity list meets before its operator asks anything of its own.
     * One constant for the four classes because they are the same two shared utilities in every caller —
     * what varies is only WHICH caller asks the root-group one, which is a property of the table rather
     * than of a family a reader could split by.
     */
    ENTITY_LIST_PRE_GATES,

    /**
     * What KIND of field `FieldValuesNotUnique` admits, and how the temporal ones must be spelled. One
     * constant because both questions are one gate in A12 under one class — the kind arm and the spelling
     * arm are the same `checkOnlyStringEnumNumberOrDateWithSameFormat` walk.
     */
    FIELD_VALUES_NOT_UNIQUE_OPERAND_KIND,

    /**
     * What KIND of declaration each field-LIST operation admits in its entity list. One constant for the
     * three classes because `CheckFeldListenOpImpl.check` is one switch on the operator over one list — the
     * arms differ in what they demand, never in where or when they are asked.
     */
    FIELD_LIST_OPERATION_OPERANDS,

    /**
     * What a date-arithmetic operator demands of the date it shifts and of the amount it shifts by. One
     * constant for the four classes because `CheckDatumAddOpImpl.check` asks them in a fixed order over one
     * operand, so which answers is a property of that order rather than of a family a reader could split.
     */
    DATE_ARITHMETIC_OPERANDS,

    /**
     * What the six rounding operators demand of the value they round and of the digit count. One constant
     * for the six classes because the two constructs share the digits gate and differ only in which grammar
     * rule their operand is — a split by class would cut the shared gate in half.
     */
    ROUNDING_OPERANDS,

    /**
     * What each of the four single-field value operators demands of the one field it reads. One constant for
     * the four classes because `CheckFeldOpImpl.check` is one switch on the operator token behind one shared
     * head, so which class answers is decided by the same read that decides whether anything answers at all.
     */
    FIELD_VALUE_OPERATOR_OPERANDS,

    /**
     * What each comparison OPERATOR demands of the two sides it joins. One constant for the three classes
     * because `CheckVergleichsBedingungImpl.check` asks them in a fixed order ahead of every agreement
     * gate, so which answers depends on where that walk stops.
     */
    COMPARISON_OPERATOR_OPERANDS,

    /**
     * What a confirm or boolean declaration may be compared to, and with which equality operator. One
     * constant for the two classes because `CheckVergleichsBedingungImpl.checkYesAndYesNoOperations` is one
     * method whose arms are the two kinds themselves, so the declaration decides the class and nothing else
     * could.
     */
    BOOLEAN_COMPARISON_AGREEMENT,

    /**
     * What a text or enumeration side of a comparison needs of its partner, and which written-out values an
     * enumeration admits. One constant for the two classes because `CheckVergleichsBedingungImpl.checkStringOrEnumType`
     * asks them in one walk per side, the domain question only where the partner is written out — so which
     * answers depends on where that walk stops.
     */
    STRING_ENUM_COMPARISON_AGREEMENT,

    /**
     * What equality demands of two numbers' declared scales, and what a number may stand against at all. One
     * constant for the two classes because `CheckVergleichsBedingungImpl.checkNumberComparison` asks them in
     * one method, the scale question only once the kind question has passed — so which answers depends on
     * where that walk stops.
     */
    NUMBER_COMPARISON_SCALE,

    /**
     * What a date or date-range side of a comparison demands of its partner. One constant for the two
     * classes because `CheckVergleichsBedingungImpl` asks them in two adjacent methods with one shared
     * precedence — the date arm claims every partner of another kind, so which answers depends on where
     * that pair of walks stops.
     */
    TEMPORAL_COMPARISON_AGREEMENT,

    /**
     * What a pattern comparison demands of its two sides. One constant for the four classes because
     * `CheckVergleichsBedingungImpl.checkPatternOp` asks them in a fixed order inside one method, so which
     * answers depends on where that walk stops rather than on anything a family could split by.
     */
    PATTERN_COMPARISON_OPERANDS,

    /**
     * That every repetition an operand crosses unstarred is one the target's own coordinates address — or,
     * where it is not, that the two branches iterate JOINTLY on a shared index field. One constant for the
     * five classes of that walk because A12 asks them as one gate chain per level, and because which class
     * answers depends on the level reached rather than on anything a family could split by.
     */
    OPERAND_REPETITION_SCOPE,

    /**
     * What each SLOT of a `Date` or `Time` construction admits, on all three of its arms — a quoted
     * constant's range and width, which extract operator belongs in which slot, and what a field component
     * has to declare to stand in one. Its own constant because A12 walks the construction's slots left to
     * right, so the answering slot rather than any gate picks both the class and the component a report has
     * to name.
     */
    DATE_CONSTRUCTION_SLOT,

    /**
     * That no path segment carries the H-model `.*` repetition suffix. Its own constant rather than part of
     * [REPETITION_WILDCARD_TARGET] because the demand is on the SPELLING rather than on where a star lands,
     * and A12 gates it between resolution and the landing site rather than alongside it.
     */
    H_MODEL_PATH_SUFFIX,

    /**
     * That the two SCALAR field predicates are not handed a group. Its own constant rather than part of
     * [GROUP_LIST_OPERANDS] because the carrier set is the mirror one and measured separately — the eight
     * field-LIST carriers expand a group operand instead of refusing it.
     */
    FIELD_OPERAND_KIND,

    /**
     * What a `Having` filter's own operand may be. Its own constant rather than part of [FILTER_WILDCARD]
     * because that family judges the filter BODY, and A12 gates the two in one method with the operand's
     * chain running first.
     */
    FILTERED_OPERAND_KIND,

    /**
     * That a `->` category access names a category its Enumeration declares. Its own constant rather than
     * part of [ENUM_CATEGORY_ALIGNMENT] because that family judges a DECLARATION and this judges a
     * REFERENCE to one — a model can be defective in either without the other.
     */
    CATEGORY_REFERENCE,

    /**
     * Everything the two SCALAR value-list predicates demand of their subject field and of the list it is
     * compared against — `CheckFeldUndWerteListenBedingungImpl.check`, whose gates run in one order. Its own
     * constant rather than part of [ENUM_VALUE_CHARACTERS] because that family judges a declared value's
     * spelling and this judges a condition's REFERENCE to one, among the other demands of the same method.
     */
    SCALAR_VALUE_LIST_GATES,

    /**
     * That every date operator's DATE-ARGUMENT slot holds something the slot takes — a date-kind field
     * without a star, one of the four date-constant shapes, or a date-valued extraction. Its own constant
     * because `CheckDatumAngabeImpl` is a SHARED checker: the gates belong to the slot rather than to any
     * operator, so folding them into a carrier's family would need one copy per carrier.
     */
    DATE_ARGUMENT_SLOT,

    /**
     * That the ranges a range-overlap operator compares are date ranges, interpret their year the standard
     * way, and agree about carrying one. Its own constant because the two walks live in
     * `CheckEntityListenUtils` and are called by two different constructs, so the law belongs to the RANGE
     * LIST rather than to either carrier's method.
     */
    DATE_RANGE_OPERAND_LIST,

    /**
     * That each of the two validity conditions holds what it takes — a text or enumeration field with a
     * non-empty predefined-type name, or a date construction naming at least one entity. Its own constant
     * because one keyword spells two grammar rules with two checker classes, and the operand shape rather
     * than the operator is what decides which law applies.
     */
    VALIDITY_CONDITION_OPERANDS,

    /**
     * That a condition asking the model for its reference year is written in a model that declares one —
     * the `BaseYear` constant and the year-less `Date` construction. Its own constant because the two
     * shapes are checked by unrelated kernel classes and share only the model-level declaration, which is
     * what makes the law a property of the MODEL rather than of either construct.
     */
    BASE_YEAR_REQUIREMENT,

    /**
     * That the two SUBSTRING operators cut a field they can cut, over an interval that exists. Its own
     * constant because one kernel method checks both operators and chains six classes over the operand and
     * the interval together, so the law belongs to the CUT rather than to either operator.
     */
    RANGE_OPERAND_GATES,

    /**
     * That two declarations a comparison equates agree about LABELLING their values, and label the values
     * they share the same way. Its own constant because the question is about a PAIR rather than about
     * either side — the same enumeration is fine opposite one partner and refused opposite another — which
     * is the one shape `STRING_ENUM_COMPARISON_AGREEMENT`'s per-side walk cannot carry.
     */
    ENUM_DISPLAY_COMPARABILITY,

    /**
     * That `ValueAsDate` resolves a field A12 can resolve — one operand, four gates, one chain. Its own
     * constant because the operator's whole law is about the OPERAND's declaration, and the class it draws
     * is the same for every declaration it refuses, so a report merging it into a kind family would lose
     * which of the four gates answered.
     */
    VALUE_AS_DATE_OPERAND,

    /**
     * That a rule reading a repetition does not reach its error field only from ABOVE its own group — A12
     * cannot then say which repetition the error attaches to. Its own constant because the demand is on the
     * CONDITION's reach rather than on the target's existence, which `ERROR_FIELD_REFERENCE` already owns,
     * and because the carrier of the offending read picks between two classes.
     */
    ERROR_TARGET_ITERATION,

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
    // …and inside a condition A12 reports the FIRST defect in the text and nothing after it, so an
    // unresolvable name withholds every class that would have been drawn later in the same text (KF340).
    // Eleven families read that text, so without this any condition carrying two defects — the normal state
    // of one being written — draws a class A12 withheld.
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
        prepared.groupRepetitionCountFindings() +
        prepared.presentationMemberFindings() + prepared.stringSlotFindings() +
        prepared.fieldTypeBindingFindings() + prepared.temporalConfigFindings() +
        prepared.elementNameAlphabetFindings() + prepared.enumCategoryFindings() +
        prepared.supportedCharacterFindings() + prepared.declaredPatternFindings() +
        prepared.emptyGroupOperandFindings() + prepared.errorFieldReferenceFindings() +
        prepared.groupListOperandFindings() + prepared.repetitionWildcardFindings() +
        prepared.computedTargetKindFindings() + prepared.computedTargetIndexFieldFindings() +
        prepared.rawTypeLengthFindings() + prepared.computationPreconditionFeatureFindings() +
        prepared.computationCycleFindings() +
        prepared.repetitionKeyPlacementFindings() +
        prepared.repetitionScopeGroupFindings() +
        prepared.repetitionKeyContainmentFindings() +
        prepared.filterWildcardFindings() +
        prepared.filterBodyIterationFindings() +
        prepared.groupConditionWildcardFindings() +
        prepared.sumOfProductsOperandFindings() +
        prepared.operandListConstantFindings() +
        prepared.temporalAggregateOperandFindings() +
        prepared.semanticIndexStructureFindings() +
        prepared.semanticIndexKeyFindings() +
        prepared.repetitionNotUniqueCombinationFindings() +
        prepared.parallelIterationUniquenessFindings() +
        prepared.filteredEntityIterationFindings() +
        prepared.arithmeticOperationGateFindings() +
        prepared.valueInFieldsConstantKindFindings() +
        prepared.valueListConditionGateFindings() +
        prepared.entityListArityFindings() +
        prepared.entityListPreGateFindings() +
        prepared.fieldValuesNotUniqueOperandKindFindings() +
        prepared.fieldListOperationOperandFindings() +
        prepared.dateArithmeticOperandFindings() +
        prepared.roundingOperandFindings() +
        prepared.fieldValueOperatorOperandFindings() +
        prepared.comparisonOperatorOperandFindings() +
        prepared.booleanComparisonAgreementFindings() +
        prepared.stringEnumComparisonAgreementFindings() +
        prepared.numberComparisonScaleFindings() +
        prepared.temporalComparisonAgreementFindings() +
        prepared.patternComparisonOperandFindings() +
        prepared.operandRepetitionScopeFindings() +
        prepared.dateConstructionSlotFindings() +
        prepared.hModelPathSuffixFindings() +
        prepared.fieldOperandKindFindings() +
        prepared.filteredOperandKindFindings() +
        prepared.categoryReferenceFindings() +
        prepared.scalarValueListGateFindings() +
        prepared.dateArgumentSlotFindings() +
        prepared.dateRangeOperandListFindings() +
        prepared.validityConditionOperandFindings() +
        prepared.baseYearRequirementFindings() +
        prepared.rangeOperandGateFindings() +
        prepared.enumDisplayComparabilityFindings() +
        prepared.valueAsDateOperandFindings() +
        prepared.errorTargetIterationFindings() +
        prepared.messageParameterFindings() +
        prepared.messageTextCharacterFindings() +
        prepared.stringTypeMessageFindings() + prepared.stringTypeParameterFindings() +
        prepared.numberTypeDigitFindings() + prepared.enumValueLabelFindings() +
        prepared.enumSpecialValueFindings())
        .filterNot { it.at in stopped && it.code !in ERROR_TARGET_CODES }
        .filterNot { it.at in resolvedFirst && it.code in CONDITION_TEXT_CODES }
                .withoutPostParseClassesBehindAPositionedDefect()
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
            CheckFamily.GROUP_ORDERING_CARRIER, CheckFamily.GROUP_REPETITION_COUNT,
            CheckFamily.COMPUTATION_ROUNDING, CheckFamily.SUPPRESSED_WARNINGS,
            CheckFamily.VARIANT_FILTER, CheckFamily.CONDITION_LEXICALITY,
            CheckFamily.CONDITION_COMPLETENESS, CheckFamily.CONNECTIVE_AGREEMENT,
            CheckFamily.CONDITION_GRAMMAR, CheckFamily.RULE_PRESENTATION_MEMBERS,
            CheckFamily.STRING_SLOT_BINDING, CheckFamily.FIELD_TYPE_BINDING,
            CheckFamily.TEMPORAL_CONFIG, CheckFamily.ELEMENT_NAME_ALPHABET,
            CheckFamily.ENUM_CATEGORY_ALIGNMENT, CheckFamily.ENUM_VALUE_CHARACTERS,
            CheckFamily.STRING_PATTERN_ADMISSION, CheckFamily.STRING_PATTERN_MESSAGE,
            CheckFamily.STRING_TYPE_PARAMETERS, CheckFamily.NUMBER_TYPE_DIGITS,
            CheckFamily.ENUM_VALUE_LABELS, CheckFamily.ENUM_S_VALUE_DOMAIN,
            CheckFamily.EMPTY_GROUP_OPERAND,
            CheckFamily.ERROR_FIELD_REFERENCE, CheckFamily.GROUP_LIST_OPERANDS,
            CheckFamily.GROUP_LIST_ROOT_OPERAND, CheckFamily.GROUP_LIST_OPERAND_KIND,
            CheckFamily.REPETITION_WILDCARD_TARGET, CheckFamily.COMPUTED_TARGET_KIND,
            CheckFamily.RAW_TYPE_LENGTH, CheckFamily.COMPUTED_TARGET_INDEX_FIELD,
            CheckFamily.COMPUTATION_PRECONDITION_FEATURES, CheckFamily.COMPUTATION_CYCLE,
            CheckFamily.REPETITION_KEY_PLACEMENT, CheckFamily.REPETITION_SCOPE_GROUP,
            CheckFamily.REPETITION_KEY_CONTAINMENT,
            CheckFamily.FILTER_WILDCARD, CheckFamily.FILTER_BODY_ITERATION,
            CheckFamily.GROUP_CONDITION_WILDCARD,
            CheckFamily.SUM_OF_PRODUCTS_OPERANDS, CheckFamily.OPERAND_LIST_CONSTANT_BUDGET,
            CheckFamily.TEMPORAL_AGGREGATE_OPERANDS,
            CheckFamily.SEMANTIC_INDEX_STRUCTURE,
            CheckFamily.SEMANTIC_INDEX_KEY,
            CheckFamily.REPETITION_NOT_UNIQUE_COMBINATION,
            CheckFamily.PARALLEL_ITERATION_UNIQUENESS, CheckFamily.FILTERED_ENTITY_ITERATION,
            CheckFamily.ARITHMETIC_OPERATION_GATES, CheckFamily.VALUE_IN_FIELDS_CONSTANT_KIND,
            CheckFamily.VALUE_LIST_CONDITION_GATES, CheckFamily.ENTITY_LIST_ARITY,
            CheckFamily.ENTITY_LIST_PRE_GATES,
            CheckFamily.FIELD_VALUES_NOT_UNIQUE_OPERAND_KIND,
            CheckFamily.FIELD_LIST_OPERATION_OPERANDS,
            CheckFamily.DATE_ARITHMETIC_OPERANDS, CheckFamily.ROUNDING_OPERANDS,
            CheckFamily.FIELD_VALUE_OPERATOR_OPERANDS,
            CheckFamily.COMPARISON_OPERATOR_OPERANDS, CheckFamily.BOOLEAN_COMPARISON_AGREEMENT,
            CheckFamily.STRING_ENUM_COMPARISON_AGREEMENT, CheckFamily.NUMBER_COMPARISON_SCALE,
            CheckFamily.TEMPORAL_COMPARISON_AGREEMENT, CheckFamily.PATTERN_COMPARISON_OPERANDS,
            CheckFamily.OPERAND_REPETITION_SCOPE, CheckFamily.DATE_CONSTRUCTION_SLOT,
            CheckFamily.H_MODEL_PATH_SUFFIX, CheckFamily.FIELD_OPERAND_KIND,
            CheckFamily.FILTERED_OPERAND_KIND, CheckFamily.CATEGORY_REFERENCE,
            CheckFamily.SCALAR_VALUE_LIST_GATES, CheckFamily.DATE_ARGUMENT_SLOT,
            CheckFamily.DATE_RANGE_OPERAND_LIST, CheckFamily.VALIDITY_CONDITION_OPERANDS,
            CheckFamily.BASE_YEAR_REQUIREMENT, CheckFamily.RANGE_OPERAND_GATES,
            CheckFamily.ENUM_DISPLAY_COMPARABILITY,
            CheckFamily.VALUE_AS_DATE_OPERAND,
            CheckFamily.ERROR_TARGET_ITERATION,
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
 * Drops a POST-PARSE class at any declaration whose condition text already draws a positioned one.
 *
 * Unlike the two model-reading gates above, this one reads the findings, because it is not the model that
 * decides it: the same unreferenced error target is reported on a sound condition and withheld on a defective
 * one, so the trigger is precisely whether another family already spoke about that text.
 *
 * This was two functions with two code sets until 2026-09-14, one for the relational classes and one for the
 * whole-rule ones, written as separate laws because they were measured separately. They are one law, and
 * splitting it is what left the whole-rule half a single member while three more classes of the same phase
 * were live ([KERNEL-FINDINGS KF375]).
 */
private fun List<Finding>.withoutPostParseClassesBehindAPositionedDefect(): List<Finding> {
    val defective = filter { it.code in POSITIONED_CONDITION_CODES }.mapTo(mutableSetOf()) { it.at }
    return filterNot { it.at in defective && it.code in POST_PARSE_CONDITION_CODES }
}

/**
 * The classes a defect at some OFFSET in the condition text draws — the ones that compete for first place.
 *
 * A12 reports the first defect in the text and nothing after it, so which of two survives depends on which
 * comes first (`ConditionClassWithholdingLawsTest`).
 *
 * Listed as the codes to DROP rather than as the codes to keep, the mirror of [ERROR_TARGET_CODES] and for
 * the opposite reason: a family added later that reads the condition text is an under-report until its code
 * is added here, where a keep-list would silently start dropping it.
 */
private val POSITIONED_CONDITION_CODES = setOf(
    "MVK_INCOMPLETE_INPUT", "MVK_EXPECTED_TOKEN_NOT_FOUND", "MVK_UNEXPECTED_TOKEN",
    "MVK_LEXER_STANDARD_ERROR", "MVK_BRACKET_MISSING", "MVK_GROUP_IS_EMPTY", "MVK_DUPLICATE_PARAM1",
    "MVK_ROOT_GROUP_REFERENCED", "MVK_NO_FIELDS_ALLOWED", "MVK_INVALID_WILDCARD",
    "MVK_NO_WILDCARD", "MVK_MULTIPLE_INDEX_FIELDS", "MVK_ERROR_FIELD_NO_INDEX",
    "MVK_WRONG_DATE_FORMAT_FOR_OP", "MVK_INVALID_DATE_OR_TIME_EXTRACT_OP",
    "MVK_DATE_OR_TIME_EXTRACT_OP_NOT_ALLOWED", "MVK_NO_DIGIT_PATTERN",
    "MVK_INVALID_ITERATION_IN_FILTER_CONDITION", "MVK_NO_ITERATION_FOR_WILDCARD",
    "MVK_NOT_IN_GROUP", "MVK_NOT_IN_SAME_PATH", "MVK_NOT_IN_RULEGROUP",
    "MVK_DATEFORMATS_NOT_COMPATIBLE", "MVK_PARTIAL_DATE_NOT_ALLOWED",
    "MVK_ERROR_FIELD_INVALID_INDEX", "MVK_ERROR_FIELD_INVALID_INDEX_TYPE",
    "MVK_SEMANTIC_INDEX_NOT_ALLOWED",
)

/**
 * Every class drawn only once the condition PARSED, which a defect at any point in the text outranks.
 *
 * The members come from two post-parse phases and what they share is the precondition, not the caller. Seven
 * are the `throwError` calls in `ParserService.checkNormalRule`'s own body, read off kernel `30.8.1` in source
 * order; the last two are relations over the resolved references, one of them drawn by the calculation
 * module's normalisation rather than by that method. All of them need a finished parse, and a parse-time
 * class ends the parse ([KERNEL-FINDINGS KF375]).
 *
 * Listed by code rather than by family, because the families that own them also report classes A12 draws
 * DURING the parse and it is the class that says which phase answered. Some members are inert today — no
 * family reports them — and are listed anyway: a family added later would otherwise start over-reporting
 * silently, which is the failure this list exists to prevent.
 *
 * **Five members joined on 2026-09-14, and how they were missed is the lesson.** The list had been assembled
 * by enumerating the method's own `throwError` sites — a citable reading that misses everything the method
 * DELEGATES. `checkNormalRule` makes two such calls, and between them they carry five A12-model classes:
 * `IterationChecker.checkIteration` draws the two error-target iteration classes,
 * `MVK_ERROR_FIELD_AND_ITERATION_INVALID` and `MVK_ITERATION_RESTRICTION_IN_A12_MODEL`, while
 * `checkSimpleIteration` draws `MVK_NEG_CONDITION_IN_ITERATION`. Only `MVK_ERROR_FIELD_NOT_REFERENCED` had
 * made the list, because it is the one of the six the checker already reported.
 *
 * The cost was live for one of them: an index field on the ROOT group makes a parallel read a parse-time
 * `MVK_MULTIPLE_INDEX_FIELDS`, and this checker named `MVK_ERROR_FIELDGROUP_ITERATION5_INVALID` beside it —
 * two classes where A12 draws one ([KERNEL-FINDINGS KF410], `consistency.RootLevelIterationPinningTest`).
 * The other four are inert and listed for the reason the paragraph above gives: the next family to report
 * one of them would otherwise repeat this defect with nothing going red.
 *
 * **One member moved OUT on 2026-09-14: `MVK_SEMANTIC_INDEX_NOT_ALLOWED`.** The method draws it, but the
 * code has three sites and the other two are parse-time — `CheckEntitySpecImpl.checkIndicesInFilterCondition`
 * and `CheckEntityUtils.checkNoSemanticIndex`. Every shape measured for it reaches the filter walk, which
 * throws during the parse and so erases the whole-rule branch of the same name
 * ([KERNEL-FINDINGS KF377]); it is therefore POSITIONED here. A code is not owned by the phase that happens
 * to name it — a second site of the same class can sit in the other phase and win every race. **Both sets
 * were swept for that on 2026-09-14 and it is the only split class**: every other member of this set throws
 * only from the post-parse phase, and no member of [POSITIONED_CONDITION_CODES] throws from it.
 *
 * Measured: `MVK_PARALLEL_ITERATION_NOT_ALLOWED` and `MVK_INVALID_COMBINATION_OF_REPETITON_NOT_UNIQUE` both
 * vanish behind a containment class on one model (`RepetitionNotUniqueFromScopeLawsTest`, whose verdict is
 * the COMPLETE class list); the two relational members were measured in both orders and on both carriers
 * (`ConditionClassWithholdingLawsTest`).
 */
private val POST_PARSE_CONDITION_CODES = setOf(
    "MVK_PARALLEL_ITERATION_NOT_ALLOWED", "MVK_PARALLEL_ITERATION_FOR_FILTERED_ENTITY_NOT_ALLOWED",
    "MVK_INVALID_COMBINATION_OF_REPETITON_NOT_UNIQUE",
    "MVK_INVALID_LENGTH_OF_RAW_TYPE", "MVK_PARALLEL_ITERATION_INVALID",
    "MVK_ERROR_FIELD_NOT_REFERENCED", "MVK_ERROR_INVALID_LANGUAGE_FEATURE",
    "MVK_ERROR_FIELDGROUP_ITERATION4_INVALID", "MVK_ERROR_FIELDGROUP_ITERATION5_INVALID",
    "MVK_ERROR_FIELD_AND_ITERATION_INVALID", "MVK_ITERATION_RESTRICTION_IN_A12_MODEL",
    "MVK_NEG_CONDITION_IN_ITERATION",
)

/**
 * Every class read out of the condition text, all of which an unresolvable name in it can withhold.
 *
 * **This gate is deliberately position-BLIND, and the price is a SUBSTITUTION, not an under-report.** A
 * finding carries no offset into the condition text, so the faithful rule — drop a class only when an
 * unresolvable name precedes it — would need one threaded through eleven families. Dropping unconditionally
 * matches A12 exactly when the bad name comes first, and when it does not we name the bad name where A12
 * names the other defect: one class either way, both true of the text, and the author reaches the same fixed
 * condition. What it never does is reject a model A12 admits, or report two classes where A12 reports one.
 *
 * `RepetitionWildcardTest.theShapesItLeavesAlone` is the row where the substitution is visible, and it used
 * to assert both classes on the reasoning that the extra one was still true. [ConsistencyReport] settles
 * that: a finding is A12's verdict on a declaration, so a class A12 did not draw is a divergence however
 * true it is.
 */
private val CONDITION_TEXT_CODES = POSITIONED_CONDITION_CODES + POST_PARSE_CONDITION_CODES
