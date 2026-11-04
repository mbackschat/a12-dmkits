package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.core.ModelLoader;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.MountOptions;
import io.github.mbackschat.a12.dm.modelkit.UniquenessCriterion;

/**
 * How far each document uniqueness criterion class of {@link DocumentUniquenessCriterionLawsTest} reaches on kernel
 * 31.1.1 (KERNEL-FINDINGS KF671): every declared locale against the one the first grid declared, a field below a
 * repeatable group's subgroup, a field that breaks two demands at once, a field named twice, a text carrying a line
 * break or a control character, and an included model's own criterion. Each row differs from the control in one
 * member; the portable checker's criterion family is built from these rows. A field named twice is judged once, as
 * A12 keeps a criterion's fields in a set, and an included model's own criterion is judged nowhere, as expansion drops
 * it. Own domain (insurance policies).
 */
class DocumentUniquenessCriterionReachLawsTest {

    private static DocumentModelDraft.Builder policy() {
        return DocumentModelDraft.named("policy-reach").locale("en_US").locale("de_DE")
                .rootGroup("Policy", p -> p
                        .stringField("PolicyNo", f -> f.required())
                        .stringField("Holder", f -> f.notPersisted())
                        .group("Claims", c -> c.repeatable(3)
                                .group("Detail", d -> d.stringField("Note", f -> f.required()))));
    }

    private static Map<String, String> text(String english, String german) {
        Map<String, String> byLocale = new LinkedHashMap<>();
        if (english != null) byLocale.put("en_US", english);
        if (german != null) byLocale.put("de_DE", german);
        return byLocale;
    }

    private static String with(UniquenessCriterion criterion) {
        return policy().uniquenessCriterion(criterion).build().toJson();
    }

    private static UniquenessCriterion key(Map<String, String> text, String... fields) {
        return new UniquenessCriterion("PolicyKey", List.of(fields), text);
    }

    private static String measured(String model, ModelResolver resolver) {
        try {
            ModelLoader.loadAndCheck(model, resolver);
            return KernelLaws.ADMITTED;
        } catch (ModelConsistencyException rejected) {
            return rejected.notifications().stream().map(n -> n.message().trim()).collect(Collectors.joining(" | "));
        } catch (RuntimeException raised) {
            return raised.getClass().getSimpleName() + ": " + raised.getMessage();
        }
    }

    private static String measured(String model) {
        return measured(model, asked -> null);
    }

    @Test
    void whereEachCriterionClassReaches() {
        Map<String, String> both = text("Taken", "Vergeben");
        String branch = DocumentModelDraft.named("branch").locale("en_US").locale("de_DE")
                .rootGroup("Branch", b -> b.stringField("Code", f -> f.required()))
                .uniquenessCriterion(new UniquenessCriterion("BranchKey", List.of("/Branch/Missing"), both))
                .build().toJson();
        String mounting = DocumentModelDraft.named("policy-reach").locale("en_US").locale("de_DE")
                .rootGroup("Policy", p -> p.stringField("PolicyNo", f -> f.required())
                        .mount("Office", "branch", MountOptions.single()))
                .build().toJson();

        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("control: a text for each locale", measured(with(key(both, "/Policy/PolicyNo"))));
        actual.put("no text for the second locale", measured(with(key(text("Taken", null), "/Policy/PolicyNo"))));
        actual.put("no text for the first locale", measured(with(key(text(null, "Vergeben"), "/Policy/PolicyNo"))));
        actual.put("an empty text for the second locale", measured(with(key(text("Taken", ""), "/Policy/PolicyNo"))));
        actual.put("a blank text for the second locale", measured(with(key(text("Taken", " "), "/Policy/PolicyNo"))));
        actual.put("a field below a repeatable group's subgroup",
                measured(with(key(both, "/Policy/Claims/Detail/Note"))));
        actual.put("an optional transient field", measured(with(key(both, "/Policy/Holder"))));
        actual.put("an optional field named twice", measured(with(key(both, "/Policy/Holder", "/Policy/Holder"))));
        actual.put("a field named with .value", measured(with(key(both, "/Policy/PolicyNo.value"))));
        actual.put("a text with a line break", measured(with(key(text("Taken\nagain", "Vergeben"), "/Policy/PolicyNo"))));
        actual.put("a text with a control character",
                measured(with(key(text("Taken\u0007", "Vergeben"), "/Policy/PolicyNo"))));
        actual.put("an included model's criterion over a missing field",
                measured(mounting, asked -> "branch".equals(asked) ? branch : null));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("control: a text for each locale", KernelLaws.ADMITTED);
        expected.put("no text for the second locale", "Document Uniqueness Criterion 'PolicyKey': No error text was specified for locale 'de_DE'.");
        expected.put("no text for the first locale", "Document Uniqueness Criterion 'PolicyKey': No error text was specified for locale 'en_US'.");
        expected.put("an empty text for the second locale", "Document Uniqueness Criterion 'PolicyKey': No error text was specified for locale 'de_DE'.");
        expected.put("a blank text for the second locale", KernelLaws.ADMITTED);
        expected.put("a field below a repeatable group's subgroup", "Document Uniqueness Criterion 'PolicyKey': The field '/Policy/Claims/Detail/Note' is repeatable.");
        expected.put("an optional transient field", "Document Uniqueness Criterion 'PolicyKey': The field '/Policy/Holder' is not set as required. | Document Uniqueness Criterion 'PolicyKey': The field '/Policy/Holder' is transient.");
        expected.put("an optional field named twice", "Document Uniqueness Criterion 'PolicyKey': The field '/Policy/Holder' is not set as required. | Document Uniqueness Criterion 'PolicyKey': The field '/Policy/Holder' is transient.");
        expected.put("a field named with .value", "Document Uniqueness Criterion 'PolicyKey': The field '/Policy/PolicyNo.value' does not exist in the Document Model.");
        expected.put("a text with a line break", "Document Uniqueness Criterion 'PolicyKey': Invalid error text for locale 'en_US'. The error text contains a line break which is not valid. [INVALID_LINEBREAK]");
        expected.put("a text with a control character", "Document Uniqueness Criterion 'PolicyKey': Invalid error text for locale 'en_US'. The error text contains invalid characters. Line separators as well as control characters are not allowed. [INVALID_CHARACTERS]");
        expected.put("an included model's criterion over a missing field", KernelLaws.ADMITTED);
        assertEquals(expected, actual);
    }
}
