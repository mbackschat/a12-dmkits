package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * Whether ONE enumeration's value list carrying two defects draws both classes or stops at the first.
 *
 * <p>The third member put through the defect-pairing pass, and the one with the most families over a single
 * list: the tokens' identity, their alphabet and the categories paired with them are three separate checks of
 * the same {@code values} array. The two members already measured answered differently — a condition reports
 * the first defect in its text ([KERNEL-FINDINGS KF342]), a message gates on characters and then reports
 * every parameter defect ([KF343]) — so this one is measured rather than inferred from either.
 *
 * <p>The defective shapes are built by editing the drafted model's own JSON, because the typed builder
 * refuses a misaligned category outright and that is the only way to put the shape in front of the kernel —
 * the same reason {@link EnumCategoryAlignmentLawsTest} does its surgery.
 *
 * <p>Own domain (a retail store's service tier).
 */
class EnumValueMultipleDefectLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Two values under a category that classifies both, with the alphabet restricted to capitals.
     *
     * <p>The restriction is what makes a lowercase token a defect at all, and the control row is what says
     * the restriction does not refuse the model's own element names.
     */
    private static final String STORE = DocumentModelDraft.named("store").locale("en_US")
            .supportedCharacters("A-Z")
            .rootGroup("Store", store -> store
                    .field("Reference", FieldKind.STRING)
                    .enumField("Tier", tier -> tier.value("BASIC").value("PLUS")
                            .category("Band", "STANDARD", "HIGH")))
            .build().toJson();

    private static final String DUPLICATE = "MVK_INTERNAL_VALUES_DUPLICATE";
    private static final String SPOILT_PAIRING = "MVK_CATEGORY_VALUE_DUPLICATE";
    private static final String BAD_CHARACTER = "MVK_ENUM_VALUE_WITH_INVALID_CHARACTER";
    private static final String MISSING_CATEGORY = "MVK_MISSING_CATEGORY_VALUE";

    @Test
    void whetherOneValueListReportsEveryDefectOrStopsAtTheFirst() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("aligned capitals — the control", verdict(List.of("BASIC", "PLUS"), 2));
        actual.put("a repeated token", verdict(List.of("BASIC", "BASIC"), 2));
        actual.put("a lowercase token", verdict(List.of("BASIC", "plus"), 2));
        actual.put("a category one token short", verdict(List.of("BASIC", "PLUS"), 1));
        actual.put("a repeated token BEFORE a lowercase one",
                verdict(List.of("BASIC", "BASIC", "plus"), 3));
        actual.put("a lowercase token BEFORE a repeated one",
                verdict(List.of("plus", "BASIC", "BASIC"), 3));
        actual.put("a repeated token with a short category", verdict(List.of("BASIC", "BASIC"), 1));
        actual.put("a lowercase token with a short category", verdict(List.of("BASIC", "plus"), 1));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("aligned capitals — the control", KernelLaws.ADMITTED);
        // A repeated token draws THREE refusals, not one: its own class, the pairing it spoils, and a
        // codeless one. What the category class is really about is the test below.
        expected.put("a repeated token", DUPLICATED_BASIC);
        // Four, because `plus` has four distinct characters — the class is per offending CHARACTER.
        expected.put("a lowercase token", LOWERCASE_PLUS);
        expected.put("a category one token short", MISSING_CATEGORY);
        // The rows with teeth: every pair reports the UNION, so nothing here is withheld. This member is
        // the field type's shape rather than the condition's, and a third member had to be measured to
        // know that — the message's answer is neither (KF343).
        expected.put("a repeated token BEFORE a lowercase one", LOWERCASE_PLUS + "," + DUPLICATED_BASIC);
        // …and the reversed row is identical, so the report's ORDER is fixed by the check rather than by
        // where the offending token sits. One row could not have shown that.
        expected.put("a lowercase token BEFORE a repeated one", LOWERCASE_PLUS + "," + DUPLICATED_BASIC);
        expected.put("a repeated token with a short category", MISSING_CATEGORY + "," + DUPLICATED_BASIC);
        expected.put("a lowercase token with a short category", LOWERCASE_PLUS + "," + MISSING_CATEGORY);
        assertEquals(expected, actual, "whether one value list reports every defect or stops at the first");
    }

    /**
     * What makes a value duplicate spoil a PAIRING, and how often A12 says so.
     *
     * <p>{@code MVK_CATEGORY_VALUE_DUPLICATE} is not what its name suggests, and a checker emitting it off
     * the name would be wrong in both directions: a category whose own tokens repeat is ADMITTED, while a
     * repeated INTERNAL value draws it once for every category whose pairing it leaves ambiguous.
     */
    @Test
    void whatMakesAValueDuplicateSpoilAPairing() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a repeated token with NO category at all", uncategorised(List.of("BASIC", "BASIC")));
        actual.put("distinct tokens with NO category — the control", uncategorised(List.of("BASIC", "PLUS")));
        actual.put("a repeated CATEGORY token with distinct values",
                verdict(List.of("BASIC", "PLUS"), List.of("STANDARD", "STANDARD")));
        actual.put("one token repeated THREE times",
                verdict(List.of("BASIC", "BASIC", "BASIC"), List.of("STANDARD", "HIGH", "TOP")));
        actual.put("one repeated token under TWO categories", twoCategories(List.of("BASIC", "BASIC")));
        actual.put("distinct tokens under TWO categories — the control",
                twoCategories(List.of("BASIC", "PLUS")));
        actual.put("TWO tokens each repeated, one category",
                verdict(List.of("BASIC", "BASIC", "PLUS", "PLUS"),
                        List.of("STANDARD", "HIGH", "TOP", "TOP")));
        actual.put("TWO tokens each repeated under TWO categories",
                twoCategories(List.of("BASIC", "BASIC", "PLUS", "PLUS")));

        Map<String, String> expected = new LinkedHashMap<>();
        // The row that makes the class about the PAIRING and not the value list: no category, no class.
        expected.put("a repeated token with NO category at all",
                DUPLICATE + "," + codeless("BASIC"));
        expected.put("distinct tokens with NO category — the control", KernelLaws.ADMITTED);
        // …and its mirror image, which is why the class cannot be read off its name.
        expected.put("a repeated CATEGORY token with distinct values", KernelLaws.ADMITTED);
        // Duplicated VALUES, not extra occurrences: three of the same token still draw one of each.
        expected.put("one token repeated THREE times", DUPLICATED_BASIC);
        expected.put("one repeated token under TWO categories",
                SPOILT_PAIRING + "," + DUPLICATED_BASIC);
        expected.put("distinct tokens under TWO categories — the control", KernelLaws.ADMITTED);
        expected.put("TWO tokens each repeated, one category",
                SPOILT_PAIRING + "," + SPOILT_PAIRING + "," + DUPLICATE + "," + DUPLICATE + ","
                        + codeless("BASIC") + "," + codeless("PLUS"));
        // The cell that settles the multiplicity: 1×2 and 2×1 both give two, which a maximum and a sum
        // predict as well, and only 2×2 separates them. Four — the cross product. The two misalignment
        // findings ride along because four values under two tokens are two values short, which is the
        // same row saying again that nothing here is withheld.
        expected.put("TWO tokens each repeated under TWO categories",
                MISSING_CATEGORY + "," + MISSING_CATEGORY + "," + SPOILT_PAIRING + "," + SPOILT_PAIRING
                        + "," + SPOILT_PAIRING + "," + SPOILT_PAIRING + "," + DUPLICATE + "," + DUPLICATE
                        + "," + codeless("BASIC") + "," + codeless("PLUS"));
        assertEquals(expected, actual, "what makes a value duplicate spoil a pairing");
    }

    /** A12's own summary for the third, codeless refusal a repeated token draws. */
    private static String codeless(String token) {
        return "(no code: Field with id '/Store/Tier': The enumeration contains the value '" + token
                + "' multiple times.)";
    }

    /** The three refusals one repeated `BASIC` draws under a single category, in A12's order. */
    private static final String DUPLICATED_BASIC =
            SPOILT_PAIRING + "," + DUPLICATE + ",(no code: Field with id '/Store/Tier': The enumeration "
                    + "contains the value 'BASIC' multiple times.)";

    /** One per distinct offending character, and `plus` has four. */
    private static final String LOWERCASE_PLUS =
            BAD_CHARACTER + "," + BAD_CHARACTER + "," + BAD_CHARACTER + "," + BAD_CHARACTER;

    /**
     * The verdict WITH its multiplicity, because these laws are reported per element.
     *
     * @param values the enum's value tokens, in order
     * @param categoryTokens how many tokens the single category keeps, so one short is a misalignment
     */
    private static String verdict(List<String> values, int categoryTokens) {
        return verdict(values, List.of("STANDARD", "HIGH", "TOP").subList(0, categoryTokens));
    }

    /** The same, with the category's tokens given outright, for a row that varies the tokens themselves. */
    private static String verdict(List<String> values, List<String> categoryTokens) {
        return checked(edited(values, categoryTokens));
    }

    /** One model's verdict, keeping multiplicity: these laws are reported per element. */
    private static String checked(String modelJson) {
        ValidationResult result = KernelLaws.checkModel(modelJson, "/Store", "../Reference",
                "FieldFilled(/Store/Reference)");
        return result.ok() ? KernelLaws.ADMITTED : result.diagnostics().stream()
                .map(diagnostic -> diagnostic.code().orElse("(no code: " + diagnostic.summary() + ")"))
                .collect(Collectors.joining(","));
    }

    /** The same enum carrying a SECOND category, to see whether the class is counted per category. */
    private static String twoCategories(List<String> values) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(edited(values, List.of("STANDARD", "HIGH")));
            ObjectNode second = (ObjectNode) ((ArrayNode) enumerationBody(root).path("categories"))
                    .addObject();
            second.put("name", "Segment");
            ArrayNode tokens = second.putArray("values");
            tokens.add("RETAIL");
            tokens.add("TRADE");
            return checked(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the drafted fixture must parse", unreadable);
        }
    }

    /** The same enum with its `categories` member removed entirely, which the builder cannot author. */
    private static String uncategorised(List<String> values) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(edited(values, List.of("STANDARD", "HIGH")));
            enumerationBody(root).remove("categories");
            return checked(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the drafted fixture must parse", unreadable);
        }
    }

    /** The drafted model with its enum's two arrays rewritten, reached by node rather than by text. */
    private static String edited(List<String> values, List<String> categoryTokens) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(STORE);
            ObjectNode enumeration = enumerationBody(root);
            ArrayNode rewritten = enumeration.putArray("values");
            values.forEach(value -> rewritten.addObject().put("value", value));
            ObjectNode category = (ObjectNode) enumeration.path("categories").get(0);
            ArrayNode tokens = category.putArray("values");
            categoryTokens.forEach(tokens::add);
            return root.toString();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the drafted fixture must parse", unreadable);
        }
    }

    private static ObjectNode enumerationBody(ObjectNode root) {
        for (JsonNode element : root.path("content").path("modelRoot").path("rootGroups").get(0)
                .path("Group").path("elements")) {
            if ("Tier".equals(element.path("name").asText())) {
                return (ObjectNode) element.path("Field").path("fieldType").path("EnumerationType");
            }
        }
        throw new IllegalStateException("the drafted model no longer declares `Tier`: " + STORE);
    }
}
