package io.github.mbackschat.a12.dm.bridge

import java.util.Optional

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

import io.github.mbackschat.a12.dm.core.ModelIndex
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.interpreter.model.ConditionLanguage
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.CustomTypeValidity
import io.github.mbackschat.a12.dm.rulekit.dsl.DateConstruction
import io.github.mbackschat.a12.dm.rulekit.dsl.DateValidity
import io.github.mbackschat.a12.dm.rulekit.dsl.EntityListPredicate
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListEntityListCondition
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupListPredicate
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupListRef
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupPresenceCondition
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupQuantifier
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupRef
import io.github.mbackschat.a12.dm.rulekit.dsl.LogicCondition
import io.github.mbackschat.a12.dm.rulekit.dsl.Quantifier
import io.github.mbackschat.a12.dm.rulekit.dsl.RepetitionNotUniqueCondition
import io.github.mbackschat.a12.dm.rulekit.dsl.ValueListCondition

/**
 * The predicate family — the uniqueness pair (`RepetitionNotUnique`, `FieldValuesNotUnique`), the
 * group-list entity quantifiers, and `FieldsNotCollectivelyFilled`, mapped with no kernel present.
 *
 * Three outcomes, kept apart: a converted [Condition], an [IllegalArgumentException] that is
 * rulekit rejecting the rule, and [UnmappedConstructException] for a shape the bridge does not cover.
 * The tests that assert a rule *converts* where the kernel would still refuse it are deliberate — they
 * record which gates rulekit delegates rather than owns.
 */
class PredicatesTest {

    private lateinit var bridge: ConditionBridge

    @BeforeEach
    fun bind() {
        val json = BridgeModels.orderJson
        bridge = ConditionBridge(BoundModel.ofJson(json), ModelIndex.of(json, ReferenceResolvers.selfContained()))
    }

    private fun translate(condition: String): Condition = bridge.translate(condition, BASE, ConditionLanguage.EN)

    // --- RepetitionNotUnique -------------------------------------------------------------------

    @Test
    fun aSingleKeyRepetitionNotUniqueConverts() {
        val c = assertInstanceOf(RepetitionNotUniqueCondition::class.java,
            translate("RepetitionNotUnique(Items/Sku)"))
        assertEquals(1, c.keys().size)
        assertEquals("/Order/Items/Sku", c.keys().get(0).path().value())
        assertEquals(Optional.empty<GroupRef>(), c.scope())
    }

    @Test
    fun aCompositeKeyRepetitionNotUniqueConverts() {
        val c = assertInstanceOf(RepetitionNotUniqueCondition::class.java,
            translate("RepetitionNotUnique(Items/Sku, Items/Count)"))
        assertEquals(2, c.keys().size)
    }

    /** The predicate name reaching the bridge is canonical English however the rule was authored. */
    @Test
    fun theGermanKeywordConvertsToTheSameNode() {
        assertInstanceOf(RepetitionNotUniqueCondition::class.java,
            bridge.translate("WiederholungNichtEindeutig(Items/Sku)", BASE, ConditionLanguage.DE))
    }

    @Test
    fun anAbsoluteFromScopeConverts() {
        val c = assertInstanceOf(RepetitionNotUniqueCondition::class.java,
            translate("RepetitionNotUnique(Items/Sku @From /Order/Items)"))
        assertEquals("/Order/Items", c.scope().orElseThrow().path().value())
    }

    /** MVK_GROUP_NOT_REPEATABLE — the @From reference group must repeat. */
    @Test
    fun aNonRepeatableFromScopeIsRejected() {
        val e = assertThrows<IllegalArgumentException> {
            translate("RepetitionNotUnique(Items/Sku @From /Order/ShippingAddress)")
        }
        assertTrue(e.message!!.contains("MVK_GROUP_NOT_REPEATABLE")) {
            "expected the kernel's own code in: ${e.message}"
        }
    }

    /** Keys are plain fields by signature, so a group key never reaches a node. */
    @Test
    fun aGroupKeyIsRejected() {
        val e = assertThrows<IllegalArgumentException> { translate("RepetitionNotUnique(ShippingAddress)") }
        assertTrue(e.message!!.contains("not a field")) { "expected a field-slot rejection in: ${e.message}" }
    }

