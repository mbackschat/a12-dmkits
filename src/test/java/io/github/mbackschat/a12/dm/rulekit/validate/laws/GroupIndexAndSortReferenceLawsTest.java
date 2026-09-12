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
        actual.put("a .. that climbs past the root", verdict(REPEATABLE, "indexFieldName", "\"../../Sku\""));
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
        // Every BARE spelling that does not name a field of THIS group stops A12 from building the model —
        // blank included, so "blank counts as absent" is false here too and in the harshest direction: the
        // absent member is legal and the blank one is fatal.
        expected.put("empty", KernelLaws.NOT_BUILDABLE);
        expected.put("whitespace only", KernelLaws.NOT_BUILDABLE);
        expected.put("a field that does not exist", KernelLaws.NOT_BUILDABLE);
        // The scope is the group's OWN children: a field of the parent is not reachable from here.
        expected.put("a field of the PARENT group", KernelLaws.NOT_BUILDABLE);
        // …but a spelling that RESOLVES to a field outside the group is a verdict rather than a build
        // failure. The two are one mechanism, not two rules: A12 resolves the spelling, then judges what it
        // landed on — nothing at all stops the build, something outside this group's own fields draws the
        // code. The rows below discriminate that from "`..` is special".
        expected.put("a relative path to one", "MVK_INVALID_INDEX_FIELD");
        expected.put("on a NON-repeatable group", KernelLaws.ADMITTED);
        // The resolution runs on every group, not only a repeatable one — the row that keeps the rows above
        // from being read as "the index is only checked where it is used".
        expected.put("unresolvable on a NON-repeatable group", KernelLaws.NOT_BUILDABLE);
        // A climb the model has no room for: the sibling resolver treats this as its own outcome, so it is
        // measured here rather than assumed to fall in with an ordinary miss.
        expected.put("a .. that climbs past the root", KernelLaws.NOT_BUILDABLE);
        // `Items/Sku` read from INSIDE `/Order/Items` names `/Order/Items/Items/Sku`, which is nothing — so
        // a path is not privileged and the `..` above was not the reason.
        expected.put("a descending path with no ..", KernelLaws.NOT_BUILDABLE);
        // The bare-name lookup is over the group's FIELDS: a child group of that name is not one.
        expected.put("a child GROUP of the named group", KernelLaws.NOT_BUILDABLE);
        // The same spelling read from `/Order`, where it DOES resolve — to a field one level down, outside
        // this group's own children. That is the case the two accounts disagree on, and it draws the code.
        expected.put("a descending path that DOES resolve", "MVK_INVALID_INDEX_FIELD");
        // An absolute spelling is refused one tier out again, and UNTYPED: `ElementPathUtils.splitRelPath`
        // raises "illegal relPath" during conversion, past every channel a caller can dispose of. The slot
        // takes a position relative to the group, and the leading `/` is not a spelling of that.
        expected.put("an ABSOLUTE path to the group's own field", KernelLaws.RAISED_UNTYPED);
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

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Sku — the control", KernelLaws.ADMITTED);
        expected.put("empty list", KernelLaws.ADMITTED);
        expected.put("a field that does not exist", KernelLaws.NOT_BUILDABLE);
        expected.put("an empty name", KernelLaws.NOT_BUILDABLE);
        // One tier further out again: an entry without a `name` refuses A12's reader outright.
        expected.put("an entry with no name", KernelLaws.UNREADABLE);
        // The rows the sibling member answers with a CODE — asked here rather than transferred, because
        // `MVK_INVALID_INDEX_FIELD` is named for the index and nothing says a sort field shares its gate.
        expected.put("a field OUTSIDE the group that resolves", "MVK_INVALID_SORT_FIELD");
        expected.put("a child GROUP of the named group", KernelLaws.NOT_BUILDABLE);
        // Asked on THIS member rather than carried from the index, which is the transfer that needs no new
        // branch and so prompts no question.
        expected.put("an absolute path", KernelLaws.RAISED_UNTYPED);
        // A non-string `name` is COERCED to the String member, as a rule's `errorCode` is — so it is a name
        // that resolves to nothing, not a shape the reader refuses. The checker reported the refusal tier
        // for it on no measured basis until this row.
        expected.put("a numeric name", KernelLaws.NOT_BUILDABLE);
        assertEquals(expected, actual, "what A12 does with each shape of a group's sortFields");
    }

    /**
     * A direct-child field is not sufficient — the index field's own TYPE is gated too.
     *
     * <p>Every admitted row above names a plain value-validating string field, so "a field of this group" and
     * "a field of this group that A12 will accept as a key" are one predicate there. They are not the same
     * claim, and the checker may only make the one it measured.
     */
    @Test
    void anIndexFieldsOwnTypeIsGatedBeyondResolvingAtAll() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a plain string field — the control", verdict(REPEATABLE, "indexFieldName", "\"Sku\""));
        actual.put("a field declared noValueValidation", noValueValidationVerdict("indexFieldName"));
        actual.put("the same, as a sort field", noValueValidationVerdict("sortFields"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a plain string field — the control", KernelLaws.ADMITTED);
        expected.put("a field declared noValueValidation", "MVK_INDEX_FIELD_NO_VALUE_VALIDATION");
        expected.put("the same, as a sort field", "MVK_SORT_FIELD_NO_VALUE_VALIDATION");
        assertEquals(expected, actual, "the index/sort field's own type");
    }

    /** The probe with `/Order/Items/Sku` redeclared as a no-value-validation string and named as the key. */
    private static String noValueValidationVerdict(String member) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(Fixtures.orderJson());
            ObjectNode items = groupBody(root, "Items");
            for (var element : items.path("elements")) {
                if ("Sku".equals(element.path("name").asText())) {
                    // Both flags together: A12 refuses `noValueValidation` without line breaks
                    // (`MVK_STRING_TYPE_NO_VAL_AND_NO_LINE_BREAK`), so setting one alone would move a second
                    // variable and measure that refusal instead of this gate.
                    ((ObjectNode) element.path("Field").path("fieldType"))
                            .putObject("StringType")
                            .put("noValueValidation", true).put("lineBreaksPermitted", true);
                }
            }
            // The fixture already keys this group by `Sku`, so leaving that in place would fire the INDEX
            // gate beside the sort one and measure both at once.
            items.remove("indexFieldName");
            items.set(member, MAPPER.readTree(
                    "sortFields".equals(member) ? "[{\"name\":\"Sku\"}]" : "\"Sku\""));
            return KernelLaws.verdictOf(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture must parse", unreadable);
        }
    }

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
     * A12 can say which is a rejection it actually makes, and it admits it — so the law is TWO gates: the
     * spelling must resolve to a field at all, and then that field's SHORT NAME must name a field of this
     * group. The rows above fit both accounts because no short name in their fixture exists at two levels.
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
        // Without the second field the spelling resolves to NOTHING, so it never reaches the second gate —
        // which is why this control had to be the same spelling rather than a different one.
        expected.put("index ../Sku, no /Order/Sku — the control", KernelLaws.NOT_BUILDABLE);
        // The row that decides it: the landing is outside the group and A12 ADMITS the model, because
        // `/Order/Items` carries a `Sku` of its own. A checker judging the LANDING rejects a model A12 takes.
        expected.put("index ../Sku, with /Order/Sku", KernelLaws.ADMITTED);
        expected.put("sort ../Sku, with /Order/Sku", KernelLaws.ADMITTED);
        // …and the name is what matters, not the duplication: a spelling landing outside on a name this group
        // does NOT carry is still refused in the very same model.
        expected.put("index ../CustomerName, with /Order/Sku", "MVK_INVALID_INDEX_FIELD");
        assertEquals(expected, actual, "whether the landing or the short name decides");
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
