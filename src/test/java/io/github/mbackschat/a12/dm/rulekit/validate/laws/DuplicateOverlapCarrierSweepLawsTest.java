package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;

/**
 * KERNEL-FINDINGS §1 — the CARRIER sweep of the duplicate/overlap class: does each gate arm transfer to the
 * carriers our client side has never asked?
 *
 * <p>Nine laws classes already measure {@code MVK_DUPLICATE_PARAM1}/{@code 2}, and between them they cover the
 * entity-list aggregates, the group-list quantifiers, the value lists and {@code FirstFilledValue}. Every one of
 * those measurements was taken on the carrier whose gate was being designed, which leaves the arms untested
 * exactly where a client-side gate would now be wired. Three questions decide that wiring, and none of them can
 * be answered by reuse:
 *
 * <ul>
 *   <li><b>Does the wildcard skip transfer to {@code NumberOfValueInFields}?</b>
 *       {@code AggregateSlotLawsTest}'s header states it "does not share the entity-list wildcard-skip", and no
 *       row measures that: its only duplicate row is a DIRECT duplicate, which the skip cannot affect either
 *       way. A prose claim about kernel behaviour with no row behind it is load-bearing in both directions —
 *       wiring the skip under-reports if it is true, and omitting it over-rejects a legal repeated star if it
 *       is false.</li>
 *   <li><b>Does the field-list quantifier family skip a starred operand?</b> The measured wildcard bar
 *       ({@code MVK_NO_WILDCARDS_G_ALLOWED}) was taken on the GROUP-list quantifiers, a different carrier.</li>
 *   <li><b>Does the overlap arm skip wildcards?</b> {@code AggregateOperand}'s javadoc calls overlap "the one
 *       duplicate gate a wildcard does not escape", measured on the aggregates; whether it holds for a STARRED
 *       ancestor is a scope transfer nobody has run.</li>
 * </ul>
 *
 * <p><b>What these fixtures cannot express, stated because a silent limit reads as a law.</b> In
 * {@code order.dm.json} every group is a direct child of {@code /Order} and only {@code Items} repeats, so a
 * group containing a group, a repeatable inside a repeatable, and two distinct starred scopes are all
 * unbuildable there — hence the second table on {@code project.dm.json}, whose {@code Milestones ▸ Tasks} nests
 * two indexed repeatables. Neither fixture has a NON-repeatable group nested inside another group, so
 * "fixed ancestor, fixed descendant, both below the root" stays unmeasured here and keeps its coverage in
 * {@code FirstFilledValueDuplicatePrecedenceLawsTest}'s own {@code Primary ▸ Detail}. Every group in both
 * fixtures carries at least two fields, so an operand naming a group is never indistinguishable from an operand
 * naming the one field that group happens to have.
 *
 * <p>The leading {@code FieldFilled(Quantity)}/{@code FieldFilled(ProjectName)} satisfies
 * {@code MVK_ERROR_FIELD_NOT_REFERENCED} for the rows whose operands do not mention the error field. It is a
 * positive presence guard, which would silently admit every row of an ITERATION table
 * ([KF1011](#kf1011)) — nothing here turns on iteration, so it is inert.
 */
class DuplicateOverlapCarrierSweepLawsTest {

    private static final String P1 = "MVK_DUPLICATE_PARAM1";
    private static final String P2 = "MVK_DUPLICATE_PARAM2";

    /**
     * The shape neither committed fixture can express: a DATE field inside a group, which the temporal
     * aggregate's containment arm needs. Both group fields are dates of one format, so a kind or
     * component-set bar cannot be what decides the rows below. Field names avoid the condition grammar's
     * reserved words — {@code To} is one, and a fixture that uses it fails to parse in a way that reads like
     * a semantic verdict.
     */
    private static final String CALENDAR = io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
            .named("calendar").locale("en_US")
            .rootGroup("Cal", c -> c
                    .field("Ref", io.github.mbackschat.a12.dm.modelkit.FieldKind.STRING)
                    .dateField("Marker", d -> d.format("yyyy-MM-dd"))
                    .group("Window", w -> w
                            .dateField("Opens", d -> d.format("yyyy-MM-dd"))
                            .dateField("Closes", d -> d.format("yyyy-MM-dd"))))
            .build().toJson();