    /**
     * A category projection is not a referenceable key operand and rulekit has no node for one in this slot.
     * That is an absence of coverage, not a verdict — so it must not arrive as a rejection.
     */
    @Test
    fun anEnumCategoryKeyIsNotMapped() {
        assertThrows<UnmappedConstructException> { translate("RepetitionNotUnique(Priority -> Bucket)") }
    }

    @Test
    fun aRelativeFromScopeConverts() {
        val c = assertInstanceOf(RepetitionNotUniqueCondition::class.java,
            translate("RepetitionNotUnique(Items/Sku @From Items)"))
        assertEquals("/Order/Items", c.scope().orElseThrow().path().value())
    }

    /** The `..` run walks up from the rule's group before descending. */
    @Test
    fun aParentRelativeFromScopeConverts() {
        val c = assertInstanceOf(RepetitionNotUniqueCondition::class.java,
            bridge.translate("RepetitionNotUnique(Sku @From ../Items)", "/Order/Items", ConditionLanguage.EN))
        assertEquals("/Order/Items", c.scope().orElseThrow().path().value())
    }

    /** `RuleGroup` as the scope is the rule's own group, not a child path. */
    @Test
    fun theRuleGroupFromScopeIsTheRulesOwnGroup() {
        val c = assertInstanceOf(RepetitionNotUniqueCondition::class.java,
            bridge.translate("RepetitionNotUnique(Sku @From RuleGroup)", "/Order/Items", ConditionLanguage.EN))
        assertEquals("/Order/Items", c.scope().orElseThrow().path().value())
    }

    /**
     * The German spelling of that same reserved token. A scope token is captured before canonicalisation, so
     * `RegelKontext` arrives verbatim and must not be read as a child group.
     */
    @Test
    fun theGermanRuleGroupFromScopeIsTheRulesOwnGroup() {
        val c = assertInstanceOf(RepetitionNotUniqueCondition::class.java,
            bridge.translate("WiederholungNichtEindeutig(Sku @Von RegelKontext)", "/Order/Items",
                ConditionLanguage.DE))
        assertEquals("/Order/Items", c.scope().orElseThrow().path().value())
    }

    /** The resolved scope is looked up in the model, so a name that is not there is a rejection. */
    @Test
    fun anUnknownFromScopeIsRejected() {
        val e = assertThrows<IllegalArgumentException> { translate("RepetitionNotUnique(Items/Sku @From Nope)") }
        assertTrue(e.message!!.contains("/Order/Nope")) { "expected the resolved path in: ${e.message}" }
    }

    /** A starred key is a flattened repetition list, not a plain field, so it never reaches a node. */
    @Test
    fun aStarredKeyIsNotMapped() {
        assertThrows<UnmappedConstructException> { translate("RepetitionNotUnique(Items*/Sku)") }
    }

    /** The catalog's valid example — an ordinary leaf under Or. */
    @Test
    fun repetitionNotUniqueComposesUnderOr() {
        val c = assertInstanceOf(LogicCondition::class.java,
            translate("RepetitionNotUnique(Items/Sku) Or FieldFilled(Items/Count)"))
        assertInstanceOf(RepetitionNotUniqueCondition::class.java, c.left())
    }

    // --- FieldFilled / FieldNotFilled -------------------------------------------------------------

    @Test
    fun fieldFilledOverAFieldConverts() {
        assertNotNull(translate("FieldFilled(CustomerName)"))
    }

    @Test
    fun fieldNotFilledOverAFieldConverts() {
        assertNotNull(translate("FieldNotFilled(CustomerName)"))
    }

    /**
     * `Dsl.fieldFilled`/`fieldNotFilled` take a lone `FieldRef`, with no N-ary overload — the kernel's own
     * grammar agrees, refusing a second operand in the parser ("muss genau ein Parameter angegeben werden",
     * no MVK code). So a multi-operand call is [NoTypedOperationException] rather than a rulekit rejection
     * or a bridge gap.
     */
    @Test
    fun fieldFilledOverTwoFieldsIsNoTypedOperation() {
        val e = assertThrows<NoTypedOperationException> { translate("FieldFilled(CustomerName, ProductCode)") }
        assertTrue(e.message!!.contains("a single field")) { "unexpected message: ${e.message}" }
    }

