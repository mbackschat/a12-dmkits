package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;

/**
 * KERNEL-FINDINGS §8 — the characters an enumeration's three value carriers refuse, and the predicate behind
 * them.
 *
 * <p>{@link EnumDeclarationShapeLawsTest} left three [MVK-LEDGER] rows as <b>unmeasured predicate</b>:
 * {@code MVK_INTERNAL_VALUE_INVALID}, {@code MVK_DISPLAY_VALUE_INVALID} and
 * {@code MVK_CATEGORY_VALUE_INVALID} are raised by metamodel CUSTOM CONDITIONS whose trigger nothing authored
 * had reached, and both markup and a control character are ADMITTED there. The names invite the reading that
 * they police XML-safety; the reading is wrong.
 *
 * <p><b>All three names bind ONE implementation.</b> {@code CheckConfiguration} registers
 * {@code ungueltigerXMLWert}, {@code ungueltigerOberflaechenWert} and {@code ungueltigerKategorieWert} as
 * three instances of {@code CheckEnumOrCategoryValue}, whose predicate is a containment test for
 * <b>newline, tab, carriage return, or the pipe</b> and nothing else (source-read,
 * {@code kernel-core-base-utils}). So the CARRIER picks the code, which is why the grid sweeps the same four
 * characters across all three carriers instead of measuring one and transferring to its siblings.
 *
 * <p><b>Only two of the four reach it, and the split is the finding.</b> The tab and the pipe draw the
 * carrier's code as written. A NEWLINE or a CARRIAGE RETURN never gets that far: an earlier gate refuses it on
 * all three carriers with one codeless sentence — {@code "Field with path '/Policy/Tier': Only values without
 * line breaks are allowed."} — so the custom condition's line-break arm is dead wherever these carriers are
 * concerned, and a checker citing {@code MVK_INTERNAL_VALUE_INVALID} for a newline would name a class A12 does
 * not use there. That row is also why {@link KernelLaws#REFUSED_CODELESS} exists: it was reported as
 * {@code NOT BUILDABLE} until this grid measured it, which asserted A12 had reached no verdict when it had.
 *
 * <p>Own domain (insurance), matching its sibling's fixture.
 */
class EnumValueForbiddenCharactersLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String POLICY = DocumentModelDraft.named("policy-enum-characters").locale("en_US")
            .rootGroup("Policy", p -> p
                    .stringField("Reference", f -> { })
                    .enumField("Tier", e -> e.value("BASIC").value("PLUS")))
            .build().toJson();

    /**
     * The characters the implementation names, each written as it appears INSIDE a JSON string.
     *
     * <p>Derived from the predicate rather than sampled, for the reason the field-name alphabet sweep states:
     * a hand-picked list cannot miss what it does not think of. The controls carry the other direction — a
     * character the predicate does NOT name must stay admitted, or a checker keyed on "unsafe text" would
     * refuse a model A12 takes.
     */
    private static final Map<String, String> CODED = new LinkedHashMap<>(Map.of(
            "1 a TAB", "\\t",
            "2 a PIPE", "|"));

    /** The other half of the same predicate, which an earlier gate answers first — codeless, on every carrier. */
    private static final Map<String, String> LINE_BREAKS = new LinkedHashMap<>(Map.of(
            "3 a NEWLINE", "\\n",
            "4 a CARRIAGE RETURN", "\\r"));

    private static final Map<String, String> ADMITTED_CONTROLS = new LinkedHashMap<>(Map.of(
            "5 a SPACE", " ",
            "6 a markup character", "<",
            "7 an escaped quote", "\\\""));

    /** The fixture with {@code Tier}'s {@code EnumerationType} body replaced by [body]. */
    private static String modelWith(String body) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(POLICY);
            ArrayNode elements = (ArrayNode) root.at("/content/modelRoot/rootGroups/0/Group/elements");
            for (var element : elements) {
                if ("Tier".equals(element.path("name").asText())) {
                    ((ObjectNode) element.path("Field")).set(
                            "fieldType",
                            MAPPER.readTree("{\"type\":\"EnumerationType\",\"EnumerationType\":{"
                                    + body + "}}"));
                }
            }
            return MAPPER.writeValueAsString(root);
        } catch (Exception failed) {
            throw new IllegalStateException(failed);
        }
    }

    private static String verdict(String body) {
        return KernelLaws.verdictOf(modelWith(body));
    }

    /** The second declared value carries [infix]; the first stays clean, so a row moves one thing. */
    private static String internalValue(String infix) {
        return "\"values\":[{\"value\":\"BASIC\"},{\"value\":\"PL" + infix + "US\"}]";
    }

    /** Both values are labelled — a partially labelled enumeration draws its own class first. */
    private static String displayValue(String infix) {
        return "\"values\":[{\"value\":\"BASIC\",\"label\":[{\"locale\":\"en_US\",\"text\":\"Basic\"}]},"
                + "{\"value\":\"PLUS\",\"label\":[{\"locale\":\"en_US\",\"text\":\"Pl" + infix + "us\"}]}]";
    }

    /** One token per declared value — a short category list draws {@code MVK_MISSING_CATEGORY_VALUE}. */
    private static String categoryValue(String infix) {
        return "\"values\":[{\"value\":\"BASIC\"},{\"value\":\"PLUS\"}],"
                + "\"categories\":[{\"name\":\"Band\",\"values\":[\"LOW\",\"HI" + infix + "GH\"]}]";
    }

    private record Carrier(String label, Function<String, String> body, String code) { }

    @Test
    void theFourCharactersEachValueCarrierRefusesAndTheCodeItsCarrierPicks() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        Carrier[] carriers = {
            new Carrier("internal value", EnumValueForbiddenCharactersLawsTest::internalValue,
                    "MVK_INTERNAL_VALUE_INVALID"),
            new Carrier("display value", EnumValueForbiddenCharactersLawsTest::displayValue,
                    "MVK_DISPLAY_VALUE_INVALID"),
            new Carrier("category token", EnumValueForbiddenCharactersLawsTest::categoryValue,
                    "MVK_CATEGORY_VALUE_INVALID"),
        };
        for (Carrier carrier : carriers) {
            actual.put(carrier.label() + " — 0 the control", verdict(carrier.body().apply("")));
            expected.put(carrier.label() + " — 0 the control", KernelLaws.ADMITTED);
            CODED.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(refused -> {
                String key = carrier.label() + " carrying " + refused.getKey();
                actual.put(key, verdict(carrier.body().apply(refused.getValue())));
                expected.put(key, carrier.code());
            });
            LINE_BREAKS.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(refused -> {
                String key = carrier.label() + " carrying " + refused.getKey();
                actual.put(key, verdict(carrier.body().apply(refused.getValue())));
                expected.put(key, KernelLaws.REFUSED_CODELESS);
            });
            ADMITTED_CONTROLS.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(control -> {
                String key = carrier.label() + " carrying " + control.getKey();
                actual.put(key, verdict(carrier.body().apply(control.getValue())));
                expected.put(key, KernelLaws.ADMITTED);
            });
        }
        assertEquals(expected, actual, "the characters an enumeration's value carriers refuse");
    }

    /**
     * How MANY findings A12 raises, and which gate answers when two are defective at once.
     *
     * <p>The grid above reports DISTINCT classes, so it cannot see multiplicity or precedence, and both are
     * decisions a checker has to encode rather than infer: report per offending character or per carrier, and
     * report the coded class or the line-break refusal when one value carries both.
     */
    @Test
    void howManyFindingsAndWhichGateAnswersFirst() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("one value carrying a tab AND a pipe", raised(internalValue("\\t|")));
        actual.put("BOTH declared values carrying a tab",
                raised("\"values\":[{\"value\":\"BA\\tSIC\"},{\"value\":\"PL\\tUS\"}]"));
        actual.put("a value and its own LABEL each carrying a tab",
                raised("\"values\":[{\"value\":\"BASIC\",\"label\":[{\"locale\":\"en_US\",\"text\":\"Basic\"}]},"
                        + "{\"value\":\"PL\\tUS\",\"label\":[{\"locale\":\"en_US\",\"text\":\"Pl\\tus\"}]}]"));
        actual.put("a value and a CATEGORY token each carrying a tab",
                raised("\"values\":[{\"value\":\"BASIC\"},{\"value\":\"PL\\tUS\"}],"
                        + "\"categories\":[{\"name\":\"Band\",\"values\":[\"LOW\",\"HI\\tGH\"]}]"));
        actual.put("one value carrying a tab AND a newline", raised(internalValue("\\t\\n")));
        actual.put("one value carrying a newline and ANOTHER carrying a tab",
                raised("\"values\":[{\"value\":\"BA\\nSIC\"},{\"value\":\"PL\\tUS\"}]"));
        actual.put("BOTH declared values carrying a newline",
                raised("\"values\":[{\"value\":\"BA\\nSIC\"},{\"value\":\"PL\\nUS\"}]"));

        Map<String, String> expected = new LinkedHashMap<>();
        // The custom condition is a containment test over the whole value, so the VALUE is the unit of
        // report and a second offending character in it adds nothing — unlike `supportedCharacters`, whose
        // family next door reports per (value, character).
        expected.put("one value carrying a tab AND a pipe", "MVK_INTERNAL_VALUE_INVALID x1");
        expected.put("BOTH declared values carrying a tab", "MVK_INTERNAL_VALUE_INVALID x2");
        // Each carrier answers on its own account, so one value can draw two classes at once.
        expected.put("a value and its own LABEL each carrying a tab",
                "MVK_INTERNAL_VALUE_INVALID x1,MVK_DISPLAY_VALUE_INVALID x1");
        expected.put("a value and a CATEGORY token each carrying a tab",
                "MVK_INTERNAL_VALUE_INVALID x1,MVK_CATEGORY_VALUE_INVALID x1");
        // …and the line-break gate answers INSTEAD of the coded one WITHIN one value — but only within it:
        // a sibling value's tab is still reported, so a checker that fell silent over the declaration would
        // drop a class A12 raises.
        expected.put("one value carrying a tab AND a newline", "(codeless) x1");
        expected.put("one value carrying a newline and ANOTHER carrying a tab",
                "(codeless) x1,MVK_INTERNAL_VALUE_INVALID x1");
        // The two gates do not even count the same way: the line-break refusal is raised once for the FIELD
        // however many of its texts carry one, where the coded class is raised per offending text.
        expected.put("BOTH declared values carrying a newline", "(codeless) x1");
        assertEquals(expected, actual, "how many findings, and which gate answers first");
    }

    /**
     * The two neighbouring texts a family keyed on "an enumeration's text" would judge as well.
     *
     * <p>Both are places a checker would reach by transfer rather than by measurement — a label written for a
     * locale the model does not declare, and the category's own {@code name} — and <b>both predictions above
     * were wrong, in opposite directions.</b> The undeclared locale's text is ADMITTED, so the display gate
     * judges only texts for DECLARED locales and a family judging every text would refuse a model A12 takes.
     * The category's name is REFUSED, and codeless — but by a gate that is not this law at all: its sentence
     * is a NAME alphabet ("starts with a letter and may contain only letters, digits and the underscore"), so
     * a family reporting {@code MVK_CATEGORY_VALUE_INVALID} there would cite a class A12 does not use for a
     * reason that is not the character it keyed on.
     */
    @Test
    void theTwoNeighbouringTextsTheGateDoesAndDoesNotReach() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a label for an UNDECLARED locale carrying a tab",
                verdict("\"values\":[{\"value\":\"BASIC\",\"label\":[{\"locale\":\"de_DE\",\"text\":\"Ba\\tsis\"}]},"
                        + "{\"value\":\"PLUS\",\"label\":[{\"locale\":\"de_DE\",\"text\":\"Plus\"}]}]"));
        actual.put("a category NAME carrying a tab",
                verdict("\"values\":[{\"value\":\"BASIC\"},{\"value\":\"PLUS\"}],"
                        + "\"categories\":[{\"name\":\"Ba\\tnd\",\"values\":[\"LOW\",\"HIGH\"]}]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a label for an UNDECLARED locale carrying a tab", KernelLaws.ADMITTED);
        expected.put("a category NAME carrying a tab", KernelLaws.REFUSED_CODELESS);
        assertEquals(expected, actual, "which neighbouring enumeration texts the gate reaches");
    }

    /** The classes A12 raised with their multiplicities, so a row can state a count rather than a set. */
    private static String raised(String body) {
        try {
            BoundModel.ofJson(modelWith(body));
            return KernelLaws.ADMITTED;
        } catch (ModelConsistencyException rejected) {
            Map<String, Integer> counted = new LinkedHashMap<>();
            for (var notification : rejected.notifications()) {
                String message = notification.message().trim();
                String code = message.matches(".*\\[\\w+]$")
                        ? message.replaceAll(".*\\[(\\w+)]$", "$1")
                        : "(codeless)";
                counted.merge(code, 1, Integer::sum);
            }
            return counted.entrySet().stream()
                    .map(entry -> entry.getKey() + " x" + entry.getValue())
                    .collect(java.util.stream.Collectors.joining(","));
        }
    }

    /**
     * The sentences the two codeless gates state, because {@link KernelLaws#REFUSED_CODELESS} says only that a
     * verdict exists.
     *
     * <p>Without these rows the grids above would be compatible with A12 having failed for some unrelated
     * reason, which is exactly the reading the old {@code NOT BUILDABLE} disposition asserted. They are also
     * what a portable checker needs in order to decide: each refusal is real, it is about this declaration,
     * and it carries no class to cite — so the checker either owns a code or stays silent.
     */
    @Test
    void theTwoCodelessGatesStateTheirVerdictsInProse() {
        Map<String, List<String>> actual = new LinkedHashMap<>();
        actual.put("a declared value carrying a newline", prose(internalValue("\\n")));
        actual.put("a category name carrying a tab",
                prose("\"values\":[{\"value\":\"BASIC\"},{\"value\":\"PLUS\"}],"
                        + "\"categories\":[{\"name\":\"Ba\\tnd\",\"values\":[\"LOW\",\"HIGH\"]}]"));

        Map<String, List<String>> expected = new LinkedHashMap<>();
        expected.put("a declared value carrying a newline", List.of(
                "Field with path '/Policy/Tier': Only values without line breaks are allowed."));
        // A different gate entirely, and the sentence is what says so: the category's name answers to a NAME
        // alphabet, not to this family's four characters, so the tab is incidental to why it was refused.
        expected.put("a category name carrying a tab", List.of(
                "Field with path '/Policy/Tier': A category name starts with a letter and may contain only "
                        + "letters, digits and the underscore"));
        assertEquals(expected, actual, "the sentences the two codeless gates state");
    }

    /** Every sentence A12 raised, in order, so a codeless refusal can be told from an unrelated failure. */
    private static List<String> prose(String body) {
        try {
            BoundModel.ofJson(modelWith(body));
            return List.of(KernelLaws.ADMITTED);
        } catch (ModelConsistencyException rejected) {
            return rejected.notifications().stream().map(n -> n.message().trim()).toList();
        }
    }
}
