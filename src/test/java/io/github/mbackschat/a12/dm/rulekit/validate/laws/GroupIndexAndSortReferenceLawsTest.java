package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.Fixtures;

/**
 * The two relative field references A12 declares on a GROUP — {@code indexFieldName} and
 * {@code sortFields[].name} — measured because no lock covers them and the portable checker names both, in
 * every report, as members it does not resolve.
 *
 * <p>A checker cannot close that gap on the reference machinery it already has: what a rule's
 * {@code errorEntityRelPath} does when it names nothing ({@code MVK_ERROR_FIELD_MISSING}) is a law about that
 * member, and reusing it here is the scope transfer that needs no new branch and so prompts no question.
 *
 * <p>Own domain (the shared order fixture: the repeatable {@code /Order/Items} keyed by {@code Sku}, and the
 * non-repeatable {@code /Order/ShippingAddress}).
 */
class GroupIndexAndSortReferenceLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String REPEATABLE = "Items";
    private static final String NON_REPEATABLE = "ShippingAddress";

    @Test
    void theIndexFieldNameMustNameAFieldOfItsOwnGroup() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("Sku — the control", verdict(REPEATABLE, "indexFieldName", "\"Sku\""));
        actual.put("another field of the same group", verdict(REPEATABLE, "indexFieldName", "\"Count\""));
        actual.put("absent", verdict(REPEATABLE, "indexFieldName", null));
        actual.put("empty", verdict(REPEATABLE, "indexFieldName", "\"\""));
        actual.put("whitespace only", verdict(REPEATABLE, "indexFieldName", "\"   \""));
        actual.put("a field that does not exist", verdict(REPEATABLE, "indexFieldName", "\"Missing\""));
        actual.put("a field of the PARENT group", verdict(REPEATABLE, "indexFieldName", "\"CustomerName\""));
        actual.put("a relative path to one", verdict(REPEATABLE, "indexFieldName", "\"../CustomerName\""));
        actual.put("on a NON-repeatable group", verdict(NON_REPEATABLE, "indexFieldName", "\"Street\""));
        actual.put("unresolvable on a NON-repeatable group",
                verdict(NON_REPEATABLE, "indexFieldName", "\"Missing\""));
        actual.put("a .. that climbs to the root", verdict(REPEATABLE, "indexFieldName", "\"../../Sku\""));
        actual.put("a .. that climbs past the root", verdict(REPEATABLE, "indexFieldName", "\"../../../Sku\""));
        actual.put("a .. landing on the parent group", verdict(REPEATABLE, "indexFieldName", "\"..\""));
        actual.put("a RULE of the group", verdictWithRule("indexFieldName", "\"ItemCheck\""));
        actual.put("a descending path with no ..", verdict(REPEATABLE, "indexFieldName", "\"Items/Sku\""));
        actual.put("a child GROUP of the named group", verdict("Order", "indexFieldName", "\"Items\""));
        actual.put("a descending path that DOES resolve", verdict("Order", "indexFieldName", "\"Items/Sku\""));
        actual.put("an ABSOLUTE path to the group's own field",
                verdict(REPEATABLE, "indexFieldName", "\"/Order/Items/Sku\""));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Sku — the control", KernelLaws.ADMITTED);
        expected.put("another field of the same group", KernelLaws.ADMITTED);
        // An index is OPTIONAL, so the member's absence is the one shape that is simply legal.
        expected.put("absent", KernelLaws.ADMITTED);
        // Kernel 31.1.1 keeps the LAST SEGMENT of the spelling joined to the group's path, wherever it lands, and
        // judges that name against the group's own fields (KERNEL-FINDINGS KF653). On 30.8.1 every spelling that
        // landed on no field left no model. An empty spelling is joined to the group itself, so it keeps the
        // group's own name, and A12 adds the basic sentence the verdict drops as prose.
        expected.put("empty", "MVK_INVALID_INDEX_FIELD");
        expected.put("whitespace only", "MVK_INVALID_INDEX_FIELD");
        expected.put("a field that does not exist", "MVK_INVALID_INDEX_FIELD");
        // The scope is the group's OWN children: a field of the parent is not reachable from here.
        expected.put("a field of the PARENT group", "MVK_INVALID_INDEX_FIELD");
        expected.put("a relative path to one", "MVK_INVALID_INDEX_FIELD");
        expected.put("on a NON-repeatable group", KernelLaws.ADMITTED);
        // The check runs on every group, not only a repeatable one.
        expected.put("unresolvable on a NON-repeatable group", "MVK_INVALID_INDEX_FIELD");
        // `../../Sku` lands on nothing at the root and keeps `Sku`, a field of this group, so it is admitted; one
        // `..` more is a climb the reader cannot resolve.
        expected.put("a .. that climbs to the root", KernelLaws.ADMITTED);
        expected.put("a .. that climbs past the root", KernelLaws.UNREADABLE);
        // A GROUP landing is the one that still leaves no model: the reader binds the group where a field is
        // expected, and the cast fails ("Group cannot be cast to Field"). A rule is not in the reader's map,
        // which holds fields and groups, so landing on one is only a name.
        expected.put("a .. landing on the parent group", KernelLaws.NOT_BUILDABLE);
        expected.put("a RULE of the group", "MVK_INVALID_INDEX_FIELD");
        // `Items/Sku` read from INSIDE `/Order/Items` names nothing, and its last segment is this group's `Sku`.
        expected.put("a descending path with no ..", KernelLaws.ADMITTED);
        expected.put("a child GROUP of the named group", KernelLaws.NOT_BUILDABLE);
        // The same spelling read from `/Order` keeps `Sku`, which `/Order` does not carry.
        expected.put("a descending path that DOES resolve", "MVK_INVALID_INDEX_FIELD");
        // An absolute spelling is refused by the reader: `ElementPathUtils.splitRelPath` raises "illegal
        // relPath" during conversion, which the adapter reports as unreadable input (KERNEL-FINDINGS KF643). The
        // slot takes a position relative to the group, and the leading `/` is not a spelling of that.
        expected.put("an ABSOLUTE path to the group's own field", KernelLaws.UNREADABLE);
        assertEquals(expected, actual, "what A12 does with each shape of a group's indexFieldName");
    }

    @Test
    void aSortFieldMustNameAFieldOfItsOwnGroup() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("Sku — the control", verdict(REPEATABLE, "sortFields", "[{\"name\":\"Sku\"}]"));
        actual.put("empty list", verdict(REPEATABLE, "sortFields", "[]"));
        actual.put("a field that does not exist", verdict(REPEATABLE, "sortFields", "[{\"name\":\"Missing\"}]"));
        actual.put("an empty name", verdict(REPEATABLE, "sortFields", "[{\"name\":\"\"}]"));
        actual.put("an entry with no name", verdict(REPEATABLE, "sortFields", "[{}]"));
        actual.put("an absolute path", verdict(REPEATABLE, "sortFields", "[{\"name\":\"/Order/Items/Sku\"}]"));
        actual.put("a numeric name", verdict(REPEATABLE, "sortFields", "[{\"name\":5}]"));
        actual.put("a field OUTSIDE the group that resolves",
                verdict(REPEATABLE, "sortFields", "[{\"name\":\"../CustomerName\"}]"));
        actual.put("a child GROUP of the named group", verdict("Order", "sortFields", "[{\"name\":\"Items\"}]"));
        actual.put("a blank name", verdict(REPEATABLE, "sortFields", "[{\"name\":\"   \"}]"));
        actual.put("a RULE of the group", verdictWithRule("sortFields", "[{\"name\":\"ItemCheck\"}]"));
        actual.put("a .. that climbs to the root", verdict(REPEATABLE, "sortFields", "[{\"name\":\"../../Sku\"}]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Sku — the control", KernelLaws.ADMITTED);
        expected.put("empty list", KernelLaws.ADMITTED);
        // The same law as the index (KERNEL-FINDINGS KF653), asked on this member rather than transferred.
        expected.put("a field that does not exist", "MVK_INVALID_SORT_FIELD");
        // An empty or missing `name` binds no reference, and the converter dereferences it ("field is null").
        // On 30.8.1 the missing one was the reader's refusal.
        expected.put("an empty name", KernelLaws.NOT_BUILDABLE);
        expected.put("an entry with no name", KernelLaws.NOT_BUILDABLE);
        // The rows the sibling member answers with a CODE — asked here rather than transferred, because
        // `MVK_INVALID_INDEX_FIELD` is named for the index and nothing says a sort field shares its gate.
        expected.put("a field OUTSIDE the group that resolves", "MVK_INVALID_SORT_FIELD");
        expected.put("a child GROUP of the named group", KernelLaws.NOT_BUILDABLE);
        // Asked on THIS member rather than carried from the index, which is the transfer that needs no new
        // branch and so prompts no question.
        expected.put("an absolute path", KernelLaws.UNREADABLE);
        // A non-string `name` is COERCED to the String member, as a rule's `errorCode` is — so it is a name
        // that resolves to nothing, not a shape the reader refuses.
        expected.put("a numeric name", "MVK_INVALID_SORT_FIELD");
        // A blank name is an unfilled entry of A12's metamodel, which wants the field and the order together.
        expected.put("a blank name", "MVK_INVALID_SORT_FIELD,MVK_SORT_INFO_MISSING");
        expected.put("a RULE of the group", "MVK_INVALID_SORT_FIELD");
        expected.put("a .. that climbs to the root", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "what A12 does with each shape of a group's sortFields");
    }

    // The index/sort field's own TYPE is a separate family, measured in GroupOrderingFieldTypeLawsTest:
    // every admitted row here names a plain value-validating string field, so "a field of this group" and
    // "a field A12 accepts as a key" are one predicate in this file and must not be read as one claim.

    /**
     * Which of the two accounts of the mechanism A12 actually follows — the row neither table above could
     * separate.
     *
     * <p>Every fixture above has DISJOINT short names per level, so "the spelling landed outside this group"
     * and "no child of this group carries the landed field's short name" are one predicate on every row. They
     * come apart the moment one short name exists at two levels: {@code ../Sku} written in {@code /Order/Items}
     * lands on {@code /Order/Sku}, which is outside — and {@code /Order/Items} has a {@code Sku} of its own.
     *
     * <p>A checker built on the landing rejects that model. A checker built on the short name admits it. Only
     * A12 can say which is a rejection it actually makes, and it admits it: the SHORT NAME must name a field of
     * this group. On 30.8.1 the spelling also had to resolve to a field; on 31.1.1 it need not (KERNEL-FINDINGS
     * KF653), so the control no longer separates anything and is kept as the record of that change.
     */
    @Test
    void aDuplicateShortNameSeparatesTheLandingFromTheName() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("index ../Sku, no /Order/Sku — the control",
                verdict(REPEATABLE, "indexFieldName", "\"../Sku\"", false));
        actual.put("index ../Sku, with /Order/Sku",
                verdict(REPEATABLE, "indexFieldName", "\"../Sku\"", true));
        actual.put("sort ../Sku, with /Order/Sku",
                verdict(REPEATABLE, "sortFields", "[{\"name\":\"../Sku\"}]", true));
        actual.put("index ../CustomerName, with /Order/Sku",
                verdict(REPEATABLE, "indexFieldName", "\"../CustomerName\"", true));

        Map<String, String> expected = new LinkedHashMap<>();
        // Without the second field the spelling resolves to nothing, and its last segment is still this group's
        // `Sku` (on 30.8.1: no model).
        expected.put("index ../Sku, no /Order/Sku — the control", KernelLaws.ADMITTED);
        // The row that decides it: the landing is outside the group and A12 ADMITS the model, because
        // `/Order/Items` carries a `Sku` of its own. A checker judging the LANDING rejects a model A12 takes.
        expected.put("index ../Sku, with /Order/Sku", KernelLaws.ADMITTED);
        expected.put("sort ../Sku, with /Order/Sku", KernelLaws.ADMITTED);
        // …and the name is what matters, not the duplication: a spelling landing outside on a name this group
        // does NOT carry is still refused in the very same model.
        expected.put("index ../CustomerName, with /Order/Sku", "MVK_INVALID_INDEX_FIELD");
        assertEquals(expected, actual, "whether the landing or the short name decides");
    }

    /**
     * What a sort list on a NON-repeatable group draws, and what the four PRIORITY codes need.
     *
     * <p>[MVK-LEDGER] carries five sort laws as {@code n/a-structural}: sort info only on a repeatable group,
     * priorities unique, a priority not exceeding the entry count, and priorities contiguous. Four of them
     * are about a PRIORITY, and A12's DM-JSON {@code Group.SortField} declares exactly two members —
     * {@code field} and {@code order}. The position an {@code ISortField} reports is derived from the list
     * INDEX by {@code GroupWrapper.SortFieldWrapper}, so a stored model has no priority to duplicate, to
     * overrun, or to leave a hole in. Those four are unreachable by SHAPE rather than by policy.
     *
     * <p>The fifth is about the carrier and is a real DM-JSON shape, so it is measured rather than reasoned.
     */
    @Test
    void whatASortListOnANonRepeatableGroupDraws() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a sort list on a REPEATABLE group — the control",
                verdict(REPEATABLE, "sortFields", "[{\"name\":\"Sku\"}]"));
        actual.put("a sort list on a NON-repeatable group",
                verdict(NON_REPEATABLE, "sortFields", "[{\"name\":\"Street\"}]"));
        actual.put("an EMPTY sort list on a non-repeatable group",
                verdict(NON_REPEATABLE, "sortFields", "[]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a sort list on a REPEATABLE group — the control", KernelLaws.ADMITTED);
        // The fifth law IS reachable, unlike the four priority ones: sorting a group that has at most one
        // row is meaningless, and A12 says so with its own class.
        expected.put("a sort list on a NON-repeatable group", "MVK_INVALID_SORTINFO");
        // …and the member's PRESENCE is not the gate — an empty list is admitted on the same carrier, which
        // is what keeps a checker from refusing every non-repeatable group that carries the key at all.
        expected.put("an EMPTY sort list on a non-repeatable group", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "whether the carrier's repeatability gates a sort list");
    }

    /**
     * Whether {@code MVK_INDEX_FIELD_INVALID} is reachable, and from where.
     *
     * <p>Its firing site is {@code CheckEntityUtils.getIndexField}, reached from a CONDITION that uses a
     * semantic index, with the message "For '%s' the used index field '%s' does not exist". On kernel 31.1.1
     * an index field naming nothing in its group also builds, and a parallel join then reaches the same class
     * (KERNEL-FINDINGS KF653, {@code adapter.laws.CheckerUnresolvedParallelIndexAgreementTest}); this row
     * keeps the semantic-index route.
     *
     * <p>That gives one discriminating shape: an {@code indexFieldName} that RESOLVES but lands outside its
     * own group builds and draws a verdict rather than stopping the build, so a semantic index over that
     * group is the only way the condition-side lookup can meet an unusable declaration.
     */
    @Test
    void whetherTheConditionSideIndexLookupIsReachable() {
        String outsideItsGroup = probe(REPEATABLE, "indexFieldName", "\"../CustomerName\"");
        String valid = probe(REPEATABLE, "indexFieldName", "\"Sku\"");
        String semanticIndex = "AllFieldsFilled(Items For \"SKU-1\", CustomerName)";

        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a usable index, with the semantic index — the control",
                KernelLaws.verdictModel(valid, "/Order", "../CustomerName", semanticIndex));
        actual.put("an index resolving OUTSIDE its group, with the semantic index",
                KernelLaws.verdictModel(outsideItsGroup, "/Order", "../CustomerName", semanticIndex));
        actual.put("the same model with NO semantic index — the control",
                KernelLaws.verdictOf(outsideItsGroup));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a usable index, with the semantic index — the control", KernelLaws.ADMITTED);
        // BOTH classes: the declaration draws its own, and the condition-side lookup adds this one. The two
        // controls bracket it — the same condition over a usable index is admitted, and the same unusable
        // index without a semantic index draws only the declaration class. One variable moves per pair.
        expected.put("an index resolving OUTSIDE its group, with the semantic index",
                "MVK_INVALID_INDEX_FIELD,MVK_INDEX_FIELD_INVALID");
        expected.put("the same model with NO semantic index — the control", "MVK_INVALID_INDEX_FIELD");
        assertEquals(expected, actual, "whether the condition-side index lookup is reachable");
    }

    /**
     * What the two MULTIPLE-index codes need, and whether stored DM-JSON can reach them.
     *
     * <p>[MVK-LEDGER] framed both as declaration laws. They are thrown from {@code getIndexGroup}, which
     * walks an entity's ancestor groups and is called from {@code checkParallelIteration} — so the locus is
     * condition-side, not the declaration, and the kernel's own member is the plural
     * {@code IGroup.getIndexFieldNames()} while DM-JSON writes the singular {@code indexFieldName}.
     *
     * <p>These rows measure what that leaves reachable, rather than reasoning from the source read.
     */
    @Test
    void whatTheMultipleIndexCodesNeed() {
        String semanticIndex = "AllFieldsFilled(Items For \"SKU-1\", CustomerName)";

        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("one indexed group on the path — the control",
                KernelLaws.verdictModel(Fixtures.orderJson(), "/Order", "../CustomerName", semanticIndex));
        actual.put("an ancestor group ALSO declaring an index",
                KernelLaws.verdictModel(probe("Order", "indexFieldName", "\"CustomerName\""),
                        "/Order", "../CustomerName", semanticIndex));
        actual.put("two indexed groups but NO semantic index",
                KernelLaws.verdictOf(probe("Order", "indexFieldName", "\"CustomerName\"")));
        actual.put("a PLURAL indexFieldNames member instead",
                KernelLaws.verdictModel(probe(REPEATABLE, "indexFieldNames", "[\"Sku\",\"Count\"]"),
                        "/Order", "../CustomerName", semanticIndex));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("one indexed group on the path — the control", KernelLaws.ADMITTED);
        expected.put("an ancestor group ALSO declaring an index", "MVK_MULTIPLE_INDEX_FIELDS");
        // The row that settles whether a checker may report this from DECLARATIONS alone: it may not. Two
        // indexed groups on one path are perfectly legal until a condition asks for the index.
        expected.put("two indexed groups but NO semantic index", KernelLaws.ADMITTED);
        // The DM-JSON reader does not honour a plural member, so the SECOND code — "one group declares
        // several index fields" — has no stored spelling to reach it: `getIndexGroup` still sees one name.
        expected.put("a PLURAL indexFieldNames member instead", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "what the multiple-index codes need");
    }

    /**
     * The group's REPETITION count and the CARDINALITY of its index, which the ledger carries as four laws.
     *
     * <p>Two of them are about a count A12's collector writes unconditionally
     * ({@code CodeGenValidationDataCollector.addRootGroup}/{@code addGroup} both emit {@code maxRepetitions}
     * from {@code EntityUtils}), so "not declared" has no stored spelling — and the cap that remains is
     * carrier-specific, which is why both carriers are walked rather than one measured and the other assumed.
     *
     * <p>The other two need a group to declare SEVERAL index fields. A12's own {@code Group} holds a single
     * reference and {@code IGroup.getIndexFieldNames()} answers with a {@code Set}, so the rows below attack
     * both spellings a stored model could reach for rather than concluding from that read.
     */
    @Test
    void theRepetitionCountAndTheIndexCardinality() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("the fixture untouched — the control", KernelLaws.verdictOf(Fixtures.orderJson()));
        actual.put("a ROOT group declaring no repetition count", verdict("Order", "repeatability", null));
        actual.put("a NESTED group declaring no repetition count",
                verdict(NON_REPEATABLE, "repeatability", null));
        actual.put("a root group repeating 999999999 times", verdict("Order", "repeatability", "999999999"));
        actual.put("a root group repeating 1000000000 times",
                verdict("Order", "repeatability", "1000000000"));
        actual.put("a nested group repeating 1000000000 times",
                verdict(REPEATABLE, "repeatability", "1000000000"));
        actual.put("a nested group repeating 1999999999 times",
                verdict(REPEATABLE, "repeatability", "1999999999"));
        actual.put("a nested group repeating 2000000000 times",
                verdict(REPEATABLE, "repeatability", "2000000000"));
        actual.put("a nested group repeating at the signed 32-bit ceiling",
                verdict(REPEATABLE, "repeatability", "2147483647"));
        actual.put("a nested group repeating one past that ceiling",
                verdict(REPEATABLE, "repeatability", "2147483648"));
        actual.put("an index member holding TWO names", verdict(REPEATABLE, "indexFieldName",
                "[\"Sku\",\"Count\"]"));
        actual.put("a plural index member repeating ONE name", verdict(REPEATABLE, "indexFieldNames",
                "[\"Sku\",\"Sku\"]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("the fixture untouched — the control", KernelLaws.ADMITTED);
        // `MVK_MAX_MISSING` has no stored spelling on either carrier: an absent member is a repetition count
        // of one, and A12 writes the count it derived rather than the member it read.
        expected.put("a ROOT group declaring no repetition count", KernelLaws.ADMITTED);
        expected.put("a NESTED group declaring no repetition count", KernelLaws.ADMITTED);
        // …and the cap that IS reachable is carrier-specific, an order of magnitude apart. A checker holding
        // one bound would refuse a legal nested group or miss an illegal root one.
        expected.put("a root group repeating 999999999 times", KernelLaws.ADMITTED);
        expected.put("a root group repeating 1000000000 times", "MVK_MAX_TOO_BIG");
        expected.put("a nested group repeating 1000000000 times", KernelLaws.ADMITTED);
        expected.put("a nested group repeating 1999999999 times", KernelLaws.ADMITTED);
        expected.put("a nested group repeating 2000000000 times", "MVK_MAX_TOO_BIG");
        // The count is a signed 32-bit member, so past its ceiling A12 stops READING rather than judging —
        // the boundary a checker must encode if it is not to name a class A12 never reached.
        expected.put("a nested group repeating at the signed 32-bit ceiling", "MVK_MAX_TOO_BIG");
        expected.put("a nested group repeating one past that ceiling", KernelLaws.UNREADABLE);
        // Both spellings of a MULTI-field index are inert, so the two cardinality laws have no stored shape:
        // the singular member is a string A12's reader refuses an array for, and the plural one it ignores.
        expected.put("an index member holding TWO names", KernelLaws.UNREADABLE);
        expected.put("a plural index member repeating ONE name", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "a group's repetition count and the cardinality of its index");
    }

    /** What A12 says about the order fixture with one member of [group]'s body replaced, or removed. */
    private static String verdict(String group, String member, String rawJson) {
        return verdict(group, member, rawJson, false);
    }

    /**
     * …and with a second field named {@code Sku} added to {@code /Order}, so the short name {@code Sku} exists
     * at two levels. That is the only variable this overload moves.
     */
    private static String verdict(String group, String member, String rawJson, boolean duplicateShortName) {
        return KernelLaws.verdictOf(probe(group, member, rawJson, duplicateShortName));
    }

    /** The [member] on {@code /Order/Items}, beside a rule {@code ItemCheck} appended to that group. */
    private static String verdictWithRule(String member, String rawJson) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(probe(REPEATABLE, member, rawJson));
            ((com.fasterxml.jackson.databind.node.ArrayNode) groupBody(root, REPEATABLE).path("elements")).add(
                    MAPPER.readTree("{\"type\":\"Rule\",\"id\":\"item-check\",\"name\":\"ItemCheck\",\"Rule\":{"
                            + "\"errorEntityRelPath\":\"../Sku\",\"errorCode\":\"ITEM\",\"errorCondition\":\"FieldFilled(Sku)\","
                            + "\"severity\":\"ERROR\",\"errorMessage\":[{\"locale\":\"en_US\",\"text\":\"Check the item.\"}]}}"));
            return KernelLaws.verdictOf(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture and the rule must parse", unreadable);
        }
    }

    private static String probe(String group, String member, String rawJson) {
        return probe(group, member, rawJson, false);
    }

    private static String probe(String group, String member, String rawJson, boolean duplicateShortName) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(Fixtures.orderJson());
            if (duplicateShortName) {
                ((com.fasterxml.jackson.databind.node.ArrayNode) groupBody(root, "Order").path("elements"))
                        .add(MAPPER.readTree("{\"type\":\"Field\",\"id\":\"/Order/Sku\",\"name\":\"Sku\","
                                + "\"Field\":{\"fieldType\":{\"type\":\"StringType\"}}}"));
            }
            ObjectNode body = groupBody(root, group);
            if (rawJson == null) {
                body.remove(member);
            } else {
                body.set(member, MAPPER.readTree(rawJson));
            }
            return root.toString();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture and the raw fragment must parse", unreadable);
        }
    }

    /** The named group's body, refusing rather than returning a model the substitution never reached. */
    private static ObjectNode groupBody(ObjectNode root, String name) {
        for (var element : root.path("content").path("modelRoot").path("rootGroups").get(0)
                .path("Group").path("elements")) {
            if (name.equals(element.path("name").asText())) {
                return (ObjectNode) element.path("Group");
            }
        }
        var root0 = root.path("content").path("modelRoot").path("rootGroups").get(0);
        if (name.equals(root0.path("name").asText())) {
            return (ObjectNode) root0.path("Group");
        }
        throw new IllegalStateException("the fixture no longer declares the group `" + name + "`");
    }

}