    /** `FieldNotFilled`'s twin of the same arity gate. */
    @Test
    fun fieldNotFilledOverTwoFieldsIsNoTypedOperation() {
        assertThrows<NoTypedOperationException> { translate("FieldNotFilled(CustomerName, ProductCode)") }
    }

    // --- GroupFilled / GroupNotFilled -----------------------------------------------------------

    @Test
    fun groupNotFilledConverts() {
        val c = assertInstanceOf(GroupPresenceCondition::class.java, translate("GroupNotFilled(ShippingAddress)"))
        assertEquals(false, c.filled())
        assertEquals("/Order/ShippingAddress", c.group().path().value())
    }

    /**
     * The operand is a group reference, never a field (catalog constraint). `GroupRef` resolution
     * carries no MVK code of its own — the model index's own "not a group" check raises it.
     */
    @Test
    fun groupNotFilledOverAFieldIsRejected() {
        val e = assertThrows<IllegalArgumentException> { translate("GroupNotFilled(CustomerName)") }
        assertTrue(e.message!!.contains("not a group")) { "expected a group-slot rejection in: ${e.message}" }
    }

    /**
     * The `$`-marked group-presence leaf reads back WITH its marker rather than being refused.
     *
     * <p>It used to raise `UnmappedConstructException` whose message said rulekit "correlates a field or a
     * repetition list, not a group" — true when written, false from the moment `GroupPresenceCondition` gained
     * the marker (API-FINDINGS 135), which also left the two read-back providers disagreeing on a
     * kernel-legal shape: the kernel-backed converter produced it while this one refused it. A cold review
     * caught both halves, 2026-09-04.
     */
    @Test
    fun aMarkedGroupPresenceLeafReadsBackWithItsMarker() {
        val marked = assertInstanceOf(GroupPresenceCondition::class.java, translate("GroupFilled(\$ShippingAddress)"))
        assertEquals(true, marked.outer(), "the \$ must survive the read, not be normalized away")
        assertEquals("/Order/ShippingAddress", marked.group().path().value())

        val plain = assertInstanceOf(GroupPresenceCondition::class.java, translate("GroupFilled(ShippingAddress)"))
        assertEquals(false, plain.outer(), "…and the unmarked spelling stays unmarked — the control")
    }

    /** The polarity sibling carries it too, so the marker belongs to the CARRIER and not to one operator. */
    @Test
    fun theMarkerSurvivesOnGroupNotFilledAsWell() {
        val c = assertInstanceOf(GroupPresenceCondition::class.java, translate("GroupNotFilled(\$ShippingAddress)"))
        assertEquals(true, c.outer())
        assertEquals(false, c.filled())
    }

    // --- AtLeastOneGroupFilled / NoGroupFilled --------------------------------------------------

    @Test
    fun atLeastOneGroupFilledOverTwoGroupsConverts() {
        val c = assertInstanceOf(GroupListPredicate::class.java,
            translate("AtLeastOneGroupFilled(ShippingAddress, BillingAddress)"))
        assertEquals(GroupQuantifier.AT_LEAST_ONE_GROUP_FILLED, c.quantifier())
        assertEquals(2, c.groups().size)
    }

    @Test
    fun noGroupFilledOverTwoGroupsConverts() {
        val c = assertInstanceOf(GroupListPredicate::class.java,
            translate("NoGroupFilled(ShippingAddress, BillingAddress)"))
        assertEquals(GroupQuantifier.NO_GROUP_FILLED, c.quantifier())
    }

    /** Despite the name, the entity list admits a checked field beside a group scope. */
    @Test
    fun aMixedEntityListConverts() {
        val c = assertInstanceOf(GroupListPredicate::class.java,
            translate("AtLeastOneGroupFilled(CustomerName, ShippingAddress)"))
        assertInstanceOf(FieldRef::class.java, c.operands().get(0))
        assertInstanceOf(GroupListRef::class.java, c.operands().get(1))
    }

