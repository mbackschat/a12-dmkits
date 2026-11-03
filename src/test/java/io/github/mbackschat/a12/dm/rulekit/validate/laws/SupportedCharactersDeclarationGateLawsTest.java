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
        actual.put("the same offending value declared twice",
                refusals(tiers(new String[] {"A-Z"}, "ab", "ab")));

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
        // The scope is the value's SPELLING rather than its position: A12 reports per (field, value,
        // character), so re-declaring the same offending value adds nothing. The row above cannot say this,
        // because its two values differ.
        expected.put("the same offending value declared twice", 2);
        assertEquals(expected, actual, "the definition's grammar and the gate's multiplicity");
    }

    /**
     * WHICH declared strings the restriction reaches — the scope question, measured rather than transferred.
     *
     * <p>All four models declare the identical restriction and spell the identical refused characters in a
     * different declared string. A consumer that read the restriction as "the model's text" would refuse
     * three models A12 accepts, which is the direction a client-side check may never take.
     *
     * <p><b>The verdict, not a count of one code.</b> Counting {@code MVK_ENUM_VALUE_WITH_INVALID_CHARACTER}
     * returns zero both for a model A12 takes and for one it refuses for some other reason, so a zero row
     * would read as ADMITTED while proving only "not this code". The whole-model verdict separates them.
     *
     * <p><b>The HINT LIST row is the carrier that reads as in-scope from the kernel's source.</b>
     * {@code NoMetaModelChecks.checkEnumValues} runs the same {@code LegalCharTester} over
     * {@code IStringType.getHintList} — but only when {@code isExtensibleEnum()} holds, and on kernel 30.8.1
     * nothing sets it: the interface default returns {@code false}, and an anchored search of the checkout
     * (excluding {@code build/} and delombok output) finds eleven mentions, ten of them calls and the
     * eleventh that default, with no {@code extensibleEnum} member on {@code md.model}'s {@code StringType}.
     * So the arm is dead for a DM-JSON document model, which is the carrier this gate is reproduced for —
     * and a source read alone could not have said so, which is why the row is measured.
     */
    @Test
    void theRestrictionReachesTheEnumValueAndNotEveryDeclaredString() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("an enum VALUE", KernelLaws.verdictOf(tiers(new String[] {"A-Z"}, "beta")));
        actual.put("an element NAME", KernelLaws.verdictOf(namedField("beta")));
        actual.put("an enum CATEGORY token", KernelLaws.verdictOf(categorised("beta")));
        actual.put("an extensible-enum HINT LIST", KernelLaws.verdictOf(suggested("beta")));

        Map<String, String> expected = new LinkedHashMap<>();
        // Once per UTF-16 unit outside the set (KERNEL-FINDINGS KF551).
        expected.put("an enum VALUE", String.join(",", java.util.Collections.nCopies(4, INVALID_CHARACTER)));
        expected.put("an element NAME", KernelLaws.ADMITTED);
        expected.put("an enum CATEGORY token", KernelLaws.ADMITTED);
        expected.put("an extensible-enum HINT LIST", KernelLaws.ADMITTED);
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
     *
     * <p><b>The middle row is the control that keeps the instrument fixed.</b> The provider alone is read
     * through {@code BoundModel.ofJson} and the mount through {@code ModelLoader.loadAndCheck}, so those two
     * rows move the mount AND the instrument together. Mounting into an UNRESTRICTED host varies only the
     * host's restriction, on the same instrument as the treatment.
     */
    @Test
    void aMountedModelsEnumValuesAnswerToTheHostsRestriction() {
        Map<String, Integer> actual = new LinkedHashMap<>();
        actual.put("the provider alone — the control", refusals(deliveryProvider()));
        actual.put("the provider mounted into an unrestricted host", mountedRefusals(null));
        actual.put("the provider mounted into a restricted host", mountedRefusals("A-Z"));

        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("the provider alone — the control", 0);
        expected.put("the provider mounted into an unrestricted host", 0);
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

    /** How many character refusals a host declaring [restriction] raises once the provider is mounted. */
    private static int mountedRefusals(String restriction) {
        DocumentModelDraft.Builder builder = DocumentModelDraft.named("shop").locale("en_US");
        if (restriction != null) {
            builder.supportedCharacters(restriction);
        }
        String host = builder
                .rootGroup("Shop", s -> {
                    s.field("Reference", FieldKind.STRING);
                    s.mount("Shipping", "delivery");
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

    /** The same restriction, with the offending spelling in a string field's HINT LIST. */
    private static String suggested(String suggestion) {
        return DocumentModelDraft.named("shop").locale("en_US")
                .supportedCharacters("A-Z")
                .rootGroup("Shop", s -> s
                        .stringField("Reference", f -> f.suggestions("en_US", suggestion)))
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
