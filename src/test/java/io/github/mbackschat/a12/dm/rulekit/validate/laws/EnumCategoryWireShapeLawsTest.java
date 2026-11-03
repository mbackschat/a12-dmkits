package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.core.ModelInputException;
import io.github.mbackschat.a12.dm.core.ModelLoader;

/**
 * WHICH stored members carry an Enumeration's values and its categories, and what A12 does with a
 * near-miss spelling.
 *
 * <p>{@link EnumCategoryAlignmentLawsTest} establishes the LAW — a category's token list pairs positionally
 * with the enum's values, so equal cardinality is the whole rule — by authoring through the kit. That is the
 * right oracle for the law and says nothing about the member NAMES a checker reading stored JSON must look
 * for, and a checker may not borrow a name from the Java field: taking {@code TypeDefType.id} for its wire
 * name is what sent an earlier probe measuring an unknown member and reading the result as a
 * dangling-reference law ([KERNEL-FINDINGS KF308], corrected).
 *
 * <p><b>The misspelling rows are what make the names a measurement, and they answer more than was asked.</b>
 * Each of the four names is put to the kernel once correct and once nearly right, and every near-miss dies in
 * A12's READER — an unknown member is refused outright, not ignored. So the pairing law can only ever be
 * asked of a body whose members A12 already knows, and a checker reading the wrong one is looking at a model
 * A12 would never have loaded. The prediction here was the opposite (silently ignored, as an over-permissive
 * reader would), which is why the rows are kept rather than deleted as uninformative.
 *
 * <p><b>The verdict keeps its multiplicity</b>, because the law is reported per element and a checker
 * reproducing it has to emit the same number of findings. Collapsing them would make "once per unclassified
 * value" unobservable here, which is the mistake {@link EnumCategoryAlignmentLawsTest} records having made.
 *
 * <p>Own domain (the shared order fixture), on {@code /Order/CustomerName} — a plain root field no other
 * declaration names, so a verdict here is about the type and not about a role the field plays.
 */
class EnumCategoryWireShapeLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern KEYED = Pattern.compile("\\[(MVK_[A-Z0-9_]+)]");

    private static final String MISSING_CATEGORY = "MVK_MISSING_CATEGORY_VALUE";
    private static final String MISSING_INTERNAL = "MVK_MISSING_INTERNAL_VALUE";

    /** Two enum values; the category's token list is whatever [tokens] spells, verbatim. */
    private static String withTokens(String tokens) {
        return "{\"values\":[{\"value\":\"BASIC\"},{\"value\":\"PLUS\"}],"
                + "\"categories\":[{\"name\":\"Band\",\"values\":" + tokens + "}]}";
    }

    @Test
    void whichStoredMembersCarryTheEnumCategoryPairing() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("two values, two tokens — the control", verdictFor(withTokens("[\"STANDARD\",\"HIGH\"]")));
        actual.put("one token short", verdictFor(withTokens("[\"STANDARD\"]")));
        actual.put("one token too many", verdictFor(withTokens("[\"STANDARD\",\"HIGH\",\"TOP\"]")));
        // TWO too many: the surplus direction's multiplicity, which the row above cannot show. Without it a
        // consumer emitting one finding per surplus token would be transferring the shortfall direction's
        // measured multiplicity onto a direction nobody had measured.
        actual.put("two tokens too many",
                verdictFor(withTokens("[\"STANDARD\",\"HIGH\",\"TOP\",\"MAX\"]")));
        actual.put("no tokens at all", verdictFor(withTokens("[]")));
        // The mirror of "no tokens at all": nothing on the LEFT of the pairing, tokens on the right. The
        // second row separates the pairing from the enum itself — an empty `values` may be refused on its
        // own account, in which case the pairing never gets asked and a checker reporting it is inventing.
        actual.put("no values at all, with tokens",
                verdictFor("{\"values\":[],\"categories\":[{\"name\":\"Band\",\"values\":[\"X\",\"Y\"]}]}"));
        actual.put("no values at all, no categories", verdictFor("{\"values\":[]}"));
        // The four names, each written nearly right — the rows that turn the names from a reading into a
        // measurement. A12's reader names the six members it knows in its own refusal message.
        actual.put("the enum's own list member misspelled",
                verdictFor("{\"value\":[{\"value\":\"BASIC\"},{\"value\":\"PLUS\"}],"
                        + "\"categories\":[{\"name\":\"Band\",\"values\":[\"STANDARD\"]}]}"));
        actual.put("a value entry's member misspelled",
                verdictFor("{\"values\":[{\"sValue\":\"BASIC\"},{\"sValue\":\"PLUS\"}],"
                        + "\"categories\":[{\"name\":\"Band\",\"values\":[\"STANDARD\"]}]}"));
        actual.put("the categories member misspelled",
                verdictFor("{\"values\":[{\"value\":\"BASIC\"},{\"value\":\"PLUS\"}],"
                        + "\"category\":[{\"name\":\"Band\",\"values\":[\"STANDARD\"]}]}"));
        actual.put("a category's token member misspelled",
                verdictFor("{\"values\":[{\"value\":\"BASIC\"},{\"value\":\"PLUS\"}],"
                        + "\"categories\":[{\"name\":\"Band\",\"value\":[\"STANDARD\"]}]}"));
        // Two categories, one aligned and one short: the law is asked of each category separately.
        actual.put("two categories, one of them short",
                verdictFor("{\"values\":[{\"value\":\"BASIC\"},{\"value\":\"PLUS\"}],\"categories\":["
                        + "{\"name\":\"Band\",\"values\":[\"STANDARD\",\"HIGH\"]},"
                        + "{\"name\":\"Tier\",\"values\":[\"ONE\"]}]}"));
        actual.put("no categories at all — the control for their absence",
                verdictFor("{\"values\":[{\"value\":\"BASIC\"},{\"value\":\"PLUS\"}]}"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("two values, two tokens — the control", KernelLaws.ADMITTED);
        expected.put("one token short", MISSING_CATEGORY);
        expected.put("one token too many", MISSING_INTERNAL);
        expected.put("two tokens too many", MISSING_INTERNAL + "," + MISSING_INTERNAL);
        expected.put("no tokens at all", MISSING_CATEGORY + "," + MISSING_CATEGORY);
        expected.put("no values at all, with tokens", KernelLaws.NOT_BUILDABLE);
        expected.put("no values at all, no categories", KernelLaws.NOT_BUILDABLE);
        // Kernel 31.1.1's reader passes over a member it does not know (KERNEL-FINDINGS KF654; 30.8.1 refused all
        // four), so each near-miss is the real member left out: no values at all, values without a value, no
        // categories, and categories without tokens.
        expected.put("the enum's own list member misspelled", KernelLaws.NOT_BUILDABLE);
        expected.put("a value entry's member misspelled", MISSING_INTERNAL + ",MVK_INTERNAL_VALUES_MISSING");
        expected.put("the categories member misspelled", KernelLaws.ADMITTED);
        expected.put("a category's token member misspelled", MISSING_CATEGORY + "," + MISSING_CATEGORY);
        expected.put("two categories, one of them short", MISSING_CATEGORY);
        expected.put("no categories at all — the control for their absence", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "which members carry the enum-category pairing, and what a near-miss costs");
    }

    /**
     * A12's verdict on the whole stored model, keeping every keyed notification in order.
     *
     * <p>Deliberately not {@link KernelLaws#verdictOf}, which reports each class once: this law fires per
     * element, so a checker reproducing it must emit the same count and a de-duplicated oracle could not say
     * whether it does.
     */
    private static String verdictFor(String rawBody) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(Fixtures.orderJson());
            for (var element : root.path("content").path("modelRoot").path("rootGroups").get(0)
                    .path("Group").path("elements")) {
                if ("CustomerName".equals(element.path("name").asText()) && element.has("Field")) {
                    ((ObjectNode) element.path("Field")).set("fieldType", MAPPER.readTree(
                            "{\"type\":\"EnumerationType\",\"EnumerationType\":" + rawBody + "}"));
                    return keyedClassesOf(root.toString());
                }
            }
            throw new IllegalStateException("the fixture no longer declares the root field `CustomerName`");
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture and the raw body must parse", unreadable);
        }
    }

    private static String keyedClassesOf(String modelJson) {
        try {
            // The Kernel gate alone, as KernelLaws.verdictOf asks it (KERNEL-FINDINGS KF649).
            ModelLoader.loadSelfContained(modelJson);
            return KernelLaws.ADMITTED;
        } catch (ModelInputException unreadable) {
            return KernelLaws.UNREADABLE;
        } catch (ModelConsistencyException rejected) {
            List<String> classes = new ArrayList<>();
            for (var notification : rejected.notifications()) {
                Matcher keyed = KEYED.matcher(String.valueOf(notification.message()));
                while (keyed.find()) {
                    classes.add(keyed.group(1));
                }
            }
            return classes.isEmpty() ? KernelLaws.NOT_BUILDABLE : String.join(",", classes);
        }
    }
}
