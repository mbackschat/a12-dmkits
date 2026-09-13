package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.core.ModelLoader;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;

/**
 * WHICH declared strings the model's {@code supportedCharacters} restriction is applied to, and how the
 * definition entries combine.
 *
 * <p>{@link CodegenErrorClassReachesConsistencyLawsTest} measured that the restriction reaches
 * {@code checkConsistency} at all, on one row: {@code A-Z} refusing an enum value spelled {@code beta}, once
 * per offending character. That settles reachability and leaves two questions a consumer reproducing the gate
 * must answer — <b>which declared strings it judges</b>, and <b>what a definition entry means</b> — and
 * neither can be inferred from the other row, because a restriction that looked like it applied to "the
 * model's text" would refuse names and messages A12 admits.
 *
 * <p><b>The scope rows are the ones that matter</b>, and they are here rather than assumed: an element NAME
 * and an error MESSAGE are declared strings in the same model, spelled with the same refused characters, and
 * the gate is measured on each. Applying an enum-value law to them needs no new branch, which is exactly the
 * scope transfer that prompts no question.
 *
 * <p>Own domain (an online shop's delivery tiers).
 */
class SupportedCharactersDeclarationGateLawsTest {

    private static final String INVALID_CHARACTER = "MVK_ENUM_VALUE_WITH_INVALID_CHARACTER";

    /** A shop model whose one enum field carries [values], under the restriction [definition] spells. */
    private static String tiers(String[] definition, String... values) {
        DocumentModelDraft.Builder builder = DocumentModelDraft.named("shop").locale("en_US");
        if (definition != null) {
            builder.supportedCharacters(definition);
        }
        return builder
                .rootGroup("Shop", s -> s
                        .stringField("Reference", f -> { })
                        .enumField("Delivery", e -> {
                            for (String value : values) {
                                e.value(value);
                            }
                        }))
                .build().toJson();
    }

    /**
     * The definition's own grammar, and the multiplicity — both read per CHARACTER.
     *
     * <p>The union row is what says a definition is a list of independent entries rather than one pattern,
     * and the boundary rows are what say a range is inclusive at both ends. Without them a consumer could
     * reproduce the refusal and still disagree with A12 on which characters it names.
     */
    @Test
    void aDefinitionEntryIsASingletonOrAnInclusiveRangeAndEachOffendingCharacterIsNamed() {
        Map<String, Integer> actual = new LinkedHashMap<>();
        actual.put("no restriction — the control", refusals(tiers(null, "beta")));
        actual.put("a conforming spelling — the control", refusals(tiers(new String[] {"A-Z"}, "BETA")));
        actual.put("four offending characters", refusals(tiers(new String[] {"A-Z"}, "beta")));
        actual.put("the same character twice", refusals(tiers(new String[] {"A-Z"}, "aa")));
        actual.put("a singleton entry beside the range",
                refusals(tiers(new String[] {"A-Z", "b"}, "beta")));
        actual.put("both range ends", refusals(tiers(new String[] {"A-Z"}, "AZ")));
        actual.put("one below a range's start", refusals(tiers(new String[] {"B-Y"}, "A")));
        actual.put("one above a range's end", refusals(tiers(new String[] {"B-Y"}, "Z")));
        actual.put("two values, one of them offending",
                refusals(tiers(new String[] {"A-Z"}, "BASIC", "ab")));
        actual.put("two values sharing the same two offenders",
                refusals(tiers(new String[] {"A-Z"}, "ab", "ba")));

        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("no restriction — the control", 0);
        expected.put("a conforming spelling — the control", 0);
        // One diagnostic per offending CHARACTER, not per value — a consumer counting values under-reports.
        expected.put("four offending characters", 4);
        // Per DISTINCT character rather than per occurrence — the row above cannot separate the two, and
        // this one says the repeat is folded.
        expected.put("the same character twice", 1);
        expected.put("a singleton entry beside the range", 3);
        // A range is inclusive at both ends, so neither boundary character is an offender.
        expected.put("both range ends", 0);
        expected.put("one below a range's start", 1);
        expected.put("one above a range's end", 1);
        expected.put("two values, one of them offending", 2);
        // …and distinctness is per VALUE, not per model: the same two offenders in a second value are
        // reported again. Without this row "distinct" would be ambiguous between the two scopes.
        expected.put("two values sharing the same two offenders", 4);
        assertEquals(expected, actual, "the definition's grammar and the gate's multiplicity");
    }