    /** `RuleGroup` (the kernel's `RegelKontext`) is the rule's own group, not a child path. */
    @Test
    fun theRuleGroupSelfReferenceResolvesToTheBaseGroup() {
        val c = assertInstanceOf(GroupListPredicate::class.java, translate("AtLeastOneGroupFilled(RuleGroup)"))
        assertEquals(BASE, c.groups().get(0).path().value())
    }

    /**
     * MVK_ROOT_GROUP_WITH_OTHER_PARAMETERS is still the kernel's, not rulekit's — but it cannot be reached on
     * a SINGLE-root model, because every second operand is inside that root and containment refuses first.
     *
     * <p>The kernel answers `MVK_DUPLICATE_PARAM2` for this exact shape, so rulekit refusing it is agreement
     * rather than over-reach; a witness for the root-group bar itself needs a two-root fixture, which is where
     * `rulekit.validate.laws.GroupQuantifierLawsTest` keeps it. Measured in
     * `rulekit.validate.laws.DuplicateOverlapCarrierSweepLawsTest`.
     */
    @Test
    fun aRootGroupBesideAFieldInsideItIsTheOverlapRefusal() {
        val thrown = assertThrows<IllegalArgumentException> {
            translate("AtLeastOneGroupFilled(/Order, CustomerName)")
        }
        assertTrue(thrown.message!!.contains("MVK_DUPLICATE_PARAM2")) {
            "expected the kernel's own overlap code in: ${thrown.message}"
        }
    }

    /** `NoGroupFilled(G*)` takes the same starred single-group form as `AtLeastOneGroupFilled`. */
    @Test
    fun noGroupFilledOverAStarredSingleGroupConverts() {
        val c = assertInstanceOf(GroupListPredicate::class.java, translate("NoGroupFilled(Items*)"))
        assertInstanceOf(GroupListRef::class.java, c.operands().get(0))
    }

    /** A starred group scope beside a plain field is still the mixed entity list. */
    @Test
    fun aStarredGroupBesideAFieldConverts() {
        val c = assertInstanceOf(GroupListPredicate::class.java,
            translate("AtLeastOneGroupFilled(Items*, CustomerName)"))
        assertInstanceOf(GroupListRef::class.java, c.operands().get(0))
        assertInstanceOf(FieldRef::class.java, c.operands().get(1))
    }

    // --- GroupsNotCollectivelyFilled / NotAllGroupsFilled ---------------------------------------

    @Test
    fun groupsNotCollectivelyFilledOverTwoGroupsConverts() {
        val c = assertInstanceOf(GroupListPredicate::class.java,
            translate("GroupsNotCollectivelyFilled(ShippingAddress, BillingAddress)"))
        assertEquals(GroupQuantifier.GROUPS_NOT_COLLECTIVELY_FILLED, c.quantifier())
        assertEquals(2, c.groups().size)
    }

    /** Despite the name, a checked field is a legal member beside a group scope. */
    @Test
    fun groupsNotCollectivelyFilledOverAMixedListConverts() {
        val c = assertInstanceOf(GroupListPredicate::class.java,
            translate("GroupsNotCollectivelyFilled(CustomerName, ShippingAddress)"))
        assertInstanceOf(FieldRef::class.java, c.operands().get(0))
        assertInstanceOf(GroupListRef::class.java, c.operands().get(1))
    }

    @Test
    fun notAllGroupsFilledOverTwoGroupsConverts() {
        val c = assertInstanceOf(GroupListPredicate::class.java,
            translate("NotAllGroupsFilled(ShippingAddress, BillingAddress)"))
        assertEquals(GroupQuantifier.NOT_ALL_GROUPS_FILLED, c.quantifier())
        assertEquals(2, c.groups().size)
    }

    /**
     * MVK_NO_WILDCARDS_G_ALLOWED bans a starred operand for these two quantifiers, but that gate stays
     * kernel-arbitrated (the typed surface admits a [GroupListRef] regardless of quantifier), so this
     * converts here rather than being caught.
     */
    @Test
    fun aStarredOperandOnNotAllGroupsFilledIsNotRulekitsToRefuse() {
        assertNotNull(translate("NotAllGroupsFilled(Items*, CustomerName)"))
    }