    private static String verdict(String json, String group, String errorField, String condition) {
        var result = KernelLaws.checkModel(json, group, errorField, condition);
        if (result.ok()) {
            return KernelLaws.ADMITTED;
        }
        return result.diagnostics().stream()
                .map(d -> d.code().orElse("NO_CODE"))
                .distinct()
                .sorted()
                .collect(Collectors.joining("+"));
    }

    private static String order(String condition) {
        return verdict(Fixtures.orderJson(), "/Order", "../Quantity", "FieldFilled(Quantity) And " + condition);
    }

    private static String project(String condition) {
        return verdict(Fixtures.projectJson(), "/Project", "../ProjectName",
                "FieldFilled(ProjectName) And " + condition);
    }

    /** One table, so a kernel shift shows every cell rather than aborting at the first. */
    @Test
    void everyCarrierAnswersBothDuplicateArmsOnItsOwnTerms() {
        var actual = new LinkedHashMap<String, String>();

        // --- NumberOfValueInFields: the carrier whose skip policy is claimed in prose and never measured.
        actual.put("NVF | direct duplicate", order("NumberOfValueInFields(0 In Quantity, Quantity) > 0"));
        actual.put("NVF | STARRED pair",
                order("NumberOfValueInFields(0 In Items*/Count, Items*/Count) > 0"));
        actual.put("NVF | group beside its own field",
                order("NumberOfValueInFields(0 In ShippingAddress, ShippingAddress/City) > 0"));

        // THE WITHIN-MODEL CONTROLS: the identical operand shapes on a carrier whose skip IS measured, one
        // builder-free variable apart (the operator name). A divergence below is about the carrier, not the shape.
        actual.put("Sum | direct duplicate", order("Sum(Quantity, Quantity) > 0"));
        actual.put("Sum | STARRED pair", order("Sum(Items*/Count, Items*/Count) > 0"));
        actual.put("Sum | group beside its own field",
                order("Sum(ShippingAddress, ShippingAddress/City) > 0"));

        // --- The FIELD-list quantifiers (EntityListPredicate), whose wildcard bar was measured on the
        // GROUP-list family instead. Our own cli PatternsScaffoldVerdictTest witnesses the direct arm as
        // kernel-caught today.
        actual.put("AllFieldsFilled | direct duplicate", order("AllFieldsFilled(Quantity, Quantity)"));
        actual.put("AllFieldsFilled | group beside its own field",
                order("AllFieldsFilled(ShippingAddress, ShippingAddress/City)"));
        actual.put("AllFieldsFilled | STARRED pair", order("AllFieldsFilled(Items*/Count, Items*/Count)"));

        // --- An UNSTARRED group scope repeated. This is the basis E1's widening needs: the shared identity
        // exempts a GroupListRef unconditionally today, which is right for a starred scope and wrong for a
        // bare one. Measured per carrier rather than transferred, since the exemption lives in the shared
        // check and the carriers differ only in what their list can hold.
        actual.put("aggregate | the same UNSTARRED non-repeatable group twice",
                order("Sum(ShippingAddress, ShippingAddress) > 0"));
        actual.put("aggregate | the same UNSTARRED repeatable group twice",
                order("Sum(Items, Items) > 0"));
        actual.put("group-list | the same UNSTARRED non-repeatable group twice",
                order("NoGroupFilled(ShippingAddress, ShippingAddress)"));

        // --- The ROOT group beside a field inside it. KF191 records in passing that a rootGroup plus a sibling
        // "trips MVK_DUPLICATE_PARAM2 first", which is prose of ours about a kernel verdict, and a bridge lock
        // asserts rulekit must not refuse this shape because the ROOT-GROUP bar is the kernel's. Both cannot be
        // right once containment is pre-checked, so the kernel is asked directly.
        actual.put("group-list | the ROOT group beside a field inside it",
                order("AtLeastOneGroupFilled(/Order, CustomerName)"));

        // --- The value-list carriers, whose gate ran only the EXACT arm before E6. The containment arm is
        // structurally the same shared check, but the widening gets a row on the carrier rather than a
        // transfer.
        actual.put("value-list | group beside its own field",
                order("NoFieldValueIncludedInValueList(ShippingAddress, ShippingAddress/City In \"x\")"));
        actual.put("value-list | the same field twice",
                order("NoFieldValueIncludedInValueList(CustomerName, CustomerName In \"x\")"));

        // --- The other side of the modifier question. The measured row above pairs an INDEXED ancestor with
        // an unindexed descendant and finds no duplicate; this pairs them under the SAME key, which is the
        // case a client gate comparing modifiers for equality will refuse. Unmeasured until now, and a bridge
        // lock asserted it stays kernel-arbitrated.
        actual.put("indexed field inside its own indexed group, SAME key",
                order("NumberOfFilledFields(Items/Count For \"a\", Items For \"a\") > 1"));
        actual.put("indexed field inside its own indexed group, DIFFERENT keys",
                order("NumberOfFilledFields(Items/Count For \"a\", Items For \"b\") > 1"));

        // --- The SCALAR value-list pair, the eighth fan-in construct. Its value list admits field references
        // beside literals, and the kernel checks the value list and the subject field as one union.
        actual.put("scalar value-list | subject repeated inside its own value list",
                order("FieldValueIncludedInValueList(CustomerName, [CustomerName])"));
        actual.put("scalar value-list | reference repeated within the value list",
                order("FieldValueIncludedInValueList(CustomerName, [ProductCode], [ProductCode])"));
        actual.put("scalar value-list | distinct subject and value reference",
                order("FieldValueIncludedInValueList(CustomerName, [ProductCode])"));
        // Why only the EXACT arm applies on this carrier: a group operand is refused outright, so no operand
        // can contain another.
        actual.put("scalar value-list | a GROUP operand",
                order("FieldValueIncludedInValueList(Quantity, [ShippingAddress])"));

        // --- The CATEGORY component of containment. The proposal had called this unmeasurable for want of an
        // enum field inside a group; the ROOT group is a container, so the pair is measurable on this fixture.
        actual.put("category | root group beside a plain read of a field inside it",
                order("NoFieldValueIncludedInValueList(/Order, Priority In \"STANDARD\")"));
        actual.put("category | root group beside a CATEGORY PROJECTION of that field",
                order("NoFieldValueIncludedInValueList(/Order, Priority -> SpeedClass In \"NORMAL\")"));

        // --- Is a CORRELATED index key authorable at all? The containment modifier folds the key field's $
        // for symmetry with the exact arm, and that fold can only matter if `For $X` and `For X` can stand in
        // one list. If they cannot, the fold changes no verdict and its mutant is expected to survive.
        actual.put("index key carrying a correlation, at a plain rule locus",
                order("NumberOfFilledFields(Items/Count For $Sku, ProductCode) > 1"));

        // --- The EXACT arm on the two indexed operand shapes. Their containment component is measured above;
        // their identity was covered only by a bridge-translation test in another module, so the rows that
        // justify a rulekit-side control belong here.
        actual.put("indexed field | same field, same key, twice",
                order("NumberOfFilledFields(Items/Count For \"a\", Items/Count For \"a\") > 1"));
        actual.put("indexed field | same field, DIFFERENT keys",
                order("NumberOfFilledFields(Items/Count For \"a\", Items/Count For \"b\") > 1"));
        actual.put("indexed group | same group, same key, twice",
                order("NumberOfFilledFields(Items For \"a\", Items For \"a\") > 1"));
        actual.put("indexed group | same group, DIFFERENT keys",
                order("NumberOfFilledFields(Items For \"a\", Items For \"b\") > 1"));

        // --- The TEMPORAL aggregate's containment arm, on the only fixture that can hold the shape.
        actual.put("temporal aggregate | group beside a date field inside it",
                verdict(CALENDAR, "/Cal", "../Marker", "MaxValue(/Cal/Window, /Cal/Window/Opens) == [/Cal/Marker]"));
        actual.put("temporal aggregate | two disjoint date fields",
                verdict(CALENDAR, "/Cal", "../Marker",
                        "MaxValue(/Cal/Window/Opens, /Cal/Window/Closes) == [/Cal/Marker]"));

        // --- RepetitionNotUnique: the repeated key is measured; a key pair spanning a nesting is not, and
        // cannot be built on this fixture (see the class note) — it takes the project table below.
        actual.put("RNU | repeated key",
                verdict(Fixtures.orderJson(), "/Order/Items", "../Count",
                        "RepetitionNotUnique(Sku, Sku)"));
        // The $ outer-iteration flag is part of every carrier's dedup identity, so a correlated key beside its
        // uncorrelated twin would have to be two operands rather than one. This locus cannot answer that: the
        // outer-iteration gate refuses the shape first, so the duplicate arm never speaks. Recorded because a
        // pre-empting gate is the reason a cell is unmeasurable, not evidence about the cell.
        actual.put("RNU | correlated key beside its uncorrelated twin",
                verdict(Fixtures.orderJson(), "/Order/Items", "../Count",
                        "RepetitionNotUnique($Sku, Sku)"));

        // --- SumOfProducts, whose two same-typed slots the kernel routes through the SAME getRefList fan-in as
        // Sum (CheckFeldListenOpImpl). Our carrier gates parallelism and not duplicates, so if the guard
        // reaches it the typed surface is rendering a condition the kernel refuses.
        actual.put("SumOfProducts | the same field on both sides",
                order("SumOfProducts(Quantity, Quantity) > 0"));
        actual.put("SumOfProducts | two distinct fields",
                order("SumOfProducts(Quantity, UnitPrice) > 0"));
        // The shape that decides whether a client gate here is REACHABLE: both operands starred at the
        // repeatable level the operator demands, and identical. If the wildcard skip admits it, then every
        // shape the duplicate arm would catch is already refused by the level requirement.
        actual.put("SumOfProducts | the same STARRED list on both sides",
                order("SumOfProducts(Items*/Count, Items*/Count) > 0"));

        // --- A Having filter is the one identity component the kernel compares and we do not. It is only
        // reachable here on a STARRED list, which the exact-duplicate arm skips regardless, so this row
        // measures whether the skip or the filter decides.
        actual.put("two starred lists differing ONLY in their Having filter",
                order("Sum(Items*/Count Having FieldFilled(Sku), Items*/Count Having FieldFilled(UnitWeight)) > 0"));
        // Whether the row above is decided by the SKIP or by the filter turns on this: if an UNSTARRED operand
        // may carry a Having filter, the filter can discriminate two otherwise-identical operands and a key
        // omitting it over-refuses. Measured rather than taken from our own renderer's javadoc, which is the
        // class of claim that already misled this sweep once.
        actual.put("an UNSTARRED operand carrying a Having filter",
                order("Sum(Quantity Having FieldFilled(ProductCode), UnitPrice) > 0"));
        // Does CONTAINMENT compare the modifiers, or only the path? FirstFilledValue's gate requires an equal
        // semantic index before it refuses an overlap, so if the kernel's indirect arm is a bare path-prefix
        // test our gate is NARROWER and under-reports. An index-selected group beside an unindexed descendant
        // of itself is the discriminator: identical paths, different modifiers.
        actual.put("index-selected group beside its own UNINDEXED descendant",
                order("Sum(Items For \"SKU-1\", Items/Count) > 0"));
        // The control, one variable apart: the same index-selected group beside a DISJOINT field.
        actual.put("index-selected group beside a disjoint field",
                order("Sum(Items For \"SKU-1\", ShippingAddress/City) > 0"));
        // The shape that CAN answer it. The descendant must be starred to be legal at all, and containment
        // does not skip a star, so a PARAM2 here means containment ignores the modifiers while anything else
        // means it compares them. The control above proves the indexed group resolves and expands.
        actual.put("index-selected group beside its own STARRED descendant",
                order("Sum(Items For \"SKU-1\", Items*/Count) > 0"));
        // THE WITHIN-MODEL CONTROL for the row above, one variable apart: the same ancestor group carrying a
        // STAR instead of an index selection. Without this the comparison would be against the project
        // fixture's starred-ancestry row, which moves the fixture as well as the modifier.
        actual.put("STARRED group beside its own starred descendant, same fixture",
                order("Sum(Items*, Items*/Count) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("NVF | direct duplicate", P1);
        expected.put("NVF | STARRED pair", KernelLaws.ADMITTED);
        expected.put("NVF | group beside its own field", P2);
        expected.put("Sum | direct duplicate", P1);
        expected.put("Sum | STARRED pair", KernelLaws.ADMITTED);
        expected.put("Sum | group beside its own field", P2);
        expected.put("AllFieldsFilled | direct duplicate", P1);
        expected.put("AllFieldsFilled | group beside its own field", P2);
        expected.put("AllFieldsFilled | STARRED pair", KernelLaws.ADMITTED);
        expected.put("SumOfProducts | the same field on both sides", P1);
        expected.put("SumOfProducts | two distinct fields", "MVK_REPEATABLE_LEVEL_REQUIRED");
        expected.put("SumOfProducts | the same STARRED list on both sides", KernelLaws.ADMITTED);
        expected.put("two starred lists differing ONLY in their Having filter", KernelLaws.ADMITTED);
        expected.put("an UNSTARRED operand carrying a Having filter", "MVK_WILDCARDS_REQUIRED");
        expected.put("index-selected group beside its own UNINDEXED descendant", "MVK_NO_WILDCARD");
        expected.put("index-selected group beside a disjoint field", "MVK_NO_NUMBER");
        expected.put("index-selected group beside its own STARRED descendant", "MVK_NO_NUMBER");
        expected.put("STARRED group beside its own starred descendant, same fixture", P2);
        expected.put("aggregate | the same UNSTARRED non-repeatable group twice", P1);
        expected.put("aggregate | the same UNSTARRED repeatable group twice", "MVK_NO_WILDCARD");
        expected.put("group-list | the same UNSTARRED non-repeatable group twice", P1);
        expected.put("group-list | the ROOT group beside a field inside it", P2);
        expected.put("value-list | group beside its own field", P2);
        expected.put("value-list | the same field twice", P1);
        expected.put("indexed field inside its own indexed group, SAME key", P2);
        expected.put("indexed field inside its own indexed group, DIFFERENT keys", KernelLaws.ADMITTED);
        expected.put("scalar value-list | subject repeated inside its own value list", P1);
        expected.put("scalar value-list | reference repeated within the value list", P1);
        expected.put("scalar value-list | distinct subject and value reference", KernelLaws.ADMITTED);
        expected.put("scalar value-list | a GROUP operand", "MVK_NO_GROUPS_ALLOWED");
        expected.put("category | root group beside a plain read of a field inside it", P2);
        expected.put("category | root group beside a CATEGORY PROJECTION of that field", "MVK_ROOT_GROUP_REFERENCED");
        expected.put("index key carrying a correlation, at a plain rule locus", "MVK_INVALID_OUTER_ITERATION");
        expected.put("indexed field | same field, same key, twice", P1);
        expected.put("indexed field | same field, DIFFERENT keys", KernelLaws.ADMITTED);
        expected.put("indexed group | same group, same key, twice", P1);
        expected.put("indexed group | same group, DIFFERENT keys", KernelLaws.ADMITTED);
        expected.put("temporal aggregate | group beside a date field inside it", P2);
        expected.put("temporal aggregate | two disjoint date fields", KernelLaws.ADMITTED);
        expected.put("RNU | repeated key", P1);
        expected.put("RNU | correlated key beside its uncorrelated twin", "MVK_INVALID_OUTER_ITERATION");

        assertEquals(expected, actual, "complete duplicate/overlap verdict per carrier");
    }

    /** The nesting the order fixture cannot express: a repeatable inside a repeatable, so a STARRED ancestor
     *  can stand beside a starred descendant. This is the overlap arm's untested scope transfer. */
    @Test
    void theOverlapArmAnswersForAStarredAncestor() {
        var actual = new LinkedHashMap<String, String>();

        actual.put("nested starred pair, repeated",
                project("Sum(Milestones*/Tasks*/Effort, Milestones*/Tasks*/Effort) > 0"));
        actual.put("starred group beside its nested starred descendant",
                project("Sum(Milestones*, Milestones*/Tasks*/Effort) > 0"));
        actual.put("starred group beside its own starred subgroup",
                project("Sum(Milestones*, Milestones*/Tasks*) > 0"));
        actual.put("FFV | starred group beside its nested starred descendant",
                project("FirstFilledValue(Milestones*, Milestones*/Tasks*/Effort) == \"x\""));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("nested starred pair, repeated", KernelLaws.ADMITTED);
        expected.put("starred group beside its nested starred descendant", P2);
        expected.put("starred group beside its own starred subgroup", P2);
        expected.put("FFV | starred group beside its nested starred descendant", P2);

        assertEquals(expected, actual, "complete overlap verdict for starred ancestry");
    }
}