    /**
     * WHICH declared strings the restriction reaches — the scope question, measured rather than transferred.
     *
     * <p>All three models declare the identical restriction and spell the identical refused characters in a
     * different declared string. A consumer that read the restriction as "the model's text" would refuse two
     * models A12 accepts, which is the direction a client-side check may never take.
     */
    @Test
    void theRestrictionReachesTheEnumValueAndNotEveryDeclaredString() {
        Map<String, Integer> actual = new LinkedHashMap<>();
        actual.put("an enum VALUE", refusals(tiers(new String[] {"A-Z"}, "beta")));
        actual.put("an element NAME", refusals(namedField("beta")));
        actual.put("an enum CATEGORY token", refusals(categorised("beta")));

        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("an enum VALUE", 4);
        expected.put("an element NAME", 0);
        expected.put("an enum CATEGORY token", 0);
        assertEquals(expected, actual, "the restriction is judged on enum values and not on every string");

        // The fourth carrier answers on the rule surface rather than the model one, so it is asserted
        // separately rather than folded into a count that could hide a refusal for another reason.
        String shop = DocumentModelDraft.named("shop").locale("en_US")
                .supportedCharacters("A-Z")
                .rootGroup("Shop", s -> s.stringField("Reference", f -> { }))
                .build().toJson();
        assertEquals(KernelLaws.ADMITTED,
                KernelLaws.messageVerdict(shop, "/Shop", "../Reference", "FieldFilled(/Shop/Reference)",
                        "en_US", "beta is required"),
                "an error MESSAGE is not judged against the model's supported characters");
    }

    /**
     * A MOUNTED model's enum values answer to the HOST's restriction, which is the carrier a reader would
     * otherwise transfer onto without noticing.
     *
     * <p>The provider declares no restriction of its own and is clean when checked alone; mounted into a
     * host that declares one, its values are judged by it. So the restriction is a property of the EXPANDED
     * model rather than of the file that declares the enum — and a consumer applying it only to the entry
     * model's own declarations would report a model A12 refuses as clean.
     */
    @Test
    void aMountedModelsEnumValuesAnswerToTheHostsRestriction() {
        Map<String, Integer> actual = new LinkedHashMap<>();
        actual.put("the provider alone — the control", refusals(deliveryProvider()));
        actual.put("the provider mounted into a restricted host", mountedRefusals());

        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("the provider alone — the control", 0);
        expected.put("the provider mounted into a restricted host", 4);
        assertEquals(expected, actual, "the restriction belongs to the expanded model, not to the declaring file");
    }

    /** A provider declaring the offending enum and NO restriction of its own. */
    private static String deliveryProvider() {
        return DocumentModelDraft.named("delivery").locale("en_US")
                .rootGroup("Delivery", d -> d
                        .field("Carrier", FieldKind.STRING)
                        .enumField("Speed", e -> e.value("beta")))
                .build().toJson();
    }

    /** How many character refusals the host raises once the provider is mounted into it. */
    private static int mountedRefusals() {
        String host = DocumentModelDraft.named("shop").locale("en_US")
                .supportedCharacters("A-Z")
                .include("dlv", "delivery")
                .rootGroup("Shop", s -> {
                    s.field("Reference", FieldKind.STRING);
                    s.mount("Shipping", "dlv");
                })
                .build().toJson();
        ModelResolver resolver = reference -> "delivery".equals(reference) ? deliveryProvider() : null;
        try {
            ModelLoader.loadAndCheck(host, resolver);
            return 0;
        } catch (RuntimeException rejected) {
            String message = String.valueOf(rejected.getMessage());
            int count = 0;
            int at = message.indexOf(INVALID_CHARACTER);
            while (at >= 0) {
                count++;
                at = message.indexOf(INVALID_CHARACTER, at + INVALID_CHARACTER.length());
            }
            return count;
        }
    }

    /** The same restriction, with the offending spelling in a FIELD NAME instead. */
    private static String namedField(String name) {
        return DocumentModelDraft.named("shop").locale("en_US")
                .supportedCharacters("A-Z")
                .rootGroup("Shop", s -> s
                        .stringField("Reference", f -> { })
                        .stringField(name, f -> { }))
                .build().toJson();
    }

    /** The same restriction, with the offending spelling in an enum CATEGORY token. */
    private static String categorised(String token) {
        return DocumentModelDraft.named("shop").locale("en_US")
                .supportedCharacters("A-Z")
                .rootGroup("Shop", s -> s
                        .stringField("Reference", f -> { })
                        .enumField("Delivery", e -> e.value("BASIC").category("Band", token)))
                .build().toJson();
    }

    /** How many {@code MVK_ENUM_VALUE_WITH_INVALID_CHARACTER} notifications A12 raises for this model. */
    private static int refusals(String modelJson) {
        try {
            BoundModel.ofJson(modelJson);
            return 0;
        } catch (ModelConsistencyException rejected) {
            return (int) rejected.notifications().stream()
                    .filter(n -> String.valueOf(n.message()).contains(INVALID_CHARACTER))
                    .count();
        }
    }
}