    // --- Invalid (custom field type) ------------------------------------------------------------

    /**
     * `Invalid(field, "TypeName")` — the custom-field-type overload (catalog id `InvalidType`);
     * the type name is opaque to model-check-time validation (KERNEL-FINDINGS / item 10), so any string
     * converts here.
     */
    @Test
    fun invalidTypeOverAStringFieldConverts() {
        val c = assertInstanceOf(CustomTypeValidity::class.java, translate("Invalid(ProductCode, \"IBAN\")"))
        assertEquals(false, c.valid())
        assertEquals("/Order/ProductCode", c.field().path().value())
        assertEquals("IBAN", c.typeName())
    }

    /** operand 1 may also be an ENUMERATION field (catalog constraint). */
    @Test
    fun invalidTypeOverAnEnumerationFieldConverts() {
        val c = assertInstanceOf(CustomTypeValidity::class.java, translate("Invalid(Priority, \"SomeCustomType\")"))
        assertEquals("/Order/Priority", c.field().path().value())
    }

    /** MVK_NO_STRING_OR_ENUM_OR_EXT_ENUM — operand 1 must be a STRING or ENUMERATION field. */
    @Test
    fun invalidTypeOverANumberFieldIsRejected() {
        val e = assertThrows<IllegalArgumentException> { translate("Invalid(Quantity, \"SomeCustomType\")") }
        assertTrue(e.message!!.contains("STRING or ENUMERATION")) { "expected a kind rejection in: ${e.message}" }
    }

    /**
     * The date-construct overload (`Invalid(Date(...))`, a distinct catalog entry despite the same
     * keyword) — via `Dsl.invalid(DateOperand)`.
     */
    @Test
    fun theDateConstructOverloadConverts() {
        val c = assertInstanceOf(DateValidity::class.java,
            translate("Invalid(Date(BirthDay, BirthMonth, BirthYear))"))
        assertEquals(false, c.valid())
        assertInstanceOf(DateConstruction::class.java, c.dateConstruct())
    }

    /**
     * A bare field is not a `Date(...)` construction — the only shape the catalog admits here (and the
     * only one the kernel's own grammar produces for this keyword; a plain-field operand is a PARSE-time
     * refusal upstream), so it stays unmapped rather than treated as a date value.
     */
    @Test
    fun invalidOverABareFieldIsNotMapped() {
        assertThrows<UnmappedConstructException> { translate("Invalid(OrderDate)") }
    }

    // --- FieldsNotCollectivelyFilled ------------------------------------------------------------

    @Test
    fun fieldsNotCollectivelyFilledOverAFieldListConverts() {
        val c = assertInstanceOf(EntityListPredicate::class.java,
            translate("FieldsNotCollectivelyFilled(CustomerName, ProductCode, Items/Sku)"))
        assertEquals(Quantifier.FIELDS_NOT_COLLECTIVELY_FILLED, c.quantifier())
        assertEquals(3, c.operands().size)
    }

    /** The catalog's valid example — a group whose recursively contained fields form the scope. */
    @Test
    fun fieldsNotCollectivelyFilledOverAGroupScopeConverts() {
        val c = assertInstanceOf(EntityListPredicate::class.java,
            translate("FieldsNotCollectivelyFilled(ShippingAddress)"))
        assertTrue(c.hasGroupScope())
    }

    // --- FieldValuesNotUnique -------------------------------------------------------------------

    @Test
    fun fieldValuesNotUniqueOverTwoFieldsConverts() {
        val c = assertInstanceOf(EntityListPredicate::class.java,
            translate("FieldValuesNotUnique(CustomerName, ProductCode)"))
        assertEquals(Quantifier.FIELD_VALUES_NOT_UNIQUE, c.quantifier())
        assertEquals(2, c.operands().size)
    }

    /** The flattened list mixes with a plain field, same as any other entity-list member. */
    @Test
    fun fieldValuesNotUniqueOverAFlattenedFieldListAndAFieldConverts() {
        val c = assertInstanceOf(EntityListPredicate::class.java,
            translate("FieldValuesNotUnique(Items*/Sku, CustomerName)"))
        assertEquals(2, c.operands().size)
        assertFalse(c.hasGroupScope())
    }

    /**
     * The comparability gate (MVK_VARYING_TYPES_NOT_ALLOWED) reads the operand list's expansion and stays
     * kernel-arbitrated, so a String beside a Number converts here.
     */
    @Test
    fun aMixedKindValueListIsNotRulekitsToRefuse() {
        assertNotNull(translate("FieldValuesNotUnique(CustomerName, Quantity)"))
    }

    // --- starred operands ------------------------------------------------------------------------

    /** A starred group scope scans instantiated repetitions; the star must survive onto the operand. */
    @Test
    fun aStarredGroupScopeKeepsItsStar() {
        val c = assertInstanceOf(GroupListPredicate::class.java, translate("AtLeastOneGroupFilled(Items*)"))
        val scope = assertInstanceOf(GroupListRef::class.java, c.operands().get(0))
        assertEquals("/Order/Items*", scope.renderedPath())
    }

    /** MVK_INVALID_WILDCARD — a star is only legal on a repeatable group. */
    @Test
    fun aStarOnANonRepeatableGroupIsRejected() {
        val e = assertThrows<IllegalArgumentException> { translate("AtLeastOneGroupFilled(ShippingAddress*)") }
        assertTrue(e.message!!.contains("MVK_INVALID_WILDCARD")) {
            "expected the kernel's own code in: ${e.message}"
        }
    }

    /**
     * A flattened repetition list is not an arm of the entity list, so rulekit cannot represent it here. That
     * is an absence of coverage rather than a verdict, and must not be counted as a rejection.
     */
    @Test
    fun aStarredFieldInTheEntityListIsNotMapped() {
        assertThrows<UnmappedConstructException> { translate("AtLeastOneGroupFilled(Items*/Sku)") }
    }

    /** The catalog's valid example — one flattened repetition list satisfies the arity on its own. */
    @Test
    fun fieldValuesNotUniqueOverARepetitionListConverts() {
        val c = assertInstanceOf(EntityListPredicate::class.java, translate("FieldValuesNotUnique(Items*/Sku)"))
        assertEquals("/Order/Items*/Sku", c.operands().get(0).renderedPath())
    }

    @Test
    fun fieldsNotCollectivelyFilledOverAStarredGroupScopeConverts() {
        val c = assertInstanceOf(EntityListPredicate::class.java,
            translate("FieldsNotCollectivelyFilled(Items*)"))
        assertTrue(c.hasGroupScope())
    }

    // --- AllGroupsFilled -------------------------------------------------------------------------

    @Test
    fun allGroupsFilledOverTwoGroupsConverts() {
        val c = assertInstanceOf(GroupListPredicate::class.java,
            translate("AllGroupsFilled(ShippingAddress, BillingAddress)"))
        assertEquals(GroupQuantifier.ALL_GROUPS_FILLED, c.quantifier())
        assertEquals(2, c.groups().size)
    }

    /** Despite the name, a checked field is a legal member beside a group scope. */
    @Test
    fun allGroupsFilledOverAMixedListConverts() {
        val c = assertInstanceOf(GroupListPredicate::class.java,
            translate("AllGroupsFilled(CustomerName, ShippingAddress)"))
        assertInstanceOf(FieldRef::class.java, c.operands().get(0))
        assertInstanceOf(GroupListRef::class.java, c.operands().get(1))
    }

    /**
     * MVK_PARAMSIZE_INVALID2 rejects a singleton, but that arity gate stays kernel-arbitrated (the typed
     * surface admits any non-empty entity list), so this converts here rather than being caught.
     */
    @Test
    fun aSingletonAllGroupsFilledIsNotRulekitsToRefuse() {
        assertNotNull(translate("AllGroupsFilled(ShippingAddress)"))
    }

    // --- Valid (custom field type) -----------------------------------------------------------------

    /**
     * `Valid(field, "TypeName")` — the positive custom-field-type overload (catalog id
     * `ValidType`), the counterpart of `Invalid` mapped via `Dsl.invalidType`.
     */
    @Test
    fun validTypeOverAStringFieldConverts() {
        val c = assertInstanceOf(CustomTypeValidity::class.java, translate("Valid(ProductCode, \"IBAN\")"))
        assertEquals(true, c.valid())
        assertEquals("/Order/ProductCode", c.field().path().value())
        assertEquals("IBAN", c.typeName())
    }

    /** operand 1 may also be an ENUMERATION field (catalog constraint). */
    @Test
    fun validTypeOverAnEnumerationFieldConverts() {
        val c = assertInstanceOf(CustomTypeValidity::class.java, translate("Valid(Priority, \"SomeCustomType\")"))
        assertEquals("/Order/Priority", c.field().path().value())
    }

    /** MVK_NO_STRING_OR_ENUM_OR_EXT_ENUM — operand 1 must be a STRING or ENUMERATION field. */
    @Test
    fun validTypeOverANumberFieldIsRejected() {
        val e = assertThrows<IllegalArgumentException> { translate("Valid(Quantity, \"SomeCustomType\")") }
        assertTrue(e.message!!.contains("STRING or ENUMERATION")) { "expected a kind rejection in: ${e.message}" }
    }

    /**
     * The date-construct overload (`Valid(Date(...))`, a distinct catalog entry despite the same
     * keyword) — via `Dsl.valid(DateOperand)`.
     */
    @Test
    fun theValidDateConstructOverloadConverts() {
        val c = assertInstanceOf(DateValidity::class.java,
            translate("Valid(Date(BirthDay, BirthMonth, BirthYear))"))
        assertEquals(true, c.valid())
        assertInstanceOf(DateConstruction::class.java, c.dateConstruct())
    }

    // --- DateRangesOverlap -------------------------------------------------------------------------

    /** The catalog's valid example — a scalar range and a starred repetition list of ranges. */
    @Test
    fun dateRangesOverlapOverAScalarAndAStarredListConverts() {
        val c = assertInstanceOf(EntityListPredicate::class.java,
            translate("DateRangesOverlap(CoverageWindow, Items*/ItemCoverage)"))
        assertEquals(Quantifier.DATE_RANGES_OVERLAP, c.quantifier())
        assertEquals(2, c.operands().size)
    }

    /** Both operands are plain fields (no star) when the rule itself is scoped inside the repeatable group. */
    @Test
    fun dateRangesOverlapOverTwoPlainFieldsConverts() {
        val c = assertInstanceOf(EntityListPredicate::class.java,
            bridge.translate("DateRangesOverlap(../CoverageWindow, ItemCoverage)", "/Order/Items",
                ConditionLanguage.EN))
        assertEquals(2, c.operands().size)
        assertInstanceOf(FieldRef::class.java, c.operands().get(0))
        assertInstanceOf(FieldRef::class.java, c.operands().get(1))
    }

    // --- AtLeastOneDateRangeOverlaps ---------------------------------------------------------------

    /** The catalog's valid example — a scalar range against a starred repetition list of ranges. */
    @Test
    fun atLeastOneDateRangeOverlapsOverAStarredListConverts() {
        val c = assertInstanceOf(FieldListEntityListCondition::class.java,
            translate("AtLeastOneDateRangeOverlaps(CoverageWindow In Items*/ItemCoverage)"))
        assertEquals(1, c.fields().size)
        assertEquals(1, c.valueFields().size)
    }

    @Test
    fun atLeastOneDateRangeOverlapsOverTwoListedRangesConverts() {
        val c = assertInstanceOf(FieldListEntityListCondition::class.java,
            translate("AtLeastOneDateRangeOverlaps(CoverageWindow In Items*/ItemCoverage, Items*/ItemCoverage)"))
        assertEquals(2, c.valueFields().size)
    }

    /** With no `In` keyword the value list is empty — a shape the DSL builder cannot construct. */
    @Test
    fun atLeastOneDateRangeOverlapsWithNoListedRangeIsNotMapped() {
        assertThrows<UnmappedConstructException> { translate("AtLeastOneDateRangeOverlaps(CoverageWindow)") }
    }

    // --- FieldValueIncludedInValueList / FieldValueNotIncludedInValueList ----------------------------

    @Test
    fun fieldValueIncludedInValueListOverAStringFieldConverts() {
        val c = assertInstanceOf(ValueListCondition::class.java,
            translate("FieldValueIncludedInValueList(ProductCode, \"ABC\", \"XYZ\")"))
        assertEquals(true, c.included())
        assertEquals("/Order/ProductCode", c.field().path().value())
        assertEquals(2, c.values().size)
    }

    @Test
    fun fieldValueNotIncludedInValueListOverAStringFieldConverts() {
        val c = assertInstanceOf(ValueListCondition::class.java,
            translate("FieldValueNotIncludedInValueList(ProductCode, \"ABC\")"))
        assertEquals(false, c.included())
    }

    /** An enumeration field's literal list is domain-checked (catalog constraint). */
    @Test
    fun fieldValueIncludedInValueListOverAnEnumerationFieldConverts() {
        val c = assertInstanceOf(ValueListCondition::class.java,
            translate("FieldValueIncludedInValueList(Priority, \"EXPRESS\", \"OVERNIGHT\")"))
        assertEquals("/Order/Priority", c.field().path().value())
        assertEquals(2, c.values().size)
    }

    /** MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION — a listed literal outside the enum's domain. */
    @Test
    fun fieldValueIncludedInValueListOverAnEnumerationFieldWithAnOutOfDomainLiteralIsRejected() {
        val e = assertThrows<IllegalArgumentException> {
            translate("FieldValueIncludedInValueList(Priority, \"BOGUS\")")
        }
        assertTrue(e.message!!.contains("MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION")) {
            "expected the kernel's own code in: ${e.message}"
        }
    }

    /** Number fields take integer literals (the `long...` signature). */
    @Test
    fun fieldValueIncludedInValueListOverANumberFieldConverts() {
        val c = assertInstanceOf(ValueListCondition::class.java,
            translate("FieldValueIncludedInValueList(Quantity, 0, 5, 10)"))
        assertEquals("/Order/Quantity", c.field().path().value())
        assertEquals(3, c.values().size)
    }

    /**
     * The kernel's grammar takes integer value-list literals only (a decimal is a parse error); a decimal
     * literal has no rulekit builder to land on, so it is an absence of coverage rather than a rejection.
     */
    @Test
    fun fieldValueIncludedInValueListOverANumberFieldWithADecimalLiteralIsNotMapped() {
        assertThrows<UnmappedConstructException> { translate("FieldValueIncludedInValueList(Quantity, 2.5)") }
    }

    /** DEV-API-GAPS DG10 — a value-list entry may be a field reference of the same kind. */
    @Test
    fun fieldValueIncludedInValueListOverStringFieldReferenceEntriesConverts() {
        val c = assertInstanceOf(ValueListCondition::class.java,
            translate("FieldValueIncludedInValueList(ProductCode, CustomerName)"))
        assertEquals(1, c.values().size)
    }

    @Test
    fun fieldValueIncludedInValueListOverNumberFieldReferenceEntriesConverts() {
        val c = assertInstanceOf(ValueListCondition::class.java,
            translate("FieldValueIncludedInValueList(Quantity, StockOnHand)"))
        assertEquals(1, c.values().size)
    }

    /** The kernel keeps literal and field-reference entries apart; mixing them has no rulekit node. */
    @Test
    fun fieldValueIncludedInValueListMixingALiteralAndAReferenceIsNotMapped() {
        assertThrows<UnmappedConstructException> {
            translate("FieldValueIncludedInValueList(ProductCode, \"ABC\", CustomerName)")
        }
    }

    /** MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED is the kernel's, but [Mapping.typedRef] has no builder for a
     *  BOOLEAN field at all, so this is an absence of coverage rather than a fabricated rejection. */
    @Test
    fun fieldValueIncludedInValueListOverABooleanFieldIsNotMapped() {
        assertThrows<UnmappedConstructException> { translate("FieldValueIncludedInValueList(ExpressShipping, \"x\")") }
    }

    /** A field with no listed value at all — the grammar shape has no rulekit node to land on. */
    @Test
    fun fieldValueIncludedInValueListWithNoValueIsNotMapped() {
        assertThrows<UnmappedConstructException> { translate("FieldValueIncludedInValueList(ProductCode)") }
    }

    private companion object {
        const val BASE = "/Order"
    }
}
